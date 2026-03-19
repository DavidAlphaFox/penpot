;; =============================================================================
;; 浏览器管理模块 (Browser Management Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块负责管理 Playwright 浏览器实例的生命周期。
;; 通过连接池机制复用浏览器上下文，提高导出性能。
;;
;; 【核心概念】
;; 1. 浏览器连接池 - 使用 generic-pool 管理浏览器实例的创建和销毁
;; 2. Playwright 封装 - 提供简化的浏览器操作 API（导航、截图、选择器等）
;; 3. 上下文隔离 - 每个导出任务在独立的浏览器上下文中执行
;; 4. 超时处理 - 优雅处理浏览器操作超时
;;
;; 【依赖关系】
;; - playwright - Playwright 浏览器自动化库
;; - generic-pool - 连接池管理库
;; - app.config - 配置模块
;;
;; =============================================================================

(ns app.browser
  (:require
   ["generic-pool" :as gp]
   ["generic-pool/lib/errors.js" :as gpe]
   ["playwright" :as pw]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.util.object :as obj]
   [promesa.core :as p]))

(l/set-level! :trace)

(def TimeoutError gpe/TimeoutError)

;; --- BROWSER API

(def default-timeout 30000)
(def default-viewport-width 1920)
(def default-viewport-height 1080)
(def default-user-agent
  (str "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/99.0.3729.169 Safari/537.36"))

(defn create-cookies
  "创建用于 Playwright 的 Cookie 数组。
   
   【参数】
   uri - URI 对象，包含 host 和 port
   {:keys [name token] :or {name \"auth-token\"}} - Cookie 选项
   
   【返回值】
   JavaScript 数组，包含配置好的 Cookie 对象。"
  [uri {:keys [name token] :or {name "auth-token"}}]
  (let [domain (str (:host uri)
                    (when (:port uri)
                      (str ":" (:port uri))))]
    #js [#js {:domain domain
              :path "/"
              :name name
              :value token}]))

(defn nav!
  "导航到指定 URL。
   
   【参数】
   page - Playwright 页面对象
   url - 目标 URL（字符串或 URI 对象）
   {:keys [wait-until timeout] :or {wait-until \"networkidle\" timeout 20000}} - 选项
   
   【返回值】
   Promise，在导航完成后解析。"
  ([page url] (nav! page url nil))
  ([page url {:keys [wait-until timeout] :or {wait-until "networkidle" timeout 20000}}]
   (.goto ^js page (str url) #js {:waitUntil wait-until :timeout timeout})))

(defn sleep
  "让页面等待指定毫秒数。
   
   【参数】
   page - Playwright 页面对象
   ms - 等待毫秒数
   
   【返回值】
   Promise，在等待时间结束后解析。"
  [page ms]
  (.waitForTimeout ^js page ms))

(defn wait-for
  "等待元素满足条件。
   
   【参数】
   locator - Playwright 定位器对象
   {:keys [state timeout] :or {state \"visible\" timeout 10000}} - 选项
   
   【返回值】
   Promise，在条件满足后解析。"
  ([locator] (wait-for locator nil))
  ([locator {:keys [state timeout] :or {state "visible" timeout 10000}}]
   (.waitFor ^js locator #js {:state state :timeout timeout})))

(defn screenshot
  "对页面或元素进行截图。
   
   【参数】
   frame - Playwright 页面或框架对象
   {:keys [full-page? omit-background? type quality path]
    :or {type \"png\" full-page? false omit-background? false quality 95}} - 截图选项
   
   【返回值】
   Promise，解析为截图的 Buffer 数据。"
  ([frame] (screenshot frame {}))
  ([frame {:keys [full-page? omit-background? type quality path]
           :or {type "png" full-page? false omit-background? false quality 95}}]
   (let [options (-> (obj/new)
                     (obj/set! "type" (name type))
                     (obj/set! "omitBackground" omit-background?)
                     (cond-> path (obj/set! "path" path))
                     (cond-> (= "jpeg" type) (obj/set! "quality" quality))
                     (cond-> full-page?      (-> (obj/set! "fullPage" true)
                                                (obj/set! "clip" nil))))]
     (.screenshot ^js frame options))))

(defn emulate-media!
  "模拟媒体类型。
   
   【参数】
   page - Playwright 页面对象
   {:keys [media]} - 媒体选项（如 \"screen\"、\"print\"）
   
   【返回值】
   页面对象（支持链式调用）。"
  [page {:keys [media]}]
  (.emulateMedia ^js page #js {:media media})
  page)

(defn pdf
  "生成 PDF 文档。
   
   【参数】
   page - Playwright 页面对象
   {:keys [scale path page-ranges] :or {page-ranges \"1\" scale 1}} - PDF 选项
   
   【返回值】
   Promise，解析为 PDF 文件路径。"
  ([page] (pdf page {}))
  ([page {:keys [scale path page-ranges]
          :or {page-ranges "1"
               scale 1}}]
   (.pdf ^js page #js {:path path
                        :scale scale
                        :pageRanges page-ranges
                        :printBackground true
                        :preferCSSPageSize true})))
(defn eval!
  "在页面上下文中执行 JavaScript 函数。
   
   【参数】
   frame - Playwright 页面或框架对象
   f - 要执行的 JavaScript 函数
   
   【返回值】
   Promise，解析为函数的返回值。"
  [frame f]
  (.evaluate ^js frame f))

(defn select
  "选择匹配选择器的第一个元素。
   
   【参数】
   frame - Playwright 页面或框架对象
   selector - CSS 选择器字符串
   
   【返回值】
   Playwright 定位器对象。"
  [frame selector]
  (.locator ^js frame selector))

(defn select-all
  "选择匹配选择器的所有元素。
   
   【参数】
   frame - Playwright 页面或框架对象
   selector - CSS 选择器字符串
   
   【返回值】
   Promise，解析为元素数组。"
  [frame selector]
  (.$$ ^js frame selector))


;; --- BROWSER STATE

(defonce pool (atom nil))
(defonce pool-browser-id (atom 1))

(def browser-pool-factory
  "浏览器连接池工厂。
   
   【类型】
   JavaScript 对象，包含 create、destroy、validate 方法。
   
   【功能说明】
   - create: 创建新的浏览器实例
   - destroy: 关闭浏览器实例
   - validate: 检查浏览器实例是否仍然有效"
  (letfn [(create []
            (p/let [opts    #js {:args #js ["--allow-insecure-localhost" "--font-render-hinting=none"]}
                    browser (.launch pw/chromium opts)
                    id      (swap! pool-browser-id inc)]
              (l/info :origin "factory" :action "create" :browser-id id)
              (unchecked-set browser "__id" id)
              browser))

          (destroy [obj]
            (let [id (unchecked-get obj "__id")]
              (l/info :origin "factory" :action "destroy" :browser-id id)
              (.close ^js obj)))

          (validate [obj]
            (let [id (unchecked-get obj "__id")]
              (l/info :origin "factory" :action "validate" :browser-id id :obj obj)
              (p/resolved (.isConnected ^js obj))))]
    #js {:create create
         :destroy destroy
         :validate validate}))

(defn init
  "初始化浏览器连接池。
   
   【参数】
   无
   
   【返回值】
   Promise，初始化完成后解析为 nil。"
  []
  (let [opts #js {:max (cf/get :browser-pool-max 5)
                  :min (cf/get :browser-pool-min 0)
                  :testOnBorrow true
                  :evictionRunIntervalMillis 5000
                  :numTestsPerEvictionRun 5
                  ;; :acquireTimeoutMillis 120000 ; 2min
                  :acquireTimeoutMillis 10000 ; 10 s
                  :idleTimeoutMillis 10000}]

    (l/info :hint "initializing browser pool" :opts opts)
    (reset! pool (gp/createPool browser-pool-factory opts))
    (p/resolved nil)))

(defn stop
  "停止浏览器连接池。
   
   【参数】
   无
   
   【返回值】
   Promise，在连接池完全关闭后解析。"
  []
  (when-let [pool (deref pool)]
    (l/info :hint "finalizing browser pool")
    (p/do!
     (.drain ^js pool)
     (.clear ^js pool))))

(defn- ex-ignore
  "将 Promise 的结果转换为 nil（忽略错误）。
   
   【参数】
   p - 输入的 Promise
   
   【返回值】
   如果 Promise 成功，解析为 nil；如果失败，也解析为 nil。"
  [p]
  (p/handle p (constantly nil)))

(defn- translate-browser-errors
  "将浏览器错误转换为应用内部错误。
   
   【参数】
   cause - 错误原因
   
   【返回值】
   如果是超时错误，抛出内部错误；否则返回被拒绝的 Promise。"
  [cause]
  (if (instance? TimeoutError cause)
    (ex/raise :type :internal
              :code :timeout
              :hint (ex-message cause)
              :cause cause)
    (p/rejected cause)))

(defn exec!
  "在浏览器中执行操作。
   
   【参数】
   config - 浏览器上下文配置
   handle - 操作函数，接收页面对象作为参数
   
   【返回值】
   Promise，解析为操作的结果。
   
   【功能说明】
   1. 从连接池获取浏览器实例
   2. 创建新的浏览器上下文
   3. 在上下文中创建新页面
   4. 执行用户提供的操作
   5. 关闭上下文并释放浏览器到连接池"
  [config handle]
  (letfn [(handle-browser [browser]
            (p/let [id      (unchecked-get browser "__id")
                    context (.newContext ^js browser config)]
              (l/trace :hint "exec:handle:start" :browser-id id)
              (p/let [page   (.newPage ^js context)
                      result (handle page)]
                (.close ^js context)
                (l/trace :hint "exec:handle:end" :browser-id id)
                result)))

          (on-acquire [pool browser]
            (-> (handle-browser browser)
                (p/then (fn [result]
                          (.release ^js pool browser)
                          result))
                (p/catch (fn [cause]
                           (p/do!
                            (ex-ignore (.destroy ^js pool browser))
                            (p/rejected cause))))))]

    (when-let [pool (deref pool)]
      (-> (p/do! (.acquire ^js pool))
          (p/then (partial on-acquire pool))
          (p/catch translate-browser-errors)))))
