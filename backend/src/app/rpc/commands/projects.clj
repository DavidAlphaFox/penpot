;; =============================================================================
;; 项目模块 (Projects Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块负责处理 Penpot 设计工具的项目相关 RPC 命令，包括：
;; - 获取项目列表 (get-projects, get-all-projects)
;; - 获取单个项目 (get-project)
;; - 创建项目 (create-project)
;; - 重命名项目 (rename-project)
;; - 删除项目 (delete-project)
;; - 置顶/取消置顶项目 (update-project-pin)
;;
;; 【核心概念】
;; 1. Project (项目) - 文件的容器，属于团队
;; 2. Team (团队) - 项目的上层组织单位
;; 3. 权限 - 项目的读取、编辑、管理权限
;;
;; 【依赖关系】
;; - app.db - 数据库访问
;; - app.rpc.commands.teams - 团队信息和权限检查
;; - app.rpc.permissions - 权限管理
;; - app.rpc.quotes - 配额限制检查
;; - app.features.logical-deletion - 软删除功能
;; - app.loggers.audit - 审计日志
;;
;; =============================================================================

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.projects
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.features.logical-deletion :as ldel]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.permissions :as perms]
   [app.rpc.quotes :as quotes]
   [app.util.services :as sv]
   [app.worker :as wrk]))

;; --- Check Project Permissions

(def ^:private sql:project-permissions
  "查询项目权限的 SQL 语句。
   通过团队关系和项目直接关系两种方式查询用户权限。")

(defn- get-permissions
  "获取用户在项目上的权限。
   
   【参数】
   conn - 数据库连接
   profile-id - 用户 ID
   project-id - 项目 ID
   
   【返回值】
   返回权限地图，包含:
   - :is-owner - 是否为所有者
   - :is-admin - 是否为管理员
   - :can-edit - 是否可编辑
   - :can-read - 是否可读取"
  [conn profile-id project-id]
  (let [rows     (db/exec! conn [sql:project-permissions
                                 project-id profile-id
                                 project-id profile-id])
        is-owner (boolean (some :is-owner rows))
        is-admin (boolean (some :is-admin rows))
        can-edit (boolean (some :can-edit rows))]
    (when (seq rows)
      {:is-owner is-owner
       :is-admin (or is-owner is-admin)
       :can-edit (or is-owner is-admin can-edit)
       :can-read true})))

(def has-edit-permissions?
  "判断用户是否有项目编辑权限的函数。
   
   【参数】
   conn - 数据库连接
   profile-id - 用户 ID
   project-id - 项目 ID
   
   【返回值】
   返回布尔值，表示用户是否可以编辑项目。")

(def has-read-permissions?
  "判断用户是否有项目读取权限的函数。
   
   【参数】
   conn - 数据库连接
   profile-id - 用户 ID
   project-id - 项目 ID
   
   【返回值】
   返回布尔值，表示用户是否可以读取项目。")

(def check-edition-permissions!
  "检查用户是否有项目编辑权限，不满足则抛出异常。
   
   【参数】
   conn - 数据库连接
   profile-id - 用户 ID
   project-id - 项目 ID
   
   【异常】
   权限不足时抛出 :type :authorization 异常。")

(def check-read-permissions!
  "检查用户是否有项目读取权限，不满足则抛出异常。
   
   【参数】
   conn - 数据库连接
   profile-id - 用户 ID
   project-id - 项目 ID
   
   【异常】
   权限不足时抛出 :type :authorization 异常。")

;; --- QUERY: Get projects

(def ^:private sql:projects
  "查询项目列表的 SQL 语句，包含文件数量统计。")

(defn get-projects
  "获取指定团队的项目列表。
   
   【参数】
   conn - 数据库连接
   profile-id - 用户 ID
   team-id - 团队 ID
   
   【返回值】
   返回项目记录列表。"
  [conn profile-id team-id]
  (db/exec! conn [sql:projects profile-id team-id]))

(def ^:private schema:get-projects
  [:map {:title "get-projects"}
   [:team-id ::sm/uuid]])

(sv/defmethod ::get-projects
  {::doc/added "1.18"
   ::doc/changes [["2.12" "This endpoint now return deleted but recoverable projects"]]
   ::sm/params schema:get-projects}
  [cfg {:keys [::rpc/profile-id team-id]}]
  (teams/check-read-permissions! cfg profile-id team-id)
  (get-projects cfg profile-id team-id))

;; --- QUERY: Get all projects

(declare get-all-projects)

(def ^:private schema:get-all-projects
  [:map {:title "get-all-projects"}])

(sv/defmethod ::get-all-projects
  {::doc/added "1.18"
   ::sm/params schema:get-all-projects}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id]}]
  (dm/with-open [conn (db/open pool)]
    (get-all-projects conn profile-id)))

(def sql:all-projects
  "查询用户所有可访问项目的 SQL 语句。
   包括通过团队间接访问和项目直接授权的项目。")

(defn get-all-projects
  "获取用户所有可访问的项目。
   
   【参数】
   conn - 数据库连接
   profile-id - 用户 ID
   
   【返回值】
   返回项目记录列表。"
  [conn profile-id]
  (db/exec! conn [sql:all-projects profile-id profile-id]))


;; --- QUERY: Get project

(def ^:private schema:get-project
  [:map {:title "get-project"}
   [:id ::sm/uuid]])

(sv/defmethod ::get-project
  "获取单个项目的详细信息。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile-id 和 :id (项目 ID)
   
   【返回值】
   返回项目记录。"
  {::doc/added "1.18"
   ::sm/params schema:get-project}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id id]}]
  (dm/with-open [conn (db/open pool)]
    (let [project (db/get-by-id conn :project id)]
      (check-read-permissions! conn profile-id id)
      project)))



;; --- MUTATION: Create Project

(defn- create-project
  "创建新项目的内部函数。
   
   【参数】
   cfg - 系统配置
   params - 创建参数，包含 :team-id, :name, :id 等
   
   【返回值】
   返回创建的项目记录。"
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/request-at profile-id team-id] :as params}]
  (assert (ct/inst? request-at) "expect request-at assigned")
  (let [params    (-> params
                      (assoc :created-at request-at)
                      (assoc :modified-at request-at))
        project   (teams/create-project conn params)
        timestamp (::rpc/request-at params)]
    (teams/create-project-role conn profile-id (:id project) :owner)
    (db/insert! conn :team-project-profile-rel
                {:project-id (:id project)
                 :profile-id profile-id
                 :created-at timestamp
                 :modified-at timestamp
                 :team-id team-id
                 :is-pinned false})
    (assoc project :is-pinned false)))

(def ^:private schema:create-project
  [:map {:title "create-project"}
   [:team-id ::sm/uuid]
   [:name [:string {:max 250 :min 1}]]
   [:id {:optional true} ::sm/uuid]])

(sv/defmethod ::create-project
  {::doc/added "1.18"
   ::webhooks/event? true
   ::sm/params schema:create-project}
  [cfg {:keys [::rpc/profile-id team-id] :as params}]

  (teams/check-edition-permissions! cfg profile-id team-id)
  (quotes/check! cfg {::quotes/id ::quotes/projects-per-team
                      ::quotes/profile-id profile-id
                      ::quotes/team-id team-id})

  (let [params (assoc params :profile-id profile-id)]
    (db/tx-run! cfg create-project params)))

;; --- MUTATION: Toggle Project Pin

(def ^:private
  sql:update-project-pin
  "insert into team_project_profile_rel (team_id, project_id, profile_id, is_pinned)
   values (?, ?, ?, ?)
       on conflict (team_id, project_id, profile_id)
       do update set is_pinned=?")

(def ^:private schema:update-project-pin
  [:map {:title "update-project-pin"}
   [:team-id ::sm/uuid]
   [:is-pinned ::sm/boolean]
   [:id ::sm/uuid]])

(sv/defmethod ::update-project-pin
  "置顶或取消置顶项目。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile-id, :id (项目 ID), :team-id, :is-pinned (是否置顶)
   
   【返回值】
   返回 nil。"
  {::doc/added "1.18"
   ::sm/params schema:update-project-pin
   ::webhooks/batch-timeout (ct/duration "5s")
   ::webhooks/batch-key (webhooks/key-fn ::rpc/profile-id :id)
   ::webhooks/event? true
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id team-id is-pinned] :as params}]
  (check-read-permissions! conn profile-id id)
  (db/exec-one! conn [sql:update-project-pin team-id id profile-id is-pinned is-pinned])
  nil)

;; --- MUTATION: Rename Project

(declare rename-project)

(def ^:private schema:rename-project
  [:map {:title "rename-project"}
   [:name [:string {:max 250 :min 1}]]
   [:id ::sm/uuid]])

(sv/defmethod ::rename-project
  "重命名项目。
   
   【参数】
   cfg - 系统配置
   params - 包含 :profile-id, :id (项目 ID), :name (新名称)
   
   【返回值】
   返回包含审计属性的响应。"
  {::doc/added "1.18"
   ::sm/params schema:rename-project
   ::webhooks/event? true
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id name] :as params}]
  (check-edition-permissions! conn profile-id id)
  (let [project (db/get-by-id conn :project id ::sql/for-update true)]
    (db/update! conn :project
                {:name name}
                {:id id})
    (rph/with-meta (rph/wrap)
      {::audit/props {:team-id (:team-id project)
                      :prev-name (:name project)}})))

;; --- MUTATION: Delete Project

(defn- delete-project
  "删除项目的内部函数（软删除）。
   
   【参数】
   conn - 数据库连接
   team - 团队记录
   project-id - 项目 ID
   
   【返回值】
   返回被删除的项目记录。
   
   【注意】
   默认项目不能被删除。"
  [conn team project-id]
  (let [delay   (ldel/get-deletion-delay team)
        project (db/update! conn :project
                            {:deleted-at (ct/in-future delay)}
                            {:id project-id}
                            {::db/return-keys true})]

    (when (:is-default project)
      (ex/raise :type :validation
                :code :non-deletable-project
                :hint "impossible to delete default project"))

    (wrk/submit! {::db/conn conn
                  ::wrk/task :delete-object
                  ::wrk/params {:object :project
                                :deleted-at (:deleted-at project)
                                :id project-id}})

    project))

(def ^:private schema:delete-project
  [:map {:title "delete-project"}
   [:id ::sm/uuid]])

(sv/defmethod ::delete-project
  {::doc/added "1.18"
   ::sm/params schema:delete-project
   ::webhooks/event? true
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id] :as params}]
  (check-edition-permissions! conn profile-id id)
  (let [team    (teams/get-team conn
                                :profile-id profile-id
                                :project-id id)
        project (delete-project conn team id)]
    (rph/with-meta (rph/wrap)
      {::audit/props {:team-id (:team-id project)
                      :name (:name project)
                      :created-at (:created-at project)
                      :modified-at (:modified-at project)}})))
