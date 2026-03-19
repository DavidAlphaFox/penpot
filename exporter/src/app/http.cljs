;; =============================================================================
;; HTTP 服务模块 (HTTP Server Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块实现了导出服务的 HTTP 服务器，使用 Node.js 的 http 模块。
;; 负责接收和处理来自 Penpot 后端的导出请求，并返回导出结果。
;;
;; 【核心概念】
;; 1. 请求适配器 - 将 Node.js 的 HTTP 请求转换为内部交换格式
;; 2. 中间件链 - 使用中间件模式处理请求（认证、参数解析、响应格式化等）
;; 3. 响应流处理 - 支持多种响应体类型的流式写入
;; 4. 健康检查端点 - 提供 /readyz 端点用于服务健康检查
;;
;; 【依赖关系】
;; - cookies - Cookie 解析和设置
;; - inflation - HTTP 请求体解压
;; - raw-body - HTTP 请求体读取
;; - app.handlers - 请求处理器
;; - app.common.transit - Transit 格式编解码
;;
;; =============================================================================

(ns app.http
  (:require
   ["cookies$default" :as Cookies]
   ["inflation$default" :as inflate]
   ["node:http" :as http]
   ["node:stream$default" :as stream]
   ["raw-body$default" :as raw-body]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.config :as cf]
   [app.handlers :as handlers]
   [cuerdas.core :as str]
   [lambdaisland.uri :as u]
   [promesa.core :as p]))

(l/set-level! :info)

(defprotocol IStreamableResponseBody
  "可流式传输的响应体协议。
   
   【说明】
   定义不同类型响应体的写入方式。"
  (write-body! [_ response]))

(extend-protocol IStreamableResponseBody
  string
  (write-body! [data response]
    (.write ^js response data)
    (.end ^js response))

  js/Buffer
  (write-body! [data response]
    (.write ^js response data)
    (.end ^js response))

  stream/Stream
  (write-body! [data response]
    (.pipe ^js data response)
    (.on ^js data "error" (fn [cause]
                            (js/console.error cause)
                            (.end response)))))

(defn- handle-response
  "处理响应并将结果写入 HTTP 响应。
   
   【参数】
   exchange - 交换对象，包含 :response/body、:response/headers 和 :response/status
   
   【返回值】
   无（直接写入响应）
   
   【功能说明】
   从 exchange 中提取响应信息，设置 HTTP 状态码和头，
   然后根据响应体类型调用相应的写入方法。"
  [{:keys [:response/body
           :response/headers
           :response/status
           response]
    :as exchange}]
  (let [status  (or status 200)
        headers (clj->js headers)
        body    (or body "")]
    (.writeHead ^js response status headers)
    (write-body! body response)))

(defn- parse-headers
  "解析 HTTP 请求头，转换为小写键的映射。
   
   【参数】
   req - Node.js HTTP 请求对象
   
   【返回值】
   包含所有请求头的映射，键为小写字符串。"
  [req]
  (let [orig (unchecked-get req "headers")]
    (persistent!
     (reduce #(assoc! %1 (str/lower %2) (unchecked-get orig %2))
             (transient {})
             (js/Object.keys orig)))))

(defn- wrap-body-params
  "中间件：解析请求体参数。
   
   【参数】
   handler - 下一个处理器函数
   
   【返回值】
   包装后的处理器函数。
   
   【功能说明】
   对于 POST 请求，读取并解析请求体（支持 Transit+JSON 格式），
   将解析后的数据存入 exchange 的 :request/body-params。"
  [handler]
  (let [opts #js {:limit "60mb" :encoding "utf8"}]
    (fn [{:keys [:request/method :request/headers request] :as exchange}]
      (let [ctype (get headers "content-type")]
        (if (= method "post")
          (-> (raw-body (inflate request) opts)
              (p/then (fn [data]
                        (cond-> data
                          (= ctype "application/transit+json")
                          (t/decode-str))))
              (p/then (fn [data]
                        (handler (assoc exchange :request/body-params data)))))
          (handler exchange))))))

(defn- wrap-params
  "中间件：合并查询参数和请求体参数。
   
   【参数】
   handler - 下一个处理器函数
   
   【返回值】
   包装后的处理器函数。
   
   【功能说明】
   将 query-params 和 body-params 合并到 :request/params 中。"
  [handler]
  (fn [{:keys [:request/body-params :request/query-params] :as exchange}]
    (handler (assoc exchange :request/params (merge query-params body-params)))))

(defn- wrap-response-format
  "中间件：格式化响应体。
   
   【参数】
   handler - 下一个处理器函数
   
   【返回值】
   包装后的处理器函数。
   
   【功能说明】
   对响应体进行格式化：
   - 如果是 map，转换为 Transit+JSON 格式
   - 如果是 nil 且状态码为 200，返回 204 无内容响应
   - 其他情况保持原样"
  [handler]
  (fn [exchange]
    (p/then
     (handler exchange)
     (fn [{:keys [:response/body :response/status] :as exchange}]
       (cond
         (map? body)
         (let [data (t/encode-str body {:type :json-verbose})
               size (js/Buffer.byteLength data "utf-8")]
           (-> exchange
               (assoc :response/body data)
               (assoc :response/status 200)
               (update :response/headers assoc "content-type" "application/transit+json")
               (update :response/headers assoc "content-length" size)))

         (and (nil? body)
              (= 200 status))
         (-> exchange
             (assoc :response/body "")
             (assoc :response/status 204)
             (assoc :response/headers {"content-length" 0}))

         :else
         exchange)))))

(defn- wrap-query-params
  "中间件：解析查询字符串参数。
   
   【参数】
   handler - 下一个处理器函数
   
   【返回值】
   包装后的处理器函数。
   
   【功能说明】
   从请求 URI 中解析查询字符串，存入 :request/query-params。"
  [handler]
  (fn [{:keys [:request/uri] :as exchange}]
    (handler (assoc exchange :request/query-params (u/query-string->map (:query uri))))))

(defn- wrap-error
  "中间件：捕获并处理错误。
   
   【参数】
   handler - 下一个处理器函数
   on-error - 错误处理回调函数
   
   【返回值】
   包装后的处理器函数。
   
   【功能说明】
   捕获处理器中抛出的错误，传递给错误处理回调。"
  [handler on-error]
  (fn [exchange]
    (-> (p/do (handler exchange))
        (p/catch (fn [cause] (on-error cause exchange))))))

(defn- wrap-auth
  "中间件：处理认证令牌。
   
   【参数】
   handler - 下一个处理器函数
   cookie-name - 认证 Cookie 的名称
   
   【返回值】
   包装后的处理器函数。
   
   【功能说明】
   从 Cookie 中提取认证令牌，存入 exchange 的 :request/auth-token。"
  [handler cookie-name]
  (fn [{:keys [:request/cookies] :as exchange}]
    (let [token (.get ^js cookies cookie-name)]
      (handler (cond-> exchange token (assoc :request/auth-token token))))))

(defn- wrap-health
  "中间件：添加健康检查端点。
   
   【参数】
   handler - 下一个处理器函数
   
   【返回值】
   包装后的处理器函数。
   
   【功能说明】
   拦截 /readyz 请求，返回 200 OK 状态；
   其他请求继续传递给下一个处理器。"
  [handler]
  (fn [{:keys [:request/path] :as exchange}]
    (if (= path "/readyz")
      (assoc exchange
             :response/status 200
             :response/body "OK")
      (handler exchange))))

(defn- create-adapter
  "创建 HTTP 请求适配器。
   
   【参数】
   handler - 请求处理器函数
   
   【返回值】
   适配器函数，接收 Node.js 的 req 和 res 对象。
   
   【功能说明】
   将 Node.js HTTP 请求转换为内部交换格式，
   包含方法、路径、URI、头、Cookie 等信息。"
  [handler]
  (fn [req res]
    (let [cookies  (Cookies. req res)
          headers  (parse-headers req)
          uri      (u/uri (unchecked-get req "url"))
          exchange {:request/method (str/lower (unchecked-get req "method"))
                    :request/path (:path uri)
                    :request/uri uri
                    :request/headers headers
                    :request/cookies cookies
                    :request req
                    :response res}]
      (-> (p/do (handler exchange))
          (p/then handle-response)))))

(defn- create-server
  "创建 HTTP 服务器。
   
   【参数】
   handler - 请求处理器函数
   
   【返回值】
   Node.js HTTP 服务器实例。"
  [handler]
  (.createServer ^js http (create-adapter handler)))

(def instance
  "HTTP 服务器实例的原子引用。
   
   【说明】
   用于存储当前运行的 HTTP 服务器实例，支持启动和停止操作。"
  (atom nil))

(defn init
  "初始化并启动 HTTP 服务器。
   
   【参数】
   无
   
   【返回值】
   无（启动服务器并监听端口）
   
   【功能说明】
   1. 构建中间件链（健康检查→认证→响应格式化→参数解析→查询参数→请求体→错误处理）
   2. 创建 HTTP 服务器
   3. 监听配置端口"
  []
  (let [handler (-> handlers/handler
                    (wrap-health)
                    (wrap-auth "auth-token")
                    (wrap-response-format)
                    (wrap-params)
                    (wrap-query-params)
                    (wrap-body-params)
                    (wrap-error handlers/on-error))
        server  (create-server handler)
        port    (cf/get :http-server-port 6061)]

    (.listen server port)
    (l/info :hint "welcome to penpot"
            :module "exporter"
            :flags cf/flags
            :version (:full cf/version))
    (l/info :hint "starting http server" :port port)
    (reset! instance server)))

(defn stop
  "停止 HTTP 服务器。
   
   【参数】
   无
   
   【返回值】
   一个 Promise，在服务器关闭后解析。"
  []
  (if-let [server @instance]
    (p/create (fn [resolve]
                (.close server (fn []
                                 (l/info :hint "shutdown http server")
                                 (resolve)))))
    (p/resolved nil)))
