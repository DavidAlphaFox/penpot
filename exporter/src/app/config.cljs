;; =============================================================================
;; 配置模块 (Configuration Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块负责管理和解析导出服务的所有配置。
;; 从环境变量和默认配置中读取配置，并进行验证和转换。
;;
;; 【核心概念】
;; 1. 环境变量解析 - 从 PENPOT_* 前缀的环境变量中读取配置
;; 2. 配置验证 - 使用 schema 验证配置的有效性
;; 3. 配置合并 - 将环境变量与默认配置合并
;; 4. 管理密钥 - 派生用于服务间通信的管理密钥
;;
;; 【依赖关系】
;; - app.common.data - 数据操作工具
;; - app.common.flags - 功能标志解析
;; - app.common.logging - 日志工具
;; - app.common.schema - Schema 验证工具
;; - app.common.version - 版本解析工具
;;
;; =============================================================================

(ns app.config
  (:refer-clojure :exclude [get])
  (:require
   ["node:buffer" :as buffer]
   ["node:crypto" :as crypto]
   ["node:process" :as process]
   [app.common.data :as d]
   [app.common.flags :as flags]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.version :as v]
   [cljs.core :as c]
   [cuerdas.core :as str]))

(l/set-level! :info)

(def ^:private defaults
  {:public-uri "http://localhost:3449"
   :tenant "default"
   :host "localhost"
   :http-server-port 6061
   :http-server-host "0.0.0.0"
   :tempdir "/tmp/penpot"
   :redis-uri "redis://redis/0"})

(def ^:private schema:config
  [:map {:title "config"}
   [:secret-key :string]
   [:public-uri {:optional true} ::sm/uri]
   [:exporter-shared-key {:optional true} :string]
   [:host {:optional true} :string]
   [:tenant {:optional true} :string]
   [:flags {:optional true} [::sm/set :keyword]]
   [:redis-uri {:optional true} :string]
   [:tempdir {:optional true} :string]
   [:browser-pool-max {:optional true} ::sm/int]
   [:browser-pool-min {:optional true} ::sm/int]])

(def ^:private decode-config
  (sm/decoder schema:config sm/string-transformer))

(def ^:private explain-config
  (sm/explainer schema:config))

(def ^:private valid-config?
  (sm/validator schema:config))

(defn- parse-flags
  "解析配置中的功能标志。
   
   【参数】
   config - 配置对象，包含 :flags 键
   
   【返回值】
   解析后的功能标志集合。"
  [config]
  (flags/parse (:flags config)))

(defn- read-env
  "读取以指定前缀开头的环境变量。
   
   【参数】
   prefix - 环境变量前缀（例如 \"penpot\" 会匹配 PENPOT_*）
   
   【返回值】
   包含所有匹配环境变量的映射，键为 kebab-case 格式的关键词。
   
   【功能说明】
   将环境变量名从 SCREAMING_SNAKE_CASE 转换为 kebab-case 关键词。
   例如：PENPOT_PUBLIC_URI -> :public-uri"
  [prefix]
  (let [env    (unchecked-get process "env")
        kwd    (fn [s] (-> (str/kebab s) (str/keyword)))
        prefix (str prefix "_")
        len    (count prefix)]
    (reduce (fn [res key]
              (let [val (unchecked-get env key)
                    key (str/lower key)]
                (cond-> res
                  (str/starts-with? key prefix)
                  (assoc (kwd (subs key len)) val))))
            {}
            (js/Object.keys env))))

(defn- prepare-config
  "准备和验证配置。
   
   【参数】
   无
   
   【返回值】
   经过验证的最终配置对象。
   
   【功能说明】
   1. 读取所有 PENPOT_* 环境变量
   2. 移除空值
   3. 与默认配置合并
   4. 验证配置有效性
   5. 如果配置无效，输出错误信息并退出进程"
  []
  (let [env  (read-env "penpot")
        env  (d/without-nils env)
        data (merge defaults env)
        data (decode-config data)]

    (when-not (valid-config? data)
      (let [explain (explain-config data)]
        (println (sm/humanize-explain explain))
        (process/exit -1)))

    data))

(def config
  "最终配置对象。
   
   【类型】
   配置映射，包含所有已验证的配置项。"
  (prepare-config))

(def version
  "解析后的版本信息。
   
   【类型】
   版本对象，包含版本详细信息。"
  (v/parse "%version%"))

(def flags
  "解析后的功能标志集合。
   
   【类型】
   标志集合，用于控制功能开关。"
  (parse-flags config))

(defn get
  "获取配置项的值。
   
   【参数】
   key - 配置项的关键词
   default - （可选）默认值，当配置项不存在时返回
   
   【返回值】
   配置项的值，如果不存在则返回默认值。"
  ([key]
   (c/get config key))
  ([key default]
   (c/get config key default)))

(def management-key
  "用于服务间通信的管理密钥。
   
   【类型】
   字符串，Base64URL 编码的密钥。
   
   【功能说明】
   如果配置中提供了 exporter-shared-key，则直接使用；
   否则从 secret-key 派生出一个管理密钥。"
  (let [key (or (c/get config :exporter-shared-key)
                (let [secret-key  (c/get config :secret-key)
                      derived-key (crypto/hkdfSync "blake2b512" secret-key, "exporter" "" 32)]
                  (-> (.from buffer/Buffer derived-key)
                      (.toString "base64url"))))]
    (l/inf :hint "exporter key initialized" :key (d/obfuscate-string key))
    key))
