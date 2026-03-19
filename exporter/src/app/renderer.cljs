;; =============================================================================
;; 渲染入口模块 (Renderer Entry Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块是导出渲染的统一入口，提供根据导出类型分发到具体渲染器的功能。
;; 支持 SVG、PDF、PNG、JPEG 和 WebP 等多种导出格式。
;;
;; 【核心概念】
;; 1. 类型分发 - 根据 type 参数将渲染请求分发到对应的渲染器
;; 2. 渲染参数验证 - 使用 spec 验证渲染参数的合法性
;; 3. 回调模式 - 使用 on-object 回调处理每个对象的渲染结果
;; 4. 统一接口 - 为不同格式提供一致的渲染接口
;;
;; 【依赖关系】
;; - app.renderer.svg - SVG 渲染器
;; - app.renderer.pdf - PDF 渲染器
;; - app.renderer.bitmap - 位图渲染器（PNG/JPEG/WebP）
;;
;; =============================================================================

(ns app.renderer
  "Common renderer interface."
  (:require
   [app.common.spec :as us]
   [app.renderer.bitmap :as rb]
   [app.renderer.pdf :as rp]
   [app.renderer.svg :as rs]
   [cljs.spec.alpha :as s]))

(s/def ::name ::us/string)
(s/def ::suffix ::us/string)
(s/def ::type #{:png :jpeg :webp :pdf :svg})
(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::share-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::token ::us/string)
(s/def ::filename ::us/string)

(s/def ::object
  (s/keys :req-un [::id ::name ::suffix ::filename]
          :opt-un [::share-id]))

(s/def ::objects
  (s/coll-of ::object :min-count 1))

(s/def ::render-params
  (s/keys :req-un [::file-id ::page-id ::scale ::token ::type ::objects]))

(defn render
  "执行渲染操作。
   
   【参数】
   {:keys [type] :as params} - 渲染参数，包含：
     - type: 导出类型 (:png :jpeg :webp :pdf :svg)
     - file-id: 文件 ID
     - page-id: 页面 ID
     - scale: 缩放比例
     - token: 认证令牌
     - objects: 要导出的对象列表
   on-object - 回调函数，接收渲染完成的单个对象
   
   【返回值】
   Promise，解析为 nil。
   
   【功能说明】
   根据 type 参数将请求分发到对应的渲染器：
   - :png/:jpeg/:webp -> bitmap 渲染器
   - :pdf -> pdf 渲染器
   - :svg -> svg 渲染器"
  [{:keys [type] :as params} on-object]
  (us/verify ::render-params params)
  (us/verify fn? on-object)
  (case type
    :png  (rb/render params on-object)
    :jpeg (rb/render params on-object)
    :webp (rb/render params on-object)
    :pdf  (rp/render params on-object)
    :svg  (rs/render params on-object)))

