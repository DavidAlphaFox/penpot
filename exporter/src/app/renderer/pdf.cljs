;; =============================================================================
;; PDF 渲染模块 (PDF Renderer Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块负责将 Penpot 设计导出为 PDF 文档格式。
;; 通过浏览器渲染页面后使用 Playwright 的 PDF 功能生成文档。
;;
;; 【核心概念】
;; 1. 页面大小同步 - 根据设计元素设置精确的 PDF 页面大小
;; 2. 多对象合并 - 将多个设计对象合并为单个 PDF 文档
;; 3. 透明背景处理 - 处理页面背景以确保正确的 PDF 输出
;;
;; 【依赖关系】
;; - app.browser - 浏览器操作 API
;; - app.util.shell - Shell 命令执行
;; - app.util.mime - MIME 类型工具
;;
;; =============================================================================

(ns app.renderer.pdf
  "PDF 渲染器模块。"
  (:require
   [app.browser :as bw]
   [app.common.data.macros :as dm]
   [app.common.logging :as l]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.util.mime :as mime]
   [app.util.shell :as sh]
   [promesa.core :as p]))

(defn render
  "渲染 PDF 格式的导出。
   
   【参数】
   {:keys [file-id page-id share-id token scale type objects] :as params} - 渲染参数：
     - file-id: 文件 ID
     - page-id: 页面 ID
     - share-id: 分享 ID
     - token: 认证令牌
     - scale: 缩放比例
     - type: 导出类型（pdf）
     - objects: 要导出的对象列表
   on-object - 回调函数，接收渲染完成的对象
   
   【返回值】
   Promise。
   
   【功能说明】
   1. 准备浏览器选项和基础 URI
   2. 在浏览器中执行每个对象的渲染
   3. 同步设置页面大小（CSS @page 规则）
   4. 等待页面稳定后生成 PDF
   5. 调用回调处理结果"
  [{:keys [file-id page-id share-id token scale type objects] :as params} on-object]
  (letfn [(prepare-options [uri]
            #js {:screen #js {:width bw/default-viewport-width
                              :height bw/default-viewport-height}
                 :viewport #js {:width bw/default-viewport-width
                                :height bw/default-viewport-height}
                 :locale "en-US"
                 :storageState #js {:cookies (bw/create-cookies uri {:token token})}
                 :deviceScaleFactor scale
                 :userAgent bw/default-user-agent})

          (prepare-uri [base-uri object-id]
            (let [params {:file-id file-id
                          :page-id page-id
                          :share-id share-id
                          :object-id object-id
                          :route "objects"}]
              (-> base-uri
                  (assoc :path "/render.html")
                  (assoc :query (u/map->query-string params)))))

          (sync-page-size! [dom]
            (bw/eval! dom
                      (fn [elem]
                        ;; IMPORTANT: No CLJS runtime allowed. Use only JS
                        ;; primitives.  This runs in a context without access to
                        ;; cljs.core. Avoid any functions that transpile to
                        ;; cljs.core/* calls, as they will break in the browser
                        ;; runtime.

                        (let [width (.getAttribute ^js elem "width")
                              height (.getAttribute ^js elem "height")
                              style-node (let [node (.createElement js/document "style")]
                                           (.appendChild (.-head js/document) node)
                                           node)]
                          (set! (.-textContent style-node)
                                (dm/str "@page { size: " width "px " height "px; margin: 0; }\n"
                                        "html, body, #app { margin: 0; padding: 0; width: " width "px; height: " height "px; overflow: visible; }"))))))

          (render-object [page base-uri {:keys [id] :as object}]
            (p/let [uri  (prepare-uri base-uri id)
                    path (sh/tempfile :prefix "penpot.tmp.pdf." :suffix (mime/get-extension type))]
              (l/info :uri uri)
              (bw/nav! page uri)
              (p/let [dom (bw/select page (dm/str "#screenshot-" id))]
                (bw/wait-for dom)
                (sync-page-size! dom)
                (bw/screenshot dom {:full-page? true})
                (bw/sleep page 2000) ; the good old fix with sleep
                (bw/pdf page {:path path})
                path)))

          (render [base-uri page]
            (p/loop [objects (seq objects)]
              (when-let [object (first objects)]
                (p/let [path (render-object page base-uri object)]
                  (on-object (assoc object :path path))
                  (p/recur (rest objects))))))]

    (let [base-uri (cf/get :public-uri)]
      (bw/exec! (prepare-options base-uri)
                (partial render base-uri)))))
