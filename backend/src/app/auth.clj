;; =============================================================================
;; 认证模块 (Authentication Module)
;; =============================================================================
;;
;; 【模块概述】
;; 本模块提供用户密码的哈希和验证功能。
;; 使用 Argon2id 算法进行密码哈希，这是一种当前最佳实践的密码哈希算法，
;; 具有防暴力破解和侧信道攻击的特性。
;;
;; 【核心概念】
;; 1. Argon2id - 密码哈希算法，结合了 Argon2i 和 Argon2d 的优点
;; 2. 密码派生 - 将用户密码转换为可验证的哈希值
;; 3. 密码验证 - 验证用户提供的密码是否与存储的哈希匹配
;;
;; 【依赖关系】
;; - buddy.hashers - 密码哈希库
;;
;; =============================================================================

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.auth
  (:require
   [buddy.hashers :as hashers]))

(def ^:private default-options
  "Argon2id 算法的默认参数。
   
   【参数说明】
   - alg: 算法类型 :argon2id
   - memory: 内存消耗 32768 KiB (32 MiB)
   - iterations: 迭代次数 3
   - parallelism: 并行度 2"
  {:alg :argon2id
   :memory 32768 ;; 32 MiB
   :iterations 3
   :parallelism 2})

(defn derive-password
  "派生密码哈希。
   
   【功能】
   使用 Argon2id 算法将用户密码转换为哈希值。
   
   【参数】
   password - 用户密码（明文）
   
   【返回值】
   密码哈希字符串，可用于存储"
  [password]
  (hashers/derive password default-options))

(defn verify-password
  "验证密码。
   
   【功能】
   验证用户提供的密码是否与存储的哈希值匹配。
   
   【参数】
   attempt - 用户提供的密码
   password - 存储的密码哈希
   
   【返回值】
   包含 :valid (布尔值) 和 :update (是否需要更新哈希) 的映射。
   如果验证过程中发生异常，也返回 :valid false"
  [attempt password]
  (try
    (hashers/verify attempt password default-options)
    (catch Throwable _
      {:update false
       :valid false})))
