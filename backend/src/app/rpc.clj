;; =============================================================================
;; RPC 处理层 (RPC Processing Layer)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块是 Penpot 后端的 RPC（远程过程调用）处理核心。
;; 提供 RPC 方法的注册、分发、认证、验证和审计功能。
;; 支持请求限流、并发限制和重试机制。
;;
;; 【核心概念】
;; 1. Multimethod - 使用 sv/defmethod 定义 RPC 方法
;; 2. Middleware Chain - 中间件链包括：认证、限流、审计、验证等
;; 3. RPC Method Types - 支持 mutation、query、command 等类型
;; 4. Rate Limiting - 使用 climit 和 rlimit 进行限流控制
;; 5. Audit Log - 审计日志记录 RPC 调用
;;
;; 【依赖关系】
;; - app.util.services - 服务方法定义框架
;; - app.rpc.climit - 并发限制
;; - app.rpc.rlimit - 速率限制
;; - app.http.session - 会话管理
;; - app.loggers.audit - 审计日志
;;
;; =============================================================================

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc
  (:require
   [app.auth.ldap :as-alias ldap]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.spec :as us]
   [app.common.time :as ct]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http :as-alias http]
   [app.http.access-token :as actoken]
   [app.http.client :as-alias http.client]
   [app.http.middleware :as mw]
   [app.http.security :as sec]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.main :as-alias main]
   [app.metrics :as mtx]
   [app.msgbus :as-alias mbus]
   [app.redis :as rds]
   [app.rpc.climit :as climit]
   [app.rpc.cond :as cond]
   [app.rpc.doc :as doc]
   [app.rpc.helpers :as rph]
   [app.rpc.retry :as retry]
   [app.rpc.rlimit :as rlimit]
   [app.setup :as-alias setup]
   [app.storage :as-alias sto]
   [app.util.inet :as inet]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [yetti.request :as yreq]
   [yetti.response :as yres]))

(s/def ::profile-id ::us/uuid)

(defn- default-handler
  "默认请求处理器。
   
   【功能】
   当找不到对应的 RPC 方法时调用，返回 404 错误。
   
   【参数】
   _ - 请求参数（忽略）
   
   【返回值】
   404 错误异常"
  [_]
  (ex/raise :type :not-found))

(defn- handle-response-transformation
  "处理响应转换函数。
   
   【功能】
   对响应应用一系列转换函数，用于修改响应内容或格式。
   
   【参数】
   response - 原始响应
   request - 请求对象
   mdata - 方法元数据（包含 ::response-transform-fns）
   
   【返回值】
   转换后的响应"
  [response request mdata]
  (reduce (fn [response transform-fn]
            (transform-fn request response))
          response
          (::response-transform-fns mdata)))

(defn- handle-before-comple-hook
  "执行完成前回调钩子。
   
   【功能】
   在响应返回前执行一系列回调函数，通常用于清理工作。
   
   【参数】
   response - 响应对象
   mdata - 方法元数据（包含 ::before-complete-fns）
   
   【返回值】
   原始响应"
  [response mdata]
  (doseq [hook-fn (::before-complete-fns mdata)]
    (ex/ignoring (hook-fn)))
  response)

(defn- handle-response
  "处理 RPC 响应。
   
   【功能】
   将 RPC 方法的返回值转换为标准的 Ring 响应格式。
   处理状态码、响应头、响应体转换等。
   
   【参数】
   request - 请求对象
   result - RPC 方法的返回值（可以是函数或普通值）
   
   【返回值】
   Ring 响应映射 {::yres/status, ::yres/headers, ::yres/body}"
  [request result]
  (let [mdata    (meta result)
        response (if (fn? result)
                   (result request)
                   (let [result  (rph/unwrap result)
                         status  (or (::http/status mdata)
                                     (if (nil? result)
                                       204
                                       200))

                         headers (::http/headers mdata {})
                         headers (cond-> headers
                                   (and (yres/stream-body? result)
                                        (not (contains? headers "content-type")))
                                   (assoc "content-type" "application/octet-stream"))]

                     {::yres/status  status
                      ::yres/headers headers
                      ::yres/body    result}))]

    (-> response
        (handle-response-transformation request mdata)
        (handle-before-comple-hook mdata))))

(defn- make-rpc-handler
  "创建 RPC 请求处理器。
   
   【功能】
   生成一个 Ring handler，将 RPC 请求分派到对应的方法处理。
   从请求中提取参数、认证信息、IP 地址等，构造上下文数据。
   
   【参数】
   methods - 方法名到处理函数的映射
   
   【返回值】
   Ring handler 函数"
  [methods]
  "Ring handler that dispatches cmd requests and convert between
   internal async flow into ring async flow."
  (let [methods (update-vals methods peek)]
    (fn [{:keys [params path-params method] :as request}]
      (let [handler-name (:method-name path-params)
            etag         (yreq/get-header request "if-none-match")

            key-id       (get request ::http/auth-key-id)
            profile-id   (or (::session/profile-id request)
                             (::actoken/profile-id request)
                             (if key-id uuid/zero nil))

            ip-addr      (inet/parse-request request)

            data         (-> params
                             (assoc ::handler-name handler-name)
                             (assoc ::ip-addr ip-addr)
                             (assoc ::request-at (ct/now))
                             (assoc ::cond/key etag)
                             (cond-> (uuid? profile-id)
                               (assoc ::profile-id profile-id)))

            data         (with-meta data
                           {::http/request request})

            handler-fn   (get methods (keyword handler-name) default-handler)]

        (when (and (or (= method :get)
                       (= method :head))
                   (not (str/starts-with? handler-name "get-")))
          (ex/raise :type :restriction
                    :code :method-not-allowed
                    :hint "method not allowed for this request"))

        ;; FIXME: why we have this cond enabled here, we need to move it outside this handler
        (binding [cond/*enabled* true]
          (let [response (handler-fn data)]
            (handle-response request response)))))))

(defn- wrap-metrics
  "性能指标收集包装器。
   
   【功能】
   包装 RPC 方法，收集方法执行时间的指标。
   
   【参数】
   cfg - 包含 ::mtx/metrics 的系统配置
   f - 要包装的处理函数
   mdata - 方法元数据（包含 ::metrics-id）
   
   【返回值】
   包装后的处理函数"
  [{:keys [::mtx/metrics ::metrics-id]} f mdata]
  (let [labels (into-array String [(::sv/name mdata)])]
    (fn [cfg params]
      (let [tp (ct/tpoint)]
        (try
          (f cfg params)
          (finally
            (mtx/run! metrics
                      :id metrics-id
                      :val (inst-ms (tp))
                      :labels labels)))))))

(defn- wrap-authentication
  "认证检查包装器。
   
   【功能】
   包装 RPC 方法，检查是否需要认证。
   如果方法需要认证但未提供有效的 profile-id，则抛出认证错误。
   
   【参数】
   cfg - 系统配置
   f - 要包装的处理函数
   mdata - 方法元数据（包含 ::auth）
   
   【返回值】
   包装后的处理函数"
  [_ f mdata]
  (fn [cfg params]
    (let [profile-id (::profile-id params)]
      (if (and (::auth mdata true) (not (uuid? profile-id)))
        (ex/raise :type :authentication
                  :code :authentication-required
                  :hint "authentication required for this endpoint")
        (f cfg params)))))

(defn- wrap-db-transaction
  "数据库事务包装器。
   
   【功能】
   包装 RPC 方法，在数据库事务中执行。
   如果方法元数据包含 ::db/transaction，则在事务中运行。
   
   【参数】
   cfg - 系统配置
   f - 要包装的处理函数
   mdata - 方法元数据（包含 ::db/transaction）
   
   【返回值】
   包装后的处理函数或在事务中执行的处理函数"
  [_ f mdata]
  (if (::db/transaction mdata)
    (fn [cfg params]
      (db/tx-run! cfg f params))
    f))

(defn- wrap-audit
  "审计日志包装器。
   
   【功能】
   包装 RPC 方法，记录审计日志。
   如果启用了审计日志或 Webhooks 功能，则在方法执行后记录审计事件。
   
   【参数】
   cfg - 系统配置
   f - 要包装的处理函数
   mdata - 方法元数据（包含 ::audit/skip）
   
   【返回值】
   包装后的处理函数或原始函数"
  [_ f mdata]
  (if (or (contains? cf/flags :webhooks)
          (contains? cf/flags :audit-log))
    (if-not (::audit/skip mdata)
      (fn [cfg params]
        (let [result (f cfg params)]
          (->> (audit/prepare-event cfg mdata params result)
               (audit/submit! cfg))
          result))
      f)
    f))

(defn- wrap-spec-conform
  "Spec conform 包装器。
   
   【功能】
   使用 clojure.spec 对参数进行conform转换。
   如果方法已使用 Malli 验证（包含 ::sm/params），则跳过此步骤。
   
   【参数】
   cfg - 系统配置
   f - 要包装的处理函数
   mdata - 方法元数据（包含 ::sv/spec）
   
   【返回值】
   包装后的处理函数或原始函数"
  [_ f mdata]
  ;; NOTE: skip spec conform operation on rpc methods that already
  ;; uses malli validation mechanism.
  (if (contains? mdata ::sm/params)
    f
    (if-let [spec (ex/ignoring (s/spec (::sv/spec mdata)))]
      (fn [cfg params]
        (f cfg (us/conform spec params)))
      f)))

(defn- wrap-params-validation
  "参数验证包装器。
   
   【功能】
   使用 Malli schema 对 RPC 方法参数进行验证和解码。
   支持 JSON 编码/解码转换。
   
   【参数】
   cfg - 系统配置
   f - 要包装的处理函数
   mdata - 方法元数据（包含 ::sm/params）
   
   【返回值】
   包装后的处理函数或原始函数"
  [_ f mdata]
  (if-let [schema (::sm/params mdata)]
    (let [validate (sm/validator schema)
          explain  (sm/explainer schema)
          decode   (sm/decoder schema sm/json-transformer)
          encode   (sm/encoder schema sm/json-transformer)]
      (fn [cfg params]
        (let [params (decode params)]
          (if (validate params)
            (let [result (f cfg params)]
              (if (instance? clojure.lang.IObj result)
                (vary-meta result assoc :encode/json encode)
                result))
            (let [params (d/without-qualified params)]
              (ex/raise :type :validation
                        :code :params-validation
                        ::sm/explain (explain params)))))))
    f))

(defn- wrap
  "为 RPC 方法应用标准中间件链。
   
   【中间件顺序】
   1. wrap-db-transaction - 数据库事务包装
   2. cond/wrap - 条件中间件
   3. retry/wrap-retry - 重试机制
   4. climit/wrap - 并发限制
   5. wrap-metrics - 性能指标收集
   6. rlimit/wrap - 速率限制
   7. wrap-audit - 审计日志
   8. wrap-spec-conform - Spec conform
   9. wrap-params-validation - 参数验证
   10. wrap-authentication - 认证检查
   
   【参数】
   cfg - 系统配置
   f - 原始处理函数
   mdata - 方法元数据
   
   【返回值】
   包装后的处理函数"
  [cfg f mdata]
  (as-> f $
    (wrap-db-transaction cfg $ mdata)
    (cond/wrap cfg $ mdata)
    (retry/wrap-retry cfg $ mdata)
    (climit/wrap cfg $ mdata)
    (wrap-metrics cfg $ mdata)
    (rlimit/wrap cfg $ mdata)
    (wrap-audit cfg $ mdata)
    (wrap-spec-conform cfg $ mdata)
    (wrap-params-validation cfg $ mdata)
    (wrap-authentication cfg $ mdata)))

(defn- wrap-management
  "为管理 RPC 方法应用中间件链。
   
   【与 wrap 的区别】
   管理方法不使用速率限制（rlimit）。
   
   【参数】
   cfg - 系统配置
   f - 原始处理函数
   mdata - 方法元数据
   
   【返回值】
   包装后的处理函数"
  [cfg f mdata]
  (as-> f $
    (wrap-db-transaction cfg $ mdata)
    (retry/wrap-retry cfg $ mdata)
    (climit/wrap cfg $ mdata)
    (wrap-metrics cfg $ mdata)
    (wrap-audit cfg $ mdata)
    (wrap-spec-conform cfg $ mdata)
    (wrap-params-validation cfg $ mdata)
    (wrap-authentication cfg $ mdata)))

(defn- process-method
  "处理单个 RPC 方法的注册。
   
   【功能】
   1. 应用中间件链
   2. 创建方法处理器
   3. 注册到方法映射
   
   【参数】
   cfg - 系统配置
   wrap-fn - 中间件包装函数
   [f mdata] - [方法函数, 方法元数据]
   
   【返回值】
   [方法名-keyword, [元数据, 处理函数]]"
  [cfg wrap-fn [f mdata]]
  (l/trc :hint "add method" :module (::module cfg) :type (::type cfg) :name (::sv/name mdata))
  (let [f (wrap-fn cfg f mdata)
        k (keyword (::sv/name mdata))]
    [k [mdata (partial f cfg)]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API METHODS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- resolve-methods
  "解析并注册所有主 RPC 方法。
   
   【功能】
   扫描 app.rpc.commands 命名空间下的所有模块，
   收集所有定义的 RPC 方法并应用中间件链。
   
   【参数】
   无
   
   【返回值】
   方法名到 [元数据, 处理函数] 的映射"
  [cfg]
  (let [cfg (assoc cfg ::module "main" ::type "command" ::metrics-id :rpc-main-timing)]
    (->> (sv/scan-ns
          'app.rpc.commands.access-token
          'app.rpc.commands.audit
          'app.rpc.commands.auth
          'app.rpc.commands.feedback
          'app.rpc.commands.fonts
          'app.rpc.commands.binfile
          'app.rpc.commands.comments
          'app.rpc.commands.demo
          'app.rpc.commands.files
          'app.rpc.commands.files-create
          'app.rpc.commands.files-share
          'app.rpc.commands.files-update
          'app.rpc.commands.files-snapshot
          'app.rpc.commands.files-thumbnails
          'app.rpc.commands.ldap
          'app.rpc.commands.management
          'app.rpc.commands.media
          'app.rpc.commands.nitrate
          'app.rpc.commands.profile
          'app.rpc.commands.projects
          'app.rpc.commands.search
          'app.rpc.commands.teams
          'app.rpc.commands.teams-invitations
          'app.rpc.commands.verify-token
          'app.rpc.commands.viewer
          'app.rpc.commands.webhooks)
         (map (partial process-method cfg wrap))
         (into {}))))

(defmethod ig/init-key ::methods
  "初始化 RPC 方法组件。
   
   【功能】
   1. 清理配置中的 nil 值
   2. 调用 resolve-methods 解析所有方法
   
   【参数】
   _ - Integrant 键（忽略）
   cfg - 方法配置
   
   【返回值】
   方法映射"
  [_ cfg]
  (let [cfg (d/without-nils cfg)]
    (resolve-methods cfg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MANAGEMENT METHODS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- resolve-management-methods
  "解析并注册所有管理 RPC 方法。
   
   【功能】
   扫描管理相关的命名空间，收集所有定义的管理 RPC 方法。
   根据功能标志（:nitrate）决定是否包含 nitrate 模块。
   
   【参数】
   cfg - 系统配置
   
   【返回值】
   方法名到 [元数据, 处理函数] 的映射"
  [cfg]
  (let [cfg  (assoc cfg ::module "management" ::type "command" ::metrics-id :rpc-management-timing)
        mods (cond->> (list 'app.rpc.management.exporter)
               (contains? cf/flags :nitrate)
               (cons 'app.rpc.management.nitrate))]

    (->> (apply sv/scan-ns mods)
         (map (partial process-method cfg wrap-management))
         (into {}))))

(def ^:private schema:management-methods-params
  [:map {:title "management-methods-params"}
   ::session/manager
   ::http.client/client
   ::db/pool
   ::rds/pool
   ::mbus/msgbus
   ::sto/storage
   ::mtx/metrics
   ::setup/props])

(defmethod ig/assert-key ::management-methods
  "验证管理方法配置。
   
   【功能】
   检查配置是否包含所需的所有依赖：会话管理器、HTTP 客户端、数据库连接池、Redis 连接池、消息总线、存储和指标。
   
   【参数】
   _ - Integrant 键（忽略）
   params - 方法配置参数
   
   【返回值】
   验证通过无返回值，失败抛出断言错误"
  [_ params]
  (assert (sm/check schema:management-methods-params params)))

(defmethod ig/init-key ::management-methods
  "初始化管理 RPC 方法组件。
   
   【功能】
   1. 清理配置中的 nil 值
   2. 调用 resolve-management-methods 解析所有管理方法
   
   【参数】
   _ - Integrant 键（忽略）
   cfg - 方法配置
   
   【返回值】
   管理方法映射"
  [_ cfg]
  (let [cfg (d/without-nils cfg)]
    (resolve-management-methods cfg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ROUTES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- redirect
  "创建重定向响应。
   
   【功能】
   生成一个返回 308 永久重定向的 Ring handler。
   
   【参数】
   href - 目标 URL
   
   【返回值】
   Ring handler 函数"
  [href]
  (fn [_]
    {::yres/status 308
     ::yres/headers {"location" (str href)}}))

(def ^:private schema:methods
  [:map-of :keyword [:tuple :map ::sm/fn]])

(sm/register! ::methods schema:methods)

(def ^:private valid-methods?
  (sm/validator schema:methods))

(defmethod ig/assert-key ::routes
  "验证 RPC 路由配置。
   
   【功能】
   检查配置是否包含有效的共享密钥、数据库连接池、会话管理器和RPC方法映射。
   
   【参数】
   _ - Integrant 键（忽略）
   params - 路由配置参数
   
   【返回值】
   验证通过无返回值，失败抛出断言错误"
  [_ params]
  (assert (map? (::setup/shared-keys params)))
  (assert (db/pool? (::db/pool params)) "expect valid database pool")
  (assert (session/manager? (::session/manager params)) "expect valid session manager")
  (assert (valid-methods? (::methods params)) "expect valid methods map")
  (assert (valid-methods? (::management-methods params)) "expect valid methods map"))

(defmethod ig/init-key ::routes
  "初始化 RPC 路由。
   
   【功能】
   构建完整的 API 路由结构，包括：
   - 管理 API 路由 (/api/management/methods/*)
   - 主 API 路由 (/api/main/methods/*)
   - 兼容路由 (/api/rpc/command/*)
   - 文档路由重定向
   
   为每个路由配置中间件链：跨域、客户端检查、认证、授权等。
   
   【参数】
   _ - Integrant 键（忽略）
   cfg - 路由配置（包含 ::methods, ::management-methods, ::setup/shared-keys）
   
   【返回值】
   Yetti 路由结构"
  [_ {:keys [::methods ::management-methods ::setup/shared-keys] :as cfg}]

  (let [public-uri (cf/get :public-uri)]
    ["/api"
     ["/management"
      ["/methods/:method-name"
       {:middleware [[mw/shared-key-auth shared-keys]
                     [session/authz cfg]]
        :handler (make-rpc-handler management-methods)}]

      (doc/routes :methods management-methods
                  :label "management"
                  :base-uri (u/join public-uri "/api/management")
                  :description "MANAGEMENT API")]

     ["/main"
      ["/methods/:method-name"
       {:middleware [[mw/cors]
                     [sec/client-header-check]
                     [session/authz cfg]
                     [actoken/authz cfg]]
        :handler (make-rpc-handler methods)}]

      (doc/routes :methods methods
                  :label "main"
                  :base-uri (u/join public-uri "/api/main")
                  :description "MAIN API")]

     ;; BACKWARD COMPATIBILITY
     ["/_doc" {:handler (redirect (u/join public-uri "/api/main/doc"))}]
     ["/doc" {:handler (redirect (u/join public-uri "/api/main/doc"))}]
     ["/openapi" {:handler (redirect (u/join public-uri "/api/main/doc/openapi"))}]
     ["/openapi.join" {:handler (redirect (u/join public-uri "/api/main/doc/openapi.json"))}]

     ["/rpc/command/:method-name"
      {:middleware [[mw/cors]
                    [sec/client-header-check]
                    [session/authz cfg]
                    [actoken/authz cfg]]
       :handler (make-rpc-handler methods)}]]))
