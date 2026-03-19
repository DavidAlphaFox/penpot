;; =============================================================================
;; 资源处理模块 (Resources Handler Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块负责管理导出过程中的临时资源文件，包括创建、上传和清理。
;; 支持创建 ZIP 归档文件用于多文件导出。
;;
;; 【核心概念】
;; 1. 临时资源 - 创建和管理导出临时文件
;; 2. ZIP 归档 - 使用 archiver 库创建 ZIP 文件
;; 3. 文件上传 - 通过 HTTP 上传临时文件到服务器
;; 4. 自动清理 - 调度删除临时文件
;;
;; 【依赖关系】
;; - archiver - ZIP 归档创建库
;; - undici - HTTP 客户端
;; - app.util.shell - Shell 工具
;; - app.util.mime - MIME 类型工具
;;
;; =============================================================================

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.handlers.resources
  "Temporal resources management."
  (:require
   ["archiver$default" :as arc]
   ["node:fs" :as fs]
   ["node:fs/promises" :as fsp]
   ["node:path" :as path]
   ["undici" :as http]
   [app.common.exceptions :as ex]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.util.mime :as mime]
   [app.util.shell :as sh]
   [cljs.core :as c]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(defn- get-path
  "生成资源文件的完整路径。
   
   【参数】
   type - 资源类型（如 :pdf、:svg、:zip）
   id - 资源 ID（UUID 字符串）
   
   【返回值】
   完整的文件路径字符串。"
  [type id]
  (path/join sh/tmpdir (str/concat  "penpot.resource." (c/name type) "." id)))

(defn create
  "创建临时资源对象。
   
   【参数】
   type - 资源类型（如 :pdf、:svg、:zip）
   name - 资源名称（用于生成文件名）
   
   【返回值】
   资源对象，包含：
   - path: 临时文件路径
   - mtype: MIME 类型
   - name: 资源名称
   - filename: 生成的文件名
   - id: 资源 ID"
  [type name]
  (let [task-id (uuid/next)
        path    (-> (get-path type task-id)
                    (sh/schedule-deletion))]
    {:path     path
     :mtype    (mime/get type)
     :name     name
     :filename (str/concat (str/slug name) (mime/get-extension type))
     :id       task-id}))

(defn create-zip
  "创建 ZIP 归档对象。
   
   【参数】
   [& {:keys [resource on-complete on-progress on-error]}] - 选项：
     - resource: 资源对象
     - on-complete: 归档完成回调
     - on-progress: 进度回调
     - on-error: 错误回调
   
   【返回值】
   ZIP 归档对象。"
  [& {:keys [resource on-complete on-progress on-error]}]
  (let [^js zip  (arc/create "zip")
        ^js out  (fs/createWriteStream (:path resource))
        on-complete (or on-complete (constantly nil))
        progress (atom 0)]
    (.on zip "error" on-error)
    (.on zip "end" on-complete)
    (.on zip "entry" (fn [data]
                       (let [name (unchecked-get data "name")
                             num  (swap! progress inc)]
                         (on-progress {:done num :filename name}))))
    (.pipe zip out)
    zip))

(defn add-to-zip
  "添加文件到 ZIP 归档。
   
   【参数】
   zip - ZIP 归档对象
   path - 要添加的文件路径
   name - 在 ZIP 中的文件名
   
   【返回值】
   无。"
  [zip path name]
  (.file ^js zip path #js {:name name}))

(defn close-zip
  "关闭 ZIP 归档并等待完成。
   
   【参数】
   zip - ZIP 归档对象
   
   【返回值】
   Promise，在归档关闭后解析。"
  [zip]
  (p/create (fn [resolve]
              (.on ^js zip "close" resolve)
              (.finalize ^js zip))))

(defn upload-resource
  "上传资源到服务器。
   
   【参数】
   auth-token - 认证令牌
   resource - 资源对象
   
   【返回值】
   Promise，解析为更新了 URI 的资源对象。
   
   【功能说明】
   1. 读取本地文件为 Blob
   2. 构建 FormData 请求
   3. 使用 management-key 和 auth-token 认证
   4. 发送到服务器上传端点
   5. 解析响应并合并到资源对象"
  [auth-token resource]
  (->> (fsp/readFile (:path resource))
       (p/fmap (fn [buffer]
                 (js/console.log buffer)
                 (new js/Blob #js [buffer] #js {:type (:mtype resource)})))
       (p/mcat (fn [blob]
                 (let [fdata  (new http/FormData)
                       agent  (new http/Agent #js {:connect #js {:rejectUnauthorized false}})
                       headers #js {"X-Shared-Key" (str "exporter " cf/management-key)
                                    "Authorization" (str "Bearer " auth-token)}

                       request #js {:headers headers
                                    :method "POST"
                                    :body fdata
                                    :dispatcher agent}
                       uri     (-> (cf/get :public-uri)
                                   (u/ensure-path-slash)
                                   (u/join "api/management/methods/upload-tempfile")
                                   (str))]

                   (.append fdata "content" blob (:filename resource))
                   (http/fetch uri request))))

       (p/mcat (fn [response]
                 (if (not= (.-status response) 200)
                   (ex/raise :type :internal
                             :code :unable-to-upload-resource
                             :response-status (.-status response))
                   (.text response))))
       (p/fmap t/decode-str)
       (p/fmap (fn [result]
                 (merge resource (dissoc result :id))))))
