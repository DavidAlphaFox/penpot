;; =============================================================================
;; HTTP 服务器 (HTTP Server)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块负责 HTTP 服务器的创建、配置和路由管理。使用 Yetti 作为底层 HTTP 适配器，
;; Reitit 作为路由框架。提供统一的 HTTP 入口，处理请求中间件、错误处理和响应转换。
;;
;; 【核心概念】
;; 1. Yetti Adapter - 基于 Netty 的异步 HTTP 服务器
;; 2. Reitit Router - 功能强大的路由中间件框架
;; 3. Ring Compatibility - 提供 Ring 风格的中间件支持
;; 4. Middleware Chain - 中间件链包括：认证、CORS、参数解析、错误处理等
;;
;; 【依赖关系】
;; - yetti.adapter - HTTP 服务器适配器
;; - reitit.core - 路由框架
;; - app.http.middleware - HTTP 中间件
;; - app.http.session - 会话管理
;; - app.http.websocket - WebSocket 支持
;; - app.rpc - RPC 路由
;; - app.metrics - 指标收集
;;
;; =============================================================================

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http
  (:require
   [app.auth.oidc :as-alias oidc]
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.transit :as t]
   [app.db :as-alias db]
   [app.http.access-token :as actoken]
   [app.http.assets :as-alias assets]
   [app.http.awsns :as-alias awsns]
   [app.http.debug :as-alias debug]
   [app.http.errors :as errors]
   [app.http.management :as mgmt]
   [app.http.middleware :as mw]
   [app.http.security :as sec]
   [app.http.session :as session]
   [app.http.websocket :as-alias ws]
   [app.main :as-alias main]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.setup :as-alias setup]
   [integrant.core :as ig]
   [reitit.core :as r]
   [reitit.middleware :as rr]
   [yetti.adapter :as yt]
   [yetti.request :as yreq]
   [yetti.response :as-alias yres]))

(declare router-handler)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP SERVER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-params
  "HTTP 服务器默认参数。
   
   【参数说明】
   - port: 默认端口 6060
   - host: 绑定地址 0.0.0.0（接受所有网络接口）
   - max-body-size: 最大请求体大小 350 MiB"
  {::port 6060
   ::host "0.0.0.0"
   ::max-body-size 367001600 ; default 350 MiB
   })

(defmethod ig/expand-key ::server
  [k v]
  {k (merge default-params (d/without-nils v))})

(def ^:private schema:server-params
  [:map
   [::port ::sm/int]
   [::host ::sm/text]
   [::io-threads {:optional true} ::sm/int]
   [::max-worker-threads {:optional true} ::sm/int]
   [::max-body-size {:optional true} ::sm/int]
   [::router {:optional true} [:fn r/router?]]
   [::handler {:optional true} ::sm/fn]])

(defmethod ig/assert-key ::server
  [_ params]
  (assert (sm/check schema:server-params params)))

(defmethod ig/init-key ::server
  "初始化 HTTP 服务器组件。
   
   【功能】
   1. 创建 Yetti 服务器实例
   2. 配置 Netty 线程池
   3. 设置请求分发计时中间件
   4. 启动服务器并返回配置
   
   【参数】
   _ - Integrant 键（忽略）
   cfg - 服务器配置，包含 handler、router、host、port、metrics 等
   
   【返回值】
   更新后的配置，包含 ::server 键"
  [_ {:keys [::handler ::router ::host ::port ::mtx/metrics] :as cfg}]
  (l/info :hint "starting http server" :port port :host host)
  (let [on-dispatch
        (fn [_ start-at-ns]
          (let [timing (- (System/nanoTime) start-at-ns)
                timing (int (/ timing 1000000))]
            (mtx/run! metrics
                      :id :http-server-dispatch-timing
                      :val timing)))

        options
        {:http/port port
         :http/host host
         :http/max-body-size (::max-body-size cfg)
         :http/max-multipart-body-size (::max-body-size cfg)
         :xnio/direct-buffers false
         :xnio/io-threads (::io-threads cfg)
         :xnio/max-worker-threads (::max-worker-threads cfg)
         :ring/compat :ring2
         :events/on-dispatch on-dispatch
         :socket/backlog 4069}

        handler
        (cond
          (some? router)
          (router-handler router)

          (some? handler)
          handler

          :else
          (throw (UnsupportedOperationException. "handler or router are required")))

        server
        (yt/server handler (d/without-nils options))]

    (assoc cfg ::server (yt/start! server))))

(defmethod ig/halt-key! ::server
  "停止 HTTP 服务器组件。
   
   【功能】
   关闭 Yetti 服务器，释放所有相关资源。
   
   【参数】
   _ - Integrant 键（忽略）
   cfg - 包含 ::server 和 ::port 的配置"
  [_ {:keys [::server ::port] :as cfg}]
  (l/info :msg "stopping http server" :port port)
  (yt/stop! server))

(defn- not-found-handler
  "404 未找到处理器。
   
   【功能】
   当请求的路径没有匹配的路由时返回 404 响应。
   
   【参数】
   _ - 请求映射（未使用）
   
   【返回值】
   状态码为 404 的响应映射"
  [_]
  {::yres/status 404})

(defn- router-handler
  "路由处理器工厂函数。
   
   【功能】
   1. 根据请求路径匹配路由
   2. 解析处理程序和路径参数
   3. 处理请求执行过程中的错误
   4. 自动设置响应的 Content-Type 为 transit+json
   
   【参数】
   router - Reitit 路由实例
   
   【返回值】
   Ring 风格的请求处理函数"
  [router]
  (letfn [(resolve-handler [request]
            (if-let [match (r/match-by-path router (yreq/path request))]
              (let [params  (:path-params match)
                    result  (:result match)
                    handler (or (:handler result) not-found-handler)
                    request (assoc request :path-params params)]
                (partial handler request))
              (partial not-found-handler request)))

          (on-error [cause request]
            (let [{:keys [::yres/body] :as response} (errors/handle cause request)]
              (cond-> response
                (map? body)
                (-> (update ::yres/headers assoc "content-type" "application/transit+json")
                    (assoc ::yres/body (t/encode-str body {:type :json-verbose}))))))]
    (fn [request]
      (let [handler (resolve-handler request)]
        (try
          (handler)
          (catch Throwable cause
            (on-error cause request)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP ROUTER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:routes
  [:vector :any])

(def ^:private schema:router-params
  [:map
   [::ws/routes schema:routes]
   [::rpc/routes schema:routes]
   [::oidc/routes schema:routes]
   [::assets/routes schema:routes]
   [::debug/routes schema:routes]
   [::mtx/routes schema:routes]
   [::awsns/routes schema:routes]
   [::mgmt/routes schema:routes]
   ::session/manager
   ::setup/props
   ::db/pool])

(defmethod ig/assert-key ::router
  [_ params]
  (assert (sm/check schema:router-params params)))

(defmethod ig/init-key ::router
  "初始化 HTTP 路由组件。
   
   【功能】
   创建 Reitit 路由实例，配置所有中间件和子路由。
   
   【中间件链】
   1. mw/server-timing - 服务器计时中间件
   2. sec/sec-fetch-metadata - 安全元数据检查
   3. mw/params - 参数解析
   4. mw/format-response - 响应格式化
   5. mw/auth - 认证处理（支持 bearer、cookie、token 三种方式）
   6. mw/parse-request - 请求解析
   7. mw/errors - 错误处理
   8. mw/restrict-methods - HTTP 方法限制
   
   【子路由】
   - /api/* - RPC API 端点
   - /api/management/* - 管理 API 端点
   - /webhooks/* - Webhook 处理
   - /management/* - 管理接口
   - /websocket - WebSocket 连接
   - /auth/oidc/* - OIDC 认证
   - /internal/assets/* - 内部资源
   - /debug/* - 调试端点
   - /metrics - 指标端点
   
   【参数】
   _ - Integrant 键（忽略）
   cfg - 路由配置，包含所有子路由和中间件配置
   
   【返回值】
   Reitit 路由实例"
  [_ cfg]
  (rr/router
   [["" {:middleware [[mw/server-timing]
                      [sec/sec-fetch-metadata]
                      [mw/params]
                      [mw/format-response]
                      [mw/auth {:bearer (partial session/decode-token cfg)
                                :cookie (partial session/decode-token cfg)
                                :token  (partial actoken/decode-token cfg)}]
                      [mw/parse-request]
                      [mw/errors errors/handle]
                      [mw/restrict-methods]]}

     (::mtx/routes cfg)
     (::assets/routes cfg)
     (::debug/routes cfg)

     ["/webhooks"
      (::awsns/routes cfg)]

     ["/management"
      (::mgmt/routes cfg)]

     (::ws/routes cfg)
     (::oidc/routes cfg)
     (::rpc/routes cfg)]]))
