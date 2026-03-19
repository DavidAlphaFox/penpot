/**
 * =============================================================================
 * Penpot MCP 服务器核心模块 (Penpot MCP Server Core)
 * =============================================================================
 *
 * 【模块概述】
 * 本模块是 Penpot MCP 服务器的核心实现类，负责：
 * - 管理 MCP 工具的注册和执行
 * - 处理 HTTP/SSE 流式传输连接
 * - 管理 WebSocket 插件桥接连接
 * - 处理会话超时和生命周期管理
 *
 * 【核心概念】
 * 1. McpServer - 来自 @modelcontextprotocol/sdk 的 MCP 协议服务器实现
 * 2. Streamable HTTP - 现代 HTTP 流式传输协议，用于 MCP 通信
 * 3. SSE (Server-Sent Events) - 传统的服务器推送事件机制
 * 4. AsyncLocalStorage - 用于在异步请求中存储会话上下文
 * 5. 会话超时管理 - 空闲超过 60 分钟的会话会自动关闭
 *
 * 【依赖关系】
 * - @modelcontextprotocol/sdk - MCP 协议 SDK
 * - express - HTTP 服务器框架
 * - Tool - MCP 工具基类
 * - PluginBridge - WebSocket 插件桥接器
 * - ConfigurationLoader - 配置加载器
 * - ApiDocs - API 文档管理器
 * - ReplServer - REPL 开发调试服务器
 *
 * 【环境变量】
 * - PENPOT_MCP_SERVER_HOST - 服务器监听地址（默认：0.0.0.0）
 * - PENPOT_MCP_SERVER_PORT - HTTP/SSE 端口（默认：4401）
 * - PENPOT_MCP_WEBSOCKET_PORT - WebSocket 端口（默认：4402）
 * - PENPOT_MCP_REPL_PORT - REPL 服务器端口（默认：4403）
 * - PENPOT_MCP_REMOTE_MODE - 远程模式标志
 *
 * =============================================================================
 */
import { AsyncLocalStorage } from "async_hooks";
import { SSEServerTransport } from "@modelcontextprotocol/sdk/server/sse.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { ExecuteCodeTool } from "./tools/ExecuteCodeTool";
import { PluginBridge } from "./PluginBridge";
import { ConfigurationLoader } from "./ConfigurationLoader";
import { createLogger } from "./logger";
import { Tool } from "./Tool";
import { HighLevelOverviewTool } from "./tools/HighLevelOverviewTool";
import { PenpotApiInfoTool } from "./tools/PenpotApiInfoTool";
import { ExportShapeTool } from "./tools/ExportShapeTool";
import { ImportImageTool } from "./tools/ImportImageTool";
import { ReplServer } from "./ReplServer";
import { ApiDocs } from "./ApiDocs";

/**
 * SessionContext - 会话上下文接口
 *
 * 存储请求级别的用户会话信息，用于在异步调用链中传递用户认证信息。
 *
 * @example
 * ```typescript
 * const sessionContext = this.sessionContext.getStore();
 * if (sessionContext?.userToken) {
 *   // 使用用户令牌进行认证
 * }
 * ```
 */
export interface SessionContext {
    /**
     * 用户认证令牌，用于多用户模式下的用户身份识别
     */
    userToken?: string;
}

/**
 * StreamableSession - Streamable HTTP 会话封装类
 *
 * 封装了单个 Streamable HTTP 会话的所有相关信息，包括传输层、会话ID、用户令牌和最后活跃时间。
 * 用于会话超时管理和会话追踪。
 */
class StreamableSession {
    /**
     * 创建会话封装实例
     *
     * @param transport - Streamable HTTP 传输层实例
     * @param userToken - 用户认证令牌（多用户模式）
     * @param lastActiveTime - 最后活跃时间戳（毫秒）
     */
    constructor(
        public readonly transport: StreamableHTTPServerTransport,
        public readonly userToken: string | undefined,
        public lastActiveTime: number
    ) {}
}

/**
 * ToolInfo - 工具信息封装类
 *
 * 存储已注册 MCP 工具的实例、名称和配置信息。
 * 用于工具的注册管理和 MCP 协议交互。
 */
class ToolInfo {
    /**
     * 创建工具信息实例
     *
     * @param instance - 工具类实例
     * @param name - 工具名称
     * @param config - 工具配置（描述和输入Schema）
     */
    constructor(
        public readonly instance: Tool<any>,
        public readonly name: string,
        public readonly config: { description: string; inputSchema: any }
    ) {}
}

export class PenpotMcpServer {
    /**
     * Timeout, in minutes, for idle Streamable HTTP sessions before they are automatically closed and removed.
     */
    private static readonly SESSION_TIMEOUT_MINUTES = 60;

    private readonly logger = createLogger("PenpotMcpServer");
    private readonly tools: ToolInfo[];
    public readonly configLoader: ConfigurationLoader;
    private app: any;
    public readonly pluginBridge: PluginBridge;
    private readonly replServer: ReplServer;
    private apiDocs: ApiDocs;
    private readonly penpotHighLevelOverview: string;
    private readonly connectionInstructions: string;

    /**
     * Manages session-specific context, particularly user tokens for each request.
     */
    private readonly sessionContext = new AsyncLocalStorage<SessionContext>();

    private readonly streamableTransports: Record<string, StreamableSession> = {};
    private readonly sseTransports: Record<string, { transport: SSEServerTransport; userToken?: string }> = {};

    public readonly host: string;
    public readonly port: number;
    public readonly webSocketPort: number;
    public readonly replPort: number;
    private sessionTimeoutInterval: ReturnType<typeof setInterval> | undefined;

    /**
     * PenpotMcpServer 构造函数
     *
     * 初始化 MCP 服务器实例，包括：
     * - 读取环境变量配置（端口、地址等）
     * - 初始化配置加载器和 API 文档
     * - 注册 MCP 工具
     * - 初始化插件桥接器和 REPL 服务器
     *
     * @param isMultiUser - 是否启用多用户模式（默认：false 单用户模式）
     */
    constructor(private isMultiUser: boolean = false) {
        // read port configuration from environment variables
        this.host = process.env.PENPOT_MCP_SERVER_HOST ?? "0.0.0.0";
        this.port = parseInt(process.env.PENPOT_MCP_SERVER_PORT ?? "4401", 10);
        this.webSocketPort = parseInt(process.env.PENPOT_MCP_WEBSOCKET_PORT ?? "4402", 10);
        this.replPort = parseInt(process.env.PENPOT_MCP_REPL_PORT ?? "4403", 10);

        this.configLoader = new ConfigurationLoader(process.cwd());
        this.apiDocs = new ApiDocs();

        // prepare instructions
        let instructions = this.configLoader.getInitialInstructions();
        instructions = instructions.replace("$api_types", this.apiDocs.getTypeNames().join(", "));
        this.penpotHighLevelOverview = instructions;
        this.connectionInstructions = this.configLoader.getBaseInstructions();

        this.tools = this.initTools();

        this.pluginBridge = new PluginBridge(this, this.webSocketPort);
        this.replServer = new ReplServer(this.pluginBridge, this.replPort);
    }

    /**
     * Indicates whether the server is running in multi-user mode,
     * where user tokens are required for authentication.
     */
    /**
     * 检查服务器是否运行在多用户模式
     *
     * 多用户模式下，每个请求需要携带用户令牌（userToken）进行身份验证。
     * 单用户模式下（默认），服务器不强制要求用户令牌。
     *
     * @returns boolean - true 表示多用户模式，false 表示单用户模式
     */
    public isMultiUserMode(): boolean {
        return this.isMultiUser;
    }

    /**
     * Indicates whether the server is running in remote mode.
     *
     * In remote mode, the server is not assumed to be accessed only by a local user on the same machine,
     * with corresponding limitations being enforced.
     * Remote mode can be explicitly enabled by setting the environment variable PENPOT_MCP_REMOTE_MODE
     * to "true". Enabling multi-user mode forces remote mode, regardless of the value of the environment
     * variable.
     */
    /**
     * 检查服务器是否运行在远程模式
     *
     * 远程模式下，服务器不再假设仅由本地用户在同一台机器上访问，
     * 并会强制执行相应的限制。可以通过设置环境变量 PENPOT_MCP_REMOTE_MODE
     * 为 "true" 来显式启用远程模式。启用多用户模式会自动强制启用远程模式，
     * 无论环境变量的值如何。
     *
     * @returns boolean - true 表示远程模式，false 表示本地模式
     */
    public isRemoteMode(): boolean {
        const isRemoteModeRequested: boolean = process.env.PENPOT_MCP_REMOTE_MODE === "true";
        return this.isMultiUserMode() || isRemoteModeRequested;
    }

    /**
     * Indicates whether file system access is enabled for MCP tools.
     * Access is enabled only in local mode, where the file system is assumed
     * to belong to the user running the server locally.
     */
    /**
     * 检查是否启用了文件系统访问功能
     *
     * 文件系统访问仅在本地模式下启用，此时假设文件系统属于
     * 在本地运行服务器的用户。远程模式下会禁用文件系统访问以确保安全。
     *
     * @returns boolean - true 表示启用文件系统访问，false 表示禁用
     */
    public isFileSystemAccessEnabled(): boolean {
        return !this.isRemoteMode();
    }

    /**
     * Retrieves the high-level overview instructions explaining core Penpot usage.
     */
    /**
     * 获取 Penpot 高级概述说明
     *
     * 返回包含 Penpot 核心功能使用说明的字符串，该说明包含
     * 初始指令和可用的 API 类型信息。
     *
     * @returns string - 高级概述说明文本
     */
    public getHighLevelOverviewInstructions(): string {
        return this.penpotHighLevelOverview;
    }

    /**
     * Retrieves the current session context.
     *
     * @returns The session context for the current request, or undefined if not in a request context
     */
    /**
     * 获取当前请求的会话上下文
     *
     * 通过 AsyncLocalStorage 获取当前请求链中的会话上下文信息，
     * 包括用户令牌等认证信息。此方法应在请求处理过程中调用。
     *
     * @returns SessionContext | undefined - 当前会话上下文，不在请求上下文中则返回 undefined
     */
    public getSessionContext(): SessionContext | undefined {
        return this.sessionContext.getStore();
    }

    /**
     * 初始化 MCP 工具列表
     *
     * 创建并注册所有可用的 MCP 工具实例。根据服务器模式
     * （本地/远程）决定是否包含需要文件系统访问的工具。
     *
     * 注册的工具包括：
     * - ExecuteCodeTool: 在 Penpot 环境中执行代码
     * - HighLevelOverviewTool: 提供 Penpot 高级概述
     * - PenpotApiInfoTool: 提供 API 信息查询
     * - ExportShapeTool: 导出设计元素
     * - ImportImageTool: 导入图片（仅本地模式）
     *
     * @returns ToolInfo[] - 已注册的工具信息数组
     */
    private initTools(): ToolInfo[] {
        const toolInstances: Tool<any>[] = [
            new ExecuteCodeTool(this),
            new HighLevelOverviewTool(this),
            new PenpotApiInfoTool(this, this.apiDocs),
            new ExportShapeTool(this),
        ];
        if (this.isFileSystemAccessEnabled()) {
            toolInstances.push(new ImportImageTool(this));
        }

        return toolInstances.map((instance) => {
            this.logger.info(`Registering tool: ${instance.getToolName()}`);
            return new ToolInfo(instance, instance.getToolName(), {
                description: instance.getToolDescription(),
                inputSchema: instance.getInputSchema(),
            });
        });
    }

    /**
     * Creates a fresh {@link McpServer} instance with all tools registered.
     */
    /**
     * 创建 MCP 服务器实例
     *
     * 创建一个新的 McpServer 实例，并注册所有已配置的 MCP 工具。
     * 每个会话（Session）都会创建一个独立的服务器实例，以确保会话隔离。
     *
     * @returns McpServer - 配置好的 MCP 服务器实例
     */
    private createMcpServer(): McpServer {
        const server = new McpServer(
            { name: "penpot", version: "1.0.0" },
            { instructions: this.connectionInstructions }
        );

        for (const tool of this.tools) {
            server.registerTool(tool.name, tool.config, async (args: any) => tool.instance.execute(args));
        }

        return server;
    }

    /**
     * Starts a periodic timer that closes and removes Streamable HTTP sessions that have been
     * idle for longer than {@link SESSION_TIMEOUT_MINUTES}.
     */
    /**
     * 启动会话超时检查定时器
     *
     * 启动一个周期性定时器，定期检查所有 Streamable HTTP 会话。
     * 如果某个会话的空闲时间超过 SESSION_TIMEOUT_MINUTES（默认 60 分钟），
     * 则关闭该会话并释放资源。这有助于防止资源泄漏和无效会话堆积。
     *
     * 检查间隔为超时时间的一半，以确保及时清理过期会话。
     */
    private startSessionTimeoutChecker(): void {
        const timeoutMs = PenpotMcpServer.SESSION_TIMEOUT_MINUTES * 60 * 1000;
        const checkIntervalMs = timeoutMs / 2;
        this.sessionTimeoutInterval = setInterval(() => {
            this.logger.info("Checking for stale sessions...");
            const now = Date.now();
            let removed = 0;
            for (const session of Object.values(this.streamableTransports)) {
                if (now - session.lastActiveTime > timeoutMs) {
                    session.transport.close();
                    removed++;
                }
            }
            this.logger.info(
                `Removed ${removed} stale session(s); total sessions remaining: ${Object.keys(this.streamableTransports).length}`
            );
        }, checkIntervalMs);
    }

    /**
     * 设置 HTTP 端点
     *
     * 配置 Express 应用的所有 HTTP 端点，包括：
     *
     * 1. /mcp (GET/POST) - 现代 Streamable HTTP 端点
     *    - 新会话：创建新的 McpServer 实例和传输层
     *    - 已有会话：复用已存储的传输层和用户令牌
     *    - 支持会话管理和用户身份追踪
     *
     * 2. /sse (GET) - 传统 SSE 端点
     *    - 建立服务器推送事件连接
     *    - 用于与传统 MCP 客户端兼容
     *
     * 3. /messages (POST) - SSE 消息端点
     *    - 处理来自已建立 SSE 会话的客户端消息
     *    - 通过 sessionId 关联到对应的传输层
     *
     * 每个请求都会通过 sessionContext.run() 设置会话上下文，
     * 使工具能够访问当前请求的用户令牌信息。
     */
    private setupHttpEndpoints(): void {
        /**
         * Modern Streamable HTTP connection endpoint.
         *
         * New sessions are created on initialize requests (no mcp-session-id header).
         * Subsequent requests for an existing session are routed to the stored transport,
         * with the session context populated from the stored userToken.
         */
        this.app.all("/mcp", async (req: any, res: any) => {
            const sessionId = req.headers["mcp-session-id"] as string | undefined;
            let userToken: string | undefined = undefined;
            let transport: StreamableHTTPServerTransport;

            // obtain transport and user token for the session, either from an existing session or by creating a new one
            if (sessionId && this.streamableTransports[sessionId]) {
                // existing session: reuse stored transport and token
                const session = this.streamableTransports[sessionId];
                transport = session.transport;
                userToken = session.userToken;
                session.lastActiveTime = Date.now();
                this.logger.info(
                    `Received request for existing session with id=${sessionId}; userToken=${session.userToken}`
                );
            } else {
                // new session: create a fresh McpServer and transport
                userToken = req.query.userToken as string | undefined;
                this.logger.info(`Received new session request; userToken=${userToken}`);
                const { randomUUID } = await import("node:crypto");
                const server = this.createMcpServer();
                transport = new StreamableHTTPServerTransport({
                    sessionIdGenerator: () => randomUUID(),
                    onsessioninitialized: (id) => {
                        this.streamableTransports[id] = new StreamableSession(transport, userToken, Date.now());
                        this.logger.info(
                            `Session initialized with id=${id} for userToken=${userToken}; total sessions: ${Object.keys(this.streamableTransports).length}`
                        );
                    },
                });
                transport.onclose = () => {
                    if (transport.sessionId) {
                        this.logger.info(`Closing session with id=${transport.sessionId} for userToken=${userToken}`);
                        delete this.streamableTransports[transport.sessionId];
                    }
                };
                await server.connect(transport);
            }

            // handle the request
            await this.sessionContext.run({ userToken }, async () => {
                await transport.handleRequest(req, res, req.body);
            });
        });

        /**
         * Legacy SSE connection endpoint.
         */
        this.app.get("/sse", async (req: any, res: any) => {
            const userToken = req.query.userToken as string | undefined;

            await this.sessionContext.run({ userToken }, async () => {
                const transport = new SSEServerTransport("/messages", res);
                this.sseTransports[transport.sessionId] = { transport, userToken };

                const server = this.createMcpServer();
                await server.connect(transport);
                res.on("close", () => {
                    delete this.sseTransports[transport.sessionId];
                    server.close();
                });
            });
        });

        /**
         * SSE message POST endpoint (using previously established session)
         */
        this.app.post("/messages", async (req: any, res: any) => {
            const sessionId = req.query.sessionId as string;
            const session = this.sseTransports[sessionId];

            if (session) {
                await this.sessionContext.run({ userToken: session.userToken }, async () => {
                    await session.transport.handlePostMessage(req, res, req.body);
                });
            } else {
                res.status(400).send("No transport found for sessionId");
            }
        });
    }

    /**
     * 启动 MCP 服务器
     *
     * 执行以下操作：
     * 1. 创建 Express 应用
     * 2. 设置 HTTP 端点（/mcp, /sse, /messages）
     * 3. 启动 HTTP 服务器监听
     * 4. 启动 REPL 服务器
     * 5. 启动会话超时检查器
     *
     * @returns Promise<void> - 服务器启动完成后解析
     */
    async start(): Promise<void> {
        const { default: express } = await import("express");
        this.app = express();
        this.app.use(express.json());

        this.setupHttpEndpoints();

        return new Promise((resolve) => {
            this.app.listen(this.port, this.host, async () => {
                this.logger.info(`Multi-user mode: ${this.isMultiUserMode()}`);
                this.logger.info(`Remote mode: ${this.isRemoteMode()}`);
                this.logger.info(`Modern Streamable HTTP endpoint: http://${this.host}:${this.port}/mcp`);
                this.logger.info(`Legacy SSE endpoint: http://${this.host}:${this.port}/sse`);
                this.logger.info(`WebSocket server URL: ws://${this.host}:${this.webSocketPort}`);

                // start the REPL server and session timeout checker
                await this.replServer.start();
                this.startSessionTimeoutChecker();

                resolve();
            });
        });
    }

    /**
     * Stops the MCP server and associated services.
     *
     * Gracefully shuts down the REPL server and other components.
     */
    /**
     * 停止 MCP 服务器及关联服务
     *
     * 执行优雅关闭：
     * 1. 清除会话超时检查器
     * 2. 停止 REPL 服务器
     * 3. 关闭所有传输连接
     */
    public async stop(): Promise<void> {
        this.logger.info("Stopping Penpot MCP Server...");
        clearInterval(this.sessionTimeoutInterval);
        await this.replServer.stop();
        this.logger.info("Penpot MCP Server stopped");
    }
}
