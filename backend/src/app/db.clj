;; =============================================================================
;; 数据库层 (Database Layer)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块是 Penpot 后端的数据库抽象层，基于 next.jdbc 封装 PostgreSQL 操作。
;; 提供连接池管理、事务处理、SQL 构建器封装以及对 PostgreSQL 特有类型（如数组、JSON、inet 等）的支持。
;;
;; 【核心概念】
;; 1. HikariCP 连接池 - 高性能的 JDBC 连接池管理
;; 2. SQL 构建器 - 简化常见 CRUD 操作的 SQL 语句构建
;; 3. 命名转换 - kebab-case 和 snake_case 之间的自动转换
;; 4. 事务管理 - 支持嵌套事务和保存点
;; 5. PostgreSQL 类型 - 处理 pgarray、pgobject、inet 等特殊类型
;;
;; 【依赖关系】
;; - next.jdbc - JDBC 操作封装
;; - com.zaxxer.hikari - 连接池实现
;; - app.db.sql - SQL 构建器
;; - app.metrics - 指标收集
;;
;; =============================================================================

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.db
  (:refer-clojure :exclude [get run!])
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.point :as gpt]
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.db.sql :as sql]
   [app.metrics :as mtx]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [integrant.core :as ig]
   [next.jdbc :as jdbc]
   [next.jdbc.date-time :as jdbc-dt]
   [next.jdbc.prepare :as jdbc.prepare]
   [next.jdbc.transaction])
  (:import
   com.zaxxer.hikari.HikariConfig
   com.zaxxer.hikari.HikariDataSource
   com.zaxxer.hikari.metrics.prometheus.PrometheusMetricsTrackerFactory
   io.whitfin.siphash.SipHasher
   io.whitfin.siphash.SipHasherContainer
   java.io.InputStream
   java.io.OutputStream
   java.sql.Connection
   java.sql.PreparedStatement
   java.sql.Savepoint
   org.postgresql.PGConnection
   org.postgresql.geometric.PGpoint
   org.postgresql.jdbc.PgArray
   org.postgresql.largeobject.LargeObject
   org.postgresql.largeobject.LargeObjectManager
   org.postgresql.util.PGInterval
   org.postgresql.util.PGobject))

(def ^:dynamic *conn* nil)

(declare open)
(declare create-pool)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initialization
;; ============================================================================

(def ^:private schema:pool-options
  "连接池选项的 Malli schema 定义。
   
   【参数说明】
   - connect-timeout: 连接超时时间（毫秒）
   - max-size: 最大连接数
   - min-size: 最小连接数
   - name: 连接池名称
   - uri: 数据库 URI
   - password: 数据库密码
   - username: 数据库用户名
   - validation-timeout: 验证超时时间
   - read-only: 是否只读模式"
  [:map {:title "pool-options"}
   [::connect-timeout {:optional true} ::sm/int]
   [::max-size {:optional true} ::sm/int]
   [::min-size {:optional true} ::sm/int]
   [::name {:optional true} :keyword]
   [::uri {:optional true} ::sm/uri]
   [::password {:optional true} :string]
   [::username {:optional true} :string]
   [::validation-timeout {:optional true} ::sm/int]
   [::read-only {:optional true} ::sm/boolean]])

(def defaults
  "连接池默认配置值。
   
   【默认值】
   - name: :main
   - min-size: 0
   - max-size: 60
   - connection-timeout: 10000ms
   - validation-timeout: 10000ms
   - idle-timeout: 120000ms (2分钟)
   - max-lifetime: 1800000ms (30分钟)
   - read-only: false"
  {::name :main
   ::min-size 0
   ::max-size 60
   ::connection-timeout 10000
   ::validation-timeout 10000
   ::idle-timeout 120000 ; 2min
   ::max-lifetime 1800000 ; 30m
   ::read-only false})

(defmethod ig/assert-key ::pool
  [_ options]
  (assert (sm/check schema:pool-options options)))

(defmethod ig/init-key ::pool
  [_ cfg]
  (let [{:keys [::uri ::read-only] :as cfg}
        (merge defaults cfg)]
    (when uri
      (l/info :hint "initialize connection pool"
              :name (d/name (::name cfg))
              :uri (str uri)
              :read-only read-only
              :credentials (and (contains? cfg ::username)
                                (contains? cfg ::password))
              :min-size (::min-size cfg)
              :max-size (::max-size cfg))
      (create-pool cfg))))

(defmethod ig/halt-key! ::pool
  [_ pool]
  (when pool
    (.close ^HikariDataSource pool)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API & Impl
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def initsql
  (str "SET statement_timeout = 300000;\n"
       "SET idle_in_transaction_session_timeout = 300000;"))

(defn- create-datasource-config
  [{:keys [::uri] :as cfg}]

  ;; (app.common.pprint/pprint cfg)
  (let [config (HikariConfig.)]
    (doto config
      (.setJdbcUrl           (str "jdbc:" uri))
      (.setPoolName          (d/name (::name cfg)))
      (.setAutoCommit true)
      (.setReadOnly          (::read-only cfg))
      (.setConnectionTimeout (::connection-timeout cfg))
      (.setValidationTimeout (::validation-timeout cfg))
      (.setIdleTimeout       (::idle-timeout cfg))
      (.setMaxLifetime       (::max-lifetime cfg))
      (.setMinimumIdle       (::min-size cfg))
      (.setMaximumPoolSize   (::max-size cfg))
      (.setConnectionInitSql initsql)
      (.setInitializationFailTimeout -1))

    ;; When metrics namespace is provided
    (when-let [instance (::mtx/metrics cfg)]
      (->> (mtx/get-registry instance)
           (PrometheusMetricsTrackerFactory.)
           (.setMetricsTrackerFactory config)))

    (some->> ^String (::username cfg) (.setUsername config))
    (some->> ^String (::password cfg) (.setPassword config))

    config))

(defn pool?
  [v]
  (instance? javax.sql.DataSource v))

(defn connection?
  [conn]
  (instance? Connection conn))

(defn connectable?
  [o]
  (or (connection? o)
      (pool? o)))

(sm/register!
 {:type ::conn
  :pred connection?})

(sm/register!
 {:type ::connectable
  :pred connectable?})

(sm/register!
 {:type ::pool
  :pred pool?})

(defn closed?
  [pool]
  (.isClosed ^HikariDataSource pool))

(defn read-only?
  [pool-or-conn]
  (cond
    (instance? HikariDataSource pool-or-conn)
    (.isReadOnly ^HikariDataSource pool-or-conn)

    (instance? Connection pool-or-conn)
    (.isReadOnly ^Connection pool-or-conn)

    :else
    (ex/raise :type :internal
              :code :invalid-connection
              :hint "invalid connection provided")))

(defn create-pool
  [cfg]
  (let [dsc (create-datasource-config cfg)]
    (jdbc-dt/read-as-instant)
    (HikariDataSource. dsc)))

(defn unwrap
  [conn klass]
  (.unwrap ^Connection conn klass))

(defn lobj-manager
  [conn]
  (let [conn (unwrap conn org.postgresql.PGConnection)]
    (.getLargeObjectAPI ^PGConnection conn)))

(defn lobj-create
  [manager]
  (.createLO ^LargeObjectManager manager LargeObjectManager/READWRITE))

(defn lobj-open
  ([manager oid]
   (lobj-open manager oid {}))
  ([manager oid {:keys [mode] :or {mode :rw}}]
   (let [mode (case mode
                (:r :read) LargeObjectManager/READ
                (:w :write) LargeObjectManager/WRITE
                (:rw :read+write) LargeObjectManager/READWRITE)]
     (.open ^LargeObjectManager manager (long oid) mode))))

(defn lobj-unlink
  [manager oid]
  (.unlink ^LargeObjectManager manager (long oid)))

(extend-type LargeObject
  io/IOFactory
  (make-reader [lobj opts]
    (let [^InputStream is (.getInputStream ^LargeObject lobj)]
      (io/make-reader is opts)))
  (make-writer [lobj opts]
    (let [^OutputStream os (.getOutputStream ^LargeObject lobj)]
      (io/make-writer os opts)))
  (make-input-stream [lobj opts]
    (let [^InputStream is (.getInputStream ^LargeObject lobj)]
      (io/make-input-stream is opts)))
  (make-output-stream [lobj opts]
    (let [^OutputStream os (.getOutputStream ^LargeObject lobj)]
      (io/make-output-stream os opts))))

(defn open
  [system-or-pool]
  (if (pool? system-or-pool)
    (jdbc/get-connection system-or-pool)
    (if (map? system-or-pool)
      (open (::pool system-or-pool))
      (throw (IllegalArgumentException. "unable to resolve connection pool")))))

(defn get-update-count
  [result]
  (:next.jdbc/update-count result))

(defn get-connection
  [cfg-or-conn]
  (if (connection? cfg-or-conn)
    cfg-or-conn
    (if (map? cfg-or-conn)
      (get-connection (::conn cfg-or-conn))
      (throw (IllegalArgumentException. "unable to resolve connection")))))

(defn connection-map?
  "Check if the provided value is a map like data structure that
  contains a database connection."
  [o]
  (and (map? o) (connection? (::conn o))))

(defn get-connectable
  "Resolve to a connection or connection pool instance; if it is not
  possible, raises an exception"
  [o]
  (cond
    (connection? o) o
    (pool? o)       o
    (map? o)        (get-connectable (or (::conn o) (::pool o)))
    :else           (throw (IllegalArgumentException. "unable to resolve connectable"))))

(def ^:private params-mapping
  {::return-keys :return-keys})

(defn rename-opts
  [opts]
  (set/rename-keys opts params-mapping))

(def ^:private default-insert-opts
  (assoc sql/default-opts :return-keys true))

(def ^:private default-opts
  sql/default-opts)

(defn exec!
  "执行原始 SQL 语句。
   
   【参数】
   ds - 数据库连接或连接池
   sv - SQL 向量或字符串
   opts - 可选的执行选项
   
   【返回值】
   执行结果（通常是结果集）"
  ([ds sv] (exec! ds sv nil))
  ([ds sv opts]
   (let [conn (get-connectable ds)
         opts (if (empty? opts)
                default-opts
                (into default-opts (rename-opts opts)))]
      (jdbc/execute! conn sv opts))))

(defn exec-one!
  "执行原始 SQL 语句并返回单行结果。
   
   【参数】
   ds - 数据库连接或连接池
   sv - SQL 向量或字符串
   opts - 可选的执行选项
   
   【返回值】
   单行结果，如果没有结果则返回 nil"
  ([ds sv] (exec-one! ds sv nil))
  ([ds sv opts]
   (let [conn (get-connectable ds)
         opts (if (empty? opts)
                default-opts
                (into default-opts (rename-opts opts)))]
      (jdbc/execute-one! conn sv opts))))

(defn insert!
  "插入数据到数据库表。
   
   【功能】
   构建 INSERT SQL 语句并执行，默认返回插入行的所有字段。
   可以使用 `::sql/columns` 选项指定返回的列。
   
   【参数】
   ds - 数据库连接或连接池
   table - 表名
   params - 要插入的字段映射
   opts - 可选选项
   
   【返回值】
   插入的行数据（包含所有或指定的列）"
  [ds table params & {:as opts}]
  (let [conn (get-connectable ds)
        sql  (sql/insert table params opts)
        opts (if (empty? opts)
               default-insert-opts
               (into default-insert-opts (rename-opts opts)))]
    (jdbc/execute-one! conn sql opts)))

(defn insert-many!
  "批量插入多条记录。
   
   【功能】
   优化的批量插入实现，将多条记录合并为单个 SQL 语句。
   对于大数据集，可能超出 SQL 字符串大小或参数数量的限制。
   
   【参数】
   ds - 数据库连接或连接池
   table - 表名
   cols - 列名向量
   rows - 要插入的行向量（每行是一个映射）
   opts - 可选选项
   
   【返回值】
   插入结果"
  [ds table cols rows & {:as opts}]
  (let [conn (get-connectable ds)
        sql  (sql/insert-many table cols rows opts)
        opts (if (empty? opts)
               default-insert-opts
               (into default-insert-opts (rename-opts opts)))
        opts (update opts :return-keys boolean)]
    (jdbc/execute! conn sql opts)))

(defn update!
  "更新数据库表中的记录。
   
   【功能】
   构建 UPDATE SQL 语句并执行。
   
   【参数】
   ds - 数据库连接或连接池
   table - 表名
   params - 要更新的字段映射
   where - WHERE 条件（字段映射或 [sql-string & params] 向量）
   opts - 可选选项（如 ::return-keys, ::many）
   
   【返回值】
   默认返回受影响的行数；设置 ::return-keys 可返回完整行数据"
  [ds table params where & {:as opts}]
  (let [conn (get-connectable ds)
        sql  (sql/update table params where opts)
        opts (if (empty? opts)
               default-opts
               (into default-opts (rename-opts opts)))
        opts (update opts :return-keys boolean)]
    (if (::many opts)
      (jdbc/execute! conn sql opts)
      (jdbc/execute-one! conn sql opts))))

(defn delete!
  "从数据库表中删除记录。
   
   【功能】
   构建 DELETE SQL 语句并执行。
   
   【参数】
   ds - 数据库连接或连接池
   table - 表名
   params - WHERE 条件字段映射
   opts - 可选选项（如 ::return-keys, ::many）
   
   【返回值】
   默认返回受影响的行数；设置 ::return-keys 可返回完整行数据"
  [ds table params & {:as opts}]
  (let [conn (get-connectable ds)
        sql  (sql/delete table params opts)
        opts (if (empty? opts)
               default-opts
               (into default-opts (rename-opts opts)))]
    (if (::many opts)
      (jdbc/execute! conn sql opts)
      (jdbc/execute-one! conn sql opts))))

(defn query
  "查询数据库表并返回多行结果。
   
   【参数】
   ds - 数据库连接或连接池
   table - 表名
   params - 查询参数（字段映射）
   opts - 可选选项
   
   【返回值】
   查询结果向量"
  [ds table params & {:as opts}]
  (exec! ds (sql/select table params opts) opts))

(defn is-row-deleted?
  "检查行是否被标记为已删除。
   
   【参数】
   row - 数据库行映射
   
   【返回值】
   如果 deleted-at 字段存在且非 nil 返回 true"
  [{:keys [deleted-at]}]
  (some? deleted-at))

(defn get*
  "查询单行数据（不抛出异常）。
   
   【功能】
   根据简单条件查询单行数据，如果找到多条也只返回第一条。
   默认过滤已删除的行（deleted-at 非 nil）。
   
   【参数】
   ds - 数据库连接或连接池
   table - 表名
   params - 查询条件（字段映射）
   opts - 可选选项（如 ::remove-deleted 禁用删除过滤）
   
   【返回值】
   找到的第一行数据，未找到返回 nil"
  [ds table params & {:as opts}]
  (let [rows (exec! ds (sql/select table params opts))
        rows (cond->> rows
               (::remove-deleted opts true)
               (remove is-row-deleted?))]
    (first rows)))

(defn get
  "查询单行数据（未找到时抛出异常）。
   
   【功能】
   根据条件查询单行数据，如果未找到则抛出 :not-found 异常。
   
   【参数】
   ds - 数据库连接或连接池
   table - 表名
   params - 查询条件（字段映射）
   opts - 可选选项（如 ::check-deleted 禁用删除检查）
   
   【返回值】
   找到的行数据，未找到则抛出异常"
  [ds table params & {:as opts}]
  (let [row (get* ds table params opts)]
    (when (and (not row) (::check-deleted opts true))
      (ex/raise :type :not-found
                :code :object-not-found
                :table table
                :params params
                :hint "database object not found"))
    row))

(defn get-with-sql
  "使用自定义 SQL 查询单行数据。
   
   【参数】
   ds - 数据库连接或连接池
   sql - SQL 向量
   opts - 可选选项
   
   【返回值】
   找到的第一行数据，未找到且启用检查时抛出异常"
  [ds sql & {:as opts}]
  (let [rows
        (cond->> (exec! ds sql opts)
          (::remove-deleted opts true)
          (remove is-row-deleted?)

          :always
          (not-empty))

    (when (and (not rows) (::throw-if-not-exists opts true))
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "database object not found"))

    (first rows)))

(defn get
  "Retrieve a single row from database that matches a simple
  filters. Raises :not-found exception if no object is found."
  [ds table params & {:as opts}]
  (let [row (get* ds table params opts)]
    (when (and (not row) (::check-deleted opts true))
      (ex/raise :type :not-found
                :code :object-not-found
                :table table
                :params params
                :hint "database object not found"))
    row))

(defn get-with-sql
  [ds sql & {:as opts}]
  (let [rows
        (cond->> (exec! ds sql opts)
          (::remove-deleted opts true)
          (remove is-row-deleted?)

          :always
          (not-empty))]

    (when (and (not rows) (::throw-if-not-exists opts true))
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "database object not found"))

    (first rows)))

(def ^:private default-plan-opts
  (-> default-opts
      (assoc :fetch-size 1000)
      (assoc :concurrency :read-only)
      (assoc :cursors :close)
      (assoc :result-type :forward-only)))

(defn plan
  ([ds sql]
   (-> (get-connectable ds)
       (jdbc/plan sql default-plan-opts)))
  ([ds sql opts]
   (-> (get-connectable ds)
       (jdbc/plan sql (merge default-plan-opts opts)))))

(defn cursor
  "Return a lazy seq of rows using server side cursors"
  [conn query & {:keys [chunk-size] :or {chunk-size 25}}]
  (let [cname  (str (gensym "cursor_"))
        fquery [(str "FETCH " chunk-size " FROM " cname)]]

    ;; declare cursor
    (exec-one! conn
               (if (vector? query)
                 (into [(str "DECLARE " cname " CURSOR FOR " (nth query 0))]
                       (rest query))
                 [(str "DECLARE " cname " CURSOR FOR " query)]))

    ;; return a lazy seq
    ((fn fetch-more []
       (lazy-seq
        (when-let [chunk (seq (exec! conn fquery))]
          (concat chunk (fetch-more))))))))

(defn get-by-id
  [ds table id & {:as opts}]
  (get ds table {:id id} opts))

(defn pgobject?
  ([v]
   (instance? PGobject v))
  ([v type]
   (and (instance? PGobject v)
        (= type (.getType ^PGobject v)))))

(defn pginterval?
  [v]
  (instance? PGInterval v))

(defn pgpoint?
  [v]
  (instance? PGpoint v))

(defn pgarray?
  ([v] (instance? PgArray v))
  ([v type]
   (and (instance? PgArray v)
        (= type (.getBaseTypeName ^PgArray v)))))

(defn pgarray-of-uuid?
  [v]
  (and (pgarray? v) (= "uuid" (.getBaseTypeName ^PgArray v))))

;; TODO rename to decode-pgarray-into
(defn decode-pgarray
  ([v] (decode-pgarray v []))
  ([v in]
   (into in (some-> ^PgArray v .getArray)))
  ([v in xf]
   (into in xf (some-> ^PgArray v .getArray))))

(defn pgarray->set
  [v]
  (set (.getArray ^PgArray v)))

(defn pgarray->vector
  [v]
  (vec (.getArray ^PgArray v)))

(defn pgpoint
  [p]
  (PGpoint. (:x p) (:y p)))

(defn create-array
  [conn type objects]
  (let [^PGConnection conn (unwrap conn org.postgresql.PGConnection)]
    (if (coll? objects)
      (.createArrayOf conn ^String type (into-array Object objects))
      (.createArrayOf conn ^String type objects))))

(defn encode-pgarray
  [data conn type]
  (create-array conn type data))

(defn decode-pgpoint
  [^PGpoint v]
  (gpt/point (.-x v) (.-y v)))

(defn pginterval
  [data]
  (org.postgresql.util.PGInterval. ^String data))

(defn savepoint
  ([^Connection conn]
   (.setSavepoint conn))
  ([^Connection conn label]
   (.setSavepoint conn (name label))))

(defn release!
  [^Connection conn ^Savepoint sp]
  (.releaseSavepoint conn sp))

(defn rollback!
  ([conn]
   (if (and (map? conn) (::savepoint conn))
     (rollback! conn (::savepoint conn))
     (let [^Connection conn (get-connection conn)]
       (l/trc :hint "explicit rollback requested")
       (.rollback conn))))
  ([conn ^Savepoint sp]
   (let [^Connection conn (get-connection conn)]
     (l/trc :hint "explicit rollback requested (savepoint)")
     (.rollback conn sp))))

(defn transact!
  "执行事务的低级函数。
   
   【功能】
   在事务中执行提供的函数。
   
   【参数】
   transactable - 数据库连接或连接池
   f - 要在事务中执行的函数
   opts - 事务选项
   
   【返回值】
   函数的返回值"
  ([transactable f] (transact! transactable f {}))
  ([transactable f opts]
   (binding [next.jdbc.transaction/*nested-tx* :ignore]
     (jdbc/transact transactable f opts))))

(defn tx-run!
  "在事务中运行函数。
   
   【功能】
   1. 解析系统配置获取连接
   2. 开始数据库事务
   3. 执行提供的函数
   4. 根据函数结果自动提交或回滚
   
   【参数】
   system - 系统配置映射（包含 ::conn 或 ::pool）
   f - 要执行的函数
   params - 传递给函数的额外参数
   
   【返回值】
   函数的返回值"
  [system f & params]
  (if (connection? system)
    (tx-run! {::conn system} f)
    (if (pool? system)
      (tx-run! {::pool system} f)
      (if-let [conn (or (::conn system)
                        (::pool system))]
        (transact! conn
                   (fn [conn]
                     (let [system' (-> system
                                       (dissoc ::rollback)
                                       (assoc ::conn conn))]
                       (apply f system' params)))
                   {:rollback-only (::rollback system)
                    :read-only (::read-only system)})
        (throw (IllegalArgumentException. "invalid system/cfg provided"))))))

(defn run!
  "使用连接或连接池运行函数。
   
   【功能】
   1. 如果是连接，直接执行函数
   2. 如果是连接池，从池中获取连接后执行
   3. 自动管理连接的打开和关闭
   
   【参数】
   system - 连接、连接池或系统配置映射
   f - 要执行的函数
   params - 传递给函数的参数
   
   【返回值】
   函数的返回值"
  [system f & params]
  (cond
    (connection? system)
    (apply run! {::conn system} f params)

    (pool? system)
    (apply run! {::pool system} f params)

    (::conn system)
    (apply f system params)

    (::pool system)
    (with-open [^Connection conn (open (::pool system))]
      (apply f (assoc system ::conn conn) params))

    :else
    (throw (IllegalArgumentException. "invalid arguments"))))

(defn interval
  [o]
  (cond
    (or (integer? o)
        (float? o))
    (->> (/ o 1000.0)
         (format "%s seconds")
         (pginterval))

    (string? o)
    (pginterval o)

    (ct/duration? o)
    (interval (inst-ms o))

    :else
    (ex/raise :type :not-implemented
              :hint (format "no implementation found for value %s" (pr-str o)))))

(defn decode-json-pgobject
  [^PGobject o]
  (when o
    (let [typ (.getType o)
          val (.getValue o)]
      (if (or (= typ "json")
              (= typ "jsonb"))
        (json/decode val :key-fn keyword)
        val))))

(defn decode-transit-pgobject
  [^PGobject o]
  (when o
    (let [typ (.getType o)
          val (.getValue o)]
      (if (or (= typ "json")
              (= typ "jsonb"))
        (t/decode-str val)
        val))))

(defn inet
  [ip-addr]
  (when ip-addr
    (doto (org.postgresql.util.PGobject.)
      (.setType "inet")
      (.setValue (str ip-addr)))))

(defn decode-inet
  [^PGobject o]
  (when o
    (if (= "inet" (.getType o))
      (.getValue o)
      nil)))

(defn tjson
  "编码为 Transit JSON 格式。
   
   【功能】
   将数据编码为 Transit JSON 格式的 PGobject，用于存储到 jsonb 列。
   
   【参数】
   data - 要编码的数据
   
   【返回值】
   设置好的 PGobject 实例"
  [data]
  (when data
    (doto (org.postgresql.util.PGobject.)
      (.setType "jsonb")
      (.setValue (t/encode-str data {:type :json-verbose})))))

(defn json
  "编码为普通 JSON 格式。
   
   【功能】
   将数据编码为普通 JSON 格式的 PGobject，用于存储到 jsonb 列。
   
   【参数】
   data - 要编码的数据
   
   【返回值】
   设置好的 PGobject 实例"
  [data]
  (when data
    (doto (org.postgresql.util.PGobject.)
      (.setType "jsonb")
      (.setValue (json/encode data)))))

;; --- Locks

(def ^:private siphash-state
  "SipHash 状态容器，用于 UUID 到哈希码的转换。"
  (SipHasher/container
   (uuid/get-bytes uuid/zero)))

(defn uuid->hash-code
  "将 UUID 转换为哈希码。
   
   【功能】
   使用 SipHash 算法将 UUID 转换为哈希码，用于数据库 Advisory Lock。
   
   【参数】
   o - UUID 或整数
   
   【返回值】
   哈希码（长整数）"
  [o]
  (.hash ^SipHasherContainer siphash-state
         ^bytes (uuid/get-bytes o)))

(defn- xact-check-param
  [n]
  (cond
    (uuid? n) (uuid->hash-code n)
    (int? n)  n
    :else (throw (IllegalArgumentException. "uuid or number allowed"))))

(defn xact-lock!
  "获取事务级 Advisory Lock（阻塞）。
   
   【功能】
   获取指定键的事务级排他 Advisory Lock。如果锁已被占用，则阻塞等待。
   
   【参数】
   conn - 数据库连接
   n - 锁键（UUID 或整数）
   
   【返回值】
   始终返回 true"
  [conn n]
  (let [n (xact-check-param n)]
    (exec-one! conn ["select pg_advisory_xact_lock(?::bigint) as lock" n])
    true))

(defn xact-try-lock!
  "尝试获取事务级 Advisory Lock（非阻塞）。
   
   【功能】
   尝试获取指定键的事务级排他 Advisory Lock。如果锁已被占用，立即返回 false。
   
   【参数】
   conn - 数据库连接
   n - 锁键（UUID 或整数）
   
   【返回值】
   成功获取锁返回 true，否则返回 false"
  [conn n]
  (let [n   (xact-check-param n)
        row (exec-one! conn ["select pg_try_advisory_xact_lock(?::bigint) as lock" n])]
    (:lock row)))

(defn sql-exception?
  "检查异常是否是 SQL 异常。
   
   【参数】
   cause - 要检查的异常
   
   【返回值】
   是 SQL 异常返回 true"
  [cause]
  (instance? java.sql.SQLException cause))

(defn connection-error?
  "检查异常是否是数据库连接错误。
   
   【功能】
   检查 SQL 异常的状态码是否属于连接错误类型。
   
   【参数】
   cause - 要检查的异常
   
   【返回值】
   是连接错误返回 true"
  [cause]
  (and (sql-exception? cause)
       (contains? #{"08003" "08006" "08001" "08004"}
                  (.getSQLState ^java.sql.SQLException cause))))

(defn serialization-error?
  [cause]
  (and (sql-exception? cause)
       (= "40001" (.getSQLState ^java.sql.SQLException cause))))

(defn duplicate-key-error?
  [cause]
  (and (sql-exception? cause)
       (= "23505" (.getSQLState ^java.sql.SQLException cause))))


(extend-protocol jdbc.prepare/SettableParameter
  clojure.lang.Keyword
  (set-parameter [^clojure.lang.Keyword v ^PreparedStatement s ^long i]
    (.setObject s i ^String (d/name v))))
