;; =============================================================================
;; 文件更新模块 (File Update Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块负责处理 Penpot 设计工具的文件更新 RPC 命令，包括：
;; - 更新文件数据和元数据 (update-file)
;; - 持久化文件更改 (persist-file!)
;; - 获取文件数据 (get-file)
;; - 处理变更并验证 (process-changes-and-validate)
;; - 管理文件库同步 (absorb-library, link-file-to-library 等)
;;
;; 【核心概念】
;; 1. Changes (变更) - 描述文件修改操作的数据结构
;; 2. Revisions (修订版本) - 文件的修订号 (revn) 和版本号 (vern)
;; 3. Library (库) - 可共享的设计资源（颜色、组件、字体等）
;; 4. Pointer Map - 延迟加载机制
;; 5. Snapshot (快照) - 文件数据的备份
;;
;; 【依赖关系】
;; - app.binfile.common - 二进制文件处理
;; - app.common.files.changes - 变更处理逻辑
;; - app.common.files.migrations - 文件迁移
;; - app.common.files.validate - 文件验证
;; - app.features.fdata - 文件数据特性
;; - app.features.file-snapshots - 文件快照
;; - app.util.pointer-map - 指针映射
;;
;; =============================================================================

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files-update
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cpc]
   [app.common.files.migrations :as fmg]
   [app.common.files.validate :as val]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-snapshots :as fsnap]
   [app.features.logical-deletion :as ldel]
   [app.http.errors :as errors]
   [app.loggers.audit :as audit]
   [app.loggers.webhooks :as webhooks]
   [app.metrics :as mtx]
   [app.msgbus :as mbus]
   [app.redis :as rds]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as climit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [clojure.set :as set]))

(declare ^:private get-lagged-changes)
(declare ^:private send-notifications!)
(declare ^:private update-file)
(declare ^:private update-file*)
(declare ^:private process-changes-and-validate)
(declare ^:private take-snapshot?)
(declare ^:private invalidate-caches!)

;; PUBLIC API; intended to be used outside of this module
(declare update-file!)
(declare update-file-data!)
(declare persist-file!)
(declare get-file)

;; --- SCHEMA

;; 【输入验证模式】
;; 定义 update-file RPC 方法的输入参数验证模式
(def ^:private
  schema:update-file
  [:map {:title "update-file"}
   [:id ::sm/uuid]
   [:session-id ::sm/uuid]
   [:revn {:min 0} ::sm/int]
   [:vern {:min 0} ::sm/int]
   [:features {:optional true} ::cfeat/features]
   [:changes {:optional true} [:vector cpc/schema:change]]
   [:changes-with-metadata {:optional true}
    [:vector [:map
              [:changes [:vector cpc/schema:change]]
              [:hint-origin {:optional true} :keyword]
              [:hint-events {:optional true} [:vector [:string {:max 250}]]]]]]
   [:skip-validate {:optional true} ::sm/boolean]])

;; 【输出验证模式】
;; 定义 update-file RPC 方法的返回值验证模式
(def ^:private
  schema:update-file-result
  [:vector {:title "update-file-result"}
   [:map
    [:changes [:vector cpc/schema:change]]
    [:file-id ::sm/uuid]
    [:id ::sm/uuid]
    [:revn {:min 0} ::sm/int]
    [:session-id ::sm/uuid]]])

;; --- HELPERS

;; File changes that affect to the library, and must be notified
;; to all clients using it.

(def ^:private library-change-types
  "定义会影响库（颜色、组件、字体等）的变更类型集合。
   这些变更需要通知所有使用该库的文件。"
  #{:add-color
    :mod-color
    :del-color
    :add-media
    :mod-media
    :del-media
    :add-component
    :mod-component
    :del-component
    :restore-component
    :add-typography
    :mod-typography
    :del-typography})

(def ^:private file-change-types
  "定义直接影响文件的变更类型集合。"
  #{:add-obj
    :mod-obj
    :del-obj
    :reg-objects
    :mov-objects})

(defn- library-change?
  "判断给定的变更是否影响库资源。
   
   【参数】
   change - 变更记录，包含 :type 字段
   
   【返回值】
   返回 true 如果变更类型影响库资源，否则返回 false。"
  [{:keys [type] :as change}]
  (or (contains? library-change-types type)
      (contains? file-change-types type)))

;; If features are specified from params and the final feature
;; set is different than the persisted one, update it on the
;; database.

(sv/defmethod ::update-file
  "更新文件数据和元数据的主入口函数。
   
   【参数】
   cfg - 系统配置，包含数据库连接和指标收集器
   params - 更新参数，包含:
   - :id - 文件 ID
   - :session-id - 会话 ID
   - :revn - 修订版本号
   - :vern - 版本号
   - :features - 特性标志（可选）
   - :changes - 变更列表（可选）
   - :changes-with-metadata - 带元数据的变更列表（可选）
   - :skip-validate - 是否跳过验证
   
   【返回值】
   返回变更结果列表，包含修订版本号和延迟变更。"
  {::climit/id [[:update-file/by-profile ::rpc/profile-id]
                [:update-file/global]]

   ::webhooks/event? true
   ::webhooks/batch-timeout (ct/duration "2m")
   ::webhooks/batch-key (webhooks/key-fn ::rpc/profile-id :id)

   ::sm/params schema:update-file
   ::sm/result schema:update-file-result
   ::doc/module :files
   ::doc/added "1.17"
   ::db/transaction true}
  [{:keys [::mtx/metrics ::db/conn] :as cfg}
   {:keys [::rpc/profile-id id changes changes-with-metadata] :as params}]

  (files/check-edition-permissions! conn profile-id id)
  (db/xact-lock! conn id)

  (let [file     (get-file cfg id)
        team     (teams/get-team conn
                                 :profile-id profile-id
                                 :team-id (:team-id file))

        features (-> (cfeat/get-team-enabled-features cf/flags team)
                     (cfeat/check-client-features! (:features params))
                     (cfeat/check-file-features! (:features file)))

        changes  (if changes-with-metadata
                   (->> changes-with-metadata (mapcat :changes) vec)
                   (vec changes))

        params   (-> params
                     (assoc :profile-id profile-id)
                     (assoc :features (set/difference features cfeat/frontend-only-features))
                     (assoc :team team)
                     (assoc :file file)
                     (assoc :changes changes))

        cfg      (assoc cfg ::timestamp (ct/now))

        tpoint   (ct/tpoint)]

    (when (not= (:vern params)
                (:vern file))
      (ex/raise :type :validation
                :code :vern-conflict
                :hint "A different version has been restored for the file."
                :context {:incoming-revn (:revn params)
                          :stored-revn (:revn file)}))

    (when (> (:revn params)
             (:revn file))
      (ex/raise :type :validation
                :code :revn-conflict
                :hint "The incoming revision number is greater that stored version."
                :context {:incoming-revn (:revn params)
                          :stored-revn (:revn file)}))

    ;; When newly computed features does not match exactly with the
    ;; features defined on team row, we update it
    (when-let [features (-> features
                            (set/difference (:features team))
                            (set/difference cfeat/no-team-inheritable-features)
                            (not-empty))]
      (let [features (-> features
                         (set/union (:features team))
                         (set/difference cfeat/no-team-inheritable-features)
                         (into-array))]
        (db/update! conn :team
                    {:features features}
                    {:id (:id team)}
                    {::db/return-keys false})))


    (mtx/run! metrics {:id :update-file-changes :inc (count changes)})

    (binding [l/*context* (some-> (meta params)
                                  (get :app.http/request)
                                  (errors/request->context))]
      (-> (update-file* cfg params)
          (rph/with-defer #(let [elapsed (tpoint)]
                             (l/trace :hint "update-file" :time (ct/format-duration elapsed))))))))

(defn- update-file*
  "文件更新的核心内部函数。
   
   【参数】
   cfg - 系统配置，包含数据库连接和时间戳
   params - 更新参数，包含:
   - :profile-id - 用户 ID
   - :file - 文件记录
   - :team - 团队记录
   - :features - 特性标志
   - :changes - 变更列表
   - :session-id - 会话 ID
   - :skip-validate - 是否跳过验证
   
   【返回值】
   返回更新后的文件记录和审计属性。"
  [{:keys [::db/conn ::timestamp] :as cfg}
   {:keys [profile-id file team features changes session-id skip-validate] :as params}]

  (binding [pmap/*tracked* (pmap/create-tracked)
            pmap/*load-fn* (partial fdata/load-pointer cfg (:id file))]

    (let [file (assoc file :features
                      (-> features
                          (set/difference cfeat/frontend-only-features)
                          (set/union (:features file))))

          ;; We need to preserve the original revn for the response
          revn
          (get file :revn)

          file
          (binding [cfeat/*current*  features
                    cfeat/*previous* (:features file)]
            (update-file-data! cfg file
                               process-changes-and-validate
                               changes skip-validate))

          deleted-at
          (ct/plus timestamp (ct/duration {:hours 1}))]

      (when-let [file (::snapshot file)]
        (let [deleted-at (ct/plus timestamp (ldel/get-deletion-delay team))
              label      (str "internal/snapshot/" revn)]

          (fsnap/create! cfg file
                         {:label label
                          :created-by "system"
                          :deleted-at deleted-at
                          :profile-id profile-id
                          :session-id session-id})))

      ;; Insert change (xlog) with deleted_at in a future data for
      ;; make them automatically eleggible for GC once they expires
      (db/insert! conn :file-change
                  {:id (uuid/next)
                   :session-id session-id
                   :profile-id profile-id
                   :created-at timestamp
                   :updated-at timestamp
                   :deleted-at deleted-at
                   :file-id (:id file)
                   :revn (:revn file)
                   :version (:version file)
                   :features (into-array (:features file))
                   :changes (blob/encode changes)}
                  {::db/return-keys false})

      (persist-file! cfg file)

      (when (contains? cf/flags :redis-cache)
        (invalidate-caches! cfg file))

      ;; Send asynchronous notifications
      (send-notifications! cfg params file)

      (with-meta {:revn revn :lagged (get-lagged-changes conn params)}
        {::audit/replace-props
         {:id         (:id file)
          :name       (:name file)
          :features   (:features file)
          :project-id (:project-id file)
          :team-id    (:team-id file)}}))))

(defn get-file
  "获取未解码的文件数据。
   
   【参数】
   cfg - 系统配置
   id - 文件 ID
   
   【返回值】
   返回文件记录，仅解码特性标志集。"
  [cfg id]
  (bfc/get-file cfg id :decode? false :lock-for-share? true))

(defn persist-file!
  "持久化已编码的文件数据。
   
   【参数】
   cfg - 系统配置，包含数据库连接
   file - 已编码的文件记录
   
   【返回值】
   返回更新后的文件记录。同时更新项目的修改时间。
   
   【注意】
   此函数应与 get-file 和 update-file-data! 配合使用。"
  [{:keys [::db/conn ::timestamp] :as cfg} file]
  (let [;; The timestamp can be nil because this function is also
        ;; intended to be used outside of this module
        modified-at
        (or timestamp (ct/now))

        file
        (-> file
            (dissoc ::snapshot)
            (assoc :modified-at modified-at)
            (assoc :has-media-trimmed false))]

    (db/update! conn :project
                {:modified-at modified-at}
                {:id (:project-id file)}
                {::db/return-keys false})

    (bfc/update-file! cfg file)))

(defn- invalidate-caches!
  "使文件相关的缓存失效。
   
   【参数】
   cfg - 系统配置
   file - 文件记录，包含 :id 字段"
  [cfg {:keys [id] :as file}]
  (rds/run! cfg (fn [{:keys [::rds/conn]}]
                  (let [key (str files/file-summary-cache-key-prefix id)]
                    (rds/del conn key)))))

(defn- attach-snapshot
  "为文件附加快照数据。
   
   【参数】
   cfg - 系统配置
   migrated? - 文件是否已迁移
   file - 文件记录
   
   【返回值】
   返回附加了 ::snapshot 元数据的文件记录。"
  [cfg migrated? file]
  (let [snapshot (if migrated? file (fdata/realize cfg file))]
    (assoc file ::snapshot snapshot)))

(defn- update-file-data!
  "执行文件数据转换，设置所有更新上下文。
   
   【参数】
   cfg - 系统配置
   file - 未解码的文件记录
   update-fn - 更新函数
   args - 传递给更新函数的额外参数
   
   【返回值】
   返回编码后的文件记录。
   
   【注意】
   此函数不负责保存文件，仅保存 fdata/pointer-map 修改的片段。"
  [cfg {:keys [id] :as file} update-fn & args]
  (let [file (update file :data (fn [data]
                                  (-> data
                                      (blob/decode)
                                      (assoc :id id))))
        libs (delay (bfc/get-resolved-file-libraries cfg file))

        need-migration?
        (fmg/need-migration? file)

        take-snapshot?
        (take-snapshot? file)

        ;; For avoid unnecesary overhead of creating multiple
        ;; pointers and handly internally with objects map in their
        ;; worst case (when probably all shapes and all pointers
        ;; will be readed in any case), we just realize/resolve them
        ;; before applying the migration to the file
        file
        (cond-> file
          ;; need-migration?
          ;; (->> (fdata/realize cfg))

          need-migration?
          (fmg/migrate-file libs)

          take-snapshot?
          (->> (attach-snapshot cfg need-migration?)))]

    (apply update-fn cfg file args)))

(defn- soft-validate-file-schema!
  "软验证文件 schema 结构。
   
   【参数】
   file - 文件记录
   
   【返回值】
   验证失败时记录错误但不影响流程。"
  [file]
  (try
    (val/validate-file-schema! file)
    (catch Throwable cause
      (l/error :hint "file schema validation error" :cause cause))))

(defn- soft-validate-file!
  "软验证文件数据完整性。
   
   【参数】
   file - 文件记录
   libs - 库列表
   
   【返回值】
   验证失败时记录错误但不影响流程。"
  [file libs]
  (try
    (val/validate-file! file libs)
    (catch Throwable cause
      (l/error :hint "file validation error"
               :cause cause))))


(defn- process-changes-and-validate
  "处理文件变更并执行验证。
   
   【参数】
   cfg - 系统配置
   file - 文件记录
   changes - 变更列表
   skip-validate - 是否跳过验证
   
   【返回值】
   返回处理并验证后的文件记录。"
  [cfg file changes skip-validate]
  (let [;; WARNING: this ruins performance; maybe we need to find
        ;; some other way to do general validation
        libs
        (when (and (or (contains? cf/flags :file-validation)
                       (contains? cf/flags :soft-file-validation))
                   (not skip-validate))
          (bfc/get-resolved-file-libraries cfg file))

        ;; The main purpose of this atom is provide a contextual state
        ;; for the changes subsystem where optionally some hints can
        ;; be provided for the changes processing. Right now we are
        ;; using it for notify about the existence of media refs when
        ;; a new shape is added.
        state
        (atom {})

        file
        (binding [cpc/*state* state]
          (-> (files/check-version! file)
              (update :revn inc)
              (update :data cpc/process-changes changes)
              (update :data d/without-nils)))

        file
        (if-let [media-refs (-> @state :media-refs not-empty)]
          (bfc/update-media-references! cfg file media-refs)
          file)]

    (binding [pmap/*tracked* nil]
      (when (contains? cf/flags :soft-file-validation)
        (soft-validate-file! file libs))

      (when (contains? cf/flags :soft-file-schema-validation)
        (soft-validate-file-schema! file))

      (when (and (contains? cf/flags :file-validation)
                 (not skip-validate))
        (val/validate-file! file libs))

      (when (and (contains? cf/flags :file-schema-validation)
                 (not skip-validate))
        (val/validate-file-schema! file)))

    file))

(defn- take-snapshot?
  "判断是否应该保存文件数据快照。
   
   【参数】
   file - 文件记录，包含 :revn (修订号) 和 :modified-at (修改时间)
   
   【返回值】
   返回 true 如果应该保存快照，否则返回 nil。"
  [{:keys [revn modified-at] :as file}]
  (when (contains? cf/flags :auto-file-snapshot)
    (let [freq    (or (cf/get :auto-file-snapshot-every) 20)
          timeout (or (cf/get :auto-file-snapshot-timeout)
                      (ct/duration {:hours 1}))]

      (or (= 1 freq)
          (zero? (mod revn freq))
          (> (inst-ms (ct/diff modified-at (ct/now)))
             (inst-ms timeout))))))

(def ^:private sql:lagged-changes
  "select s.id, s.revn, s.file_id,
          s.session_id, s.changes
     from file_change as s
    where s.file_id = ?
      and s.revn > ?
    order by s.created_at asc")

(defn- get-lagged-changes
  "获取延迟的变更记录。
   
   【参数】
   conn - 数据库连接
   params - 包含 :id (文件 ID) 和 :revn (当前修订号)
   
   【返回值】
   返回变更记录列表，每条记录包含解码后的变更数据。"
  [conn {:keys [id revn] :as params}]
  (->> (db/exec! conn [sql:lagged-changes id revn])
       (filter :changes)
       (mapv (fn [row]
               (update row :changes blob/decode)))))

(defn- send-notifications!
  "发送文件变更通知。
   
   【参数】
   cfg - 系统配置
   params - 包含 :team, :changes, :session-id
   file - 文件记录
   
   【返回值】
   返回 nil。发送消息到消息总线通知所有相关客户端。"
  [cfg {:keys [team changes session-id] :as params} file]
  (let [lchanges (filter library-change? changes)
        msgbus   (::mbus/msgbus cfg)]

    (mbus/pub! msgbus
               :topic (:id file)
               :message {:type :file-change
                         :profile-id (:profile-id params)
                         :file-id (:id file)
                         :session-id (:session-id params)
                         :revn (:revn file)
                         :vern (:vern file)
                         :changes changes})

    (when (and (:is-shared file) (seq lchanges))
      (mbus/pub! msgbus
                 :topic (:id team)
                 :message {:type :library-change
                           :profile-id (:profile-id params)
                           :file-id (:id file)
                           :session-id session-id
                           :revn (:revn file)
                           :modified-at (ct/now)
                           :changes lchanges}))))
