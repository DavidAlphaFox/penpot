;; =============================================================================
;; 帧导出处理器模块 (Export Frames Handler Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块处理帧级别的导出请求，将多个设计帧导出为合并的 PDF 文档。
;; 支持进度报告和错误处理。
;;
;; 【核心概念】
;; 1. PDF 合并 - 使用 pdfunite 将多个 PDF 合并为一个
;; 2. 进度报告 - 通过 Redis 发布导出进度更新
;; 3. 资源管理 - 创建临时 PDF 资源文件
;; 4. 上传处理 - 导出完成后上传到服务器
;;
;; 【依赖关系】
;; - app.renderer - 渲染器
;; - app.handlers.resources - 资源管理
;; - app.redis - Redis 客户端
;; - app.handlers.export-shapes - 导出准备工具
;;
;; =============================================================================

(ns app.handlers.export-frames
  (:require
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.handlers.export-shapes :refer [prepare-exports]]
   [app.handlers.resources :as rsc]
   [app.redis :as redis]
   [app.renderer :as rd]
   [app.util.shell :as sh]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(declare ^:private handle-export)
(declare ^:private create-pdf)
(declare ^:private join-pdf)
(declare ^:private move-file)

(s/def ::name ::us/string)
(s/def ::file-id ::us/uuid)
(s/def ::page-id ::us/uuid)
(s/def ::object-id ::us/uuid)

(s/def ::export
  (s/keys :req-un [::file-id ::page-id ::object-id ::name]))

(s/def ::exports
  (s/every ::export :kind vector? :min-count 1))

(s/def ::params
  (s/keys :req-un [::exports]
          :opt-un [::name]))

(defn handler
  "处理帧导出请求。
   
   【参数】
   [{:keys [:request/auth-token] :as exchange} {:keys [exports] :as params}] - 
     exchange: HTTP 交换对象
     params: 请求参数，包含 exports 列表
   
   【返回值】
   添加了响应信息的 exchange 对象。
   
   【功能说明】
   1. 准备导出参数列表（添加 type、scale、suffix）
   2. 调用 handle-export 进行实际导出处理"
  [{:keys [:request/auth-token] :as exchange} {:keys [exports] :as params}]
  ;; NOTE: we need to have the `:type` prop because the exports
  ;; datastructure preparation uses it for creating the groups.
  (let [exports  (-> (map #(assoc % :type :pdf :scale 1 :suffix "") exports)
                     (prepare-exports auth-token))]

    (handle-export exchange (assoc params :exports exports))))

(defn handle-export
  "执行帧导出。
   
   【参数】
   [{:keys [:request/auth-token] :as exchange} {:keys [exports name profile-id] :as params}] -
     exchange: HTTP 交换对象
     params: 请求参数
   
   【返回值】
   添加了响应信息的 exchange 对象。
   
   【功能说明】
   1. 创建 PDF 资源
   2. 设置进度回调（通过 Redis 发布）
   3. 设置完成回调和错误回调
   4. 并行渲染所有帧
   5. 合并 PDF 文件
   6. 移动文件到目标位置
   7. 上传资源"
  [{:keys [:request/auth-token] :as exchange} {:keys [exports name profile-id] :as params}]
  (let [topic       (str profile-id)
        file-id     (-> exports first :file-id)

        resource
        (rsc/create :pdf (or name (-> exports first :name)))

        on-progress
        (fn [done]
          (let [data {:type :export-update
                      :resource-id (:id resource)
                      :status "running"
                      :done done}]
            (redis/pub! topic data)))

        on-complete
        (fn [resource]
          (let [data {:type :export-update
                      :resource-id (:id resource)
                      :resource-uri (:uri resource)
                      :name (:name resource)
                      :filename (:filename resource)
                      :mtype (:mtype resource)
                      :status "ended"}]
            (redis/pub! topic data)))

        on-error
        (fn [cause]
          (l/error :hint "unexpected error on frames exportation" :cause cause)
          (let [data {:type :export-update
                      :resource-id (:id resource)
                      :name (:name resource)
                      :filename (:filename resource)
                      :status "error"
                      :cause (ex-message cause)}]
            (redis/pub! topic data)))

        result-cache
        (atom [])

        on-object
        (fn [{:keys [path] :as object}]
          (let [res (swap! result-cache conj path)]
            (on-progress (count res))))

        procs
        (->> (seq exports)
             (map #(rd/render % on-object)))]

    (->> (p/all procs)
         (p/fmap (fn [] @result-cache))
         (p/mcat (partial join-pdf file-id))
         (p/mcat (partial move-file resource))
         (p/fmap (constantly resource))
         (p/mcat (partial rsc/upload-resource auth-token))
         (p/mcat (fn [resource]
                   (->> (sh/stat (:path resource))
                        (p/fmap #(merge resource %)))))
         (p/merr on-error)
         (p/fnly (fn [resource cause]
                   (when-not cause
                     (on-complete resource)))))

    (assoc exchange :response/body (dissoc resource :path))))

(defn- join-pdf
  "合并多个 PDF 文件。
   
   【参数】
   file-id - 文件 ID（用于生成临时文件前缀）
   paths - PDF 文件路径列表
   
   【返回值】
   Promise，解析为合并后的 PDF 文件路径。"
  [file-id paths]
  (p/let [prefix (str/concat "penpot.pdfunite." file-id ".")
          path   (sh/tempfile :prefix prefix :suffix ".pdf")]
    (sh/run-cmd! (str "pdfunite " (str/join " " paths) " " path))
    path))

(defn- move-file
  "移动文件到目标位置。
   
   【参数】
   {:keys [path] :as resource} - 资源对象
   output-path - 目标路径
   
   【返回值】
   资源对象。"
  [{:keys [path] :as resource} output-path]
  (p/do
    (sh/move! output-path path)
    resource))
