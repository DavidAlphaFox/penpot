;; =============================================================================
;; 核心入口模块 (Core Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块是 Penpot 导出服务的入口点，负责初始化和协调各个子系统。
;; 它初始化浏览器、Redis 和 HTTP 服务器，并处理进程级别的异常和信号。
;;
;; 【核心概念】
;; 1. 服务生命周期管理 - 负责启动和停止各个服务组件
;; 2. 进程异常处理 - 捕获未捕获的异常和进程信号
;; 3. 统一日志初始化 - 配置整个应用的日志级别
;;
;; 【依赖关系】
;; - app.browser - 浏览器管理模块
;; - app.redis - Redis 客户端模块
;; - app.http - HTTP 服务器模块
;;
;; =============================================================================

(ns app.core
  (:require
   ["node:process" :as proc]
   [app.browser :as bwr]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.http :as http]
   [app.redis :as redis]
   [promesa.core :as p]))

(enable-console-print!)
(l/setup! {:app :info})

(defn start
  "启动导出服务。
   
   【参数】
   _ - 不使用的参数（兼容主入口调用）
   
   【返回值】
   返回一个 Promise，在所有服务初始化完成后解析。
   
   【功能说明】
   1. 输出初始化日志信息（公共 URI 和版本号）
   2. 初始化浏览器连接池
   3. 初始化 Redis 客户端连接
   4. 初始化 HTTP 服务器"
  [& _]
  (l/info :msg "initializing"
          :public-uri (str (cf/get :public-uri))
          :version (:full cf/version))
  (p/do!
   (bwr/init)
   (redis/init)
   (http/init)))

(def main start)

(defn stop
  "停止导出服务。
   
   【参数】
   done - 回调函数，在所有服务停止完成后调用
   
   【返回值】
   返回一个 Promise，在所有服务停止完成后解析。
   
   【功能说明】
   1. 输出空行以便于观察重启日志
   2. 输出停止日志
   3. 停止浏览器连接池
   4. 停止 Redis 客户端连接
   5. 停止 HTTP 服务器
   6. 调用完成回调"
  [done]
  ;; an empty line for visual feedback of restart
  (js/console.log "")

  (l/info :msg "stopping")
  (p/do!
   (bwr/stop)
   (redis/stop)
   (http/stop)
   (done)))

(.on proc/default "uncaughtException"
     (fn [cause]
       (js/console.error cause)))

(.on proc/default "SIGTERM" (fn [] (proc/exit 0)))
(.on proc/default "SIGINT" (fn [] (proc/exit 0)))
