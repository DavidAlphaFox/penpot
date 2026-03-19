;; =============================================================================
;; 文件创建模块 (File Creation Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块负责处理 Penpot 设计工具的文件创建 RPC 命令，包括：
;; - 创建新设计文件 (create-file)
;; - 创建文件角色关联 (create-file-role!)
;;
;; 【核心概念】
;; 1. File (文件) - 设计文件，包含页面、组件、颜色、字体等元素
;; 2. Project (项目) - 文件所属的项目容器
;; 3. Team (团队) - 项目所属的团队
;; 4. Features (特性) - 控制文件功能的特性标志
;;
;; 【依赖关系】
;; - app.binfile.common - 二进制文件处理
;; - app.common.features - 特性标志管理
;; - app.common.types.file - 文件类型定义
;; - app.rpc.commands.projects - 项目权限检查
;; - app.rpc.commands.teams - 团队信息查询
;; - app.rpc.permissions - 权限管理
;; - app.rpc.quotes - 配额限制检查
;;
;; =============================================================================

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.files-create
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.files.migrations :as fmg]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.file :as ctf]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.permissions :as perms]
   [app.rpc.quotes :as quotes]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [clojure.set :as set]))

(defn create-file-role!
  "为文件创建用户角色关联。
   
   【参数】
   conn - 数据库连接
   params - 包含:
   - :file-id - 文件 ID
   - :profile-id - 用户 ID
   - :role - 角色类型 (:owner, :editor, :viewer, :commenter)
   
   【返回值】
   返回创建的关联记录。"
  [conn {:keys [file-id profile-id role]}]
  (let [params {:file-id file-id
                :profile-id profile-id}]

    (->> (perms/assign-role-flags params role)
         (db/insert! conn :file-profile-rel))))

(defn create-file
  "创建新的设计文件。
   
   【参数】
   cfg - 系统配置，包含数据库连接
   params - 创建参数，包含:
   - :id - 文件 ID（可选，自动生成）
   - :name - 文件名
   - :project-id - 项目 ID
   - :is-shared - 是否共享（默认 false）
   - :revn - 版本号（默认 0）
   - :create-page - 是否创建默认页面（默认 true）
   - :page-id - 页面 ID（可选）
   - :ignore-sync-until - 忽略同步截止时间
   - :features - 特性标志集合
   
   【返回值】
   返回创建的文件记录，包含所有文件属性。"
  [{:keys [::db/conn] :as cfg}
   {:keys [id name project-id is-shared revn
           modified-at deleted-at create-page page-id
           ignore-sync-until features]
    :or {is-shared false revn 0 create-page true}
    :as params}]

  (assert (db/connection? conn) "expected a valid connection")

  (binding [pmap/*tracked* (pmap/create-tracked)
            cfeat/*current* features]

    (let [file (ctf/make-file {:id id
                               :project-id project-id
                               :name name
                               :revn revn
                               :is-shared is-shared
                               :features features
                               :migrations fmg/available-migrations
                               :ignore-sync-until ignore-sync-until
                               :created-at modified-at
                               :deleted-at deleted-at}
                              {:create-page create-page
                               :page-id page-id})]

      (bfc/insert-file! cfg file)

      (->> (assoc params :file-id (:id file) :role :owner)
           (create-file-role! conn))

      (db/update! conn :project
                  {:modified-at (ct/now)}
                  {:id project-id})

      (bfc/get-file cfg (:id file)))))

;; 【输入验证模式】
;; 定义 create-file RPC 方法的输入参数验证模式
(def ^:private schema:create-file
  [:map {:title "create-file"}
   [:name [:string {:max 250}]]
   [:project-id ::sm/uuid]
   [:id {:optional true} ::sm/uuid]
   [:is-shared {:optional true} ::sm/boolean]
   [:features {:optional true} ::cfeat/features]])

(sv/defmethod ::create-file
   "创建新设计文件的 RPC 入口函数。
    
    【参数】
    cfg - 系统配置，包含数据库连接
    params - 创建参数，包含:
    - ::rpc/profile-id - 当前用户 ID
    - :project-id - 项目 ID
    - :name - 文件名
    - :id - 文件 ID（可选）
    - :is-shared - 是否共享（可选）
    - :features - 特性标志（可选）
    
    【返回值】
    返回创建的文件记录，包含审计属性。
    
    【处理流程】
    1. 检查用户对项目的编辑权限
    2. 获取团队信息和启用特性
    3. 验证客户端请求的特性和迁移特性
    4. 检查项目文件配额
    5. 更新团队特性（如果有新增）
    6. 创建文件并设置所有者角色
    7. 返回文件记录（带审计属性）
    
    【注意事项】
    - 此方法在事务中执行
    - 触发 Webhook 事件"
   {::doc/added "1.17"
   ::doc/module :files
   ::webhooks/event? true
   ::sm/params schema:create-file
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id project-id] :as params}]
  (projects/check-edition-permissions! conn profile-id project-id)
  (let [team     (teams/get-team conn
                                 :profile-id profile-id
                                 :project-id project-id)
        team-id  (:id team)

        features (-> (cfeat/get-team-enabled-features cf/flags team)
                     (cfeat/check-client-features! (:features params)))

        ;; We also include all no migration features declared by
        ;; client; that enables the ability to enable a runtime
        ;; feature on frontend and make it permanent on file
        features (-> (:features params #{})
                     (set/intersection cfeat/no-migration-features)
                     (set/difference cfeat/frontend-only-features)
                     (set/union features))

        params   (-> params
                     (assoc :profile-id profile-id)
                     (assoc :features features))]

    (quotes/check! cfg {::quotes/id ::quotes/files-per-project
                        ::quotes/team-id team-id
                        ::quotes/profile-id profile-id
                        ::quotes/project-id project-id})

    ;; FIXME: IMPORTANT: this code can have race conditions, because
    ;; we have no locks for updating team so, creating two files
    ;; concurrently can lead to lost team features updating
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

    (-> (create-file cfg params)
        (vary-meta assoc ::audit/props {:team-id team-id}))))
