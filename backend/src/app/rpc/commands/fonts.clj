;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

;; =============================================================================
;; 字体模块 (Fonts Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块负责处理Penpot设计工具中的字体资源管理功能。
;; 提供字体的上传、查询、更新、删除以及下载等RPC命令。
;;
;; 【核心概念】
;; 1. 字体变体 (Font Variant) - 同一字体的不同样式组合（字重、字形）
;; 2. 字体族 (Font Family) - 具有相同名称的一组字体变体
;; 3. 字体格式 - 支持OTF、TTF、WOFF、WOFF2四种Web字体格式
;; 4. 团队字体 - 存储在团队级别，供团队内所有项目使用
;;
;; 【依赖关系】
;; - app.rpc.commands.teams - 团队权限验证
;; - app.rpc.commands.files - 文件权限验证
;; - app.storage - 字体文件存储
;; - app.media - 字体处理和格式转换
;; - app.features.logical-deletion - 软删除支持
;;
;; 【数据库表】
;; - team-font-variant: 存储团队字体变体信息
;;
;; =============================================================================

(ns app.rpc.commands.fonts
  (:require
   [app.binfile.common :as bfc]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.media :as cmedia]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.features.logical-deletion :as ldel]
   [app.http :as-alias http]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.quotes :as quotes]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.util.services :as sv]
   [datoteka.io :as io])
  (:import
   java.io.InputStream
   java.io.OutputStream
   java.io.SequenceInputStream
   java.util.Collections
   java.util.zip.ZipEntry
   java.util.zip.ZipOutputStream))

(set! *warn-on-reflection* true)


(def valid-weight #{100 200 300 400 500 600 700 800 900 950})
(def valid-style #{"normal" "italic"})

;; --- QUERY: 获取字体变体 (Get Font Variants)
;;
;; 【功能说明】
;; 根据团队ID、文件ID或项目ID查询可用的字体变体列表。
;; 返回指定范围内的所有未删除字体变体。
;;
;; 【参数】
;; team-id - 团队ID（可选，与file-id/project-id互斥）
;; file-id - 文件ID（可选）
;; project-id - 项目ID（可选）
;;
;; 【返回值】
;; 返回字体变体列表，每个变体包含字体族、字重、字形等信息
;;
;; 【权限检查】
;; - 团队ID: 需要团队读取权限
;; - 项目ID: 需要项目读取权限
;; - 文件ID: 需要文件读取权限
;;

(def ^:private
  schema:get-font-variants
  [:and
   [:map {:title "get-font-variants"}
    [:team-id {:optional true} ::sm/uuid]
    [:file-id {:optional true} ::sm/uuid]
    [:project-id {:optional true} ::sm/uuid]
    [:share-id {:optional true} ::sm/uuid]]
   [::sm/contains-any #{:team-id :file-id :project-id}]])

(sv/defmethod ::get-font-variants
  {::doc/added "1.18"
   ::sm/params schema:get-font-variants}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id file-id project-id share-id] :as params}]
  (dm/with-open [conn (db/open pool)]
    (cond
      (uuid? team-id)
      (do
        (teams/check-read-permissions! conn profile-id team-id)
        (db/query conn :team-font-variant
                  {:team-id team-id
                   :deleted-at nil}))

      (uuid? project-id)
      (let [project (db/get-by-id conn :project project-id {:columns [:id :team-id]})]
        (projects/check-read-permissions! conn profile-id project-id)
        (db/query conn :team-font-variant
                  {:team-id (:team-id project)
                   :deleted-at nil}))

      (uuid? file-id)
      (let [file    (db/get-by-id conn :file file-id {:columns [:id :project-id]})
            project (db/get-by-id conn :project (:project-id file) {:columns [:id :team-id]})
            perms   (bfc/get-file-permissions conn profile-id file-id share-id)]
        (files/check-read-permissions! perms)
        (db/query conn :team-font-variant
                  {:team-id (:team-id project)
                   :deleted-at nil})))))


(declare create-font-variant)

;; --- 创建字体变体 (Create Font Variant)
;;
;; 【功能说明】
;; 上传并创建新的字体变体。支持多种字体格式（OTF、TTF、WOFF、WOFF2），
;; 系统会自动生成缺失的字体格式，并处理分块上传的数据合并。
;;
;; 【参数】
;; team-id - 团队ID
;; font-id - 字体族ID
;; font-family - 字体族名称
;; font-weight - 字重值（100-950）
;; font-style - 字形（normal/italic）
;; data - 字体文件数据映射，键为MIME类型，值为文件内容或字节数组
;;
;; 【返回值】
;; 返回创建的字体变体记录，包含各格式文件的存储对象ID
;;
;; 【处理流程】
;; 1. 合并分块上传的字体数据
;; 2. 生成缺失的字体格式
;; 3. 将字体文件持久化到存储
;; 4. 创建数据库记录
;;
;; 【注意事项】
;; - FIXME: 需要重构，不应在字体创建过程中持有整个数据库连接
;; - 触发Webhooks事件
;; - 受速率限制：每用户/全局处理限制
;;
(def ^:private schema:create-font-variant
  [:map {:title "create-font-variant"}
   [:team-id ::sm/uuid]
   [:data [:map-of ::sm/text [:or ::sm/bytes
                              [::sm/vec ::sm/bytes]]]]
   [:font-id ::sm/uuid]
   [:font-family ::sm/text]
   [:font-weight [::sm/one-of {:format "number"} valid-weight]]
   [:font-style [::sm/one-of {:format "string"} valid-style]]])

;; FIXME: IMPORTANT: refactor this, we should not hold a whole db
;; connection around the font creation

;; --- 内部函数: 创建字体变体 (Create Font Variant - Internal)
;;
;; 【功能说明】
;; 创建字体变体的内部实现函数。负责处理字体数据的合并、格式生成、
;; 文件存储以及数据库记录创建。
;;
;; 【参数】
;; cfg - 包含存储和数据库连接的配置
;; params - 包含字体变体参数的映射
;;
;; 【返回值】
;; 创建的字体变体记录
;;
(sv/defmethod ::create-font-variant
  {::doc/added "1.18"
   ::climit/id [[:process-font/by-profile ::rpc/profile-id]
                [:process-font/global]]
   ::webhooks/event? true
   ::sm/params schema:create-font-variant}
  [cfg {:keys [::rpc/profile-id team-id] :as params}]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn] :as cfg}]
                (teams/check-edition-permissions! conn profile-id team-id)
                (quotes/check! cfg {::quotes/id ::quotes/font-variants-per-team
                                    ::quotes/profile-id profile-id
                                    ::quotes/team-id team-id})
                (create-font-variant cfg (assoc params :profile-id profile-id)))))

(defn create-font-variant
  [{:keys [::sto/storage ::db/conn]} {:keys [data] :as params}]
  (letfn [(generate-missing [data]
            (let [data (media/run {:cmd :generate-fonts :input data})]
              (when (and (not (contains? data "font/otf"))
                         (not (contains? data "font/ttf"))
                         (not (contains? data "font/woff"))
                         (not (contains? data "font/woff2")))
                (ex/raise :type :validation
                          :code :invalid-font-upload
                          :hint "invalid font upload, unable to generate missing font assets"))
              data))

          (process-chunks [chunks]
            (let [tmp     (tmp/tempfile :prefix "penpot.tempfont." :suffix "")
                  streams (map io/input-stream chunks)
                  streams (Collections/enumeration streams)]
              (with-open [^OutputStream output (io/output-stream tmp)
                          ^InputStream input (SequenceInputStream. streams)]
                (io/copy input output))
              tmp))

          (join-chunks [data]
            (reduce-kv (fn [data mtype content]
                         (if (vector? content)
                           (assoc data mtype (process-chunks content))
                           data))
                       data
                       data))

          (prepare-font [data mtype]
            (when-let [resource (get data mtype)]

              (let [hash    (sto/calculate-hash resource)
                    content (-> (sto/content resource)
                                (sto/wrap-with-hash hash))]
                {::sto/content content
                 ::sto/touched-at (ct/now)
                 ::sto/deduplicate? true
                 :content-type mtype
                 :bucket "team-font-variant"})))

          (persist-fonts-files! [data]
            (let [otf-params (prepare-font data "font/otf")
                  ttf-params (prepare-font data "font/ttf")
                  wf1-params (prepare-font data "font/woff")
                  wf2-params (prepare-font data "font/woff2")]

              (cond-> {}
                (some? otf-params)
                (assoc :otf (sto/put-object! storage otf-params))
                (some? ttf-params)
                (assoc :ttf (sto/put-object! storage ttf-params))
                (some? wf1-params)
                (assoc :woff1 (sto/put-object! storage wf1-params))
                (some? wf2-params)
                (assoc :woff2 (sto/put-object! storage wf2-params)))))

          (insert-font-variant! [{:keys [woff1 woff2 otf ttf]}]
            (db/insert! conn :team-font-variant
                        {:id (uuid/next)
                         :team-id (:team-id params)
                         :font-id (:font-id params)
                         :font-family (:font-family params)
                         :font-weight (:font-weight params)
                         :font-style (:font-style params)
                         :woff1-file-id (:id woff1)
                         :woff2-file-id (:id woff2)
                         :otf-file-id (:id otf)
                         :ttf-file-id (:id ttf)}))]

    (let [data   (join-chunks data)
          data   (generate-missing data)
          assets (persist-fonts-files! data)
          result (insert-font-variant! assets)]
      (vary-meta result assoc ::audit/replace-props (update params :data (comp vec keys))))))

;; --- UPDATE FONT FAMILY (更新字体族)

;; --- 更新字体族名称 (Update Font Family)
;;
;; 【功能说明】
;; 更新指定字体族的名称。影响该字体族下的所有变体。
;;
;; 【参数】
;; team-id - 团队ID
;; id - 字体族ID
;; name - 新的字体族名称
;;
;; 【返回值】
;; 返回nil，变更通过Webhooks传播
;;
;; 【权限检查】
;; 需要团队编辑权限
;;
(def ^:private
  schema:update-font
  [:map {:title "update-font"}
   [:team-id ::sm/uuid]
   [:id ::sm/uuid]
   [:name :string]])

(sv/defmethod ::update-font
  {::doc/added "1.18"
   ::webhooks/event? true
   ::sm/params schema:update-font}
  [cfg {:keys [::rpc/profile-id team-id id name]}]
  (db/tx-run! cfg
              (fn [{:keys [::db/conn]}]
                (teams/check-edition-permissions! conn profile-id team-id)

                (db/update! conn :team-font-variant
                            {:font-family name}
                            {:font-id id
                             :team-id team-id})

                (rph/with-meta (rph/wrap nil)
                  {::audit/replace-props {:id id
                                          :name name
                                          :team-id team-id
                                          :profile-id profile-id}}))))

;; --- DELETE FONT (删除字体)

;; --- 删除字体 (Delete Font)
;;
;; 【功能说明】
;; 软删除指定的字体族及其所有变体。使用逻辑删除，
;; 数据在延迟期后会被永久清除。
;;
;; 【参数】
;; team-id - 团队ID
;; id - 字体族ID
;;
;; 【返回值】
;; 返回删除操作的审计属性
;;
;; 【删除延迟】
;; - 使用团队配置的删除延迟期
;; - 支持撤销（在延迟期内）
;;
;; 【权限检查】
;; 需要团队编辑权限
;;
(def ^:private
  schema:delete-font
  [:map {:title "delete-font"}
   [:team-id ::sm/uuid]
   [:id ::sm/uuid]])

(sv/defmethod ::delete-font
  {::doc/added "1.18"
   ::webhooks/event? true
   ::sm/params schema:delete-font
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id id team-id]}]
  (let [team  (teams/get-team conn
                              :profile-id profile-id
                              :team-id team-id)

        fonts (db/query conn :team-font-variant
                        {:team-id team-id
                         :font-id id
                         :deleted-at nil}
                        {::sql/for-update true})

        delay (ldel/get-deletion-delay team)
        tnow  (ct/in-future delay)]

    (teams/check-edition-permissions! (:permissions team))

    (when-not (seq fonts)
      (ex/raise :type :not-found
                :code :object-not-found))


    (doseq [font fonts]
      (db/update! conn :team-font-variant
                  {:deleted-at tnow}
                  {:id (:id font)}
                  {::db/return-keys false}))

    (rph/with-meta (rph/wrap)
      {::audit/props {:id id
                      :team-id team-id
                      :name (:font-family (peek fonts))
                      :profile-id profile-id}})))

;; --- DELETE FONT VARIANT (删除字体变体)

;; --- 删除字体变体 (Delete Font Variant)
;;
;; 【功能说明】
;; 软删除单个字体变体。使用逻辑删除机制。
;;
;; 【参数】
;; team-id - 团队ID
;; id - 字体变体ID
;;
;; 【返回值】
;; 返回删除操作的审计属性（字体族名称和字体ID）
;;
;; 【权限检查】
;; 需要团队编辑权限
;;
(def ^:private schema:delete-font-variant
  [:map {:title "delete-font-variant"}
   [:team-id ::sm/uuid]
   [:id ::sm/uuid]])

(sv/defmethod ::delete-font-variant
  {::doc/added "1.18"
   ::webhooks/event? true
   ::sm/params schema:delete-font-variant
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id id team-id]}]
  (let [team    (teams/get-team conn
                                :profile-id profile-id
                                :team-id team-id)
        variant (db/get conn :team-font-variant
                        {:id id :team-id team-id}
                        {::sql/for-update true})
        delay   (ldel/get-deletion-delay team)]

    (teams/check-edition-permissions! (:permissions team))
    (db/update! conn :team-font-variant
                {:deleted-at (ct/in-future delay)}
                {:id (:id variant)}
                {::db/return-keys false})

    (rph/with-meta (rph/wrap)
      {::audit/props {:font-family (:font-family variant)
                      :font-id (:font-id variant)}})))

;; --- DOWNLOAD FONT (下载字体)

;; --- 内部函数: 创建临时存储对象 (Make Temporal Storage Object)
;;
;; 【功能说明】
;; 将内容转换为临时存储对象。用于下载字体时创建临时文件。
;; 生成带哈希的内容，设置30分钟过期时间。
;;
;; 【参数】
;; cfg - 存储配置
;; profile-id - 用户ID
;; content - 包含mtype和path的内容映射
;;
;; 【返回值】
;; 存储对象
;;
(defn- make-temporal-storage-object
  [cfg profile-id content]
  (let [storage (sto/resolve cfg)
        content (media/check-input content)
        hash    (sto/calculate-hash (:path content))
        data    (-> (sto/content (:path content))
                    (sto/wrap-with-hash hash))
        mtype   (:mtype content "application/octet-stream")
        content {::sto/content data
                 ::sto/deduplicate? true
                 ::sto/touched-at (ct/in-future {:minutes 30})
                 :profile-id profile-id
                 :content-type mtype
                 :bucket "tempfile"}]

    (sto/put-object! storage content)))

(defn- make-variant-filename
  "生成字体变体文件名。
   
   【参数】
   v - 字体变体数据
   mtype - 内容类型（MIME类型）
   
   【返回值】
   格式为: font-family-weight-style.extension 的文件名"
  [v mtype]
  (str (:font-family v) "-" (:font-weight v)
       (when-not (= "normal" (:font-style v)) (str "-" (:font-style v)))
       (cmedia/mtype->extension mtype)))

;; --- 下载字体 (Download Font)
;;
;; 【功能说明】
;; 下载单个字体文件。自动选择最佳可用格式（优先TTF以获得更广泛的兼容性）。
;; 返回HTTP重定向到资源URI。
;;
;; 【参数】
;; id - 字体变体ID
;;
;; 【返回值】
;; 包含id、uri和name的映射
;;
;; 【格式优先级】
;; TTF > OTF > WOFF2 > WOFF1
;;
(def ^:private schema:download-font
  [:map {:title "download-font"}
   [:id ::sm/uuid]])

(sv/defmethod ::download-font
  "Download the font file. Returns a http redirect to the asset resource uri."
  {::doc/added "2.15"
   ::sm/params schema:download-font}
  [{:keys [::sto/storage ::db/pool] :as cfg} {:keys [::rpc/profile-id id]}]
  (let [variant (db/get pool :team-font-variant {:id id})]
    (teams/check-read-permissions! pool profile-id (:team-id variant))

    ;; Try to get the best available font format (prefer TTF for broader compatibility).
    (let [media-id (or (:ttf-file-id variant)
                       (:otf-file-id variant)
                       (:woff2-file-id variant)
                       (:woff1-file-id variant))
          sobj     (sto/get-object storage media-id)
          mtype    (-> sobj meta :content-type)]

      {:id (:id sobj)
       :uri (files/resolve-public-uri (:id sobj))
       :name (make-variant-filename variant mtype)})))

;; --- 下载字体族 (Download Font Family)
;;
;; 【功能说明】
;; 下载整个字体族作为ZIP文件。包含该族的所有字体变体。
;; 返回ZIP文件的字节流，不进行编码或JSON转换。
;;
;; 【参数】
;; font-id - 字体族ID
;;
;; 【返回值】
;; 包含id、uri和name的映射，name为 font-family.zip
;;
;; 【处理流程】
;; 1. 查询字体族的所有变体
;; 2. 为每个变体选择最佳格式
;; 3. 将所有字体打包成ZIP
;; 4. 创建临时存储对象
;; 5. 返回下载链接
;;
(def ^:private schema:download-font-family
  [:map {:title "download-font-family"}
   [:font-id ::sm/uuid]])

(sv/defmethod ::download-font-family
  "Download the entire font family as a zip file. Returns the zip
  bytes on the body, without encoding it on transit or json."
  {::doc/added "2.15"
   ::sm/params schema:download-font-family}
  [{:keys [::sto/storage ::db/pool] :as cfg} {:keys [::rpc/profile-id font-id]}]
  (let [variants (db/query pool :team-font-variant
                           {:font-id font-id
                            :deleted-at nil})]

    (when-not (seq variants)
      (ex/raise :type :not-found
                :code :object-not-found))

    (teams/check-read-permissions! pool profile-id (:team-id (first variants)))

    (let [tempfile (tmp/tempfile :suffix ".zip")
          ffamily  (-> variants first :font-family)]

      (with-open [^OutputStream output (io/output-stream tempfile)
                  ^OutputStream output (ZipOutputStream. output)]
        (doseq [v variants]
          (let [media-id (or (:ttf-file-id v)
                             (:otf-file-id v)
                             (:woff2-file-id v)
                             (:woff1-file-id v))
                sobj     (sto/get-object storage media-id)
                mtype    (-> sobj meta :content-type)
                name     (make-variant-filename v mtype)]

            (with-open [input (sto/get-object-data storage sobj)]
              (.putNextEntry ^ZipOutputStream output (ZipEntry. ^String name))
              (io/copy input output :size (:size sobj))
              (.closeEntry ^ZipOutputStream output)))))

      (let [{:keys [id] :as sobj} (make-temporal-storage-object cfg profile-id
                                                                {:mtype "application/zip"
                                                                 :path tempfile})]
        {:id id
         :uri (files/resolve-public-uri id)
         :name (str ffamily ".zip")}))))
