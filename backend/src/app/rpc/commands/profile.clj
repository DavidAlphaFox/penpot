;; =============================================================================
;; 用户配置模块 (Profile Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块负责处理 Penpot 设计工具的用户配置相关 RPC 命令，包括：
;; - 获取用户配置 (get-profile)
;; - 更新用户配置 (update-profile)
;; - 更新密码 (update-profile-password)
;; - 更新头像 (update-profile-photo)
;; - 更新通知设置 (update-profile-notifications)
;; - 更新属性 (update-profile-props)
;; - 请求邮箱更改 (request-email-change)
;; - 删除账户 (delete-profile)
;; - 获取订阅使用情况 (get-subscription-usage)
;;
;; 【核心概念】
;; 1. Profile (用户配置) - 用户账户信息，包含邮箱、全名、头像等
;; 2. Props (属性) - 用户偏好设置，如主题、语言、通知设置等
;; 3. Session (会话) - 用户认证会话
;;
;; 【依赖关系】
;; - app.auth - 密码处理
;; - app.email - 邮件发送
;; - app.http.session - 会话管理
;; - app.media - 媒体处理
;; - app.storage - 存储管理
;; - app.tokens - 令牌生成
;; - app.worker - 后台任务
;;
;; =============================================================================

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.profile
  (:require
   [app.auth :as auth]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.plugins :refer [schema:plugin-registry]]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.email :as eml]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.main :as-alias main]
   [app.media :as media]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as climit]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.setup :as-alias setup]
   [app.storage :as sto]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [app.worker :as wrk]
   [cuerdas.core :as str]))

(declare check-profile-existence!)
(declare decode-row)
(declare filter-props)
(declare get-profile)
(declare strip-private-attrs)

;; --- Schemas (Malli 数据模式定义)

;; 用户通知属性模式
(def schema:props-notifications
  "用户通知设置的数据模式。
   
   【字段】
   - dashboard-comments: 仪表盘评论通知 (:all, :partial, :none)
   - email-comments: 邮件评论通知 (:all, :partial, :none)
   - email-invites: 邮件邀请通知 (:all, :none)"
  [:map {:title "props-notifications"}
   [:dashboard-comments [::sm/one-of #{:all :partial :none}]]
   [:email-comments [::sm/one-of #{:all :partial :none}]]
   [:email-invites [::sm/one-of #{:all :none}]]])

;; 用户属性模式
(def schema:props
  "用户偏好设置的数据模式。
   
   【字段】
   - plugins: 插件注册表
   - mcp-status: MCP 状态
   - newsletter-updates: 通讯更新
   - newsletter-news: 通讯新闻
   - onboarding-team-id: 入团队 ID
   - onboarding-viewed: 入视图
   - v2-info-shown: V2 信息显示
   - welcome-file-id: 欢迎文件 ID
   - release-notes-viewed: 发布说明查看
   - notifications: 通知设置
   - workspace-visited: 工作区访问"
  [:map {:title "ProfileProps"}
   [:plugins {:optional true} schema:plugin-registry]
   [:mcp-status {:optional true} ::sm/boolean]
   [:newsletter-updates {:optional true} ::sm/boolean]
   [:newsletter-news {:optional true} ::sm/boolean]
   [:onboarding-team-id {:optional true} ::sm/uuid]
   [:onboarding-viewed {:optional true} ::sm/boolean]
   [:v2-info-shown {:optional true} ::sm/boolean]
   [:welcome-file-id {:optional true} [:maybe ::sm/boolean]]
   [:release-notes-viewed {:optional true}
    [::sm/text {:max 100}]]
   [:notifications {:optional true} schema:props-notifications]
   [:workspace-visited {:optional true} ::sm/boolean]])

;; 用户配置模式
(def schema:profile
  "用户配置的数据模式。
   
   【字段】
   - id: 用户 ID
   - fullname: 全名
   - email: 邮箱地址
   - is-active: 是否激活
   - is-blocked: 是否被封禁
   - is-demo: 是否演示用户
   - is-muted: 是否被禁言
   - created-at: 创建时间
   - modified-at: 修改时间
   - default-project-id: 默认项目 ID
   - default-team-id: 默认团队 ID
   - props: 用户属性"
  [:map {:title "Profile"}
   [:id ::sm/uuid]
   [:fullname [::sm/word-string {:max 250}]]
   [:email ::sm/email]
   [:is-active {:optional true} ::sm/boolean]
   [:is-blocked {:optional true} ::sm/boolean]
   [:is-demo {:optional true} ::sm/boolean]
   [:is-muted {:optional true} ::sm/boolean]
   [:created-at {:optional true} ::ct/inst]
   [:modified-at {:optional true} ::ct/inst]
   [:default-project-id {:optional true} ::sm/uuid]
   [:default-team-id {:optional true} ::sm/uuid]
   [:props {:optional true} schema:props]])

(defn clean-email
  "清理并标准化邮箱地址字符串。
   
   【参数】
   email - 邮箱地址字符串
   
   【返回值】
   返回标准化后的邮箱地址（小写、去除 mailto: 前缀和 <> 包裹）。"
  [email]
  (let [email (str/lower email)
        email (if (str/starts-with? email "mailto:")
                (subs email 7)
                email)
        email (if (or (str/starts-with? email "<")
                      (str/ends-with? email ">"))
                (str/trim email "<>")
                email)]
    email))

;; --- QUERY: Get profile (own)

(sv/defmethod ::get-profile
  "获取当前登录用户的基本配置信息。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile-id (当前登录用户 ID)
   
   【返回值】
   返回用户配置信息（不包含敏感属性）。
   如果用户未登录或不存在，返回匿名用户对象。"
  {::rpc/auth false
   ::doc/added "1.18"
   ::sm/params [:map]
   ::sm/result schema:profile}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id]}]
      {:id uuid/zero :fullname "Anonymous User"})))

(defn get-profile
  "根据 ID 获取用户配置。
   
   【参数】
   conn - 数据库连接
   id - 用户配置 ID
   opts - 可选参数（如 :for-update 锁定）
   
   【返回值】
   返回用户配置记录。如果未找到则抛出异常。"
  [conn id & {:as opts}]
  ;; NOTE: We need to set ::db/remove-deleted to false because demo profiles
  ;; are created with a set deleted-at value
  (-> (db/get-by-id conn :profile id (assoc opts ::db/remove-deleted false))
      (decode-row)))

;; --- MUTATION: Update Profile (own)

(def ^:private
  schema:update-profile
  [:map {:title "update-profile"}
   [:fullname [::sm/word-string {:max 250}]]
   [:lang {:optional true} [:string {:max 8}]]
   [:theme {:optional true} [:string {:max 250}]]])

(sv/defmethod ::update-profile
  "更新当前用户的基本配置信息（姓名、语言、主题）。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile-id, :fullname, :lang (可选), :theme (可选)
   
   【返回值】
   返回更新后的用户配置信息（不包含敏感属性）。"
  {::doc/added "1.0"
   ::sm/params schema:update-profile
   ::sm/result schema:profile
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id fullname lang theme] :as params}]
  ;; NOTE: we need to retrieve the profile independently if we use
  ;; it or not for explicit locking and avoid concurrent updates of
  ;; the same row/object.
  (let [profile (get-profile conn profile-id ::db/for-update true)
        ;; Update the profile map with direct params
        profile (-> profile
                    (assoc :fullname fullname)
                    (assoc :lang lang)
                    (assoc :theme theme))]

    (db/update! conn :profile
                {:fullname fullname
                 :lang lang
                 :theme theme}
                {:id profile-id}
                {::db/return-keys false})

    (-> profile
        (strip-private-attrs)
        (d/without-nils)
        (rph/with-meta {::audit/props (audit/profile->props profile)}))))


;; --- MUTATION: Update Password

(declare validate-password!)
(declare update-profile-password!)

(def ^:private
  schema:update-profile-password
  [:map {:title "update-profile-password"}
   [:password [::sm/word-string {:max 500}]]
   ;; Social registered users don't have old-password
   [:old-password {:optional true} [:maybe [::sm/word-string {:max 500}]]]])

(sv/defmethod ::update-profile-password
  "更新用户密码。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile-id, :password (新密码), :old-password (可选)
   
   【返回值】
   返回 nil。
   
   【异常】
   - 旧密码不匹配时抛出 :type :validation 异常
   - 使用邮箱作为密码时抛出 :type :validation 异常"
  {::doc/added "1.0"
   ::sm/params schema:update-profile-password
   ::climit/id :auth/global
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id password] :as params}]
  (let [profile (validate-password! cfg (assoc params :profile-id profile-id))]

    (when (= (:email profile) (str/lower (:password params)))
      (ex/raise :type :validation
                :code :email-as-password
                :hint "you can't use your email as password"))

    (update-profile-password! cfg (assoc profile :password password))

    (->> (rph/get-request params)
         (session/get-session)
         (session/invalidate-others cfg))

    nil))

(defn- validate-password!
  "验证用户输入的旧密码是否正确。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile-id 和 :old-password
   
   【返回值】
   返回用户配置记录。
   
   【异常】
   旧密码不匹配时抛出 :type :validation 异常。"
  [{:keys [::db/conn] :as cfg} {:keys [profile-id old-password] :as params}]
  (let [profile (db/get-by-id conn :profile profile-id ::sql/for-update true)]
    (when (and (not= (:password profile) "!")
               (not (:valid (auth/verify-password old-password (:password profile)))))
      (ex/raise :type :validation
                :code :old-password-not-match))
    profile))

(defn update-profile-password!
  "更新用户密码。
   
   【参数】
   cfg - 系统配置
   profile - 包含 :id (用户 ID) 和 :password (新密码)
   
   【返回值】
   返回 nil。"
  [{:keys [::db/conn] :as cfg} {:keys [id password] :as profile}]
  (when-not (db/read-only? conn)
    (db/update! conn :profile
                {:password (auth/derive-password password)}
                {:id id})
    nil))


;; --- MUTATION: Update notifications

(def ^:private
  schema:update-profile-notifications
  [:map {:title "update-profile-notifications"}
   [:dashboard-comments [::sm/one-of #{:all :partial :none}]]
   [:email-comments [::sm/one-of #{:all :partial :none}]]
   [:email-invites [::sm/one-of #{:all :none}]]])

(declare update-notifications!)

(sv/defmethod ::update-profile-notifications
  "更新用户通知设置。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile-id, :dashboard-comments, :email-comments, :email-invites
   
   【返回值】
   返回 nil。"
  {::doc/added "2.4.0"
   ::sm/params schema:update-profile-notifications
   ::climit/id :auth/global}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (db/tx-run! cfg update-notifications! (assoc params :profile-id profile-id)))

(defn- update-notifications!
  "更新用户通知设置的内部函数。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile-id, :dashboard-comments, :email-comments, :email-invites
   
   【返回值】
   返回 nil。"
  [{:keys [::db/conn] :as cfg} {:keys [profile-id dashboard-comments email-comments email-invites]}]
  (let [profile
        (get-profile conn profile-id ::db/for-update true)

        notifications
        {:dashboard-comments dashboard-comments
         :email-comments email-comments
         :email-invites email-invites}

        props
        (-> (get profile :props)
            (assoc :notifications notifications))]

    (db/update! conn :profile
                {:props (db/tjson props)}
                {:id profile-id}
                {::db/return-keys false})
    nil))

;; --- MUTATION: Update Photo

(declare upload-photo)
(declare update-profile-photo)

(def ^:private
  schema:update-profile-photo
  [:map {:title "update-profile-photo"}
   [:file media/schema:upload]])

(sv/defmethod ::update-profile-photo
  "更新用户头像。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile-id 和 :file (上传的图片文件)
   
   【返回值】
   返回包含文件元数据的审计属性响应。"
  {:doc/added "1.1"
   ::sm/params schema:update-profile-photo
   ::sm/result :nil}
  [cfg {:keys [::rpc/profile-id file] :as params}]
  ;; Validate incoming mime type
  (media/validate-media-type! file #{"image/jpeg" "image/png" "image/webp"})
  (update-profile-photo cfg (assoc params :profile-id profile-id)))

(defn update-profile-photo
  "更新用户头像的内部函数。
   
   【参数】
   cfg - 系统配置 (包含 :pool 和 :storage)
   params - 包含 :profile-id 和 :file (上传的文件)
   
   【返回值】
   返回包含审计属性的响应。"
  [{:keys [::db/pool ::sto/storage] :as cfg} {:keys [profile-id file] :as params}]

  (let [photo   (upload-photo cfg params)
        profile (db/get-by-id pool :profile profile-id ::sql/for-update true)]

    ;; Schedule deletion of old photo
    (when-let [id (:photo-id profile)]
      (sto/touch-object! storage id))

    ;; Save new photo
    (db/update! pool :profile
                {:photo-id (:id photo)}
                {:id profile-id})

    (-> (rph/wrap)
        (rph/with-meta {::audit/replace-props
                        {:file-name (:filename file)
                         :file-size (:size file)
                         :file-path (str (:path file))
                         :file-mtype (:mtype file)}}))))

(defn- generate-thumbnail
  "生成用户头像缩略图。
   
   【参数】
   cfg - 系统配置
   input - 输入图像
   
   【返回值】
   返回缩略图对象，包含内容、存储信息和元数据。"
  [_ input]
  (let [input   (media/run {:cmd :info :input input})
        thumb   (media/run {:cmd :profile-thumbnail
                            :format :jpeg
                            :quality 85
                            :width 256
                            :height 256
                            :input input})
        hash    (sto/calculate-hash (:data thumb))
        content (-> (sto/content (:data thumb) (:size thumb))
                    (sto/wrap-with-hash hash))]
    {::sto/content content
     ::sto/deduplicate? true
     :bucket "profile"
     :content-type (:mtype thumb)}))

(defn upload-photo
  "上传用户头像。
   
   【参数】
   cfg - 系统配置 (包含 :storage)
   params - 包含 :profile-id 和 :file
   
   【返回值】
   返回上传的文件对象。"
  [{:keys [::sto/storage] :as cfg} {:keys [file] :as params}]
  (let [params (-> cfg
                  (assoc ::climit/id [[:process-image/by-profile (:profile-id params)]
                                      [:process-image/global]])
                  (assoc ::climit/label "upload-photo")
                  (climit/invoke! generate-thumbnail file))]
    (sto/put-object! storage params)))

;; --- MUTATION: Request Email Change

(declare ^:private request-email-change!)
(declare ^:private change-email-immediately!)

(def ^:private
  schema:request-email-change
  [:map {:title "request-email-change"}
   [:email ::sm/email]])

(sv/defmethod ::request-email-change
  "请求更改用户邮箱地址。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile-id 和 :email (新邮箱)
   
   【返回值】
   如果 SMTP 已配置，发送验证邮件；否则立即更改邮箱。"
  {::doc/added "1.0"
   ::sm/params schema:request-email-change}
  [cfg {:keys [::rpc/profile-id email] :as params}]
  (db/tx-run! cfg
              (fn [cfg]
                (let [profile (db/get-by-id cfg :profile profile-id)
                      params  (assoc params
                                     :profile profile
                                     :email (clean-email email))]
                  (if (contains? cf/flags :smtp)
                    (request-email-change! cfg params)
                    (change-email-immediately! cfg params))))))

(defn- change-email-immediately!
  "立即更改用户邮箱（无需验证邮件）。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile 和 :email
   
   【返回值】
   返回包含 :changed 键的地图。
   
   【异常】
   邮箱已被使用时抛出异常。"
  [{:keys [::db/conn]} {:keys [profile email] :as params}]
  (when (not= email (:email profile))
    (check-profile-existence! conn params))

  (db/update! conn :profile
              {:email email}
              {:id (:id profile)})

  {:changed true})

(defn- request-email-change!
  "请求更改用户邮箱（发送验证邮件）。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile 和 :email
   
   【返回值】
   返回 nil。
   
   【异常】
   - 邮箱已被使用时抛出 :type :validation 异常
   - 用户被禁言时抛出 :type :validation 异常
   - 邮箱有投诉/退信记录时抛出 :type :restriction 异常"
  [{:keys [::db/conn] :as cfg} {:keys [profile email] :as params}]
  (let [token   (tokens/generate cfg
                                 {:iss :change-email
                                  :exp (ct/in-future "15m")
                                  :profile-id (:id profile)
                                  :email email})
        ptoken  (tokens/generate cfg
                                 {:iss :profile-identity
                                  :profile-id (:id profile)
                                  :exp (ct/in-future {:days 30})})]

    (when (not= email (:email profile))
      (check-profile-existence! conn params))

    (when-not (eml/allow-send-emails? conn profile)
      (ex/raise :type :validation
                :code :profile-is-muted
                :hint "looks like the profile has reported repeatedly as spam or has permanent bounces."))

    (when (eml/has-bounce-reports? conn email)
      (ex/raise :type :restriction
                :code :email-has-permanent-bounces
                :email email
                :hint "looks like the email has bounce reports"))

    (when (eml/has-complaint-reports? conn email)
      (ex/raise :type :restriction
                :code :email-has-complaints
                :email email
                :hint "looks like the email has spam complaint reports"))

    (when (eml/has-bounce-reports? conn (:email profile))
      (ex/raise :type :restriction
                :code :email-has-permanent-bounces
                :email (:email profile)
                :hint "looks like the email has bounce reports"))

    (when (eml/has-complaint-reports? conn (:email profile))
      (ex/raise :type :restriction
                :code :email-has-complaints
                :email (:email profile)
                :hint "looks like the email has spam complaint reports"))

    (eml/send! {::eml/conn conn
                ::eml/factory eml/change-email
                :public-uri (cf/get :public-uri)
                :to (:email profile)
                :name (:fullname profile)
                :pending-email email
                :token token
                :extra-data ptoken})
    nil))

;; --- MUTATION: Update Profile Props

(def ^:private
  schema:update-profile-props
  [:map {:title "update-profile-props"}
   [:props schema:props]])

(defn update-profile-props
  "更新用户属性的内部函数。
   
   【参数】
   cfg - 系统配置 (包含 :conn)
   profile-id - 用户 ID
   props - 要更新的属性映射
   
   【返回值】
   返回过滤后的属性映射（去除命名空间限定的键）。"
  [{:keys [::db/conn] :as cfg} profile-id props]
  (let [profile (get-profile conn profile-id ::db/for-update true)
        props   (reduce-kv (fn [props k v]
                             ;; We don't accept namespaced keys
                             (if (simple-ident? k)
                               (if (nil? v)
                                 (dissoc props k)
                                 (assoc props k v))
                               props))
                           (:props profile)
                           props)]

    (db/update! conn :profile
                {:props (db/tjson props)}
                {:id profile-id}
                {::db/return-keys false})

    (filter-props props)))

(sv/defmethod ::update-profile-props
  "更新用户属性配置（如主题、语言、插件等）。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile-id 和 :props (属性映射)
   
   【返回值】
   返回过滤后的属性映射。"
  {::doc/added "1.0"
   ::sm/params schema:update-profile-props
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id props]}]
  (update-profile-props cfg profile-id props))

;; --- MUTATION: Delete Profile

(declare ^:private get-owned-teams)

(sv/defmethod ::delete-profile
  "删除当前用户账户（软删除）。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile-id
   
   【返回值】
   返回会话删除响应。
   
   【异常】
   用户拥有包含其他成员的团队时抛出 :type :validation 异常。"
  {::doc/added "1.0"
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id] :as params}]
  (let [teams      (get-owned-teams conn profile-id)
        deleted-at (ct/now)]

    ;; If we found owned teams with participants, we don't allow
    ;; delete profile until the user properly transfer ownership or
    ;; explicitly removes all participants from the team
    (when (some pos? (map :participants teams))
      (ex/raise :type :validation
                :code :owner-teams-with-people
                :hint "The user need to transfer ownership of owned teams."
                :context {:teams (mapv :id teams)}))

    ;; Mark profile deleted immediatelly
    (db/update! conn :profile
                {:deleted-at deleted-at}
                {:id profile-id})

    ;; Schedule cascade deletion to a worker
    (wrk/submit! {::db/conn conn
                  ::wrk/task :delete-object
                  ::wrk/params {:object :profile
                                :deleted-at deleted-at
                                :id profile-id}})


    (-> (rph/wrap nil)
        (rph/with-transform (session/delete-fn cfg)))))

;; --- SQL 查询定义

(def sql:get-subscription-editors
  "获取用户订阅的编辑者列表的 SQL 语句。
   查询用户拥有的团队中的所有可编辑成员。"
  "SELECT DISTINCT
          p.id,
          p.fullname AS name,
          p.email AS email
     FROM team_profile_rel AS tpr1
     JOIN team as t
       ON tpr1.team_id = t.id
     JOIN team_profile_rel AS tpr2
       ON (tpr1.team_id = tpr2.team_id)
     JOIN profile AS p
       ON (tpr2.profile_id = p.id)
    WHERE tpr1.profile_id = ?
      AND tpr1.is_owner IS true
      AND tpr2.can_edit IS true
      AND t.deleted_at IS NULL")

(def sql:owned-teams
  "获取用户拥有的团队及每个团队成员数量的 SQL 语句。"
  "WITH owner_teams AS (
      SELECT tpr.team_id AS id
        FROM team_profile_rel AS tpr
        JOIN team AS t ON (t.id = tpr.team_id)
       WHERE tpr.is_owner IS TRUE
         AND tpr.profile_id = ?
         AND t.deleted_at IS NULL
    )
    SELECT tpr.team_id AS id,
           count(tpr.profile_id) - 1 AS participants
      FROM team_profile_rel AS tpr
     WHERE tpr.team_id IN (SELECT id from owner_teams)
     GROUP BY 1")

(def ^:private sql:profile-existence
  "检查邮箱是否已存在且未被删除的 SQL 语句。"
  "select exists (select * from profile
                   where email = ?
                     and deleted_at is null) as val")

(def ^:private sql:profile-by-email
  "根据邮箱查找用户的 SQL 语句。"
  "select p.* from profile as p
    where p.email = ?
      and (p.deleted_at is null or
           p.deleted_at > now())")

;; --- QUERY: Get Subscription Usage

(sv/defmethod ::get-subscription-usage
  "获取用户订阅使用情况（编辑者列表）。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile-id
   
   【返回值】
   返回包含 :editors 键的地图，列出用户团队中的所有编辑者。"
  {::doc/added "2.9"}
  [cfg {:keys [::rpc/profile-id]}]
  (let [editors (db/exec! cfg [sql:get-subscription-editors profile-id])]
    {:editors editors}))

(defn get-profile-by-email
  "根据邮箱地址查找用户配置。
   
   【参数】
   conn - 数据库连接
   email - 邮箱地址
   
   【返回值】
   返回用户配置记录，如果未找到则返回 nil。"
  [conn email]
  (->> (db/exec! conn [sql:profile-by-email (clean-email email)])
       (map decode-row)
       (first)))

(defn strip-private-attrs
  "移除用户配置中的敏感私有属性。
   
   【参数】
   row - 用户配置记录
   
   【返回值】
   返回不包含密码和删除标记的用户配置记录。"
  [row]
  (dissoc row :password :deleted-at))

(defn filter-props
  "过滤用户属性，移除命名空间限定的键。
   
   【参数】
   props - 属性映射
   
   【返回值】
   返回只包含简单标识符键的属性映射。"
  [props]
  (into {} (filter (fn [[k _]] (simple-ident? k))) props))

(defn decode-row
  "解码用户配置记录中的 JSON/Transit 字段。
   
   【参数】
   row - 数据库记录
   
   【返回值】
   返回解码后的记录。"
  [{:keys [props] :as row}]
  (cond-> row
    (db/pgobject? props "jsonb")
    (assoc :props (db/decode-transit-pgobject props))))
