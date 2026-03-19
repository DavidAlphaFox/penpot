;; =============================================================================
;; 位图渲染模块 (Bitmap Renderer Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块负责将 Penpot 设计导出为位图格式（PNG、JPEG、WebP）。
;; 使用浏览器截图功能生成高质量的位图图像。
;;
;; 【核心概念】
;; 1. 多格式支持 - 支持 PNG、JPEG 和 WebP 三种位图格式
;; 2. 透明背景 - PNG 支持透明背景，JPEG 使用白色背景
;; 3. 格式转换 - WebP 需要先截图为 PNG 再转换
;; 4. 质量控制 - JPEG 和 WebP 支持质量参数设置
;;
;; 【依赖关系】
;; - app.browser - 浏览器操作 API
;; - app.util.shell - Shell 命令执行
;; - app.util.mime - MIME 类型工具
;;
;; =============================================================================

(ns app.renderer.bitmap
  "A bitmap renderer."
  (:require
   [app.browser :as bw]
   [app.common.logging :as l]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.util.mime :as mime]
   [app.util.shell :as sh]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(defn render
  "渲染位图格式的导出。
   
   【参数】
   {:keys [file-id page-id share-id token scale type objects skip-children] :as params} - 渲染参数：
     - file-id: 文件 ID
     - page-id: 页面 ID
     - share-id: 分享 ID
     - token: 认证令牌
     - scale: 缩放比例
     - type: 导出类型（:png :jpeg :webp）
     - objects: 要导出的对象列表
     - skip-children: 是否跳过子元素
   on-object - 回调函数，接收渲染完成的对象
   
   【返回值】
   Promise。
   
   【功能说明】
   1. 准备浏览器选项和渲染 URI
   2. 导航到渲染页面并将背景设为透明
   3. 对每个对象进行截图：
      - PNG/JPEG: 直接使用 Playwright 截图
      - WebP: 先截图为 PNG，再使用 ImageMagick 转换
   4. 调用回调处理结果"
  [{:keys [file-id page-id share-id token scale type objects skip-children] :as params} on-object]
  (letfn [(prepare-options [uri]
            #js {:screen #js {:width bw/default-viewport-width
                              :height bw/default-viewport-height}
                 :viewport #js {:width bw/default-viewport-width
                                :height bw/default-viewport-height}
                 :locale "en-US"
                 :storageState #js {:cookies (bw/create-cookies uri {:token token})}
                 :deviceScaleFactor scale
                 :userAgent bw/default-user-agent})

          (render-object [page {:keys [id] :as object}]
            (p/let [path (sh/tempfile :prefix "penpot.tmp.bitmap." :suffix (mime/get-extension type))
                    node (bw/select page (str/concat "#screenshot-" id))]
              (bw/wait-for node)
              (case type
                :png  (bw/screenshot node {:omit-background? true :type type :path path})
                :jpeg (bw/screenshot node {:omit-background? false :type type :path path})
                :webp (p/let [png-path (sh/tempfile :prefix "penpot.tmp.bitmap." :suffix ".png")]
                        ;; playwright only supports jpg and png, we need to convert it afterwards
                        (bw/screenshot node {:omit-background? true :type :png :path png-path})
                        (sh/run-cmd! (str "convert " png-path " -quality 100 WEBP:" path))))
              (on-object (assoc object :path path))))

          (render [uri page]
            (l/info :uri uri)
            (p/do
              ;; navigate to the page and perform basic setup
              (bw/nav! page (str uri))
              (bw/sleep page 1000) ; the good old fix with sleep
              (bw/eval! page (js* "() => document.body.style.background = 'transparent'"))

              ;; take the screnshot of requested objects, one by one
              (p/run (partial render-object page) objects)
              nil))]

    (p/let [params {:file-id file-id
                    :page-id page-id
                    :share-id share-id
                    :object-id (mapv :id objects)
                    :route "objects"
                    :skip-children skip-children}
            uri    (-> (cf/get :public-uri)
                       (assoc :path "/render.html")
                       (assoc :query (u/map->query-string params)))]
      (bw/exec! (prepare-options uri) (partial render uri)))))
