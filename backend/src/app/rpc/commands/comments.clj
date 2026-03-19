;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

;; =============================================================================
;; 评论模块 (Comments Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块处理Penpot设计工具中的评论功能。
;; 提供评论线程的创建、查询、更新、删除以及评论通知等RPC命令。
;;
;; 【核心概念】
;; 1. 评论线程 (Comment Thread) - 针对画布上特定位置的评论集合
;; 2. 评论 (Comment) - 线程中的单条评论消息
;; 3. 提及 (Mention) - @用户提及，用于通知
;; 4. 已读状态 (Read Status) - 用户对线程的已读/未读状态
;; 5. 解决状态 (Resolved Status) - 线程是否已标记为已解决
;;
;; 【依赖关系】
;; - app.rpc.commands.files - 文件权限验证
;; - app.rpc.commands.profile - 用户资料处理
;; - app.rpc.commands.teams - 团队管理
;; - app.email - 邮件通知发送
;; - app.features.fdata - 文件数据加载
;; - app.loggers.audit - 审计日志
;;
;; 【数据库表】
;; - comment_thread: 评论线程表
;; - comment: 评论表
;; - comment_thread_status: 用户对线程的阅读状态
;;
;; =============================================================================

(ns app.rpc.commands.comments
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.geom.point :as gpt]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uri :as uri]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.email :as eml]
   [app.features.fdata :as feat.fdata]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.profile :as profile]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.quotes :as quotes]
   [app.rpc.retry :as rtry]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [clojure.set :as set]
   [cuerdas.core :as str]))

;; --- GENERAL PURPOSE INTERNAL HELPERS (通用内部辅助函数)

;; --- 解析评论内容的正则表达式
;; r-mentions-split: 用于分割包含@提及的内容
;; r-mentions: 用于提取@提及的用户信息（格式: @[用户名](用户ID)）

(def r-mentions-split #"@\[[^\]]*\]\([^\)]*\)")
(def r-mentions #"@\[([^\]]*)\]\(([^\)]*)\)")

;; --- 评论最大长度限制
(def comment-max-length 750)

;; --- 格式化评论内容 (Format Comment)
;;
;; 【功能说明】
;; 将评论内容中的@提及转换为纯文本格式。
;; 从Markdown格式 (@[用户名](用户ID)) 提取用户名。
;;
;; 【参数】
;; content - 包含@提及的评论内容
;;
;; 【返回值】
;; 格式化后的纯文本内容
;;
(defn- format-comment
  [{:keys [content]}]
  (->> (d/interleave-all
        (str/split content r-mentions-split)
        (->> (re-seq r-mentions content)
             (map (fn [[_ user _]] user))))
       (str/join "")))

(defn- format-comment-url
  [thread file]
  (str/ffmt "%/#/workspace?%"
            (cf/get :public-uri)
            (uri/map->query-string
             {:file-id (:id file)
              :page-id (:page-id file)
              :team-id (:team-id file)
              :comment-id (:id thread)})))

(defn- format-comment-ref
  [thread file]
  (str/ffmt "#%, %, %"
            (:seqn thread)
            (:name file)
            (:page-name file)))

(defn- get-team-users
  [conn team-id]
  (->> (teams/get-users+props conn team-id)
       (map profile/decode-row)
       (d/index-by :id)))

(defn- notification-email?
  [profile-id owner-id props]
  (if (= profile-id owner-id)
    (not= :none (-> props :notifications :email-comments))
    (= :all (-> props :notifications :email-comments))))

(defn- mention-email?
  "检查用户是否开启评论提及邮件通知。
   
   【参数】
   props - 用户通知设置属性
   
   【返回值】
   布尔值，true表示开启邮件通知"
  [props]
  (not= :none (-> props :notifications :email-comments)))

;; --- 发送评论通知邮件 (Send Comment Emails)
;;
;; 【功能说明】
;; 向相关用户发送评论通知邮件。根据不同场景发送不同类型的邮件：
;; 1. 评论提及通知 - 当用户在评论中被@时
;; 2. 线程通知 - 当有新评论添加到用户参与的线程时
;; 3. 通用通知 - 当用户开启全员通知时
;;
;; 【参数】
;; conn - 数据库连接
;; profile - 当前评论用户资料
;; comment - 评论对象
;; thread - 评论线程对象
;; file - 文件对象
;;
;; 【通知策略】
;; - 被@的用户：总是发送提及邮件
;; - 线程参与者：发送线程更新邮件
;; - 其他用户：仅当开启全员通知时发送
;;
(defn send-comment-emails!
  [conn profile comment thread file]
  (let [team-users        (get-team-users conn (:team-id file))
        comment-reference (format-comment-ref thread file)
        comment-content   (format-comment comment)
        comment-url       (format-comment-url thread file)
        profile-id        (get profile :id)

        ;; Users mentioned in this comment
        comment-mentions
        (-> (:mentions comment)
            (disj profile-id))

        ;; Users mentioned in this thread
        thread-mentions
        (-> (:mentions thread)
            ;; Remove the mentions in the comment because we're already sending a
            ;; notification
            (set/difference comment-mentions)
            (disj profile-id))

        ;; All users
        notificate-users-ids
        (-> (set (keys team-users))
            (set/difference comment-mentions)
            (set/difference thread-mentions)
            (disj profile-id))]

    (doseq [mention comment-mentions]
      (let [{:keys [fullname email props]} (get team-users mention)]
        (when (mention-email? props)
          (eml/send!
           {::eml/conn conn
            ::eml/factory eml/comment-mention
            :public-uri (cf/get :public-uri)
            :to email
            :name fullname
            :source-user (:fullname profile)
            :comment-reference comment-reference
            :comment-content comment-content
            :comment-url comment-url}))))

    ;; Send to the thread users
    (doseq [mention thread-mentions]
      (let [{:keys [fullname email props]} (get team-users mention)]
        (when (mention-email? props)
          (eml/send!
           {::eml/conn conn
            ::eml/factory eml/comment-thread
            :public-uri (cf/get :public-uri)
            :to email
            :name fullname
            :source-user (:fullname profile)
            :comment-reference comment-reference
            :comment-content comment-content
            :comment-url comment-url}))))

    ;; Send to users with the "all" flag activated
    (doseq [user-id notificate-users-ids]
      (let [{:keys [id fullname email props]} (get team-users user-id)]
        (when (notification-email? id (:owner-id thread) props)
          (eml/send!
           {::eml/conn conn
            ::eml/factory eml/comment-notification
            :public-uri (cf/get :public-uri)
            :to email
            :name fullname
            :source-user (:fullname profile)
            :comment-reference comment-reference
            :comment-content comment-content
            :comment-url comment-url}))))))

(defn- decode-row
  [{:keys [participants position mentions] :as row}]
  (cond-> row
    (db/pgpoint? position) (assoc :position (db/decode-pgpoint position))
    (db/pgobject? participants) (assoc :participants (db/decode-transit-pgobject participants))
    (db/pgarray? mentions) (assoc :mentions (db/decode-pgarray mentions #{}))))

(def xf-decode-row
  (map decode-row))

(defn- get-file
  "A specialized version of get-file for comments module."
  [cfg file-id page-id]
  (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg file-id)]
    (let [file (bfc/get-file cfg file-id)
          data (get file :data)]
      (-> file
          (assoc :page-name (dm/get-in data [:pages-index page-id :name]))
          (assoc :page-id page-id)
          (dissoc :data)))))

;; FIXME: rename
(defn- get-comment-thread
  [conn thread-id & {:as opts}]
  (-> (db/get-by-id conn :comment-thread thread-id opts)
      (decode-row)))

(defn- get-comment
  [conn comment-id & {:as opts}]
  (db/get-by-id conn :comment comment-id opts))

(def ^:private sql:get-next-seqn
  "SELECT (f.comment_thread_seqn + 1) AS next_seqn
     FROM file AS f
    WHERE f.id = ?
      FOR UPDATE")

(defn- get-next-seqn
  [conn file-id]
  (let [res (db/exec-one! conn [sql:get-next-seqn file-id])]
    (:next-seqn res)))

(def sql:upsert-comment-thread-status
  "insert into comment_thread_status (thread_id, profile_id, modified_at)
   values (?, ?, ?)
       on conflict (thread_id, profile_id)
       do update set modified_at = ?
   returning modified_at;")

(defn upsert-comment-thread-status!
  ([conn profile-id thread-id]
   (upsert-comment-thread-status! conn profile-id thread-id (ct/in-future "1s")))
  ([conn profile-id thread-id mod-at]
   (db/exec-one! conn [sql:upsert-comment-thread-status thread-id profile-id mod-at mod-at])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUERY COMMANDS
;; ;;;;

;; --- COMMAND: 获取评论线程 (Get Comment Threads)
;;
;; 【功能说明】
;; 获取指定文件的所有评论线程列表。
;; 返回线程详细信息，包括评论数量和未读数量。
;;
;; 【参数】
;; file-id - 文件ID（可选，与team-id互斥）
;; team-id - 团队ID（可选）
;; share-id - 分享ID（可选，用于公开链接访问）
;;
;; 【返回值】
;; 评论线程列表，每个包含：
;; - 线程基本信息
;; - 所有者信息（姓名、邮箱、头像）
;; - 评论数量
;; - 未读评论数量
;;
;; 【权限检查】
;; 需要文件评论权限
;;
(declare ^:private get-comment-threads)

(def ^:private
  schema:get-comment-threads
  [:and
   [:map {:title "get-comment-threads"}
    [:file-id {:optional true} ::sm/uuid]
    [:team-id {:optional true} ::sm/uuid]
    [:share-id {:optional true} [:maybe ::sm/uuid]]]
   [::sm/contains-any #{:file-id :team-id}]])

(sv/defmethod ::get-comment-threads
  {::doc/added "1.15"
   ::sm/params schema:get-comment-threads}
  [cfg {:keys [::rpc/profile-id file-id share-id] :as params}]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (files/check-comment-permissions! conn profile-id file-id share-id)
                 (get-comment-threads conn profile-id file-id))))

(defn- get-comment-threads-sql
  [where]
  (str/ffmt
   "SELECT DISTINCT ON (ct.id)
           ct.*,
           pf.fullname AS owner_fullname,
           pf.email AS owner_email,
           pf.photo_id AS owner_photo_id,
           p.team_id AS team_id,
           f.name AS file_name,
           f.project_id AS project_id,
           first_value(c.content) OVER w AS content,
           (SELECT count(1)
              FROM comment AS c
             WHERE c.thread_id = ct.id) AS count_comments,
           (SELECT count(1)
              FROM comment AS c
             WHERE c.thread_id = ct.id
               AND c.created_at >= coalesce(cts.modified_at, ct.created_at)) AS count_unread_comments
      FROM comment_thread AS ct
     INNER JOIN comment AS c ON (c.thread_id = ct.id)
     INNER JOIN file AS f ON (f.id = ct.file_id)
     INNER JOIN project AS p ON (p.id = f.project_id)
      LEFT JOIN comment_thread_status AS cts ON (cts.thread_id = ct.id AND cts.profile_id = ?)
      LEFT JOIN profile AS pf ON (ct.owner_id = pf.id)
     WHERE f.deleted_at IS NULL
       AND p.deleted_at IS NULL
       %1
    WINDOW w AS (PARTITION BY c.thread_id ORDER BY c.created_at ASC)"
   where))

(def ^:private sql:comment-threads-by-file-id
  (get-comment-threads-sql "AND ct.file_id = ?"))

(defn- get-comment-threads
  [conn profile-id file-id]
  (->> (db/exec! conn [sql:comment-threads-by-file-id profile-id file-id])
       (into [] xf-decode-row)))

;; --- COMMAND: 获取未读评论线程 (Get Unread Comment Threads)
;;
;; 【功能说明】
;; 获取当前用户未读的评论线程列表。
;; 根据用户通知设置（全员/部分）返回不同范围的未读线程。
;;
;; 【参数】
;; team-id - 团队ID
;;
;; 【返回值】
;; 未读评论线程列表
;;
;; 【通知设置】
;; - :all: 返回团队所有未读线程
;; - :partial: 仅返回用户创建的或被@的线程
;;
(def ^:private sql:unread-all-comment-threads-by-team
  (str "WITH threads AS ("
       (get-comment-threads-sql "AND p.team_id = ?")
       ")"
       "SELECT t.* FROM threads AS t
         WHERE t.count_unread_comments > 0"))

(def ^:private sql:unread-partial-comment-threads-by-team
  (str "WITH threads AS ("
       (get-comment-threads-sql "AND p.team_id = ? AND (ct.owner_id = ? OR ? = ANY(ct.mentions))")
       ")"
       "SELECT t.* FROM threads AS t
         WHERE t.count_unread_comments > 0"))

(defn- get-unread-comment-threads
  [cfg profile-id team-id]
  (let [profile (-> (db/get cfg :profile {:id profile-id} ::db/remove-deleted false)
                    (profile/decode-row))
        notify  (or (-> profile :props :notifications :dashboard-comments) :all)
        result  (case notify
                  :all     (db/exec! cfg [sql:unread-all-comment-threads-by-team profile-id team-id])
                  :partial (db/exec! cfg [sql:unread-partial-comment-threads-by-team profile-id team-id profile-id profile-id])
                  [])]
    (into [] xf-decode-row result)))

(def ^:private
  schema:get-unread-comment-threads
  [:map {:title "get-unread-comment-threads"}
   [:team-id ::sm/uuid]])

(sv/defmethod ::get-unread-comment-threads
  {::doc/added "1.15"
   ::sm/params schema:get-unread-comment-threads}
  [cfg {:keys [::rpc/profile-id team-id] :as params}]
  (teams/check-read-permissions! cfg profile-id team-id)
  (get-unread-comment-threads cfg profile-id team-id))

;; --- COMMAND: 获取单个评论线程 (Get Single Comment Thread)
;;
;; 【功能说明】
;; 获取指定文件的特定评论线程详情。
;;
;; 【参数】
;; file-id - 文件ID
;; id - 线程ID
;; share-id - 分享ID（可选）
;;
;; 【返回值】
;; 线程详细信息，包括所有相关评论的摘要
;;
(def ^:private
  schema:get-comment-thread
  [:map {:title "get-comment-thread"}
   [:file-id ::sm/uuid]
   [:id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(def ^:private sql:get-comment-thread
  (get-comment-threads-sql "AND ct.file_id = ? AND ct.id = ?"))

(sv/defmethod ::get-comment-thread
  {::doc/added "1.15"
   ::sm/params schema:get-comment-thread}
  [cfg {:keys [::rpc/profile-id file-id id share-id] :as params}]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (files/check-comment-permissions! conn profile-id file-id share-id)
                 (some-> (db/exec-one! conn [sql:get-comment-thread profile-id file-id id])
                         (decode-row)))))

;; --- COMMAND: 获取评论列表 (Retrieve Comments)
;;
;; 【功能说明】
;; 获取指定评论线程的所有评论。
;; 按创建时间升序排列。
;;
;; 【参数】
;; thread-id - 线程ID
;; share-id - 分享ID（可选）
;;
;; 【返回值】
;; 评论列表，每条评论包含：
;; - 评论内容
;; - 所有者信息
;; - 创建时间
;;
(def ^:private
  schema:get-comments
  [:map {:title "get-comments"}
   [:thread-id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::get-comments
  {::doc/added "1.15"
   ::sm/params schema:get-comments}
  [cfg {:keys [::rpc/profile-id thread-id share-id]}]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (let [{:keys [file-id]} (get-comment-thread conn thread-id)]
                   (files/check-comment-permissions! conn profile-id file-id share-id)
                   (get-comments conn thread-id)))))

(def sql:get-comments
  "SELECT c.*,
          ct.file_id AS file_id,
          pf.fullname AS owner_fullname,
          pf.email AS owner_email,
          pf.photo_id AS owner_photo_id
     FROM comment AS c
    INNER JOIN comment_thread AS ct ON (ct.id = c.thread_id)
     LEFT JOIN profile AS pf ON (c.owner_id = pf.id)
    WHERE c.thread_id = ?
    ORDER BY c.created_at ASC")

(defn- get-comments
  [conn thread-id]
  (->> (db/exec! conn [sql:get-comments thread-id])
       (into [] xf-decode-row)))

;; --- COMMAND: 获取文件评论用户 (Get File Comments Users)
;;
;; 【功能说明】
;; 获取在文件中留下评论的所有用户资料。
;; 包括当前用户（即使未评论）。
;;
;; 【参数】
;; file-id - 文件ID
;; share-id - 分享ID（可选）
;;
;; 【返回值】
;; 用户列表，包含ID、邮箱、全名、头像和活跃状态
;;
;; 【权限检查】
;; 需要文件评论权限
;;
(def ^:private sql:file-comment-users
  "WITH available_profiles AS (
     SELECT DISTINCT owner_id AS id
       FROM comment
      WHERE thread_id IN (SELECT id FROM comment_thread WHERE file_id=?)
  )
  SELECT p.id,
         p.email,
         p.fullname AS name,
         p.fullname AS fullname,
         p.photo_id,
         p.is_active
    FROM profile AS p
   WHERE p.id IN (SELECT id FROM available_profiles) OR p.id=?")

(defn get-file-comments-users
  [conn file-id profile-id]
  (db/exec! conn [sql:file-comment-users file-id profile-id]))

(def ^:private
  schema:get-profiles-for-file-comments
  [:map {:title "get-profiles-for-file-comments"}
   [:file-id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::get-profiles-for-file-comments
  "Retrieves a list of profiles with limited set of properties of all
  participants on comment threads of the file."
  {::doc/added "1.15"
   ::doc/changes ["1.15" "Imported from queries and renamed."]
   ::sm/params schema:get-profiles-for-file-comments}
  [cfg {:keys [::rpc/profile-id file-id share-id]}]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (files/check-comment-permissions! conn profile-id file-id share-id)
                 (get-file-comments-users conn file-id profile-id))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MUTATION COMMANDS
;; ;;;;

(declare ^:private create-comment-thread)

;; --- COMMAND: 创建评论线程 (Create Comment Thread)
;;
;; 【功能说明】
;; 在画布上创建新的评论线程。
;; 同时创建线程和第一条评论，并发送提及通知邮件。
;;
;; 【参数】
;; file-id - 文件ID
;; position - 评论在画布上的位置坐标
;; content - 评论内容（最大750字符）
;; page-id - 页面ID
;; frame-id - 画框ID
;; share-id - 分享ID（可选）
;; mentions - 被@的用户ID集合（可选）
;;
;; 【返回值】
;; 创建的线程对象，包含：
;; - 线程基本信息
;; - 所有者信息
;; - 首条评论ID
;;
;; 【权限检查】
;; 需要文件评论权限
;;
;; 【约束检查】
;; - 检查文件评论数量配额
;; - 检查评论线程数量配额
;;
;; 【注意事项】
;; - FIXME: 此方法会锁定文件表，需要优化到独立表或Redis管理序列号
;; - 触发Webhooks事件
;; - 支持重试机制（处理并发冲突）
;;
(def ^:private
  schema:create-comment-thread
  [:map {:title "create-comment-thread"}
   [:file-id ::sm/uuid]
   [:position ::gpt/point]
   [:content [:string {:max comment-max-length}]]
   [:page-id ::sm/uuid]
   [:frame-id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]
   [:mentions {:optional true} [::sm/set ::sm/uuid]]])

(defn- update-thread-seqn
  [conn file-id seqn]
  (db/update! conn :file
              {:comment-thread-seqn seqn}
              {:id file-id}
              {::db/return-keys false}))

(defn add-owner
  [thread-or-comment profile]
  (-> thread-or-comment
      (assoc :owner-fullname (:fullname profile))
      (assoc :owner-email (:email profile))
      (assoc :owner-photo-id (:photo-id profile))))

(sv/defmethod ::create-comment-thread
  {::doc/added "1.15"
   ::webhooks/event? true
   ::rtry/enabled true
   ::rtry/when rtry/conflict-exception?
   ::sm/params schema:create-comment-thread}
  [cfg {:keys [::rpc/profile-id file-id page-id share-id] :as params}]
  (files/check-comment-permissions! cfg profile-id file-id share-id)

  (let [{:keys [team-id project-id] :as file} (get-file cfg file-id page-id)]
    (-> cfg
        (assoc ::quotes/profile-id profile-id)
        (assoc ::quotes/team-id team-id)
        (assoc ::quotes/project-id project-id)
        (assoc ::quotes/file-id file-id)
        (quotes/check! {::quotes/id ::quotes/comment-threads-per-file}
                       {::quotes/id ::quotes/comments-per-file}))

    (let [params (assoc params ::file file)
          thread (db/tx-run! cfg create-comment-thread params)]

      (vary-meta thread assoc ::audit/props thread))))

(defn- create-comment-thread
  [{:keys [::db/conn] :as cfg}
   {:keys [::rpc/profile-id ::rpc/request-at ::file position content mentions frame-id] :as params}]

  (let [;; NOTE: we take the next seq number from a separate query
        ;; because we need to lock the file for avoid race conditions

        ;; FIXME: this method touches and locks the file table,which
        ;; is already heavy-update tablel; we need to think on move
        ;; the sequence state management to a different table or
        ;; different storage (example: redis) for alivate the update
        ;; pression on the file table

        profile   (profile/get-profile conn profile-id)
        seqn      (get-next-seqn conn (:id file))

        file-id   (get file :id)
        thread-id (uuid/next)

        thread    (-> (db/insert! conn :comment-thread
                                  {:id thread-id
                                   :file-id file-id
                                   :page-name (:page-name file)
                                   :page-id (:page-id file)
                                   :owner-id profile-id
                                   :participants (db/tjson #{profile-id})
                                   :created-at request-at
                                   :modified-at request-at
                                   :seqn seqn
                                   :frame-id frame-id
                                   :position (db/pgpoint position)
                                   :mentions (db/encode-pgarray mentions conn "uuid")})
                      (decode-row))
        comment   (-> (db/insert! conn :comment
                                  {:id (uuid/next)
                                   :thread-id thread-id
                                   :owner-id profile-id
                                   :created-at request-at
                                   :modified-at request-at
                                   :mentions (db/encode-pgarray mentions conn "uuid")
                                   :content content})
                      (decode-row))]

    ;; Make the current thread as read.
    (upsert-comment-thread-status! conn profile-id thread-id request-at)

    ;; Optimistic update of current seq number on file.
    (update-thread-seqn conn file-id seqn)

    ;; Send mentions emails
    (send-comment-emails! conn profile comment thread file)

    (-> thread
        (add-owner profile)
        (assoc :comment-id (:id comment)))))

;; --- COMMAND: 更新评论线程状态 (Update Comment Thread Status)
;;
;; 【功能说明】
;; 将评论线程标记为已读。
;; 更新当前用户对线程的阅读状态。
;;
;; 【参数】
;; id - 线程ID
;; share-id - 分享ID（可选）
;;
;; 【返回值】
;; 无返回值
;;
;; 【权限检查】
;; 需要文件评论权限
;;
(sv/defmethod ::update-comment-thread-status
  {::doc/added "1.15"
   ::sm/params schema:update-comment-thread-status
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id share-id]}]
  (let [{:keys [file-id]} (get-comment-thread conn id ::sql/for-update true)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (upsert-comment-thread-status! conn profile-id id)))

;; --- COMMAND: 更新评论线程 (Update Comment Thread)
;;
;; 【功能说明】
;; 更新评论线程的解决状态。
;; 标记线程为已解决或未解决。
;;
;; 【参数】
;; id - 线程ID
;; is-resolved - 是否已解决（布尔值）
;; share-id - 分享ID（可选）
;;
;; 【返回值】
;; 无返回值
;;
;; 【权限检查】
;; 需要文件评论权限
;;
(def ^:private
  schema:update-comment-thread
  [:map {:title "update-comment-thread"}
   [:id ::sm/uuid]
   [:is-resolved :boolean]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::update-comment-thread
  {::doc/added "1.15"
   ::sm/params schema:update-comment-thread
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id is-resolved share-id]}]
  (let [{:keys [file-id]} (get-comment-thread conn id ::sql/for-update true)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (db/update! conn :comment-thread
                {:is-resolved is-resolved}
                {:id id})
    nil))

;; --- COMMAND: 添加评论 (Add Comment)
;;
;; 【功能说明】
;; 向现有评论线程添加新评论。
;; 更新线程的参与者和修改时间，并发送通知邮件。
;;
;; 【参数】
;; thread-id - 线程ID
;; content - 评论内容（最大750字符）
;; share-id - 分享ID（可选）
;; mentions - 被@的用户ID集合（可选）
;;
;; 【返回值】
;; 创建的评论对象
;;
;; 【权限检查】
;; 需要文件评论权限
;;
;; 【约束检查】
;; - 检查文件评论数量配额
;;
;; 【处理流程】
;; 1. 创建评论记录
;; 2. 更新线程参与者集合
;; 3. 更新线程修改时间
;; 4. 如果页面改变，同步更新缓存的页面名
;; 5. 更新用户阅读状态
;; 6. 发送通知邮件
;; 7. 记录审计日志
;;
(sv/defmethod ::create-comment
  schema:create-comment
  [:map {:title "create-comment"}
   [:thread-id ::sm/uuid]
   [:content [:string {:max comment-max-length}]]
   [:share-id {:optional true} [:maybe ::sm/uuid]]
   [:mentions {:optional true} [::sm/set ::sm/uuid]]])

(sv/defmethod ::create-comment
  {::doc/added "1.15"
   ::webhooks/event? true
   ::sm/params schema:create-comment
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id ::rpc/request-at thread-id share-id content mentions]}]
  (let [{:keys [file-id page-id] :as thread}
        (get-comment-thread conn thread-id ::sql/for-update true)

        {:keys [team-id project-id] :as file}
        (get-file cfg file-id page-id)]

    (files/check-comment-permissions! conn profile-id file-id share-id)

    (quotes/check! cfg {::quotes/id ::quotes/comments-per-file
                        ::quotes/profile-id profile-id
                        ::quotes/team-id team-id
                        ::quotes/project-id project-id
                        ::quotes/file-id file-id})

    (let [profile  (profile/get-profile conn profile-id)
          mentions (into #{} mentions)
          params   {:id (uuid/next)
                    :created-at request-at
                    :modified-at request-at
                    :thread-id thread-id
                    :owner-id profile-id
                    :content content
                    :mentions (db/encode-pgarray mentions conn "uuid")}

          comment (-> (db/insert! conn :comment params)
                      (decode-row)
                      (assoc :file-id file-id)
                      (add-owner profile))]

      ;; Update thread modified-at attribute and assoc the current
      ;; profile to the participant set.
      (let [mentions     (into (:mentions thread) mentions)
            participants (-> (:participants thread #{})
                             (conj profile-id))
            params       {:modified-at request-at
                          :participants (db/tjson participants)
                          :mentions (db/encode-pgarray mentions conn "uuid")}

            ;; Update the page-name cached attribute on comment thread table.
            params       (cond-> params
                           (not= (:page-name file) (:page-name thread))
                           (assoc :page-name (:page-name file)))]

        (db/update! conn :comment-thread params
                    {:id thread-id}
                    {::db/return-keys false}))

      ;; Update the current profile status in relation to the current thread
      (upsert-comment-thread-status! conn profile-id thread-id)

      (send-comment-emails! conn profile comment thread file)

      (vary-meta comment assoc ::audit/props comment))))

;; --- COMMAND: 更新评论 (Update Comment)
;;
;; 【功能说明】
;; 更新评论的内容和提及用户。
;; 仅评论所有者可以编辑自己的评论。
;;
;; 【参数】
;; id - 评论ID
;; content - 新评论内容（最大750字符）
;; share-id - 分享ID（可选）
;; mentions - 新的被@用户ID集合（可选）
;;
;; 【返回值】
;; 无返回值
;;
;; 【权限检查】
;; - 需要文件评论权限
;; - 仅评论所有者可编辑（否则抛出:not-allowed错误）
;;
;; 【TODO】
;; - 检查是否有新增提及，如有则发送新邮件通知
;;
(sv/defmethod ::update-comment
  schema:update-comment
  [:map {:title "update-comment"}
   [:id ::sm/uuid]
   [:content [:string {:max comment-max-length}]]
   [:share-id {:optional true} [:maybe ::sm/uuid]]
   [:mentions {:optional true} [::sm/set ::sm/uuid]]])

;; TODO: Check if there are new mentions, if there are send the new emails.

(sv/defmethod ::update-comment
  {::doc/added "1.15"
   ::sm/params schema:update-comment
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id ::rpc/request-at id share-id content mentions]}]
  (let [{:keys [thread-id owner-id] :as comment}
        (get-comment conn id ::sql/for-update true)

        {:keys [file-id page-id] :as thread}
        (get-comment-thread conn thread-id ::sql/for-update true)]

    (files/check-comment-permissions! conn profile-id file-id share-id)

    ;; Don't allow edit comments to not owners
    (when-not (= owner-id profile-id)
      (ex/raise :type :validation
                :code :not-allowed))

    (let [{:keys [page-name]} (get-file cfg file-id page-id)]
      (db/update! conn :comment
                  {:content content
                   :modified-at request-at
                   :mentions (db/encode-pgarray mentions conn "uuid")}
                  {:id id})

      (db/update! conn :comment-thread
                  {:modified-at request-at
                   :page-name page-name
                   :mentions
                   (-> (:mentions thread)
                       (into mentions)
                       (db/encode-pgarray conn "uuid"))}
                  {:id thread-id}
                  {::db/return-keys false})
      nil)))

;; --- COMMAND: 删除评论线程 (Delete Comment Thread)
;;
;; 【功能说明】
;; 删除整个评论线程及其所有评论。
;; 仅线程所有者可以删除。
;;
;; 【参数】
;; id - 线程ID
;; share-id - 分享ID（可选）
;;
;; 【返回值】
;; 无返回值
;;
;; 【权限检查】
;; - 需要文件评论权限
;; - 仅线程所有者可删除（否则抛出:not-allowed错误）
;;
(sv/defmethod ::delete-comment-thread
  schema:delete-comment-thread
  [:map {:title "delete-comment-thread"}
   [:id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::delete-comment-thread
  {::doc/added "1.15"
   ::sm/params schema:delete-comment-thread
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id share-id]}]
  (let [{:keys [owner-id file-id] :as thread} (get-comment-thread conn id ::sql/for-update true)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (when-not (= owner-id profile-id)
      (ex/raise :type :validation
                :code :not-allowed))

    (db/delete! conn :comment-thread {:id id}
                {::db/return-keys false})
    nil))

;; --- COMMAND: 删除评论 (Delete Comment)
;;
;; 【功能说明】
;; 删除单条评论（非整个线程）。
;; 仅评论所有者可以删除自己的评论。
;;
;; 【参数】
;; id - 评论ID
;; share-id - 分享ID（可选）
;;
;; 【返回值】
;; 无返回值
;;
;; 【权限检查】
;; - 需要文件评论权限
;; - 仅评论所有者可删除（否则抛出:not-allowed错误）
;;
(sv/defmethod ::delete-comment
  schema:delete-comment
  [:map {:title "delete-comment"}
   [:id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::delete-comment
  {::doc/added "1.15"
   ::sm/params schema:delete-comment
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id share-id]}]
  (let [{:keys [owner-id thread-id] :as comment}
        (get-comment conn id ::sql/for-update true)

        {:keys [file-id]}
        (get-comment-thread conn thread-id)]

    (files/check-comment-permissions! conn profile-id file-id share-id)
    (when-not (= owner-id profile-id)
      (ex/raise :type :validation
                :code :not-allowed))

    (db/delete! conn :comment {:id id}
                {::db/return-keys false})
    nil))

;; --- COMMAND: 更新评论线程位置 (Update Comment Thread Position)
;;
;; 【功能说明】
;; 更新评论线程在画布上的位置。
;; 当画框移动时需要同步更新评论位置。
;;
;; 【参数】
;; id - 线程ID
;; position - 新位置坐标
;; frame-id - 新画框ID
;; share-id - 分享ID（可选）
;;
;; 【返回值】
;; 无返回值
;;
;; 【权限检查】
;; 需要文件评论权限
;;
(def ^:private
  schema:update-comment-thread-position
  [:map {:title "update-comment-thread-position"}
   [:id ::sm/uuid]
   [:position ::gpt/point]
   [:frame-id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::update-comment-thread-position
  {::doc/added "1.15"
   ::sm/params schema:update-comment-thread-position
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id ::rpc/request-at id position frame-id share-id]}]
  (let [{:keys [file-id]} (get-comment-thread conn id ::sql/for-update true)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (db/update! conn :comment-thread
                {:modified-at request-at
                 :position (db/pgpoint position)
                 :frame-id frame-id}
                {:id id}
                {::db/return-keys false})
    nil))

;; --- COMMAND: 更新评论线程画框 (Update Comment Frame)
;;
;; 【功能说明】
;; 更新评论线程所属的画框。
;; 当评论被移动到不同画框时使用。
;;
;; 【参数】
;; id - 线程ID
;; frame-id - 新画框ID
;; share-id - 分享ID（可选）
;;
;; 【返回值】
;; 无返回值
;;
;; 【权限检查】
;; 需要文件评论权限
;;
(def ^:private
  schema:update-comment-thread-frame
  [:map {:title "update-comment-thread-frame"}
   [:id ::sm/uuid]
   [:frame-id ::sm/uuid]
   [:share-id {:optional true} [:maybe ::sm/uuid]]])

(sv/defmethod ::update-comment-thread-frame
  {::doc/added "1.15"
   ::sm/params schema:update-comment-thread-frame
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id ::rpc/request-at id frame-id share-id]}]
  (let [{:keys [file-id]} (get-comment-thread conn id ::sql/for-update true)]
    (files/check-comment-permissions! conn profile-id file-id share-id)
    (db/update! conn :comment-thread
                {:modified-at request-at
                 :frame-id frame-id}
                {:id id}
                {::db/return-keys false})
    nil))

(def ^:private
  schema:mark-all-threads-as-read
  [:map {:title "mark-all-threads-as-read"}
   [:threads [:vector ::sm/uuid]]])

;; --- COMMAND: 标记所有线程为已读 (Mark All Threads As Read)
;;
;; 【功能说明】
;; 批量将多个评论线程标记为已读状态。
;; 用户查看通知中心时常用此功能。
;;
;; 【参数】
;; threads - 要标记为已读的线程ID列表
;;
;; 【返回值】
;; 无返回值
;;
(sv/defmethod ::mark-all-threads-as-read
  {::doc/added "1.15"
   ::sm/params schema:mark-all-threads-as-read}
  [cfg {:keys [::rpc/profile-id threads] :as params}]
  (db/tx-run!
   cfg
   (fn [{:keys [::db/conn]}]
     (doseq [thread-id threads]
       (upsert-comment-thread-status! conn profile-id thread-id)))))
