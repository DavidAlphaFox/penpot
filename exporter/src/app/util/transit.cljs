;; =============================================================================
;; Transit 工具模块 (Transit Utility Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块提供 Transit 格式的编解码功能。
;; Transit 是 Cognitect 开发的一种高效的 JSON 扩展格式。
;;
;; 【核心概念】
;; 1. Transit 格式 - 一种紧凑的、基于 JSON 的数据交换格式
;; 2. 编解码器 - 使用 cognitect.transit 库进行编码和解码
;; 3. UUID 处理 - 自定义 UUID 的读写处理器
;;
;; 【依赖关系】
;; - cognitect.transit - Transit 格式编解码库
;;
;; =============================================================================

(ns app.util.transit
  (:require
   [cognitect.transit :as t]))

;; --- Transit Handlers

(def ^:private +read-handlers+
  "Transit 读处理器映射。
   
   【类型】
   映射，键为类型标签字符，值为对应的读取处理器。"
  {"u" uuid})

(def ^:private +write-handlers+
  "Transit 写处理器映射。
   
   【类型】
   映射，当前为空，使用默认的写处理器。"
  {})

;; --- Public Api

(defn decode
  "解码 Transit 格式字符串。
   
   【参数】
   data - Transit 格式的字符串
   
   【返回值】
   解码后的 Clojure 数据结构。"
  [data]
  (let [r (t/reader :json {:handlers +read-handlers+})]
    (t/read r data)))

(defn encode
  "编码数据为 Transit 格式。
   
   【参数】
   data - 要编码的 Clojure 数据结构
   
   【返回值】
   Transit 格式的字符串（JSON verbose 格式）。"
  [data]
  (let [w (t/writer :json-verbose {:handlers +write-handlers+})]
    (t/write w data)))
