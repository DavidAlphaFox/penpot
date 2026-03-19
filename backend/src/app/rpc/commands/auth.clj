;; =============================================================================
;; 认证模块 (Authentication Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块负责处理 Penpot 设计工具的所有认证相关 RPC 命令，包括：
;; - 用户密码登录 (login-with-password)
;; - 用户注销 (logout)
;; - 账户恢复 (recover-profile / request-profile-recovery)
;; - 用户注册流程 (prepare-register-profile / register-profile)
;; - SSO 提供商查询 (get-sso-provider)
;;
;; 【核心概念】
;; 1. Session (会话) - 用户认证后会创建会话，使用 session 中间件管理
;; 2. Token (令牌) - 用于账户恢复、邮箱验证、团队邀请等场景的临时令牌
;; 3. OIDC (OpenID Connect) - 单点登录认证协议支持
;; 4. 密码派生 - 使用 auth/derive-password 安全存储密码
;;
;; 【依赖关系】
;; - app.auth - 密码验证和派生逻辑
;; - app.auth.oidc - OIDC 认证提供商支持
;; - app.email - 邮件发送功能
;; - app.tokens - 令牌生成和验证
;; - app.http.session - 会话管理
;; - app.rpc.commands.profile - 用户配置查询
;; - app.rpc.commands.teams - 团队邀请处理
;;
;; =============================================================================

(ns app.rpc.commands.auth
  (:require
   [app.auth :as auth]
   [app.auth.oidc :as oidc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as eml]
   [app.email.blacklist :as email.blacklist]
   [app.email.whitelist :as email.whitelist]
   [app.http :as-alias http]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.setup :as-alias setup]
   [app.setup.welcome-file :refer [create-welcome-file]]
   [app.storage :as sto]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [app.worker :as wrk]
   [cuerdas.core :as str]))

(def schema:password
  [::sm/word-string {:max 500}])

(def schema:token
  [::sm/word-string {:max 6000}])

(defn- elapsed-verify-threshold?
  "检查用户配置文件的修改时间是否超过了邮件验证阈值。
   
   【参数】
   profile - 用户配置文件，包含 :modified-at 字段
   
   【返回值】
   返回 true 如果距离上次修改超过了配置的阈值时间，否则返回 false"
  [profile]
  (let [elapsed (ct/diff (:modified-at profile) (ct/now))
        verify-threshold (cf/get :email-verify-threshold)]
    (pos? (compare elapsed verify-threshold))))

;; ---- COMMAND: login with password

(defn login-with-password
  "使用邮箱和密码进行用户认证。
   
   【参数】
   cfg - 系统配置，包含数据库连接等信息
   params - 认证参数，包含 :email (邮箱) 和 :password (密码)
           可选 :invitation-token 用于团队邀请关联
   
   【返回值】
   返回包含用户会话信息的响应，包含:
   - :id - 用户ID
   - :email - 用户邮箱
   - :fullname - 用户全名
   - :is-admin - 是否为管理员
   - :invitation-token - 如果有未处理的团队邀请则返回邀请令牌"
  [cfg {:keys [email password] :as params}]

  (when-not (or (contains? cf/flags :login)
                (contains? cf/flags :login-with-password))
    (ex/raise :type :restriction
              :code :login-disabled
              :hint "login is disabled"))

  (letfn [(check-password [cfg profile password]
            (if (= (:password profile) "!")
              (ex/raise :type :validation
                        :code :account-without-password
                        :hint "the current account does not have password")
              (let [result (auth/verify-password password (:password profile))]
                (when (:update result)
                  (l/trc :hint "updating profile password"
                         :id (str (:id profile))
                         :email (:email profile))
                  (profile/update-profile-password! cfg (assoc profile :password password)))
                (:valid result))))

          (validate-profile [cfg profile]
            (when-not profile
              (ex/raise :type :validation
                        :code :wrong-credentials))
            (when-not (:is-active profile)
              (ex/raise :type :validation
                        :code :wrong-credentials))
            (when (:is-blocked profile)
              (ex/raise :type :restriction
                        :code :profile-blocked
                        :hint "profile is marked as blocked"))
            (when-not (check-password cfg profile password)
              (ex/raise :type :validation
                        :code :wrong-credentials))
            (when-let [deleted-at (:deleted-at profile)]
              (when (ct/is-after? (ct/now) deleted-at)
                (ex/raise :type :validation
                          :code :wrong-credentials)))

            profile)

          (login [{:keys [::db/conn] :as cfg}]
            (let [profile    (->> (profile/clean-email email)
                                  (profile/get-profile-by-email conn)
                                  (validate-profile cfg)
                                  (profile/strip-private-attrs))

                  invitation (when-let [token (:invitation-token params)]
                               (tokens/verify cfg {:token token :iss :team-invitation}))

                  ;; If invitation member-id does not matches the profile-id, we just proceed to ignore the
                  ;; invitation because invitations matches exactly; and user can't login with other email and
                  ;; accept invitation with other email
                  response   (if (and (some? invitation) (= (:id profile) (:member-id invitation)))
                               {:invitation-token (:invitation-token params)}
                               (assoc profile :is-admin (let [admins (cf/get :admins)]
                                                          (contains? admins (:email profile)))))]
              (-> response
                  (rph/with-transform (session/create-fn cfg profile))
                  (rph/with-meta {::audit/props (audit/profile->props profile)
                                  ::audit/profile-id (:id profile)}))))]

    (db/tx-run! cfg login)))

(def schema:login-with-password
  [:map {:title "login-with-password"}
   [:email ::sm/email]
   [:password schema:password]
   [:invitation-token {:optional true} schema:token]])

(sv/defmethod ::login-with-password
  "Performs authentication using penpot password."
  {::rpc/auth false
   ::doc/added "1.15"
   ::climit/id :auth/global
   ::sm/params schema:login-with-password}
  [cfg params]
  (login-with-password cfg params))

;; ---- COMMAND: Logout

(def ^:private schema:logout
  [:map {:title "logoug"}
   [:profile-id {:optional true} ::sm/uuid]])

(defn logout
  "用户注销命令。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile-id (可选)，如果提供则验证是否与当前会话匹配
   
   【返回值】
   如果使用 SSO 登录则返回包含 :redirect-uri 的地图用于重定向到 SSO 注销页面，
   否则返回空地图。都会清除当前会话。"
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id] :as params}]

(sv/defmethod ::logout
  "Clears the authentication cookie and logout the current session."
  {::rpc/auth false
   ::doc/changes [["2.1" "Now requires profile-id passed in the body"]]
   ::doc/added "1.0"
   ::sm/params schema:logout}
  [cfg params]
  (if (= (:profile-id params)
         (::rpc/profile-id params))
    (let [{:keys [claims]}
          (rph/get-auth-data params)

          provider
          (some->> (get claims :sso-provider-id)
                   (oidc/get-provider cfg))

          response
          (if (and provider (:logout-uri provider))
            (let [params {"logout_hint" (get claims :sso-session-id)
                          "client_id" (get provider :client-id)
                          "post_logout_redirect_uri" (str (cf/get :public-uri))}
                  uri    (-> (u/uri (:logout-uri provider))
                             (assoc :query (u/map->query-string params)))]
              {:redirect-uri uri})
            {})]

      (rph/with-transform response (session/delete-fn cfg)))
    {}))

;; ---- COMMAND: Recover Profile

(defn recover-profile
  "使用密码恢复令牌重置用户密码。
   
   【参数】
   cfg - 系统配置，包含数据库连接
   params - 包含 :token (密码恢复令牌) 和 :password (新密码)
   
   【返回值】
   返回 nil。密码更新后用户账户将被激活。"
  [{:keys [::db/conn] :as cfg} {:keys [token password]}]
  (letfn [(validate-token [token]
            (let [tdata (tokens/verify cfg {:token token :iss :password-recovery})]
              (:profile-id tdata)))

          (update-password [conn profile-id]
            (let [pwd (auth/derive-password password)]
              (db/update! conn :profile {:password pwd :is-active true} {:id profile-id})
              nil))]

    (->> (validate-token token)
         (update-password conn))

    nil))

(def schema:recover-profile
  [:map {:title "recover-profile"}
   [:token schema:token]
   [:password schema:password]])

(sv/defmethod ::recover-profile
  {::rpc/auth false
   ::doc/added "1.15"
   ::sm/params schema:recover-profile
   ::climit/id :auth/global
   ::db/transaction true}
  [cfg params]
  (recover-profile cfg params))

;; ---- COMMAND: Prepare Register

(defn- validate-register-attempt!
  "验证注册请求的合法性。
   
   【参数】
   cfg - 系统配置
   params - 注册参数，包含 :email 等字段
   
   【返回值】
   如果验证失败则抛出异常，成功则返回 nil。
   
   【验证内容】
   - 检查注册功能是否启用
   - 如果有邀请令牌，验证邮箱是否与邀请匹配
   - 检查邮箱域名是否在黑名单或不在白名单
   - 检查邮箱和密码是否相同
   - 检查邮箱是否有退回报告或投诉报告"
  [cfg params]

  (when (or (not (contains? cf/flags :registration))
            (not (contains? cf/flags :login-with-password)))
    (ex/raise :type :restriction
              :code :registration-disabled
              :hint "registration disabled"))

  (when (contains? params :invitation-token)
    (let [invitation (tokens/verify cfg
                                    {:token (:invitation-token params)
                                     :iss :team-invitation})]
      (when-not (= (:email params) (:member-email invitation))
        (ex/raise :type :restriction
                  :code :email-does-not-match-invitation
                  :hint "email should match the invitation"))))

  (when (and (email.blacklist/enabled? cfg)
             (email.blacklist/contains? cfg (:email params)))
    (ex/raise :type :restriction
              :code :email-domain-is-not-allowed
              :hint "email domain in blacklist"))

  (when (and (email.whitelist/enabled? cfg)
             (not (email.whitelist/contains? cfg (:email params))))
    (ex/raise :type :restriction
              :code :email-domain-is-not-allowed
              :hint "email domain not in whitelist"))

  ;; Perform a basic validation of email & password
  (when (= (str/lower (:email params))
           (str/lower (:password params)))
    (ex/raise :type :validation
              :code :email-as-password
              :hint "you can't use your email as password"))

  (when (eml/has-bounce-reports? cfg (:email params))
    (ex/raise :type :restriction
              :code :email-has-permanent-bounces
              :email (:email params)
              :hint "email has bounce reports"))

  (when (eml/has-complaint-reports? cfg (:email params))
    (ex/raise :type :restriction
              :code :email-has-complaints
              :email (:email params)
              :hint "email has complaint reports")))

(defn prepare-register
  "准备注册流程：验证注册信息并生成注册令牌。
   
   【参数】
   cfg - 系统配置
   params - 包含:
   - :fullname - 用户全名
   - :email - 用户邮箱
   - :password - 用户密码
   - :accept-newsletter-updates - 是否接受新闻更新
   - :invitation-token - 团队邀请令牌（可选）
   
   【返回值】
   返回包含 :token 的地图，该令牌用于完成注册。"
  [{:keys [::db/pool] :as cfg} {:keys [fullname email accept-newsletter-updates] :as params}]

  (validate-register-attempt! cfg params)

  (let [email   (profile/clean-email email)
        profile (profile/get-profile-by-email pool email)
        params  {:email email
                 :fullname fullname
                 :password (:password params)
                 :invitation-token (:invitation-token params)
                 :backend "penpot"
                 :iss :prepared-register
                 :profile-id (:id profile)
                 :exp (ct/in-future {:days 7})
                 :props {:newsletter-updates (or accept-newsletter-updates false)}}

        params (d/without-nils params)
        token  (tokens/generate cfg params)]

    (with-meta {:token token}
      {::audit/profile-id uuid/zero})))

(def schema:prepare-register-profile
  [:map {:title "prepare-register-profile"}
   [:fullname ::sm/text]
   [:email ::sm/email]
   [:password schema:password]
   [:create-welcome-file {:optional true} :boolean]
   [:invitation-token {:optional true} schema:token]])

(sv/defmethod ::prepare-register-profile
  {::rpc/auth false
   ::doc/added "1.15"
   ::sm/params schema:prepare-register-profile}
  [cfg params]
  (prepare-register cfg params))

;; ---- COMMAND: Register Profile

(defn import-profile-picture
  "从 URI 导入用户头像图片。
   
   【参数】
   cfg - 系统配置
   uri - 头像图片的 URI 地址
   
   【返回值】
   成功时返回存储对象的 ID，失败时返回 nil。"
  [cfg uri]
  (try
    (let [storage (sto/resolve cfg)
          input   (media/download-image cfg uri)
          input   (media/run {:cmd :info :input input})
          hash    (sto/calculate-hash (:path input))
          content (-> (sto/content (:path input) (:size input))
                      (sto/wrap-with-hash hash))
          sobject (sto/put-object! storage {::sto/content content
                                            ::sto/deduplicate? true
                                            :bucket "profile"
                                            :content-type (:mtype input)})]
      (:id sobject))
    (catch Throwable cause
      (l/wrn :hint "unable to import profile picture"
             :uri uri
             :cause cause)
      nil)))

(defn create-profile
  "在数据库中创建用户配置记录。
   
   【参数】
   cfg - 系统配置，包含数据库连接
   params - 创建参数，包含:
   - :email - 用户邮箱
   - :fullname - 用户全名
   - :password - 密码（可选，默认为 "!" 表示无密码）
   - :locale - 语言环境（可选）
   - :backend - 认证后端（默认为 "penpot"）
   - :is-demo - 是否为演示账户
   - :is-muted - 是否被静音
   - :is-active - 是否激活
   - :theme - 主题偏好
   - :props - 额外属性
   
   【返回值】
   返回创建的用户配置记录，包含所有属性。"
  [{:keys [::db/conn] :as cfg} {:keys [email] :as params}]
  (let [id        (or (:id params) (uuid/next))
        props     (-> (audit/extract-utm-params params)
                      (merge (:props params))
                      (merge {:viewed-tutorial? false
                              :viewed-walkthrough? false
                              :nudge {:big 10 :small 1}
                              :v2-info-shown true
                              :release-notes-viewed (:main cf/version)}))

        password  (or (:password params) "!")

        locale    (:locale params)
        locale    (when (and (string? locale) (not (str/blank? locale)))
                    locale)

        backend   (:backend params "penpot")
        is-demo   (:is-demo params false)
        is-muted  (:is-muted params false)
        is-active (:is-active params false)
        theme     (:theme params nil)
        email     (str/lower email)

        photo-id  (some->> (or (:oidc/picture props)
                               (:google/picture props)
                               (:github/picture props)
                               (:gitlab/picture props))
                           (import-profile-picture cfg))

        params    {:id id
                   :fullname (:fullname params)
                   :email email
                   :auth-backend backend
                   :lang locale
                   :password password
                   :deleted-at (:deleted-at params)
                   :props (db/tjson props)
                   :theme theme
                   :photo-id photo-id
                   :is-active is-active
                   :is-muted is-muted
                   :is-demo is-demo}]

    (try
      (-> (db/insert! conn :profile params)
          (profile/decode-row))
      (catch org.postgresql.util.PSQLException cause
        (if (db/duplicate-key-error? cause)
          (ex/raise :type :validation
                    :code :email-already-exists
                    :hint "email already exists"
                    :cause cause)
          (throw cause))))))


(defn create-profile-rels
  "为新创建的用户配置建立关联关系。
   
   【参数】
   conn - 数据库连接
   profile - 用户配置记录
   
   【返回值】
   返回更新后的用户配置，包含默认团队 ID 和默认项目 ID。"
  [conn {:keys [id] :as profile}]
  (let [features (cfeat/get-enabled-features cf/flags)
        team     (teams/create-team conn
                                    {:profile-id id
                                     :name "Default"
                                     :features features
                                     :is-default true})]
    (-> (db/update! conn :profile
                    {:default-team-id (:id team)
                     :default-project-id  (:default-project-id team)}
                    {:id id}
                    {::db/return-keys true})
        (profile/decode-row))))

(defn send-email-verification!
  "发送邮箱验证邮件。
   
   【参数】
   cfg - 系统配置
   profile - 用户配置记录
   
   【返回值】
   返回 nil。发送验证邮件到用户邮箱，包含验证链接。"
  [{:keys [::db/conn] :as cfg} profile]
  (let [vtoken (tokens/generate cfg
                                {:iss :verify-email
                                 :exp (ct/in-future "72h")
                                 :profile-id (:id profile)
                                 :email (:email profile)})
        ;; NOTE: this token is mainly used for possible complains
        ;; identification on the sns webhook
        ptoken (tokens/generate cfg
                                {:iss :profile-identity
                                 :profile-id (:id profile)
                                 :exp (ct/in-future {:days 30})})]
    (eml/send! {::eml/conn conn
                ::eml/factory eml/register
                :public-uri (cf/get :public-uri)
                :to (:email profile)
                :name (:fullname profile)
                :token vtoken
                :extra-data ptoken})))

(defn register-profile
  "完成用户注册流程。
   
   【参数】
   cfg - 系统配置，包含数据库连接和工作执行器
   params - 包含 :token (之前准备的注册令牌)
   
   【返回值】
   根据不同情况返回不同结果：
   - 如果用户被阻止，返回包含邮箱的地图
   - 如果来自团队邀请，返回新的邀请令牌和会话
   - 如果新用户已激活，返回用户会话信息
   - 如果新用户未激活，返回邮箱地址（需验证）
   - 如果是重复注册，返回相应信息"
  [{:keys [::db/conn ::wrk/executor] :as cfg} {:keys [token] :as params}]
  (let [claims     (tokens/verify cfg {:token token :iss :prepared-register})
        params     (into claims params)

        profile    (if-let [profile-id (:profile-id claims)]
                     (profile/get-profile conn profile-id)
                     ;; NOTE: we first try to match existing profile
                     ;; by email, that in normal circumstances will
                     ;; not return anything, but when a user tries to
                     ;; reuse the same token multiple times, we need
                     ;; to detect if the profile is already registered
                     (or (profile/get-profile-by-email conn (:email claims))
                         (let [is-active (or (boolean (:is-active claims))
                                             (boolean (:email-verified claims))
                                             (not (contains? cf/flags :email-verification)))
                               params    (-> params
                                             (assoc :is-active is-active)
                                             (update :password auth/derive-password))
                               profile   (->> (create-profile cfg params)
                                              (create-profile-rels conn))]
                           (vary-meta profile assoc :created true))))

        created?   (-> profile meta :created true?)

        invitation (when-let [token (:invitation-token params)]
                     (tokens/verify cfg {:token token :iss :team-invitation}))

        props      (-> (audit/profile->props profile)
                       (assoc :from-invitation (some? invitation)))


        create-welcome-file-when-needed
        (fn []
          (when (:create-welcome-file params)
            (let [cfg (dissoc cfg ::db/conn)]
              (wrk/submit! executor (create-welcome-file cfg profile)))))]
    (cond
      ;; When profile is blocked, we just ignore it and return plain data
      (:is-blocked profile)
      (do
        (l/wrn :hint "register attempt for already blocked profile"
               :profile-id (str  (:id profile))
               :profile-email (:email profile))
        (rph/with-meta {:email (:email profile)}
          {::audit/replace-props props
           ::audit/context {:action "ignore-because-blocked"}
           ::audit/profile-id (:id profile)
           ::audit/name "register-profile-retry"}))

      ;; If invitation token comes in params, this is because the user
      ;; comes from team-invitation process; in this case, regenerate
      ;; token and send back to the user a new invitation token (and
      ;; mark current session as logged). This happens only if the
      ;; invitation email matches with the register email.
      (and (some? invitation)
           (= (:email profile)
              (:member-email invitation)))
      (let [invitation (assoc invitation :member-id  (:id profile))
            token      (tokens/generate cfg invitation)]
        (-> {:invitation-token token}
            (rph/with-transform (session/create-fn cfg profile claims))
            (rph/with-meta {::audit/replace-props props
                            ::audit/context {:action "accept-invitation"}
                            ::audit/profile-id (:id profile)})))

      ;; When a new user is created and it is already activated by
      ;; configuration or specified by OIDC, we just mark the profile
      ;; as logged-in
      created?
      (if (:is-active profile)
        (-> (profile/strip-private-attrs profile)
            (rph/with-transform (session/create-fn cfg profile claims))
            (rph/with-defer create-welcome-file-when-needed)
            (rph/with-meta
              {::audit/replace-props props
               ::audit/context {:action "login"}
               ::audit/profile-id (:id profile)}))

        (do
          (when-not (eml/has-reports? conn (:email profile))
            (send-email-verification! cfg profile))

          (-> {:email (:email profile)}
              (rph/with-defer create-welcome-file-when-needed)
              (rph/with-meta
                {::audit/replace-props props
                 ::audit/context {:action "email-verification"}
                 ::audit/profile-id (:id profile)}))))

      :else
      (let [elapsed? (elapsed-verify-threshold? profile)
            reports? (eml/has-reports? conn (:email profile))
            action   (if reports?
                       "ignore-because-complaints"
                       (if elapsed?
                         "resend-email-verification"
                         "ignore"))]

        (l/wrn :hint "repeated registry detected"
               :profile-id (str (:id profile))
               :profile-email (:email profile)
               :context-action action)

        (when (= action "resend-email-verification")
          (db/update! conn :profile
                      {:modified-at (ct/now)}
                      {:id (:id profile)})
          (send-email-verification! cfg profile))

        (rph/with-meta {:email (:email profile)}
          {::audit/replace-props (audit/profile->props profile)
           ::audit/context {:action action}
           ::audit/profile-id (:id profile)
           ::audit/name "register-profile-retry"})))))

(def schema:register-profile
  [:map {:title "register-profile"}
   [:token schema:token]])

(sv/defmethod ::register-profile
  {::rpc/auth false
   ::doc/added "1.15"
   ::sm/params schema:register-profile
   ::climit/id :auth/global}
  [cfg params]
  (db/tx-run! cfg register-profile params))

;; ---- COMMAND: Request Profile Recovery

(defn- request-profile-recovery
  "请求密码恢复：查找用户并发送恢复邮件。
   
   【参数】
   cfg - 系统配置
   params - 包含 :email (用户邮箱)
   
   【返回值】
   返回 nil。发送包含恢复链接的邮件到用户邮箱。"
  [{:keys [::db/conn] :as cfg} {:keys [email] :as params}]
  (letfn [(create-recovery-token [{:keys [id] :as profile}]
            (let [token (tokens/generate cfg
                                         {:iss :password-recovery
                                          :exp (ct/in-future "15m")
                                          :profile-id id})]
              (assoc profile :token token)))

          (send-email-notification [conn profile]
            (let [ptoken (tokens/generate cfg
                                          {:iss :profile-identity
                                           :profile-id (:id profile)
                                           :exp (ct/in-future {:days 30})})]
              (eml/send! {::eml/conn conn
                          ::eml/factory eml/password-recovery
                          :public-uri (cf/get :public-uri)
                          :to (:email profile)
                          :token (:token profile)
                          :name (:fullname profile)
                          :extra-data ptoken})
              nil))]

    (let [profile (->> (profile/clean-email email)
                       (profile/get-profile-by-email conn))]

      (cond
        (not profile)
        (l/wrn :hint "attempt of profile recovery: no profile found"
               :profile-email email)

        (not (eml/allow-send-emails? conn profile))
        (l/wrn :hint "attempt of profile recovery: profile is muted"
               :profile-id (str (:id profile))
               :profile-email (:email profile))

        (eml/has-bounce-reports? conn (:email profile))
        (l/wrn :hint "attempt of profile recovery: email has bounces"
               :profile-id (str (:id profile))
               :profile-email (:email profile))

        (eml/has-complaint-reports? conn (:email profile))
        (l/wrn :hint "attempt of profile recovery: email has complaints"
               :profile-id (str (:id profile))
               :profile-email (:email profile))

        (not (elapsed-verify-threshold? profile))
        (l/wrn :hint "attempt of profile recovery: retry attempt threshold not elapsed"
               :profile-id (str (:id profile))
               :profile-email (:email profile))

        :else
        (do
          (db/update! conn :profile
                      {:modified-at (ct/now)}
                      {:id (:id profile)})
          (->> profile
               (create-recovery-token)
               (send-email-notification conn)))))))

(def schema:request-profile-recovery
  [:map {:title "request-profile-recovery"}
   [:email ::sm/email]])

(sv/defmethod ::request-profile-recovery
  {::rpc/auth false
   ::doc/added "1.15"
   ::sm/params schema:request-profile-recovery}
  [cfg params]
  (db/tx-run! cfg request-profile-recovery params))

;; --- COMMAND: get-sso-config

(defn- extract-domain
  "从邮箱地址中提取域名部分。
   
   【参数】
   email - 邮箱地址字符串
   
   【返回值】
   返回域名部分（小写并去除空格），如果格式无效则返回 nil。"
  [email]
  (let [at (str/last-index-of email "@")]
    (when (and (>= at 0)
               (< at (dec (count email))))
      (-> (subs email (inc at))
          (str/trim)
          (str/lower)))))

(def ^:private schema:get-sso-provider
  [:map {:title "get-sso-config"}
   [:email ::sm/email]])

(def ^:private schema:get-sso-provider-result
  [:map {:title "SSOProvider"}
   [:id ::sm/uuid]])

(sv/defmethod ::get-sso-provider
  {::rpc/auth false
   ::doc/added "2.12"
   ::sm/params schema:get-sso-provider
   ::sm/result schema:get-sso-provider-result}
  [cfg {:keys [email]}]
  (when-let [domain (extract-domain email)]
    (when-let [config (db/get* cfg :sso-provider {:domain domain})]
      (select-keys config [:id]))))
