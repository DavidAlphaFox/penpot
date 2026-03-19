;; =============================================================================
;; 处理器入口模块 (Handler Entry Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块是 HTTP 请求处理的核心入口，负责将请求路由到对应的处理器。
;; 提供统一的错误处理和参数验证机制。
;;
;; 【核心概念】
;; 1. 命令分发 - 根据 :cmd 参数将请求分发到不同的处理器
;; 2. 错误处理 - 统一处理各种类型的错误并返回合适的 HTTP 状态码
;; 3. 参数验证 - 使用 spec 验证请求参数的合法性
;; 4. 中间件模式 - 使用多方法分发处理不同命令
;;
;; 【依赖关系】
;; - app.handlers.export-shapes - 形状导出处理器
;; - app.handlers.export-frames - 帧导出处理器
;; - app.util.transit - Transit 格式编解码
;;
;; =============================================================================

(ns app.handlers
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.handlers.export-frames :as export-frames]
   [app.handlers.export-shapes :as export-shapes]
   [app.util.transit :as t]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

(l/set-level! :debug)

(defn on-error
  "处理错误并生成错误响应。
   
   【参数】
   error - 错误对象
   exchange - HTTP 交换对象
   
   【返回值】
   添加了错误响应信息的 exchange 对象。
   
   【功能说明】
   根据错误类型返回不同的 HTTP 状态码：
   - :validation/:assertion -> 400
   - :not-found -> 404
   - :browser-not-ready -> 503
   - 其他内部错误 -> 500"
  [error exchange]
  (let [{:keys [type code] :as data} (ex-data error)]
    (cond
      (or (= :validation type)
          (= :assertion type))
      (let [explain (us/pretty-explain data)
            data    (-> data
                        (assoc :explain explain)
                        (assoc :type :validation)
                        (dissoc ::s/problems ::s/value ::s/spec))]
        (-> exchange
            (assoc :response/status 400)
            (assoc :response/body (t/encode data))
            (assoc :response/headers {"content-type" "application/transit+json"})))

      (= :not-found type)
      (-> exchange
          (assoc :response/status 404)
          (assoc :response/body (t/encode data))
          (assoc :response/headers {"content-type" "application/transit+json"}))

      (and (= :internal type)
           (= :browser-not-ready code))
      (let [data {:type :server-error
                  :code :internal
                  :hint (ex-message error)
                  :data data}]
        (-> exchange
            (assoc :response/status 503)
            (assoc :response/body (t/encode data))
            (assoc :response/headers {"content-type" "application/transit+json"})))

      :else
      (let [data {:type :server-error
                  :code code
                  :hint (ex-message error)
                  :data data}]
        (l/error :hint "unexpected internal error" :cause error)
        (-> exchange
            (assoc :response/status 500)
            (assoc :response/body (t/encode (d/without-nils data)))
            (assoc :response/headers {"content-type" "application/transit+json"}))))))

(defmulti command-spec :cmd)

(s/def ::id ::us/string)
(s/def ::wait ::us/boolean)
(s/def ::cmd ::us/keyword)

(defmethod command-spec :export-shapes [_] ::export-shapes/params)
(defmethod command-spec :export-frames [_] ::export-frames/params)

(s/def ::params
  (s/and (s/keys :req-un [::cmd]
                 :opt-un [::wait])
         (s/multi-spec command-spec :cmd)))

(defn handler
  "处理 HTTP 请求的主入口。
   
   【参数】
   {:keys [:request/params] :as exchange} - HTTP 交换对象，包含请求参数
   
   【返回值】
   添加了响应信息的 exchange 对象。
   
   【功能说明】
   1. 验证请求参数是否符合规范
   2. 根据 :cmd 分发到对应的处理器
   3. 如果命令未实现，抛出错误"
  [{:keys [:request/params] :as exchange}]
  (let [{:keys [cmd] :as params} (us/conform ::params params)]
    (l/debug :hint "process-request" :cmd cmd)
    (case cmd
      :export-shapes (export-shapes/handler exchange params)
      :export-frames (export-frames/handler exchange params)
      (ex/raise :type :internal
                :code :method-not-implemented
                :hint (str/istr "method ~{cmd} not implemented")))))
