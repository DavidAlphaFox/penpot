;; =============================================================================
;; Shell 和文件系统工具模块 (Shell & FS Utilities Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块提供操作系统 Shell 命令执行和文件系统操作的工具函数。
;; 用于临时文件管理、文件移动、Shell 命令执行等操作。
;;
;; 【核心概念】
;; 1. 临时文件管理 - 创建和管理临时文件，支持自动删除调度
;; 2. Shell 命令执行 - 异步执行系统命令并返回结果
;; 3. 文件操作 - 读写、移动、删除文件和目录
;; 4. 文件统计 - 获取文件元数据（大小、创建时间等）
;;
;; 【依赖关系】
;; - node:child_process - Node.js 子进程模块
;; - node:fs - Node.js 文件系统模块
;; - app.config - 配置模块
;;
;; =============================================================================

(ns app.util.shell
  "Shell & FS utilities."
  (:require
   ["node:child_process" :as proc]
   ["node:fs" :as fs]
   ["node:path" :as path]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(l/set-level! :trace)

(def ^:const default-deletion-delay
  "默认的文件删除延迟时间（秒）。
   
   【类型】
   数字，表示秒数。
   
   【说明】
   默认为 1 小时（3600 秒）。"
  (* 60 60 1)) ;; 1h

(def tmpdir
  "临时目录路径。
   
   【类型】
   字符串，目录路径。
   
   【功能说明】
   从配置中读取临时目录路径，如果不存在则创建。
   设置了日志记录。"
  (let [path (cf/get :tempdir)]
    (l/inf :hint "tmptdir setup" :path path)
    (when-not (fs/existsSync path)
      (fs/mkdirSync path #js {:recursive true}))
    path))

(defn schedule-deletion
  "调度在指定延迟后删除文件。
   
   【参数】
   path - 要删除的文件路径
   delay - （可选）延迟时间（秒），默认使用 default-deletion-delay
   
   【返回值】
   文件路径。"
  ([path] (schedule-deletion path default-deletion-delay))
  ([path delay]
   (let [remove-path
         (fn []
           (try
             (when (fs/existsSync path)
               (fs/rmSync path #js {:recursive true})
               (l/trc :hint "tempfile permanently deleted" :path path))
             (catch :default cause
               (l/err :hint "error on deleting temporal file"
                      :path path
                      :cause cause))))
         scheduled-at
         (-> (ct/now) (ct/plus #js {:seconds delay}))]

     (l/trc :hint "schedule tempfile deletion"
            :path path
            :scheduled-at (ct/format-inst scheduled-at))

     (js/setTimeout remove-path (* delay 1000))
     path)))

(defn tempfile
  "创建临时文件。
   
   【参数】
   [& {:keys [prefix suffix] :or {prefix \"penpot.\" suffix \".tmp\"}}] - 选项：
     - prefix: 文件名前缀
     - suffix: 文件名后缀
   
   【返回值】
   临时文件的路径字符串。
   
   【功能说明】
   在临时目录中创建一个唯一的临时文件，
   并调度在默认延迟后自动删除。"
  [& {:keys [prefix suffix]
      :or {prefix "penpot."
           suffix ".tmp"}}]
  (loop [i 0]
    (if (< i 1000)
      (let [path (path/join tmpdir (str/concat prefix (uuid/next) "-" i  suffix))]
        (if (fs/existsSync path)
          (recur (inc i))
          (schedule-deletion path)))
      (ex/raise :type :internal
                :code :unable-to-locate-temporal-file
                :hint "unable to find a tempfile candidate"))))

(defn move!
  "移动文件到目标位置。
   
   【参数】
   origin-path - 源文件路径
   dest-path - 目标文件路径
   
   【返回值】
   Promise，在移动完成后解析。"
  [origin-path dest-path]
  (.rename fs/promises origin-path dest-path))

(defn stat
  "获取文件统计信息。
   
   【参数】
   path - 文件路径
   
   【返回值】
   Promise，解析为包含文件信息的映射：
   - path: 文件路径
   - created-at: 创建时间（毫秒时间戳）
   - size: 文件大小（字节）
   如果文件不存在，返回 nil。"
  [path]
  (->> (.stat fs/promises path)
       (p/fmap (fn [data]
                 {:path path
                  :created-at (inst-ms (.-ctime ^js data))
                  :size (.-size data)}))
       (p/merr (fn [_cause]
                 (p/resolved nil)))))

(defn rmdir!
  "删除目录及其所有内容。
   
   【参数】
   path - 目录路径
   
   【返回值】
   Promise，在删除完成后解析。"
  [path]
  (.rm fs/promises path #js {:recursive true}))

(defn write-file!
  "写入内容到文件。
   
   【参数】
   fpath - 文件路径
   content - 要写入的内容
   
   【返回值】
   Promise，在写入完成后解析。"
  [fpath content]
  (.writeFile fs/promises fpath content))

(defn read-file
  "读取文件内容。
   
   【参数】
   fpath - 文件路径
   
   【返回值】
   Promise，解析为文件内容（Buffer）。"
  [fpath]
  (.readFile fs/promises fpath))

(defn run-cmd!
  "执行 Shell 命令。
   
   【参数】
   cmd - 要执行的命令字符串
   
   【返回值】
   Promise，解析为命令的标准输出（Buffer）。
   如果命令执行失败，Promise 会被拒绝。"
  [cmd]
  (p/create
   (fn [resolve reject]
     (l/trace :fn :run-cmd :cmd cmd)
     (proc/exec cmd #js {:encoding "buffer"}
                (fn [error stdout _stderr]
                  ;; (l/trace :fn :run-cmd :stdout stdout)
                  (if error
                    (reject error)
                    (resolve stdout)))))))

