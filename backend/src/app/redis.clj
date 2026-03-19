;; =============================================================================
;; Redis 缓存层 (Redis Cache Layer)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块是 Redis 客户端的封装层，基于 Lettuce 库实现。
;; 提供连接池管理、Pub/Sub 消息订阅、脚本执行等功能的抽象接口。
;;
;; 【核心概念】
;; 1. Lettuce - 异步 Redis 客户端库
;; 2. 连接池管理 - 使用通用连接池框架管理 Redis 连接
;; 3. Pub/Sub - 发布/订阅模式用于消息传递
;; 4. Lua 脚本 - Redis 脚本支持用于原子操作
;; 5. Netty - 使用 Netty 进行异步 IO
;;
;; 【依赖关系】
;; - io.lettuce.core - Redis 客户端
;; - app.common.generic-pool - 通用连接池
;; - app.redis.script - Redis 脚本管理
;; - app.worker - Netty 执行器配置
;;
;; =============================================================================

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.redis
  "The msgbus abstraction implemented using redis as underlying backend."
  (:refer-clojure :exclude [eval get set run!])
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.generic-pool :as gpool]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.metrics :as mtx]
   [app.redis.script :as-alias rscript]
   [app.worker :as wrk]
   [app.worker.executor]
   [clojure.core :as c]
   [clojure.java.io :as io]
   [cuerdas.core :as str]
   [integrant.core :as ig])
  (:import
   clojure.lang.MapEntry
   io.lettuce.core.KeyValue
   io.lettuce.core.RedisClient
   io.lettuce.core.RedisCommandInterruptedException
   io.lettuce.core.RedisCommandTimeoutException
   io.lettuce.core.RedisException
   io.lettuce.core.RedisURI
   io.lettuce.core.ScriptOutputType
   io.lettuce.core.SetArgs
   io.lettuce.core.api.StatefulRedisConnection
   io.lettuce.core.api.sync.RedisCommands
   io.lettuce.core.api.sync.RedisScriptingCommands
   io.lettuce.core.codec.RedisCodec
   io.lettuce.core.codec.StringCodec
   io.lettuce.core.pubsub.RedisPubSubListener
   io.lettuce.core.pubsub.StatefulRedisPubSubConnection
   io.lettuce.core.pubsub.api.sync.RedisPubSubCommands
   io.lettuce.core.resource.ClientResources
   io.lettuce.core.resource.DefaultClientResources
   io.netty.channel.nio.NioEventLoopGroup
   io.netty.util.HashedWheelTimer
   io.netty.util.Timer
   io.netty.util.concurrent.EventExecutorGroup
   java.lang.AutoCloseable
   java.time.Duration))

(set! *warn-on-reflection* true)

(def ^:const MAX-EVAL-RETRIES 18)

(def default-timeout
  (ct/duration "10s"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPL & PRIVATE API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; 【协议定义】
;; 定义 Redis 连接的核心接口

(defprotocol IConnection
  "连接超时管理协议。
   
   【方法】
   -set-timeout - 设置连接超时时间
   -get-timeout - 获取当前超时时间
   -reset-timeout - 重置为默认超时时间"
  (-set-timeout [_ timeout] "set connection timeout")
  (-get-timeout [_] "get current timeout")
  (-reset-timeout [_] "reset to default timeout"))

(defprotocol IDefaultConnection
  "标准 Redis 连接公共 API。
   
   【方法】
   -publish - 发布消息到主题
   -rpush - 将元素推入列表右侧
   -blpop - 阻塞式弹出列表元素
   -eval - 执行 Lua 脚本
   -get - 获取键值
   -set - 设置键值
   -del - 删除键
   -ping - ping 服务器"
  "Public API of default redis connection"
  (-publish [_ topic message])
  (-rpush [_ key payload])
  (-blpop [_ timeout keys])
  (-eval [_ script])
  (-get [_ key])
  (-set [_ key val args])
  (-del [_ key-or-keys])
  (-ping [_]))

(defprotocol IPubSubConnection
  "发布/订阅连接协议。
   
   【方法】
   -add-listener - 添加消息监听器
   -subscribe - 订阅主题
   -unsubscribe - 取消订阅主题"
  (-add-listener [_ listener])
  (-subscribe [_ topics])
  (-unsubscribe [_ topics]))

(def ^:private default-codec
  (RedisCodec/of StringCodec/UTF8 StringCodec/UTF8))

(defn- impl-eval
  "执行 Redis Lua 脚本的内部实现。
   
   【功能】
   加载并执行 Redis Lua 脚本，支持脚本缓存和自动重载。
   如果脚本不存在（NOSCRIPT 错误），自动重新加载脚本并重试。
   
   【参数】
   cmd - Redis 命令接口
   cache - 脚本缓存（原子映射）
   metrics - 指标记录器
   script - 脚本映射（包含 ::rscript/name, ::rscript/path 等）
   
   【返回值】
   脚本执行结果"
  [cmd cache metrics script]
  (let [keys    (into-array String (map str (::rscript/keys script)))
        vals    (into-array String (map str (::rscript/vals script)))
        sname   (::rscript/name script)

        read-script
        (fn []
          (-> script ::rscript/path io/resource slurp))

        load-script
        (fn []
          (let [id (.scriptLoad ^RedisScriptingCommands cmd
                                ^String (read-script))]
            (swap! cache assoc sname id)
            (l/trc :hint "load script" :name sname :id id)

            id))

        eval-script
        (fn [id]
          (try
            (let [tpoint  (ct/tpoint)
                  result  (.evalsha ^RedisScriptingCommands cmd
                                    ^String id
                                    ^ScriptOutputType ScriptOutputType/MULTI
                                    ^"[Ljava.lang.String;" keys
                                    ^"[Ljava.lang.String;" vals)
                  elapsed (tpoint)]

              (mtx/run! metrics {:id :redis-eval-timing
                                 :labels [(name sname)]
                                 :val (inst-ms elapsed)})

              (l/trc :hint "eval script"
                     :name (name sname)
                     :id id
                     :params (str/join "," (::rscript/vals script))
                     :elapsed (ct/format-duration elapsed))

              result)

            (catch io.lettuce.core.RedisNoScriptException _cause
              ::load)

            (catch Throwable cause
              (when-let [on-error (::rscript/on-error script)]
                (on-error cause))
              (throw cause))))

        eval-script'
        (fn [id]
          (loop [id      id
                 retries 0]
            (if (> retries MAX-EVAL-RETRIES)
              (ex/raise :type :internal
                        :code ::max-eval-retries-reached
                        :hint (str "unable to eval redis script " sname))
              (let [result (eval-script id)]
                (if (= result ::load)
                  (recur (load-script)
                         (inc retries))
                  result)))))]

    (if-let [id (c/get @cache sname)]
      (eval-script' id)
      (-> (load-script)
          (eval-script')))))

(deftype Connection [^StatefulRedisConnection conn
                     ^RedisCommands cmd
                     ^Duration timeout
                     cache metrics]
  AutoCloseable
  (close [_]
    (ex/ignoring (.close conn)))

  IConnection
  (-set-timeout [_ timeout]
    (.setTimeout conn ^Duration timeout))

  (-reset-timeout [_]
    (.setTimeout conn timeout))

  (-get-timeout [_]
    (.getTimeout conn))

  IDefaultConnection
  (-publish [_ topic message]
    (.publish cmd ^String topic ^String message))

  (-rpush [_ key elements]
    (try
      (let [vals (make-array String (count elements))]
        (loop [i 0 xs (seq elements)]
          (when xs
            (aset ^"[[Ljava.lang.String;" vals i ^String (first xs))
            (recur (inc i) (next xs))))

        (.rpush cmd
                ^String key
                ^"[[Ljava.lang.String;" vals))

      (catch RedisCommandInterruptedException cause
        (throw (InterruptedException. (ex-message cause))))))

  (-blpop [_ keys timeout]
    (try
      (let [keys (into-array String keys)]
        (when-let [res (.blpop cmd
                               ^double timeout
                               ^"[Ljava.lang.String;" keys)]
          (MapEntry/create
           (.getKey ^KeyValue res)
           (.getValue ^KeyValue res))))
      (catch RedisCommandInterruptedException cause
        (throw (InterruptedException. (ex-message cause))))))

  (-get [_ key]
    (assert (string? key) "key expected to be string")
    (.get cmd ^String key))

  (-set [_ key val args]
    (.set cmd
          ^String key
          ^bytes val
          ^SetArgs args))

  (-del [_ keys]
    (let [keys (into-array String keys)]
      (.del cmd ^String/1 keys)))

  (-ping [_]
    (.ping cmd))

  (-eval [_ script]
    (impl-eval cmd cache metrics script)))


(deftype SubscriptionConnection [^StatefulRedisPubSubConnection conn
                                 ^RedisPubSubCommands cmd
                                 ^Duration timeout]
  AutoCloseable
  (close [_]
    (ex/ignoring (.close conn)))

  IConnection
  (-set-timeout [_ timeout]
    (.setTimeout conn ^Duration timeout))

  (-reset-timeout [_]
    (.setTimeout conn timeout))

  (-get-timeout [_]
    (.getTimeout conn))

  IPubSubConnection
  (-add-listener [_ listener]
    (.addListener conn ^RedisPubSubListener listener))

  (-subscribe [_ topics]
    (try
      (let [topics (into-array String topics)]
        (.subscribe cmd topics))
      (catch RedisCommandInterruptedException cause
        (throw (InterruptedException. (ex-message cause))))))

  (-unsubscribe [_ topics]
    (try
      (let [topics (into-array String topics)]
        (.unsubscribe cmd topics))
      (catch RedisCommandInterruptedException cause
        (throw (InterruptedException. (ex-message cause)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn build-set-args
  "构建 Redis SET 命令的参数。
   
   【功能】
   将 Clojure 映射转换为 Redis SetArgs 对象，用于 SET 命令的可选参数。
   
   【参数】
   options - Clojure 映射，支持 :ex (秒), :px (毫秒), :nx (仅设置), :keep-ttl (保留过期时间)
   
   【返回值】
   SetArgs 实例"
  [options]
  (reduce-kv (fn [^SetArgs args k v]
               (case k
                 :ex (if (instance? Duration v)
                       (.ex args ^Duration v)
                       (.ex args (long v)))
                 :px (.px args (long v))
                 :nx (if v (.nx args) args)
                 :keep-ttl (if v (.keepttl args) args)))
             (SetArgs.)
             options))

(defn pubsub-listener
  "创建 Pub/Sub 监听器。
   
   【功能】
   创建一个 RedisPubSubListener 实例，用于处理发布/订阅消息事件。
   
   【参数】
   on-message - 收到消息时的回调函数 (pattern, topic, message)
   on-subscribe - 订阅成功时的回调函数 (pattern, topic, count)
   on-unsubscribe - 取消订阅时的回调函数 (pattern, topic, count)
   
   【返回值】
   RedisPubSubListener 实例"
  [& {:keys [on-message on-subscribe on-unsubscribe]}]
  (reify RedisPubSubListener
    (message [_ pattern topic message]
      (when on-message
        (on-message pattern topic message)))

    (message [_ topic message]
      (when on-message
        (on-message nil topic message)))

    (psubscribed [_ pattern count]
      (when on-subscribe
        (on-subscribe pattern nil count)))

    (punsubscribed [_ pattern count]
      (when on-unsubscribe
        (on-unsubscribe pattern nil count)))

    (subscribed [_ topic count]
      (when on-subscribe
        (on-subscribe nil topic count)))

    (unsubscribed [_ topic count]
      (when on-unsubscribe
        (on-unsubscribe nil topic count)))))

(defn connect
  "创建 Redis 标准连接。
   
   【功能】
   创建一个同步的 Redis 连接，用于执行普通命令。
   
   【参数】
   cfg - 系统配置（需要包含 ::mtx/metrics 和 ::rds/client）
   options - 可选参数，如 :timeout
   
   【返回值】
   Redis 连接实例"
  [cfg & {:as options}]
  (assert (contains? cfg ::mtx/metrics) "missing ::mtx/metrics on provided system")
  (assert (contains? cfg ::client) "missing ::rds/client on provided system")

  (let [state   (::client cfg)

        cache   (::cache state)
        client  (::client state)
        timeout (or (some-> (:timeout options) ct/duration)
                    (::timeout state))

        conn    (.connect ^RedisClient client
                          ^RedisCodec default-codec)
        cmd     (.sync ^StatefulRedisConnection conn)]

    (.setTimeout ^StatefulRedisConnection conn ^Duration timeout)
    (->Connection conn cmd timeout cache (::mtx/metrics cfg))))

(defn connect-pubsub
  "创建 Redis Pub/Sub 连接。
   
   【功能】
   创建一个用于发布/订阅的 Redis 连接。
   
   【参数】
   cfg - 系统配置
   options - 可选参数，如 :timeout
   
   【返回值】
   Pub/Sub 连接实例"
  [cfg & {:as options}]
  (let [state   (::client cfg)
        client  (::client state)

        timeout (or (some-> (:timeout options) ct/duration)
                    (::timeout state))
        conn    (.connectPubSub ^RedisClient client
                                ^RedisCodec default-codec)
        cmd     (.sync ^StatefulRedisPubSubConnection conn)]


    (.setTimeout ^StatefulRedisPubSubConnection conn
                 ^Duration timeout)
    (->SubscriptionConnection conn cmd timeout)))

(defn get
  "获取 Redis 键的值。
   
   【参数】
   conn - Redis 连接
   key - 键名（字符串）
   
   【返回值】
   键对应的值，如果超时返回 nil"
  [conn key]
  (assert (string? key) "key must be string instance")
  (try
    (-get conn key)
    (catch RedisCommandTimeoutException cause
      (l/err :hint "timeout on get redis key" :key key :cause cause)
      nil)))

(defn set
  "设置 Redis 键值对。
   
   【参数】
   conn - Redis 连接
   key - 键名（字符串）
   val - 值（字符串）
   args - 可选参数，如 {:ex 3600} 表示 3600 秒过期
   
   【返回值】
   设置成功返回 true，超时返回 nil"
  ([conn key val]
   (set conn key val nil))
  ([conn key val args]
   (assert (string? key) "key must be string instance")
   (assert (string? val) "val must be string instance")
   (let [args (cond
                 (or (instance? SetArgs args)
                     (nil? args))
                 args

                 (map? args)
                 (build-set-args args)

                 :else
                 (throw (IllegalArgumentException. "invalid args")))]

      (try
       (-set conn key val args)
       (catch RedisCommandTimeoutException cause
         (l/err :hint "timeout on set redis key" :key key :cause cause)
         nil)))))

(defn del
  "删除 Redis 键。
   
   【参数】
   conn - Redis 连接
   key-or-keys - 要删除的键或键向量
   
   【返回值】
   删除的键数量，超时返回 nil"
  [conn key-or-keys]
  (let [keys (if (vector? key-or-keys) key-or-keys [key-or-keys])]
    (assert (every? string? keys) "only string keys allowed")
    (try
      (-del conn keys)
      (catch RedisCommandTimeoutException cause
        (l/err :hint "timeout on del redis key" :key key :cause cause)
        nil))))

(defn ping
  "Ping Redis 服务器。
   
   【参数】
   conn - Redis 连接
   
   【返回值】
   服务器响应"
  [conn]
  (-ping conn))

(defn blpop
  "阻塞式列表弹出操作。
   
   【功能】
   从列表左侧弹出元素，如果列表为空则阻塞等待。
   
   【参数】
   conn - Redis 连接
   key-or-keys - 键或键列表
   timeout - 超时时间（秒）
   
   【返回值】
   [key value] 或超时返回 nil"
  [conn key-or-keys timeout]
  (let [keys    (if (vector? key-or-keys) key-or-keys [key-or-keys])
        timeout (cond
                  (ct/duration? timeout)
                  (/ (double (inst-ms timeout)) 1000.0)

                  (double? timeout)
                  timeout

                  (int? timeout)
                  (/ (double timeout) 1000.0)

                  :else
                  0)]

    (assert (every? string? keys) "only string keys allowed")
    (-blpop conn keys timeout)))

(defn rpush
  "将元素推入列表右侧。
   
   【参数】
   conn - Redis 连接
   key - 列表键
   elements - 要推入的元素列表
   
   【返回值】
   列表长度"
  [conn key elements]
  (assert (string? key) "key must be string instance")
  (assert (every? string? elements) "elements should be all strings")
  (let [elements (vec elements)]
    (-rpush conn key elements)))

(defn publish
  "发布消息到指定主题。
   
   【参数】
   conn - Redis 连接
   topic - 主题名
   payload - 消息内容
   
   【返回值】
   订阅者数量"
  [conn topic payload]
  (assert (string? topic) "expected topic to be string")
  (assert (string? payload) "expected message to be a byte array")
  (-publish conn topic payload))

(def ^:private schema:script
  [:map {:title "script"}
   [::rscript/name qualified-keyword?]
   [::rscript/path ::sm/text]
   [::rscript/keys {:optional true} [:vector :any]]
   [::rscript/vals {:optional true} [:vector :any]]])

(def ^:private valid-script?
  (sm/lazy-validator schema:script))

(defn eval
  "执行 Redis Lua 脚本。
   
   【参数】
   conn - Redis 连接
   script - 脚本映射（包含 ::rscript/name, ::rscript/path 等）
   
   【返回值】
   脚本执行结果"
  [conn script]
  (assert (valid-script? script) "expected valid script")
  (-eval conn script))

(defn add-listener
  "添加 Pub/Sub 监听器。
   
   【参数】
   conn - Redis Pub/Sub 连接
   listener - 监听器（可以是映射或 RedisPubSubListener 实例）
   
   【返回值】
   无返回值"
  [conn listener]
  (let [listener (cond
                   (map? listener)
                   (pubsub-listener listener)

                   (instance? RedisPubSubListener listener)
                   listener

                   :else
                   (throw (IllegalArgumentException. "invalid listener provided")))]

    (-add-listener conn listener)))

(defn subscribe
  "订阅一个或多个主题。
   
   【参数】
   conn - Redis Pub/Sub 连接
   topic-or-topics - 主题或主题列表
   
   【返回值】
   无返回值"
  [conn topic-or-topics]
  (let [topics (if (vector? topic-or-topics) topic-or-topics [topic-or-topics])]
    (assert (every? string? topics))
    (-subscribe conn topics)))

(defn unsubscribe
  "取消订阅一个或多个主题。
   
   【参数】
   conn - Redis Pub/Sub 连接
   topic-or-topics - 主题或主题列表
   
   【返回值】
   无返回值"
  [conn topic-or-topics]
  (let [topics (if (vector? topic-or-topics) topic-or-topics [topic-or-topics])]
    (assert (every? string? topics))
    (-unsubscribe conn topics)))

(defn set-timeout
  "设置连接超时时间。
   
   【参数】
   conn - Redis 连接
   timeout - 超时时间（Duration 或可转换的时间值）
   
   【返回值】
   无返回值"
  [conn timeout]
  (let [timeout (ct/duration timeout)]
    (-set-timeout conn timeout)))

(defn get-timeout
  "获取连接超时时间。
   
   【参数】
   conn - Redis 连接
   
   【返回值】
   当前超时时间"
  [conn]
  (-get-timeout conn))

(defn reset-timeout
  "重置连接超时时间为默认值。
   
   【参数】
   conn - Redis 连接
   
   【返回值】
   无返回值"
  [conn]
  (-reset-timeout conn))

(defn timeout-exception?
  "检查异常是否是 Redis 超时异常。
   
   【参数】
   cause - 要检查的异常
   
   【返回值】
   是超时异常返回 true"
  [cause]
  (instance? RedisCommandTimeoutException cause))

(defn exception?
  "检查异常是否是 Redis 异常。
   
   【参数】
   cause - 要检查的异常
   
   【返回值】
   是 Redis 异常返回 true"
  [cause]
  (instance? RedisException cause))

(defn get-pooled
  "从连接池获取一个连接。
   
   【参数】
   cfg - 系统配置（需要包含 ::pool）
   
   【返回值】
   连接实例"
  [cfg]
  (let [pool (::pool cfg)]
    (gpool/get pool)))

(defn close
  "关闭可关闭对象。
   
   【参数】
   o - 实现 AutoCloseable 的对象
   
   【返回值】
   无返回值"
  [o]
  (.close ^AutoCloseable o))

(defn pool
  "创建 Redis 连接池。
   
   【参数】
   cfg - 系统配置
   options - 可选参数
   
   【返回值】
   连接池实例"
  [cfg & {:as options}]
  (gpool/create :create-fn (partial connect cfg options)
                :destroy-fn close
                :dispose-fn -reset-timeout))

(defn run!
  "使用连接池执行函数。
   
   【功能】
   如果配置中已包含连接池，直接使用；否则从配置中获取连接池。
   
   【参数】
   cfg - 系统配置
   f - 要执行的函数
   args - 传递给函数的参数
   
   【返回值】
   函数的返回值"
  [cfg f & args]
  (if (gpool/pool? cfg)
    (apply f {::pool cfg} f args)
    (let [pool (::pool cfg)]
      (with-open [^AutoCloseable conn (gpool/get pool)]
        (apply f (assoc cfg ::conn @conn) args)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INITIALIZATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/expand-key ::client
  [k v]
  {k (-> (d/without-nils v)
         (assoc ::timeout (ct/duration "10s")))})

(def ^:private schema:client
  [:map {:title "RedisClient"}
   [::timer [:fn #(instance? HashedWheelTimer %)]]
   [::cache ::sm/atom]
   [::timeout ::ct/duration]
   [::resources [:fn #(instance? DefaultClientResources %)]]])

(def check-client
  (sm/check-fn schema:client))

(sm/register! ::client schema:client)
(sm/register!
 {:type ::pool
  :pred gpool/pool?})

(def ^:private schema:client-params
  [:map {:title "redis-params"}
   ::wrk/netty-io-executor
   ::wrk/netty-executor
   [::uri ::sm/uri]
   [::timeout ::ct/duration]])

(def ^:private check-client-params
  (sm/check-fn schema:client-params))

(defmethod ig/assert-key ::client
  [_ params]
  (check-client-params params))

(defmethod ig/init-key ::client
  [_ {:keys [::uri ::wrk/netty-io-executor ::wrk/netty-executor] :as params}]

  (l/inf :hint "initialize redis client" :uri (str uri))

  (let [timer     (HashedWheelTimer.)
        cache     (atom {})

        resources (.. (DefaultClientResources/builder)
                      (eventExecutorGroup ^EventExecutorGroup netty-executor)

                      ;; We provide lettuce with a shared event loop
                      ;; group instance instead of letting lettuce to
                      ;; create its own
                      (eventLoopGroupProvider
                       (reify io.lettuce.core.resource.EventLoopGroupProvider
                         (allocate [_ _] netty-io-executor)
                         (threadPoolSize [_]
                           (.executorCount ^NioEventLoopGroup netty-io-executor))
                         (release [_ _ _ _ _]
                           ;; Do nothing
                           )
                         (shutdown [_ _ _ _]
                           ;; Do nothing
                           )))

                      (timer ^Timer timer)
                      (build))

        redis-uri (RedisURI/create ^String (str uri))
        client    (RedisClient/create ^ClientResources resources
                                      ^RedisURI redis-uri)]

    {::client client
     ::cache cache
     ::timer timer
     ::timeout default-timeout
     ::resources resources}))

(defmethod ig/halt-key! ::client
  [_ {:keys [::client ::timer ::resources]}]
  (ex/ignoring (.shutdown ^RedisClient client))
  (ex/ignoring (.shutdown ^ClientResources resources))
  (ex/ignoring (.stop ^Timer timer)))

(defmethod ig/assert-key ::pool
  [_ {:keys [::client]}]
  (check-client client))

(defmethod ig/init-key ::pool
  [_ cfg]
  (pool cfg {:timeout (ct/duration 2000)}))

(defmethod ig/halt-key! ::pool
  [_ instance]
  (.close ^java.lang.AutoCloseable instance))
