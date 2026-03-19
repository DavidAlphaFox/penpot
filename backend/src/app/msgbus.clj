;; =============================================================================
;; 消息总线 (Message Bus)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块是消息总线的实现，基于 Redis Pub/Sub 构建。
;; 提供发布/订阅模式的消息传递机制，支持多租户隔离。
;; 用于系统内部的实时通信，如 WebSocket 消息推送、任务通知等。
;;
;; 【核心概念】
;; 1. Pub/Sub Pattern - 发布/订阅模式，解耦消息生产者和消费者
;; 2. Topic - 主题，用于分类消息
;; 3. Channel - 通道，本地订阅者与 Redis 订阅的桥梁
;; 4. Multi-tenancy - 多租户支持，通过主题前缀隔离不同租户
;; 5. Core Async - 使用 core.async 进行异步消息处理
;;
;; 【依赖关系】
;; - app.redis - Redis 客户端
;; - app.worker - 执行器配置
;; - promesa.exec.csp - Core Async 通道操作
;;
;; =============================================================================

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.msgbus
  "The msgbus abstraction implemented using redis as underlying backend."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.config :as cfg]
   [app.redis :as rds]
   [app.worker :as wrk]
   [integrant.core :as ig]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]))

(set! *warn-on-reflection* true)
(def ^:private prefix (cfg/get :tenant))

(defprotocol IMsgBus
  (-sub [_ topics chan])
  (-pub [_ topic message])
  (-purge [_ chans]))



(defn- prefix-topic
  "为给定的主题名称添加租户前缀。
   
   【参数】
   topic - 主题名称（字符串）
   
   【返回值】
   带租户前缀的主题名称字符串，格式：\"{tenant}.{topic}\""
  [topic]
  (str prefix "." topic))

(def ^:private xform-prefix-topic
  (map (fn [obj] (update obj :topic prefix-topic))))

(declare ^:private redis-pub)
(declare ^:private redis-sub)
(declare ^:private redis-unsub)
(declare ^:private start-io-loop)
(declare ^:private subscribe-to-topics)
(declare ^:private unsubscribe-channels)

(defn msgbus?
  "检查给定对象是否实现了 IMsgBus 消息总线协议。
   
   【参数】
   o - 要检查的对象
   
   【返回值】
   如果对象实现了 IMsgBus 协议返回 true，否则返回 false"
  [o]
  (satisfies? IMsgBus o))

(sm/register!
 {:type ::msgbus
  :pred msgbus?})

"扩展 Integrant 配置键，设置默认值。
   
   【参数】
   k - 配置键
   v - 配置值映射
   
   【返回值】
   包含默认配置的映射"
(defmethod ig/expand-key ::msgbus
  [k v]
  {k (-> (d/without-nils v)
         (assoc ::buffer-size 128)
         (assoc ::timeout (ct/duration {:seconds 30})))})

(def ^:private schema:params
  [:map
   ::rds/client
   ::wrk/executor])

"验证消息总线配置参数的有效性。
   
   【参数】
   _ - 配置键（忽略）
   params - 要验证的参数映射
   
   【返回值】
   验证通过时返回 true，否则抛出断言错误"
(defmethod ig/assert-key ::msgbus
  [_ params]
  (assert (sm/check schema:params params)))

"初始化消息总线实例。
   创建 Redis 连接、核心异步通道和 I/O 线程。
   
   【参数】
   _ - 配置键（忽略）
   cfg - 配置映射，包含：
         - ::buffer-size - 通道缓冲区大小（默认 128）
         - ::wrk/executor - 执行器用于异步任务
         - ::timeout - Redis 连接超时时间
   
   【返回值】
   实现 IMsgBus 协议和 AutoCloseable 接口的消息总线实例"
(defmethod ig/init-key ::msgbus
  [_ {:keys [::buffer-size ::wrk/executor ::timeout] :as cfg}]
  (l/info :hint "initialize msgbus" :buffer-size buffer-size)
  (let [cmd-ch (sp/chan :buf buffer-size)
        rcv-ch (sp/chan :buf (sp/dropping-buffer buffer-size))
        pub-ch (sp/chan :buf (sp/dropping-buffer buffer-size)
                        :xf  xform-prefix-topic)
        state  (agent {})

        ;; Open persistent connections to redis
        pconn  (rds/connect cfg :timeout timeout)
        sconn  (rds/connect-pubsub cfg :timeout timeout)

        _      (set-error-handler! state #(l/error :cause % :hint "unexpected error on agent" ::l/sync? true))
        _      (set-error-mode! state :continue)

        cfg    (-> cfg
                   (assoc ::pconn pconn)
                   (assoc ::sconn sconn)
                   (assoc ::cmd-ch cmd-ch)
                   (assoc ::rcv-ch rcv-ch)
                   (assoc ::pub-ch pub-ch)
                   (assoc ::state state))

        io-thr (start-io-loop cfg)]

    (reify
      java.lang.AutoCloseable
      (close [_]
        (px/interrupt! io-thr)
        (sp/close! cmd-ch)
        (sp/close! rcv-ch)
        (sp/close! pub-ch)
        (d/close! pconn)
        (d/close! sconn))

      IMsgBus
      (-sub [_ topics chan]
        (l/debug :hint "subscribe" :topics topics :chan (hash chan))
        (send-via executor state subscribe-to-topics cfg topics chan))

      (-pub [_ topic message]
        (let [message (assoc message :topic topic)]
          (sp/put! pub-ch {:topic topic :message message})))

      (-purge [_ chans]
        (l/debug :hint "purge" :chans (count chans))
        (send-via executor state unsubscribe-channels cfg chans)))))

"停止并关闭消息总线实例。
   释放所有资源，包括 Redis 连接和核心异步通道。
   
   【参数】
   _ - 配置键（忽略）
   instance - 要关闭的消息总线实例
   
   【返回值】
   无返回值"
(defmethod ig/halt-key! ::msgbus
  [_ instance]
  (d/close! instance))

(defn sub!
  "订阅一个或多个主题，将消息路由到指定的通道。
   
   【参数】
   instance - 消息总线实例
   :topic - 单个主题名称
   :topics - 主题名称向量
   :chan - 接收消息的核心异步通道
   
   【返回值】
   返回 nil"
  [instance & {:keys [topic topics chan]}]
  (assert (satisfies? IMsgBus instance) "expected valid msgbus instance")
  (let [topics (into [] (map prefix-topic) (if topic [topic] topics))]
    (-sub instance topics chan)
    nil))

(defn pub!
  "发布消息到指定主题。
   
   【参数】
   instance - 消息总线实例
   :topic - 目标主题名称
   :message - 要发布的消息（映射）
   
   【返回值】
   返回 nil"
  [instance & {:keys [topic message]}]
  (assert (satisfies? IMsgBus instance) "expected valid msgbus instance")
  (-pub instance topic message))

(defn purge!
  "取消订阅并关闭指定的通道。
   
   【参数】
   instance - 消息总线实例
   chans - 要关闭的核心异步通道序列
   
   【返回值】
   返回 nil"
  [instance chans]
  (assert (satisfies? IMsgBus instance) "expected valid msgbus instance")
  (assert (every? sp/chan? chans) "expected a seq of chans")
  (-purge instance chans)
  nil)

;; --- IMPL

(defn- conj-subscription
  "底层函数，按需创建 Redis 订阅。
   如果订阅已存在则复用。
   
   【参数】
   nsubs - 当前订阅集合（可能为 nil）
   cfg - 配置映射
   topic - 主题名称
   chan - 本地核心异步通道
   
   【返回值】
   更新后的订阅集合"
  [nsubs cfg topic chan]
  (let [nsubs (if (nil? nsubs) #{chan} (conj nsubs chan))]
    (when (= 1 (count nsubs))
      (l/trace :hint "open subscription" :topic topic ::l/sync? true)
      (redis-sub cfg topic))
    nsubs))

(defn- disj-subscription
  "底层函数，移除订阅。
   只有当没有任何本地订阅时才真正从 Redis 移除订阅。
   
   【参数】
   nsubs - 当前订阅集合
   cfg - 配置映射
   topic - 主题名称
   chan - 本地核心异步通道
   
   【返回值】
   更新后的订阅集合"
  [nsubs cfg topic chan]
  (let [nsubs (disj nsubs chan)]
    (when (empty? nsubs)
      (l/trace :hint "close subscription" :topic topic ::l/sync? true)
      (redis-unsub cfg topic))
    nsubs))

(defn- subscribe-to-topics
  "将本地订阅附加到状态管理器。
   
   【参数】
   state - 代理状态
   cfg - 配置映射
   topics - 主题名称向量
   chan - 本地核心异步通道
   
   【返回值】
   更新后的状态"
  [state cfg topics chan]
  (let [state (update state :chans assoc chan topics)]
    (reduce (fn [state topic]
              (update-in state [:topics topic] conj-subscription cfg topic chan))
            state
            topics)))

(defn- unsubscribe-channel
  "辅助函数，从状态中移除单个本地订阅。
   
   【参数】
   state - 代理状态
   cfg - 配置映射
   chan - 要移除的核心异步通道
   
   【返回值】
   更新后的状态"
  [state cfg chan]
  (let [topics (get-in state [:chans chan])
        state  (update state :chans dissoc chan)]
    (reduce (fn [state topic]
              (update-in state [:topics topic] disj-subscription cfg topic chan))
            state
            topics)))

(defn- unsubscribe-channels
  "从状态中分离多个通道的订阅。
   用于客户端断开连接或批量取消订阅操作。
   计划在代理中执行。
   
   【参数】
   state - 代理状态
   cfg - 配置映射
   channels - 要移除的核心异步通道序列
   
   【返回值】
   更新后的状态"
  [state cfg channels]
  (reduce #(unsubscribe-channel %1 cfg %2) state channels))

(defn- create-listener
  "创建 Redis 消息监听器。
   使用滑动缓冲区处理背压情况。
   
   【参数】
   rcv-ch - 接收消息的核心异步通道
   
   【返回值】
   包含 on-message 回调的映射"
  [rcv-ch]
  {:on-message (fn [_ topic message]
                 ;; There are no back pressure, so we use a slidding
                 ;; buffer for cases when the pubsub broker sends
                 ;; more messages that we can process.
                 (let [val {:topic topic :message (t/decode-str message)}]
                   (when-not (sp/offer! rcv-ch val)
                     (l/warn :msg "dropping message on subscription loop"))))})

(defn- process-input
  "处理接收到的消息，将消息路由到订阅的通道。
   
   【参数】
   cfg - 配置映射
   topic - 消息主题
   message - 消息内容
   
   【返回值】
   无返回值"
  [{:keys [::state ::wrk/executor] :as cfg} topic message]
  (let [chans (get-in @state [:topics topic])]
    (when-let [closed (loop [chans  (seq chans)
                            closed #{}]
                        (if-let [ch (first chans)]
                          (if (sp/put! ch message)
                            (recur (rest chans) closed)
                            (recur (rest chans) (conj closed ch)))
                          (seq closed)))]
      (send-via executor state unsubscribe-channels cfg closed))))


"启动消息总线的 I/O 循环线程。
   负责处理 Redis 发布/订阅消息和本地通道的读写。
   
   【参数】
   cfg - 配置映射，包含：
         - ::sconn - Redis Pub/Sub 连接
         - ::rcv-ch - 接收通道
         - ::pub-ch - 发布通道
         - ::state - 代理状态
         - ::wrk/executor - 执行器
   
   【返回值】
   返回启动的线程"
(defn start-io-loop
  [{:keys [::sconn ::rcv-ch ::pub-ch ::state ::wrk/executor] :as cfg}]
  (rds/add-listener sconn (create-listener rcv-ch))

  (px/thread
    {:name "penpot/msgbus"}
    (try
      (loop []
        (let [timeout-ch (sp/timeout-chan 1000)
              [val port] (sp/alts! [timeout-ch pub-ch rcv-ch])]
          (cond
            (identical? port timeout-ch)
            (let [closed (->> (:chans @state)
                              (map key)
                              (filter sp/closed?))]
              (when (seq closed)
                (send-via executor state unsubscribe-channels cfg closed)
                (l/debug :hint "proactively purge channels" :count (count closed)))
              (recur))

            (nil? val)
            (throw (InterruptedException. "internally interrupted"))

            (identical? port rcv-ch)
            (let [{:keys [topic message]} val]
              (process-input cfg topic message)
              (recur))

            (identical? port pub-ch)
            (do
              (redis-pub cfg val)
              (recur)))))

      (catch InterruptedException _
        (l/trace :hint "io-loop thread interrumpted"))

      (catch Throwable cause
        (l/error :hint "unexpected exception on io-loop thread"
                 :cause cause))
      (finally
        (l/trace :hint "clearing io-loop state")
        (when-let [chans (:chans @state)]
          (run! sp/close! (keys chans)))

        (l/debug :hint "io-loop thread terminated")))))

(defn- redis-pub
  "将消息发布到 Redis 服务器。
   异步操作，计划在 core.async go 块中使用。
   
   【参数】
   cfg - 配置映射，包含 ::pconn（Redis 发布连接）
   {:keys [topic message]} - 包含主题和消息内容的映射
   
   【返回值】
   无返回值"
  [{:keys [::pconn] :as cfg} {:keys [topic message]}]
  (try
    (rds/publish pconn topic (t/encode-str message))
    (catch InterruptedException cause
      (throw cause))
    (catch Throwable cause
      (l/error :hint "unexpected error on publishing"
               :message message
               :cause cause))))

(defn- redis-sub
  "创建 Redis 订阅。阻塞操作，计划在代理中使用。
   
   【参数】
   cfg - 配置映射，包含 ::sconn（Redis Pub/Sub 连接）
   topic - 要订阅的主题名称
   
   【返回值】
   无返回值"
  [{:keys [::sconn] :as cfg} topic]
  (try
    (rds/subscribe sconn [topic])
    (catch InterruptedException cause
      (throw cause))
    (catch Throwable cause
      (l/trace :hint "exception on subscribing" :topic topic :cause cause))))

(defn- redis-unsub
  "移除 Redis 订阅。阻塞操作，计划在代理中使用。
   
   【参数】
   cfg - 配置映射，包含 ::sconn（Redis Pub/Sub 连接）
   topic - 要取消订阅的主题名称
   
   【返回值】
   无返回值"
  [{:keys [::sconn] :as cfg} topic]
  (try
    (rds/unsubscribe sconn [topic])
    (catch InterruptedException cause
      (throw cause))
    (catch Throwable cause
      (l/trace :hint "exception on unsubscribing" :topic topic :cause cause))))

