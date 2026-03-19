;; =============================================================================
;; Redis 客户端模块 (Redis Client Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块封装了 Redis 客户端，用于导出服务与后端之间的消息发布。
;; 通过 Redis 发布/订阅机制，导出服务可以向后端推送导出进度和结果。
;;
;; 【核心概念】
;; 1. 连接池管理 - 管理 Redis 客户端连接的生命周期
;; 2. 发布/订阅 - 向指定主题发布消息，通知后端导出状态
;; 3. 消息编码 - 使用 Transit 格式编码消息内容
;; 4. 多租户隔离 - 使用租户 ID 作为主题前缀
;;
;; 【依赖关系】
;; - ioredis - Node.js Redis 客户端库
;; - app.config - 配置模块
;; - app.common.transit - Transit 消息编码
;;
;; =============================================================================

(ns app.redis
  (:require
   ["ioredis" :as redis]
   [app.common.data.macros :as dm]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.config :as cf]))

(l/set-level! :trace)

(def client
  "Redis 客户端实例的原子引用。
   
   【说明】
   用于存储当前活动的 Redis 客户端连接，支持发布消息和连接管理。"
  (atom nil))

(defn- create-client
  "创建 Redis 客户端实例。
   
   【参数】
   uri - Redis 连接 URI
   
   【返回值】
   配置好的 Redis 客户端实例。
   
   【功能说明】
   1. 创建 ioredis 客户端
   2. 设置连接、错误、关闭、重连等事件处理器
   3. 输出相应的日志信息"
  [uri]
  (let [^js client (new redis/default uri)]
    (.on client "connect"
         (fn [] (l/info :hint "redis connection established" :uri uri)))
    (.on client "error"
         (fn [cause] (l/error :hint "error on redis connection" :cause cause)))
    (.on client "close"
         (fn [] (l/warn :hint "connection closed")))
    (.on client "reconnect"
         (fn [ms] (l/warn :hint "reconnecting to redis" :ms ms)))
    (.on client "end"
         (fn [] (l/warn :hint "client ended, no more connections will be attempted")))
    client))

(defn init
  "初始化 Redis 客户端连接。
   
   【参数】
   无
   
   【返回值】
   无（更新全局 client 原子）
   
   【功能说明】
   如果已存在连接，先断开旧连接，然后创建新连接。"
  []
  (swap! client (fn [prev]
                  (when prev (.disconnect ^js prev))
                  (create-client (cf/get :redis-uri)))))


(defn stop
  "停止 Redis 客户端连接。
   
   【参数】
   无
   
   【返回值】
   无（更新全局 client 原子为 nil）
   
   【功能说明】
   向 Redis 服务器发送 QUIT 命令，等待连接关闭后重置 client。"
  []
  (swap! client (fn [client]
                  (when client (.quit ^js client))
                  nil)))

(def ^:private
  tenant
  "当前租户标识符。
   
   【说明】
   从配置中获取当前租户 ID，用于构建 Redis 主题前缀，实现多租户隔离。
   格式：{tenant}.{topic}"
  (cf/get :tenant))

(defn pub!
  "发布消息到指定主题。
   
   【参数】
   topic - 主题名称（不带租户前缀）
   payload - 消息内容（可以是 map 或字符串）
   
   【返回值】
   无
   
   【功能说明】
   1. 如果 payload 是 map，使用 Transit 格式编码
   2. 主题名称添加租户前缀（格式：{tenant}.{topic}）
   3. 调用 Redis publish 发布消息"
  [topic payload]
  (let [payload (if (map? payload) (t/encode-str payload) payload)
        topic   (dm/str tenant "." topic)]
    (when-let [client @client]
      (.publish ^js client topic payload))))
