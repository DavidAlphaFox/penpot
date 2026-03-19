;; =============================================================================
;; MIME 类型工具模块 (MIME Type Utilities Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块提供 MIME 类型和文件扩展名之间的转换功能。
;; 用于确定导出文件的 MIME 类型和文件扩展名。
;;
;; 【核心概念】
;; 1. MIME 类型 - 互联网媒体类型，用于标识文件格式
;; 2. 文件扩展名 - 文件名后缀，用于识别文件类型
;; 3. 类型映射 - 支持的类型：svg、pdf、png、jpeg、webp、zip
;;
;; 【依赖关系】
;; - cljs.core - ClojureScript 核心库
;;
;; =============================================================================

(ns app.util.mime
  "Mimetype and file extension helpers."
  (:refer-clojure :exclude [get])
  (:require
   [cljs.core :as c]))

(defn get-extension
  "获取文件类型对应的扩展名。
   
   【参数】
   type - 文件类型关键词（:svg :pdf :png :jpeg :webp :zip）
   
   【返回值】
   扩展名字符串（如 \".svg\"、\".pdf\"）。"
  [type]
  (case type
    :png  ".png"
    :jpeg ".jpg"
    :webp ".webp"
    :svg  ".svg"
    :pdf  ".pdf"
    :zip  ".zip"))

(defn get
  "获取文件类型对应的 MIME 类型。
   
   【参数】
   type - 文件类型关键词（:svg :pdf :png :jpeg :webp :zip）
   
   【返回值】
   MIME 类型字符串（如 \"image/svg+xml\"、\"application/pdf\"）。"
  [type]
  (case type
    :zip  "application/zip"
    :pdf  "application/pdf"
    :svg  "image/svg+xml"
    :jpeg "image/jpeg"
    :png  "image/png"
    :webp "image/webp"))


