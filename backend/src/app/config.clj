;; =============================================================================
;; 配置管理 (Configuration Management)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块负责管理和验证应用程序的所有配置选项。配置从环境变量中读取，
;; 并使用 Malli  schema 进行验证。提供配置获取接口，支持默认值和动态配置更新。
;;
;; 【核心概念】
;; 1. 环境变量前缀 - 配置使用 PENPOT_ 前缀，环境变量自动转换
;; 2. Malli Schema - 使用 Malli 进行配置验证和类型转换
;; 3. Feature Flags - 功能标志，用于动态启用/禁用功能
;; 4. 配置默认值 - 提供合理的默认值配置
;;
;; 【依赖关系】
;; - environ.core - 环境变量读取
;; - app.common.flags - 功能标志管理
;; - app.common.schema - Malli schema 工具
;; - integrant.core - Integrant 框架集成
;;
;; =============================================================================

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.config
  (:refer-clojure :exclude [get])
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.flags :as flags]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uri :as u]
   [app.common.version :as v]
   [app.util.overrides]
   [clojure.core :as c]
   [clojure.java.io :as io]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [environ.core :refer [env]]
   [integrant.core :as ig]))

(defmethod ig/init-key :default
  [_ data]
  (d/without-nils data))

(defmethod ig/expand-key :default
  [k v]
  {k (if (map? v)
       (d/without-nils v)
       v)})

(def default
  {:database-uri "postgresql://postgres/penpot"
   :database-username "penpot"
   :database-password "penpot"

   :default-blob-version 4

   :rpc-rlimit-config "resources/rlimit.edn"
   :rpc-climit-config "resources/climit.edn"

   :auto-file-snapshot-every 5
   :auto-file-snapshot-timeout "3h"

   :public-uri "http://localhost:3449"

   :host "localhost"
   :tenant "default"

   :redis-uri "redis://redis/0"

   :file-data-backend "legacy-db"

   :objects-storage-backend "fs"
   :objects-storage-fs-directory "assets"

   :auth-token-cookie-name "auth-token"

   :assets-path "/internal/assets/"
   :smtp-default-reply-to "Penpot <no-reply@example.com>"
   :smtp-default-from "Penpot <no-reply@example.com>"

   :profile-complaint-max-age (ct/duration {:days 7})
   :profile-complaint-threshold 2

   :profile-bounce-max-age (ct/duration {:days 7})
   :profile-bounce-threshold 10

   :telemetry-uri "https://telemetry.penpot.app/"

   :media-max-file-size (* 1024 1024 30) ; 30MiB

   :ldap-user-query "(|(uid=:username)(mail=:username))"
   :ldap-attrs-username "uid"
   :ldap-attrs-email "mail"
   :ldap-attrs-fullname "cn"

   ;; a server prop key where initial project is stored.
   :initial-project-skey "initial-project"

   ;; time to avoid email sending after profile modification
   :email-verify-threshold "15m"})

(def schema:config
  (do #_sm/optional-keys
   [:map {:title "config"}
    [:flags {:optional true} [::sm/set :string]]
    [:admins {:optional true} [::sm/set ::sm/email]]
    [:secret-key {:optional true} :string]

    [:tenant {:optional false} :string]
    [:public-uri {:optional false} ::sm/uri]
    [:host {:optional false} :string]

    [:http-server-port {:optional true} ::sm/int]
    [:http-server-host {:optional true} :string]
    [:http-server-max-body-size {:optional true} ::sm/int]
    [:http-server-io-threads {:optional true} ::sm/int]
    [:http-server-max-worker-threads {:optional true} ::sm/int]

    [:exporter-shared-key {:optional true} :string]
    [:nitrate-shared-key {:optional true} :string]
    [:nexus-shared-key {:optional true} :string]
    [:management-api-key {:optional true} :string]

    [:telemetry-uri {:optional true} :string]
    [:telemetry-with-taiga {:optional true} ::sm/boolean] ;; DELETE

    [:auto-file-snapshot-every {:optional true} ::sm/int]
    [:auto-file-snapshot-timeout {:optional true} ::ct/duration]

    [:media-max-file-size {:optional true} ::sm/int]
    [:deletion-delay {:optional true} ::ct/duration]
    [:file-clean-delay {:optional true} ::ct/duration]
    [:telemetry-enabled {:optional true} ::sm/boolean]
    [:default-blob-version {:optional true} ::sm/int]
    [:allow-demo-users {:optional true} ::sm/boolean]
    [:error-report-webhook {:optional true} :string]
    [:user-feedback-destination {:optional true} :string]

    [:default-rpc-rlimit {:optional true} [::sm/vec :string]]
    [:rpc-rlimit-config {:optional true} ::fs/path]
    [:rpc-climit-config {:optional true} ::fs/path]

    [:audit-log-archive-uri {:optional true} :string]
    [:audit-log-http-handler-concurrency {:optional true} ::sm/int]

    [:default-executor-parallelism {:optional true} ::sm/int] ;; REVIEW
    [:scheduled-executor-parallelism {:optional true} ::sm/int] ;; REVIEW
    [:worker-default-parallelism {:optional true} ::sm/int]
    [:worker-webhook-parallelism {:optional true} ::sm/int]

    [:database-password {:optional true} [:maybe :string]]
    [:database-uri {:optional true} ::sm/uri]
    [:database-username {:optional true} [:maybe :string]]
    [:database-readonly {:optional true} ::sm/boolean]
    [:database-min-pool-size {:optional true} ::sm/int]
    [:database-max-pool-size {:optional true} ::sm/int]

    [:quotes-teams-per-profile {:optional true} ::sm/int]
    [:quotes-access-tokens-per-profile {:optional true} ::sm/int]
    [:quotes-projects-per-team {:optional true} ::sm/int]
    [:quotes-invitations-per-team {:optional true} ::sm/int]
    [:quotes-profiles-per-team {:optional true} ::sm/int]
    [:quotes-files-per-project {:optional true} ::sm/int]
    [:quotes-files-per-team {:optional true} ::sm/int]
    [:quotes-font-variants-per-team {:optional true} ::sm/int]
    [:quotes-comment-threads-per-file {:optional true} ::sm/int]
    [:quotes-comments-per-file {:optional true} ::sm/int]
    [:quotes-snapshots-per-file {:optional true} ::sm/int]
    [:quotes-snapshots-per-team {:optional true} ::sm/int]
    [:quotes-team-access-requests-per-team {:optional true} ::sm/int]
    [:quotes-team-access-requests-per-requester {:optional true} ::sm/int]

    [:auth-token-cookie-name {:optional true} :string]
    [:auth-token-cookie-max-age {:optional true} ::ct/duration]

    [:registration-domain-whitelist {:optional true} [::sm/set :string]]
    [:email-verify-threshold {:optional true} ::ct/duration]

    [:github-client-id {:optional true} :string]
    [:github-client-secret {:optional true} :string]
    [:gitlab-base-uri {:optional true} :string]
    [:gitlab-client-id {:optional true} :string]
    [:gitlab-client-secret {:optional true} :string]
    [:google-client-id {:optional true} :string]
    [:google-client-secret {:optional true} :string]
    [:oidc-client-id {:optional true} :string]
    [:oidc-user-info-source {:optional true} [:enum "auto" "userinfo" "token"]]
    [:oidc-client-secret {:optional true} :string]
    [:oidc-base-uri {:optional true} :string]
    [:oidc-token-uri {:optional true} :string]
    [:oidc-auth-uri {:optional true} :string]
    [:oidc-user-uri {:optional true} :string]
    [:oidc-jwks-uri {:optional true} :string]
    [:oidc-scopes {:optional true} [::sm/set :string]]
    [:oidc-roles {:optional true} [::sm/set :string]]
    [:oidc-roles-attr {:optional true} :string]
    [:oidc-email-attr {:optional true} :string]
    [:oidc-name-attr {:optional true} :string]

    [:ldap-attrs-email {:optional true} :string]
    [:ldap-attrs-fullname {:optional true} :string]
    [:ldap-attrs-username {:optional true} :string]
    [:ldap-base-dn {:optional true} :string]
    [:ldap-bind-dn {:optional true} :string]
    [:ldap-bind-password {:optional true} :string]
    [:ldap-host {:optional true} :string]
    [:ldap-port {:optional true} ::sm/int]
    [:ldap-ssl {:optional true} ::sm/boolean]
    [:ldap-starttls {:optional true} ::sm/boolean]
    [:ldap-user-query {:optional true} :string]

    [:profile-bounce-max-age {:optional true} ::ct/duration]
    [:profile-bounce-threshold {:optional true} ::sm/int]
    [:profile-complaint-max-age {:optional true} ::ct/duration]
    [:profile-complaint-threshold {:optional true} ::sm/int]

    [:redis-uri {:optional true} ::sm/uri]

    [:email-domain-blacklist {:optional true} ::fs/path]
    [:email-domain-whitelist {:optional true} ::fs/path]

    [:smtp-default-from {:optional true} :string]
    [:smtp-default-reply-to {:optional true} :string]
    [:smtp-host {:optional true} :string]
    [:smtp-password {:optional true} [:maybe :string]]
    [:smtp-port {:optional true} ::sm/int]
    [:smtp-ssl {:optional true} ::sm/boolean]
    [:smtp-tls {:optional true} ::sm/boolean]
    [:smtp-username {:optional true} [:maybe :string]]

    [:urepl-host {:optional true} :string]
    [:urepl-port {:optional true} ::sm/int]
    [:prepl-host {:optional true} :string]
    [:prepl-port {:optional true} ::sm/int]

    [:file-data-backend {:optional true} [:enum "db" "legacy-db" "storage"]]

    [:media-directory {:optional true} :string] ;; REVIEW
    [:media-uri {:optional true} :string]
    [:assets-path {:optional true} :string]

    [:netty-io-threads {:optional true} ::sm/int]
    [:executor-threads {:optional true} ::sm/int]

    [:nitrate-backend-uri {:optional true} ::sm/uri]

    ;; DEPRECATED
    [:assets-storage-backend {:optional true} :keyword]
    [:storage-assets-fs-directory {:optional true} :string]
    [:storage-assets-s3-bucket {:optional true} :string]
    [:storage-assets-s3-region {:optional true} :keyword]
    [:storage-assets-s3-endpoint {:optional true} ::sm/uri]

    [:objects-storage-backend {:optional true} :keyword]
    [:objects-storage-fs-directory {:optional true} :string]
    [:objects-storage-s3-bucket {:optional true} :string]
    [:objects-storage-s3-region {:optional true} :keyword]
    [:objects-storage-s3-endpoint {:optional true} ::sm/uri]]))

(defn- parse-flags
  "解析配置中的功能标志。
   
   【参数】
   config - 配置映射，包含 :flags 和 :public-uri 字段
   
   【返回值】
   返回解析后的功能标志集合。如果 public-uri 不是 localhost 的 http 地址，
   会自动添加 :disable-secure-session-cookies 标志。"
  [config]
  (let [public-uri  (c/get config :public-uri)
        public-uri  (some-> public-uri (u/uri))
        extra-flags (if (and public-uri
                             (= (:scheme public-uri) "http")
                             (not= (:host public-uri) "localhost"))
                      #{:disable-secure-session-cookies}
                      #{})]
    (flags/parse flags/default extra-flags (:flags config))))

(defn read-env
  "从环境变量中读取配置。
   
   【参数】
   prefix - 环境变量前缀，如 \"penpot\" 会读取 PENPOT_* 开头的变量
   
   【返回值】
   返回一个映射，将去掉前缀的键名（keyword 格式）映射到对应的环境变量值"
  [prefix]
  (let [prefix (str prefix "-")
        len    (count prefix)]
    (reduce-kv
     (fn [acc k v]
       (cond-> acc
         (str/starts-with? (name k) prefix)
         (assoc (keyword (subs (name k) len)) v)))
     {}
     env)))

;; 配置解码器：将原始配置值转换为 Malli schema 定义的类型
(def decode-config
  (sm/decoder schema:config sm/string-transformer))

;; 配置验证器：检查配置是否符合 schema:config 定义
(def validate-config
  (sm/validator schema:config))

;; 配置解释器：生成配置验证错误的详细说明
(def explain-config
  (sm/explainer schema:config))

(defn read-config
  "Reads the configuration from enviroment variables and decodes all
  known values."
  [& {:keys [prefix default] :or {prefix "penpot"}}]
  (->> (read-env prefix)
       (merge default)
       (decode-config)))

;; 应用程序版本：从 version.txt 资源文件读取，解析为版本对象
(def version
  (v/parse (or (some-> (io/resource "version.txt")
                       (slurp)
                       (str/trim))
               "%version%")))

;; 全局配置绑定：使用 defonce 确保只初始化一次
;; 可在测试中使用 alter-var-root 动态绑定不同的配置
(defonce ^:dynamic config (read-config :default default))

;; 全局功能标志绑定：使用 defonce 确保只初始化一次
;; 存储解析后的功能标志集合
(defonce ^:dynamic flags (parse-flags config))

(defn validate!
  "验证当前加载的配置数据。
   
   【功能】
   使用 Malli validator 验证配置是否符合 schema:config 定义。
   如果验证失败，打印详细的错误信息并根据 exit-on-error? 决定是否退出程序。
   
   【参数】
   exit-on-error? - 是否在验证失败时退出程序（默认 true）
   
   【返回值】
   验证通过返回 true，失败则打印错误信息并可能退出程序"
  [& {:keys [exit-on-error?] :or {exit-on-error? true}}]
  (if (validate-config config)
    true
    (let [explain (explain-config config)]
      (println "Error on validating configuration:")
      (sm/pretty-explain explain
                         :variant ::sm/schemaless-explain
                         :message "Configuration Validation Error")
      (flush)
      (if exit-on-error?
        (System/exit -1)
        (ex/raise :type :validation
                  :code :config-validaton
                  ::sm/explain explain)))))

(defn get-deletion-delay
  "获取删除延迟时间。
   
   【返回值】
   返回删除操作的延迟时间，默认为 7 天。
   使用 ct/duration 类型表示。"
  []
  (or (c/get config :deletion-delay)
      (ct/duration {:days 7})))

(defn get-file-clean-delay
  "获取文件清理延迟时间。
   
   【返回值】
   返回文件清理操作的延迟时间，默认为 2 天。
   使用 ct/duration 类型表示。"
  []
  (or (c/get config :file-clean-delay)
      (ct/duration {:days 2})))

(defn get
  "配置获取器函数。
   
   【参数】
   key - 配置项的键
   default - 可选的默认值
   
   【返回值】
   返回配置项的值，如果不存在且没有默认值则返回 nil
   
   【用途】
   此函数有助于代码的可测试性，允许在测试时动态绑定不同的配置"
  ([key]
   (c/get config key))
  ([key default]
   (c/get config key default)))

(defn logging-context
  "获取日志上下文信息。
   
   【返回值】
   返回包含版本信息的映射，用于日志记录时的上下文填充"
  []
  {:version/backend (:full version)})

;; Set value for all new threads bindings.
(alter-var-root #'*assert* (constantly (contains? flags :backend-asserts)))
