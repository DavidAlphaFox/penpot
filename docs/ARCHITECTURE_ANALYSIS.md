# Penpot 项目架构分析

> 本文档提供 Penpot 设计工具的完整架构概览，包括各组件的技术栈、职责和交互关系。

---

## 目录

1. [架构概览](#架构概览)
2. [组件详解](#组件详解)
   - [Frontend (前端应用)](#frontend-前端应用)
   - [Backend (后端服务)](#backend-后端服务)
   - [Common (共享代码)](#common-共享代码)
   - [Exporter (导出服务)](#exporter-导出服务)
   - [Render-WASM (渲染引擎)](#render-wasm-渲染引擎)
   - [MCP (模型上下文协议)](#mcp-模型上下文协议)
   - [Plugins (插件系统)](#plugins-插件系统)
3. [数据流与通信](#数据流与通信)
4. [技术栈总览](#技术栈总览)
5. [开发环境](#开发环境)
6. [测试策略](#测试策略)

---

## 架构概览

Penpot 是一个全栈开源设计工具，采用典型的 SPA（单页应用）架构，由多个独立组件构成：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              用户浏览器                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     Frontend (ClojureScript + React)                │   │
│  │  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────────────┐ │   │
│  │  │  UI 层    │  │  Store    │  │  Worker   │  │  WASM Renderer    │ │   │
│  │  │ (Rumext)  │  │  (Potok)  │  │ (Web)     │  │  (Rust/Skia)      │ │   │
│  │  └───────────┘  └───────────┘  └───────────┘  └───────────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                    │                           │
                    │ RPC API (Transit)         │ WebSocket (实时协作)
                    ▼                           ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Backend (Clojure/JVM)                             │
│  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────────────────────┐ │
│  │ HTTP/RPC  │  │   Auth    │  │  Tasks    │  │    Notifications          │ │
│  │  Server   │  │  Module   │  │  Worker   │  │    (Redis Pub/Sub)        │ │
│  └───────────┘  └───────────┘  └───────────┘  └───────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
                    │                           │
                    ▼                           ▼
        ┌───────────────────┐       ┌───────────────────┐
        │   PostgreSQL      │       │      Redis        │
        │   (主数据库)       │       │   (消息/缓存)      │
        └───────────────────┘       └───────────────────┘
```

### 核心设计原则

1. **语言统一**：前后端均使用 Clojure/ClojureScript，实现代码和数据的无缝共享
2. **函数式编程**：采用不可变数据结构和纯函数设计
3. **事件驱动**：使用 Potok 实现类 Redux 的事件循环模式
4. **实时协作**：通过 WebSocket 和 Redis Pub/Sub 实现多用户实时编辑

---

## 组件详解

### Frontend (前端应用)

**位置**: `frontend/`  
**语言**: ClojureScript + SCSS  
**框架**: React (通过 Rumext 封装)

#### 目录结构

```
frontend/src/app/
├── main/           # 主应用入口
│   ├── ui/         # React UI 组件
│   │   ├── auth/       # 登录/注册界面
│   │   ├── dashboard/  # 仪表盘
│   │   ├── workspace/  # 设计工作区
│   │   ├── viewer/     # 查看器
│   │   ├── settings/   # 设置界面
│   │   └── components/ # 通用组件库
│   ├── data/       # Potok 事件处理器
│   │   ├── users/      # 用户相关事件
│   │   ├── dashboard/  # 仪表盘事件
│   │   ├── workspace/  # 工作区事件
│   │   └── viewer/     # 查看器事件
│   ├── refs/       # 响应式订阅 (Okulary lenses)
│   ├── store/      # Potok 事件存储
│   └── repo/       # 后端 API 调用
├── util/           # 工具函数
├── worker/         # Web Worker (后台计算)
└── config.cljs     # 配置
```

#### 状态管理 (Potok)

```clojure
;; 事件定义示例
(defn my-event
  [data]
  (ptk/reify ::my-event
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :key data))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/cmd! :some-rpc-command params)
           (rx/map success-event)
           (rx/catch error-handler)))))
```

#### UI 组件规范 (Rumext)

```clojure
;; 现代组件语法
(mf/defc my-component*
  {::mf/wrap [mf/memo]}
  [{:keys [name on-click]}]
  [:div {:class (stl/css :root)
         :on-click on-click}
   name])
```

#### 样式规范

- 使用 CSS Modules 模式 (`.scss` 文件与组件共置)
- 优先使用 CSS 自定义属性 (`var(--sp-xs)`)
- 使用逻辑属性支持 RTL/LTR (`margin-inline-start` 替代 `margin-left`)
- 通过 `use-typography()` mixin 管理排版

---

### Backend (后端服务)

**位置**: `backend/`  
**语言**: Clojure (JVM)  
**依赖注入**: Integrant

#### 目录结构

```
backend/src/app/
├── rpc/            # RPC 命令实现
│   └── commands/   # 各类 RPC 方法
├── http/           # HTTP 路由和中间件
├── db/             # 数据库层 (next.jdbc)
├── migrations/     # SQL 迁移脚本
├── tasks/          # 后台任务
├── auth/           # 认证模块
├── storage/        # 文件存储
├── loggers/        # 审计日志等
├── main.clj        # Integrant 系统入口
├── config.clj      # 环境变量配置
├── notifications.clj # WebSocket 管理
└── worker.clj      # 任务调度器
```

#### RPC 方法定义

```clojure
(sv/defmethod ::my-command
  {::rpc/auth true            ;; 需要认证
   ::sm/params [:map ...]     ;; Malli 输入模式
   ::sm/result [:map ...]}    ;; Malli 输出模式
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
  {:id (uuid/next)})
```

#### 数据库访问

```clojure
;; 查询示例
(db/get cfg :table {:id id})           ;; 获取单行
(db/query cfg :table {:team-id tid})   ;; 获取多行
(db/insert! cfg :table {:name "x"})    ;; 插入
(db/update! cfg :table {:name "y"} {:id id})  ;; 更新

;; 事务
(db/tx-run! cfg (fn [{:keys [::db/conn]}]
                  (db/insert! conn :table row)))
```

#### 配置管理

- 环境变量前缀: `PENPOT_*`
- 通过 Malli 验证
- 访问方式: `(cf/get :smtp-host)`
- 特性标志: `(cf/flags :enable-smtp)`

---

### Common (共享代码)

**位置**: `common/`  
**语言**: Clojure/CLJC (跨平台)

#### 目录结构

```
common/src/app/common/
├── types/          # 共享数据类型 (Malli 模式)
├── schema/         # Malli 抽象层
├── geom/           # 几何和变换
├── pages/          # 数据模型定义
│   ├── spec/       # 数据结构规范
│   ├── changes/    # 事务性操作
│   └── migrations/ # 数据模型迁移
├── data/           # 通用工具
├── math/           # 数学工具
├── json/           # JSON 编解码
├── data/macros.cljc # 性能宏
└── exceptions.cljc # 异常处理
```

#### Reader Conditionals (平台条件编译)

```clojure
(defn ordered-set?
  [o]
  #?(:cljs (instance? lks/LinkedSet o)
     :clj (instance? LinkedSet o)))
```

#### 性能宏 (必须使用)

```clojure
(dm/select-keys m [:a :b])  ;; ~6x 快于 core/select-keys
(dm/get-in obj [:a :b :c])  ;; 快于 core/get-in
(dm/str "a" "b" "c")        ;; 字符串拼接
```

---

### Exporter (导出服务)

**位置**: `exporter/`  
**语言**: ClojureScript (Node.js)  
**核心**: Playwright + Headless Chrome

#### 职责

- 将设计文件导出为 PNG/SVG/PDF
- 使用无头浏览器渲染前端应用
- 截图或提取 SVG 实现精确导出

#### 目录结构

```
exporter/src/app/
├── http/           # HTTP 端点
├── renderer/       # 渲染逻辑
├── browser.cljs    # Puppeteer 控制
├── config.cljs     # 配置
└── util/           # 工具函数
```

#### 工作流程

1. 前端请求导出特定形状/文件
2. Exporter 启动无头 Chrome
3. 加载前端应用的渲染端点
4. 截图或提取 DOM 中的 SVG
5. 转换为目标格式并返回

---

### Render-WASM (渲染引擎)

**位置**: `render-wasm/`  
**语言**: Rust → WebAssembly  
**图形库**: Skia (via rust-skia)  
**编译目标**: `wasm32-unknown-emscripten`

#### 架构特点

- **全局状态**: 单一 `unsafe static mut State`，通过宏访问
- **瓦片渲染**: 仅渲染视口内 512×512 瓦片
- **两阶段更新**: 先写入形状数据，再调用 `render_frame()` 绘制
- **形状池**: 扁平化存储，父子关系单独追踪

#### 目录结构

```
render-wasm/
├── src/
│   ├── lib.rs      # WASM 导出函数
│   ├── state.rs    # 全局状态定义
│   ├── render/     # 瓦片渲染管线
│   ├── shapes/     # 形状类型和绘制逻辑
│   └── wasm/       # JS 互操作
└── docs/
    ├── serialization.md
    ├── tile_rendering.md
    └── texts.md
```

#### 前端集成

```clojure
;; ClojureScript 调用 WASM
(app.render-wasm/set-shape-data shape-id data)
(app.render-wasm/render_frame)
```

---

### MCP (模型上下文协议)

**位置**: `mcp/`  
**语言**: TypeScript  
**协议**: Model Context Protocol

#### 架构

```
┌─────────────┐     MCP Protocol      ┌─────────────────┐
│  AI Client  │◄──────────────────────│   MCP Server    │
│   (LLM)     │                       │  (port 4401)    │
└─────────────┘                       └────────┬────────┘
                                               │
                                          WebSocket
                                               │
                                      ┌────────▼────────┐
                                      │   MCP Plugin    │
                                      │  (in Penpot)    │
                                      └─────────────────┘
```

#### 组件结构

```
mcp/
├── packages/
│   ├── common/     # 共享类型定义
│   ├── server/     # MCP 服务器实现
│   │   ├── 提供 MCP 工具给 LLM
│   │   ├── WebSocket 服务器
│   │   └── 任务超时和错误处理
│   └── plugin/     # Penpot 插件
│       ├── 连接 MCP 服务器
│       ├── 使用 Plugin API 执行任务
│       └── 返回结构化响应
└── types-generator/  # API 类型生成器
```

#### 配置

| 环境变量 | 描述 | 默认值 |
|---------|------|-------|
| `PENPOT_MCP_SERVER_PORT` | HTTP/SSE 端口 | 4401 |
| `PENPOT_MCP_WEBSOCKET_PORT` | WebSocket 端口 | 4402 |
| `PENPOT_MCP_LOG_LEVEL` | 日志级别 | info |

---

### Plugins (插件系统)

**位置**: `plugins/`  
**语言**: TypeScript  
**管理**: pnpm workspaces

#### 架构

```
plugins/
├── libs/
│   ├── plugins-runtime/  # 插件运行时
│   │   ├── 初始化插件
│   │   ├── 监听页面/文件/选择变化
│   │   └── 提供 Plugin API
│   └── plugins-styles/    # 样式库
├── apps/
│   ├── contrast-plugin/   # 对比度检查插件
│   ├── icons-plugin/      # Feather 图标插件
│   ├── lorem-ipsum-plugin/# Lorem ipsum 生成器
│   ├── table-plugin/      # 表格工具
│   └── ...                # 其他示例插件
└── docs/
    └── create-plugin.md   # 创建插件指南
```

#### 插件 API 能力

- 读取/修改设计文件内容
- 创建/删除形状
- 访问选中元素
- 监听文件变化事件

---

## 数据流与通信

### 1. RPC 通信

```
Frontend                          Backend
   │                                 │
   │  POST /api/rpc/command/:name    │
   │  Body: Transit-encoded params   │
   │────────────────────────────────►│
   │                                 │
   │  Response: Transit-encoded data │
   │◄────────────────────────────────│
   │                                 │
```

### 2. 实时协作 (WebSocket + Redis Pub/Sub)

```
User A (Frontend)              Backend              User B (Frontend)
      │                           │                        │
      │  WebSocket: join file     │                        │
      │──────────────────────────►│                        │
      │                           │  Redis: subscribe      │
      │                           │───────────┐            │
      │                           │           │            │
      │  Change: update shape     │           │            │
      │──────────────────────────►│           │            │
      │                           │  Redis: publish        │
      │                           │───────────►            │
      │                           │           │            │
      │                           │  WebSocket: notify     │
      │                           │───────────────────────►│
      │                           │                        │
```

### 3. 导出流程

```
Frontend          Exporter          Headless Chrome         Frontend App
   │                  │                    │                     │
   │  Export request  │                    │                     │
   │─────────────────►│                    │                     │
   │                  │  Navigate to       │                     │
   │                  │  render endpoint   │                     │
   │                  │───────────────────►│                     │
   │                  │                    │  Load app           │
   │                  │                    │────────────────────►│
   │                  │                    │                     │
   │                  │  Screenshot/SVG    │                     │
   │                  │◄───────────────────│                     │
   │                  │                    │                     │
   │  File download   │                    │                     │
   │◄─────────────────│                    │                     │
   │                  │                    │                     │
```

---

## 技术栈总览

| 组件 | 语言 | 框架/库 | 数据存储 |
|------|------|---------|----------|
| Frontend | ClojureScript | React, Rumext, Potok, RxJS | - |
| Backend | Clojure | Integrant, next.jdbc | PostgreSQL, Redis |
| Common | CLJC | Malli | - |
| Exporter | ClojureScript | Playwright | - |
| Render-WASM | Rust | Skia, Emscripten | - |
| MCP | TypeScript | MCP Protocol | - |
| Plugins | TypeScript | Plugin API | - |

### 关键依赖

**前端**:
- `rumext` - React 封装
- `potok` - 状态管理
- `okulary` - 响应式 lenses
- `rxjs` - 响应式编程

**后端**:
- `integrant` - 依赖注入
- `next.jdbc` - 数据库访问
- `malli` - 数据验证
- `transit-clj` - 数据序列化

---

## 开发环境

### 常用命令

```bash
# 设置环境
./scripts/setup

# 格式化检查
pnpm run check-fmt:clj
pnpm run check-fmt:js
pnpm run check-fmt:scss

# 修复格式
pnpm run fmt

# Lint 检查
pnpm run lint:clj
pnpm run lint:js
pnpm run lint:scss

# 运行测试
pnpm run test              # 前端测试
pnpm run test:jvm          # Common JVM 测试
pnpm run test:js           # Common JS 测试
clojure -M:dev:test        # 后端测试
```

### WASM 构建

```bash
cd render-wasm
./build      # 编译 Rust → WASM
./watch      # 增量重建
./test       # Rust 单元测试
./lint       # clippy 检查
```

### MCP 启动

```bash
cd mcp
./scripts/setup
pnpm run bootstrap   # 构建并启动所有组件
```

---

## 测试策略

### 前端测试

- **单元测试**: `test/frontend_tests/` (cljs.test)
- **E2E 测试**: `frontend/playwright/` (Playwright)
- 运行: `pnpm run test`, `pnpm run test:e2e`

### 后端测试

- **单元测试**: `test/backend_tests/`
- 运行: `clojure -M:dev:test --focus backend-tests.my-ns-test`

### Common 测试

- 需在 JVM 和 JS 环境都运行
- JVM: `pnpm run test:jvm`
- JS: `pnpm run test:js`

### WASM 测试

```bash
cargo test my_test_name      # 按测试名
cargo test shapes::          # 按模块
```

---

## 参考资源

- [官方文档](https://help.penpot.app/technical-guide/)
- [架构文档](https://help.penpot.app/technical-guide/developer/architecture/)
- [贡献指南](../CONTRIBUTING.md)
- [AGENTS.md](../AGENTS.md) - AI Agent 开发指南

---

*文档生成日期: 2026-03-19*
