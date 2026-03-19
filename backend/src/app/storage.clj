;; =============================================================================
;; 存储服务层 (Storage Service Layer)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块是 Penpot 的对象存储抽象层，支持多种存储后端（S3、文件系统）。
;; 提供统一的存储接口，用于存储用户上传的文件、图片、设计资源等数据。
;; 支持存储对象的元数据管理、哈希计算和生命周期管理。
;;
;; 【核心概念】
;; 1. Storage Backend - 存储后端抽象（S3、文件系统）
;; 2. Storage Object - 存储对象，包含 ID、大小、创建时间、后端类型等元数据
;; 3. Content Hash - 内容哈希，用于去重和完整性验证
;; 4. Bucket - 存储桶概念，用于组织不同类型的对象
;; 5. Touch - 标记对象被访问，用于垃圾回收
;;
;; 【依赖关系】
;; - app.storage.fs - 文件系统存储实现
;; - app.storage.s3 - S3 存储实现
;; - app.storage.impl - 存储接口定义
;; - app.db - 数据库操作
;;
;; =============================================================================

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.storage
  "Objects storage abstraction layer."
  (:refer-clojure :exclude [resolve])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.storage.fs :as sfs]
   [app.storage.impl :as impl]
   [app.storage.s3 :as ss3]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [integrant.core :as ig])
  (:import
   java.io.InputStream))

(defn get-legacy-backend
  "获取遗留存储后端配置。
   
   【功能】
   检查是否使用了已废弃的存储后端配置（assets-storage-backend）。
   如果使用了旧配置，输出警告信息并返回对应的后端类型。
   
   【返回值】
   存储后端关键字（:fs 或 :s3），如果未使用旧配置返回 nil"
  []
  (when-let [name (cf/get :assets-storage-backend)]
    (l/wrn :hint "using deprecated configuration, please read 2.11 release notes"
           :href "https://github.com/penpot/penpot/releases/tag/2.11.0")
    (case name
      :assets-fs :fs
      :assets-s3 :s3
      nil)))

(def default-bucket
  "默认存储桶名称。
   
   【说明】
   用于存储通用媒体文件的默认存储桶标识符"
  "file-media-object")

(def valid-buckets
  "有效的存储桶名称集合。
   
   【说明】
   定义系统中所有合法的存储桶标识符，用于验证和限制存储对象的分类"
  #{"file-media-object"
    "team-font-variant"
    "file-object-thumbnail"
    "file-thumbnail"
    "profile"
    "tempfile"
    "file-data"
    "file-data-fragment"
    "file-change"})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Storage Module State
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:backends
  [:map-of :keyword
   [:maybe
    [:or ::ss3/backend ::sfs/backend]]])

(def ^:private valid-backends?
  (sm/validator schema:backends))

(def ^:private schema:storage
  [:map {:title "storage"}
   [::backends schema:backends]
   [::backend [:enum :s3 :fs]]
   ::db/connectable])

(def valid-storage?
  (sm/validator schema:storage))

(sm/register! ::storage schema:storage)

(defmethod ig/assert-key ::storage
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected valid database pool")
  (assert (valid-backends? (::backends params)) "expected valid backends map"))

(defmethod ig/init-key ::storage
  [_ {:keys [::backends ::db/pool] :as cfg}]
  (let [backend (or (get-legacy-backend)
                    (cf/get :objects-storage-backend)
                    :fs)
        backends (d/without-nils backends)]

    (l/dbg :hint "initialize"
           :default (d/name backend)
           :available (str/join "," (map d/name (keys backends))))

    (-> (d/without-nils cfg)
        (assoc ::backends backends)
        (assoc ::backend backend)
        (assoc ::db/connectable pool))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Database Objects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-metadata
  "提取存储对象的元数据。
   
   【功能】
   从参数映射中提取元数据，移除所有限定关键字（qualified keywords）。
   限定关键字通常是命名空间限定的键，用于内部处理而非存储。
   
   【参数】
   params - 参数映射
   
   【返回值】
   移除限定关键字后的映射"
  [params]
  (reduce-kv (fn [res k _]
               (if (qualified-keyword? k)
                 (dissoc res k)
                 res))
             params
             params))

(defn- get-database-object-by-hash
  "通过内容哈希查找存储对象。
   
   【功能】
   根据内容哈希和存储桶查找已存在的存储对象，用于去重处理。
   仅返回未删除的对象。
   
   【参数】
   connectable - 数据库连接
   backend - 存储后端类型
   bucket - 存储桶名称
   hash - 内容哈希值
   
   【返回值】
   找到的存储对象，如果不存在返回 nil"
  [connectable backend bucket hash]
  (let [sql (str "select * from storage_object "
                 " where (metadata->>'~:hash') = ? "
                 "   and (metadata->>'~:bucket') = ? "
                 "   and backend = ?"
                 "   and deleted_at is null"
                 " limit 1")]
    (some-> (db/exec-one! connectable [sql hash bucket (name backend)])
            (update :metadata db/decode-transit-pgobject))))

(defn- create-database-object
  "创建数据库中的存储对象记录。
   
   【功能】
   1. 为存储对象生成或使用提供的 ID
   2. 如果启用去重，查找是否存在相同哈希的对象
   3. 在数据库中创建存储对象记录
   4. 返回存储对象结构
   
   【参数】
   storage - 存储实例（包含 backend 和 connectable）
   params - 对象参数，包含：
     - ::content - 存储内容（可选，支持 IContentHash 接口）
     - ::expired-at - 过期时间（可选）
     - ::touched-at - 访问时间（可选）
     - ::touch - 是否标记为已访问
     - ::deduplicate? - 是否启用去重
     - ::id - 指定的对象 ID（可选）
   
   【返回值】
   存储对象结构"
  [{:keys [::backend ::db/connectable]} {:keys [::content ::expired-at ::touched-at ::touch] :as params}]
  (let [id     (or (::id params) (uuid/random))
        mdata  (cond-> (get-metadata params)
                 (satisfies? impl/IContentHash content)
                 (assoc :hash (impl/get-hash content)))

        touched-at (if touch
                     (or touched-at (ct/now))
                     touched-at)

        ;; NOTE: for now we don't reuse the deleted objects, but in
        ;; futute we can consider reusing deleted objects if we
        ;; found a duplicated one and is marked for deletion but
        ;; still not deleted.
        result (when (and (::deduplicate? params)
                          (:hash mdata)
                          (:bucket mdata))
                 (let [result (get-database-object-by-hash connectable backend
                                                           (:bucket mdata)
                                                           (:hash mdata))]
                   (if touch
                     (do
                       (db/update! connectable :storage-object
                                   {:touched-at touched-at}
                                   {:id (:id result)}
                                   {::db/return-keys false})
                       (assoc result :touced-at touched-at))
                     result)))

        result (or result
                   (-> (db/insert! connectable :storage-object
                                   {:id id
                                    :size (impl/get-size content)
                                    :backend (name backend)
                                    :metadata (db/tjson mdata)
                                    :deleted-at expired-at
                                    :touched-at touched-at})
                       (update :metadata db/decode-transit-pgobject)
                       (update :metadata assoc ::created? true)))]

    (impl/storage-object
     (:id result)
     (:size result)
     (:created-at result)
     (:deleted-at result)
     (:touched-at result)
     backend
     (:metadata result))))

(defn row->storage-object
  "将数据库行转换为存储对象。
   
   【功能】
   从数据库查询结果创建存储对象结构，处理元数据的反序列化。
   
   【参数】
   res - 数据库行结果
   
   【返回值】
   存储对象结构"
  [res]
  (let [mdata (or (some-> (:metadata res) (db/decode-transit-pgobject)) {})]
    (impl/storage-object
     (:id res)
     (:size res)
     (:created-at res)
     (:deleted-at res)
     (:touched-at res)
     (keyword (:backend res))
     mdata)))

(def ^:private sql:get-storage-object
  "SELECT *
     FROM storage_object
    WHERE id = ?
      AND (deleted_at IS NULL)")

(defn- get-database-object
  "从数据库获取存储对象。
   
   【功能】
   根据对象 ID 从数据库查询存储对象，仅返回未删除的对象。
   
   【参数】
   conn - 数据库连接
   id - 对象 ID
   
   【返回值】
   存储对象，如果不存在或已删除返回 nil"
  [conn id]
  (some-> (db/exec-one! conn [sql:get-storage-object id])
          (row->storage-object)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn object->relative-path
  "将存储对象转换为相对路径。
   
   【参数】
   obj - 存储对象
   
   【返回值】
   相对路径字符串"
  [{:keys [id] :as obj}]
  (impl/id->path id))

(defn file-url->path
  "将文件 URL 转换为路径。
   
   【参数】
   url - 文件 URL
   
   【返回值】
   路径对象"
  [url]
  (when url
    (fs/path (java.net.URI. (str url)))))

(dm/export impl/content)
(dm/export impl/wrap-with-hash)
(dm/export impl/object?)

(defn get-object
  "获取存储对象。
   
   【参数】
   storage - 存储实例
   id - 对象 ID
   
   【返回值】
   存储对象，如果不存在返回 nil"
  [{:keys [::db/connectable] :as storage}  id]
  (assert (valid-storage? storage))
  (get-database-object connectable id))

(defn put-object!
  "创建新的存储对象。
   
   【功能】
   1. 在数据库中创建对象记录
   2. 将内容存储到后端存储系统
   3. 支持去重（如果启用且找到重复对象则返回已有对象）
   
   【参数】
   storage - 存储实例
   params - 对象参数（包含 ::content 等）
   
   【返回值】
   创建的存储对象"
  [{:keys [::backend] :as storage} {:keys [::content] :as params}]
  (assert (valid-storage? storage))
  (assert (impl/content? content) "expected an instance of content")

  (let [object (create-database-object storage params)]
    (if (::created? (meta object))
      ;; Store the data finally on the underlying storage subsystem.
      (-> (impl/resolve-backend storage backend)
          (impl/put-object object content))
      object)))

(defn touch-object!
  "标记对象为已访问。
   
   【功能】
   更新对象的 touched-at 时间戳，用于垃圾回收判断。
   
   【参数】
   storage - 存储实例
   object-or-id - 存储对象或对象 ID
   
   【返回值】
   是否成功更新"
  [{:keys [::db/connectable] :as storage} object-or-id]
  (assert (valid-storage? storage))
  (let [id (if (impl/object? object-or-id) (:id object-or-id) object-or-id)]
    (-> (db/update! connectable :storage-object
                    {:touched-at (ct/now)}
                    {:id id})
        (db/get-update-count)
        (pos?))))

(defn get-object-data
  "获取对象内容作为输入流。
   
   【参数】
   storage - 存储实例
   object - 存储对象
   
   【返回值】
   InputStream 实例，如果对象已过期返回 nil"
  ^InputStream
  [storage object]
  (assert (valid-storage? storage))
  (when (or (nil? (:expired-at object))
            (ct/is-after? (:expired-at object) (ct/now)))
    (-> (impl/resolve-backend storage (:backend object))
        (impl/get-object-data object))))

(defn get-object-bytes
  "获取对象内容的字节数组。
   
   【参数】
   storage - 存储实例
   object - 存储对象
   
   【返回值】
   字节数组，如果对象已过期返回 nil"
  [storage object]
  (assert (valid-storage? storage))
  (when (or (nil? (:expired-at object))
            (ct/is-after? (:expired-at object) (ct/now)))
    (-> (impl/resolve-backend storage (:backend object))
        (impl/get-object-bytes object))))

(defn get-object-url
  "获取对象的访问 URL。
   
   【参数】
   storage - 存储实例
   object - 存储对象
   options - 可选参数
   
   【返回值】
   访问 URL，如果对象已过期返回 nil"
  ([storage object]
   (get-object-url storage object nil))
  ([storage object options]
   (assert (valid-storage? storage))
   (when (or (nil? (:expired-at object))
             (ct/is-after? (:expired-at object) (ct/now)))
     (-> (impl/resolve-backend storage (:backend object))
         (impl/get-object-url object options)))))

(defn get-object-path
  "获取对象的路径（仅适用于文件系统存储）。
   
   【参数】
   storage - 存储实例
   object - 存储对象
   
   【返回值】
   路径对象，如果后端不是文件系统或对象已过期返回 nil"
  [storage object]
  (assert (valid-storage? storage))
  (let [backend (impl/resolve-backend storage (:backend object))]
    (when (and (= :fs (::type backend))
               (or (nil? (:expired-at object))
                   (ct/is-after? (:expired-at object) (ct/now))))
      (-> (impl/get-object-url backend object nil) file-url->path))))

(defn del-object!
  "删除存储对象（软删除）。
   
   【功能】
   将存储对象标记为已删除，设置删除时间戳。
   这是软删除操作，数据仍保留在数据库中。
   
   【参数】
   storage - 存储实例
   object-or-id - 存储对象或对象 ID
   
   【返回值】
   是否成功更新（布尔值）"
  [{:keys [::db/connectable] :as storage} object-or-id]
  (assert (valid-storage? storage))
  (let [id  (if (impl/object? object-or-id) (:id object-or-id) object-or-id)
        res (db/update! connectable :storage-object
                        {:deleted-at (ct/now)}
                        {:id id})]
    (pos? (db/get-update-count res))))

(dm/export impl/calculate-hash)
(dm/export impl/get-hash)
(dm/export impl/get-size)

(defn configure
  "配置存储实例的数据库连接。
   
   【功能】
   为存储实例关联数据库连接，使其能够执行数据库操作。
   
   【参数】
   storage - 存储实例
   connectable - 数据库连接
   
   【返回值】
   配置了数据库连接的存储实例"
  [storage connectable]
  (assert (valid-storage? storage))
  (assoc storage ::db/connectable connectable))

(defn resolve
  "解析存储实例。
   
   【功能】
   从配置中获取已配置的存储实例。可选择重用配置中的数据库连接。
   
   【参数】
   cfg - 系统配置
   opts - 可选参数：
     - ::db/reuse-conn - 是否重用配置中的数据库连接（默认 false）
   
   【返回值】
   存储实例"
  [cfg & {:as opts}]
  (let [storage (::storage cfg)]
    (if (::db/reuse-conn opts false)
      (configure storage (db/get-connectable cfg))
      storage)))
