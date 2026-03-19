;; =============================================================================
;; JavaScript 对象工具模块 (JavaScript Object Utilities Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块提供操作 JavaScript 对象的辅助函数。
;; 由于 ClojureScript 与 JavaScript 交互的需要，提供了统一的对象操作接口。
;;
;; 【核心概念】
;; 1. 对象创建 - 创建新的 JavaScript 对象
;; 2. 属性访问 - 安全地获取和设置对象属性
;; 3. 对象合并 - 合并多个 JavaScript 对象
;; 4. 属性转换 - Clojure 关键词与 JavaScript 属性名之间的转换
;;
;; 【依赖关系】
;; - cuerdas.core - 字符串工具库
;;
;; =============================================================================

(ns app.util.object
  "A collection of helpers for work with javascript objects."
  (:refer-clojure :exclude [set! get get-in merge clone contains?])
  (:require
   [cuerdas.core :as str]))

(defn new
  "创建新的 JavaScript 空对象。
   
   【返回值】
   新的空 JavaScript 对象。"
  [] #js {})

(defn get
  "安全地获取对象的属性值。
   
   【参数】
   obj - JavaScript 对象
   k - 属性键
   default - （可选）默认值，当属性不存在时返回
   
   【返回值】
   属性值，如果不存在则返回默认值。"
  ([obj k]
   (when-not (nil? obj)
     (unchecked-get obj k)))
  ([obj k default]
   (let [result (get obj k)]
     (if (undefined? result) default result))))

(defn contains?
  "检查对象是否包含指定属性。
   
   【参数】
   obj - JavaScript 对象
   k - 属性键
   
   【返回值】
   如果属性存在返回 true，否则返回 false。"
  [obj k]
  (some? (unchecked-get obj k)))

(defn get-keys
  "获取对象的所有键。
   
   【参数】
   obj - JavaScript 对象
   
   【返回值】
   键的数组。"
  [obj]
  (js/Object.keys ^js obj))

(defn get-in
  "获取嵌套的对象属性。
   
   【参数】
   obj - JavaScript 对象
   keys - 键的序列
   default - （可选）默认值
   
   【返回值】
   嵌套属性的值，如果不存在则返回默认值。"
  ([obj keys]
   (get-in obj keys nil))

  ([obj keys default]
   (loop [key (first keys)
          keys (rest keys)
          res obj]
     (if (or (nil? key) (nil? res))
       (or res default)
       (recur (first keys)
              (rest keys)
              (unchecked-get res key))))))

(defn clone
  "克隆 JavaScript 对象。
   
   【参数】
   a - 要克隆的对象
   
   【返回值】
   克隆的新对象。"
  [a]
  (js/Object.assign #js {} a))

(defn merge!
  "原地合并 JavaScript 对象。
   
   【参数】
   a - 目标对象
   b - 源对象
   more - （可选）更多源对象
   
   【返回值】
   合并后的目标对象（被修改的对象）。"
  ([a b]
   (js/Object.assign a b))
  ([a b & more]
   (reduce merge! (merge! a b) more)))

(defn merge
  "合并 JavaScript 对象创建新对象。
   
   【参数】
   a - 第一个对象
   b - 第二个对象
   more - （可选）更多对象
   
   【返回值】
   合并后的新对象。"
  ([a b]
   (js/Object.assign #js {} a b))
  ([a b & more]
   (reduce merge! (merge a b) more)))

(defn set!
  "设置对象的属性值。
   
   【参数】
   obj - JavaScript 对象
   key - 属性键
   value - 属性值
   
   【返回值】
   修改后的对象。"
  [obj key value]
  (unchecked-set obj key value)
  obj)

(defn update!
  "更新对象的属性值。
   
   【参数】
   obj - JavaScript 对象
   key - 属性键
   f - 更新函数
   args - （可选）额外参数
   
   【返回值】
   修改后的对象。如果属性不存在则不做修改。"
  [obj key f & args]
  (let [found (get obj key ::not-found)]
    (if-not (identical? ::not-found found)
      (do (unchecked-set obj key (apply f found args))
          obj)
      obj)))

(defn- props-key-fn
  "将 Clojure 关键词转换为 JavaScript 属性名。
   
   【参数】
   key - Clojure 关键词
   
   【返回值】
   JavaScript 属性名字符串。"
  [key]
  (if (or (= key :class) (= key :class-name))
    "className"
    (str/camel (name key))))

(defn clj->props
  "将 Clojure 映射转换为 JavaScript 属性对象。
   
   【参数】
   props - Clojure 映射
   
   【返回值】
   JavaScript 对象。"
  [props]
  (clj->js props :keyword-fn props-key-fn))

(defn ^boolean in?
  "检查属性是否在对象中。
   
   【参数】
   obj - JavaScript 对象
   prop - 属性名
   
   【返回值】
   如果属性存在返回 true。"
  [obj prop]
  (js* "~{} in ~{}" prop obj))
