;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

;; =============================================================================
;; 媒体文件管理模块 (Media File Management Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块提供文件媒体对象(File Media Object)相关的RPC命令,
;; 包括图片上传、从URL创建、克隆等功能。
;; 媒体对象是Penpot中用于存储设计文件中使用的图片资源的实体。
;;
;; 【核心概念】
;; 1. 文件媒体对象(File Media Object) - 存储在文件中的图片资源
;; 2. 缩略图(Thumbnail) - 为大图片自动生成的预览缩略图
;; 3. 媒体处理(Media Processing) - 图片的格式转换、尺寸调整等处理
;; 4. 存储对象(Storage Object) - 实际存储媒体数据的对象
;;
;; 【依赖关系】
;; - app.media - 媒体处理相关功能
;; - app.storage - 存储服务
;; - app.rpc.commands.files - 文件操作相关命令
;; - app.rpc.climit - 并发限制控制
;;
;; 【主要功能】
;; - 上传图片到文件
;; - 从URL创建媒体对象
;; - 克隆/复制媒体对象
;; - 图片处理（生成缩略图、计算哈希、去重）
;;
;; =============================================================================

(ns app.rpc.commands.media
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.loggers.audit :as-alias audit]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as climit]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.storage :as sto]
   [app.util.services :as sv]))

;; 缩略图配置选项
;; 用于生成图片缩略图的默认参数
(def thumbnail-options
  {:width 100
   :height 100
   :quality 85
   :format :jpeg})

;; --- Create File Media object (upload)

;; 声明后续定义的函数
(declare create-file-media-object)

;; 上传文件媒体对象的请求模式
(def ^:private schema:upload-file-media-object
  [:map {:title "upload-file-media-object"}
   [:id {:optional true} ::sm/uuid]
   [:file-id ::sm/uuid]
   [:is-local ::sm/boolean]
   [:name [:string {:max 250}]]
   [:content media/schema:upload]])

;; 上传文件媒体对象的RPC入口点
;; 验证权限和媒体类型后，创建媒体对象并更新文件的修改时间
(sv/defmethod ::upload-file-media-object
  {::doc/added "1.17"
   ::sm/params schema:upload-file-media-object
   ::climit/id [[:process-image/by-profile ::rpc/profile-id]
                [:process-image/global]]}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id content] :as params}]
  (files/check-edition-permissions! pool profile-id file-id)
  (media/validate-media-type! content)
  (media/validate-media-size! content)

  (db/run! cfg (fn [{:keys [::db/conn] :as cfg}]
                 ;; We get the minimal file for proper checking if
                 ;; file is not already deleted
                 (let [_     (files/get-minimal-file conn file-id)
                       mobj  (create-file-media-object cfg params)]

                   (db/update! conn :file
                               {:modified-at (ct/now)
                                :has-media-trimmed false}
                               {:id file-id}
                               {::db/return-keys false})

                   (with-meta mobj
                     {::audit/replace-props
                      {:name (:name params)
                       :file-id file-id
                       :is-local (:is-local params)
                       :size (:size content)
                       :mtype (:mtype content)}})))))

;; 检查图片是否足够大以生成独立缩略图
;; 如果图片尺寸大于缩略图选项，则生成缩略图
;;
;; 【参数】
;; info - 图片信息映射，包含 :width 和 :height
;;
;; 【返回值】
;; 如果图片尺寸大于缩略图选项返回 true，否则返回 false
(defn- big-enough-for-thumbnail?
  "Checks if the provided image info is big enough for
  create a separate thumbnail storage object."
  [info]
  (or (> (:width info) (:width thumbnail-options))
      (> (:height info) (:height thumbnail-options))))

;; 检查是否为SVG图片
;;
;; 【参数】
;; info - 图片信息映射，包含 :mtype
;;
;; 【返回值】
;; 如果MIME类型为 image/svg+xml 返回 true，否则返回 false
(defn- svg-image?
  [info]
  (= (:mtype info) "image/svg+xml"))

;; NOTE: we use the `on conflict do update` instead of `do nothing`
;; because postgresql does not returns anything if no update is
;; performed, the `do update` does the trick.

;; 创建文件媒体对象的SQL语句
;; 使用 upsert 模式，如果存在则更新创建时间
(def sql:create-file-media-object
  "insert into file_media_object (id, file_id, is_local, name, media_id, thumbnail_id, width, height, mtype)
   values (?, ?, ?, ?, ?, ?, ?, ?, ?)
       on conflict (id) do update set created_at=file_media_object.created_at
       returning *")

;; NOTE: the following function executes without a transaction, this
;; means that if something fails in the middle of this function, it
;; will probably leave leaked/unreferenced objects in the database and
;; probably in the storage layer. For handle possible object leakage,
;; we create all media objects marked as touched, this ensures that if
;; something fails, all leaked (already created storage objects) will
;; be eventually marked as deleted by the touched-gc task.
;;
;; The touched-gc task, performs periodic analysis of all touched
;; storage objects and check references of it. This is the reason why
;; `reference` metadata exists: it indicates the name of the table
;; witch holds the reference to storage object (it some kind of
;; inverse, soft referential integrity).

;; 处理主图图片
;; 计算哈希、读取内容、准备存储参数
;;
;; 【参数】
;; info - 图片信息映射，包含 :path、:mtype、:ts
;;
;; 【返回值】
;; 存储对象参数映射，包含 :content-type、:bucket、::sto/content、::sto/deduplicate?、::sto/touched-at
(defn- process-main-image
  [info]
  (let [hash (sto/calculate-hash (:path info))
        data (-> (sto/content (:path info))
                 (sto/wrap-with-hash hash))]
    {::sto/content data
     ::sto/deduplicate? true
     ::sto/touched-at (:ts info)
     :content-type (:mtype info)
     :bucket "file-media-object"}))

;; 处理缩略图图片
;; 生成缩略图、计算哈希、准备存储参数
;;
;; 【参数】
;; info - 图片信息映射，包含 :path、:mtype、:ts
;;
;; 【返回值】
;; 存储对象参数映射，包含 :content-type、:bucket、::sto/content、::sto/deduplicate?、::sto/touched-at
(defn- process-thumb-image
  [info]
  (let [thumb (-> thumbnail-options
                  (assoc :cmd :generic-thumbnail)
                  (assoc :input info)
                  (media/run))
        hash  (sto/calculate-hash (:data thumb))
        data  (-> (sto/content (:data thumb) (:size thumb))
                  (sto/wrap-with-hash hash))]
    {::sto/content data
     ::sto/deduplicate? true
     ::sto/touched-at (:ts info)
     :content-type (:mtype thumb)
     :bucket "file-media-object"}))

;; 处理图片（主图和可选的缩略图）
;; 分析图片信息，决定是否生成缩略图
;;
;; 【参数】
;; content - 上传的媒体内容
;;
;; 【返回值】
;; 处理结果映射，包含 :mtype、:width、:height、::image（主图）、::thumb（缩略图，可选）
(defn- process-image
  [content]
  (let [info (media/run {:cmd :info :input content})]
    (cond-> info
      (and (not (svg-image? info))
           (big-enough-for-thumbnail? info))
      (assoc ::thumb (process-thumb-image info))

      :always
      (assoc ::image (process-main-image info)))))

;; 创建文件媒体对象（内部函数）
;; 处理图片、上传到存储、创建数据库记录
;;
;; 【参数】
;; cfg - 配置映射，包含 :sto/storage 和 :db/conn
;; params - 参数映射，包含 :id、:file-id、:is-local、:name、:content
;;
;; 【返回值】
;; 创建的媒体对象映射
(defn- create-file-media-object
  [{:keys [::sto/storage ::db/conn] :as cfg}
   {:keys [id file-id is-local name content]}]
  (let [result (process-image content)
        image  (sto/put-object! storage (::image result))
        thumb  (when-let [params (::thumb result)]
                 (sto/put-object! storage params))]

    (db/exec-one! conn [sql:create-file-media-object
                        (or id (uuid/next))
                        file-id is-local name
                        (:id image)
                        (:id thumb)
                        (:width result)
                        (:height result)
                        (:mtype result)])))

;; --- Create File Media Object (from URL)

;; 声明后续定义的函数
(declare ^:private create-file-media-object-from-url)

;; 从URL创建文件媒体对象的请求模式
(def ^:private schema:create-file-media-object-from-url
  [:map {:title "create-file-media-object-from-url"}
   [:file-id ::sm/uuid]
   [:is-local ::sm/boolean]
   [:url ::sm/uri]
   [:id {:optional true} ::sm/uuid]
   [:name {:optional true} [:string {:max 250}]]])

;; 从URL创建文件媒体对象的RPC入口点
;; 下载远程图片并创建为文件媒体对象
(sv/defmethod ::create-file-media-object-from-url
  {::doc/added "1.17"
   ::sm/params schema:create-file-media-object-from-url}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (files/check-edition-permissions! pool profile-id file-id)
  ;; We get the minimal file for proper checking if file is not
  ;; already deleted
  (let [_    (files/get-minimal-file cfg file-id)
        mobj (create-file-media-object-from-url cfg (assoc params :profile-id profile-id))]

    (db/update! pool :file
                {:modified-at (ct/now)
                 :has-media-trimmed false}
                {:id file-id}
                {::db/return-keys false})

    mobj))

;; 从URL创建文件媒体对象（内部函数）
;; 下载图片后调用标准的创建媒体对象流程
;;
;; 【参数】
;; cfg - 配置映射
;; params - 参数映射，包含 :url、:name 等
;;
;; 【返回值】
;; 创建的媒体对象映射
(defn- create-file-media-object-from-url
  [cfg {:keys [url name] :as params}]
  (let [content (media/download-image cfg url)
        params  (-> params
                    (assoc :content content)
                    (assoc :name (d/nilv name "unknown")))]

    ;; NOTE: we use the climit here in a dynamic invocation because we
    ;; don't want saturate the process-image limit with IO (download
    ;; of external image)

    (-> cfg
        (assoc ::climit/id [[:process-image/by-profile (:profile-id params)]
                            [:process-image/global]])
        (assoc ::climit/label "create-file-media-object-from-url")
        (climit/invoke! #(db/run! %1 create-file-media-object %2) params))))


;; --- Clone File Media object (Upload and create from url)

;; 声明后续定义的函数
(declare clone-file-media-object)

;; 克隆文件媒体对象的请求模式
(def ^:private schema:clone-file-media-object
  [:map {:title "clone-file-media-object"}
   [:file-id ::sm/uuid]
   [:is-local ::sm/boolean]
   [:id ::sm/uuid]])

;; 克隆文件媒体对象的RPC入口点
;; 复制一个已有的媒体对象到新的文件
(sv/defmethod ::clone-file-media-object
  {::doc/added "1.17"
   ::sm/params schema:clone-file-media-object
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id file-id] :as params}]
  (files/check-edition-permissions! conn profile-id file-id)
  (clone-file-media-object cfg params))

;; 克隆文件媒体对象
;; 在同一个文件或不同文件间复制媒体对象
;; 实际存储对象（media-id、thumbnail-id）不复制，只复制引用
;;
;; 【参数】
;; cfg - 配置映射，包含 :db/conn
;; params - 参数映射，包含 :id（原媒体对象ID）、:file-id（目标文件ID）、:is-local
;;
;; 【返回值】
;; 创建的新媒体对象映射
(defn clone-file-media-object
  [{:keys [::db/conn]} {:keys [id file-id is-local]}]
  (let [mobj (db/get-by-id conn :file-media-object id)]
    (db/insert! conn :file-media-object
                {:id (uuid/next)
                 :file-id file-id
                 :is-local is-local
                 :name (:name mobj)
                 :media-id (:media-id mobj)
                 :thumbnail-id (:thumbnail-id mobj)
                 :width  (:width mobj)
                 :height (:height mobj)
                 :mtype  (:mtype mobj)})))
