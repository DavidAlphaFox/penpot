/**
 * =============================================================================
 * 插件桥接器模块 (Plugin Bridge)
 * =============================================================================
 *
 * 【模块概述】
 * 本模块负责管理 MCP 服务器与 Penpot 插件之间的 WebSocket 通信：
 * - 维护插件连接池
 * - 处理请求/响应关联
 * - 管理任务超时
 * - 单用户/多用户模式支持
 *
 * 【核心概念】
 * 1. WebSocket - 双向通信协议，用于服务器与插件实时交互
 * 2. 任务队列 - 存储待处理的插件任务及其 Promise 解析器
 * 3. 超时管理 - 防止任务无限等待
 * 4. 客户端连接映射 - 按 WebSocket 和 userToken 索引连接
 *
 * 【依赖关系】
 * - ws - Node.js WebSocket 实现
 * - PluginTask - 插件任务基类
 * - PluginTaskResponse - 插件任务响应类型
 * - PenpotMcpServer - MCP 服务器引用
 *
 * =============================================================================
 */
import * as http from "http";
import { PluginTask } from "./PluginTask";
import { PluginTaskResponse, PluginTaskResult } from "@penpot/mcp-common";
import { createLogger } from "./logger";
import type { PenpotMcpServer } from "./PenpotMcpServer";

const KEEP_ALIVE_TIME = 30000; // 30 seconds

/**
 * ClientConnection - 客户端连接接口
 *
 * 存储单个插件客户端的 WebSocket 连接和用户令牌信息。
 */
interface ClientConnection {
    /**
     * WebSocket 连接实例
     */
    socket: WebSocket;
    /**
     * 用户认证令牌（多用户模式）
     */
    userToken: string | null;
}

/**
 * Manages WebSocket connections to Penpot plugin instances and handles plugin tasks
 * over these connections.
 */
export class PluginBridge {
    private readonly logger = createLogger("PluginBridge");
    private readonly wsServer: WebSocketServer;
    private readonly connectedClients: Map<WebSocket, ClientConnection> = new Map();
    private readonly clientsByToken: Map<string, ClientConnection> = new Map();
    private readonly pendingTasks: Map<string, PluginTask<any, any>> = new Map();
    private readonly taskTimeouts: Map<string, NodeJS.Timeout> = new Map();

    /**
     * 创建插件桥接器实例
     *
     * @param mcpServer - MCP 服务器引用
     * @param port - WebSocket 服务器监听端口
     * @param taskTimeoutSecs - 任务超时时间（秒，默认 30）
     */
    constructor(
        public readonly mcpServer: PenpotMcpServer,
        private port: number,
        private taskTimeoutSecs: number = 30
    ) {
        this.wsServer = new WebSocketServer({ port: port });
        this.setupWebSocketHandlers();
    }

    /**
     * Sets up WebSocket connection handlers for plugin communication.
     *
     * Manages client connections and provides bidirectional communication
     * channel between the MCP mcpServer and Penpot plugin instances.
     */
    private setupWebSocketHandlers(): void {
        let interval: NodeJS.Timeout | undefined;

        this.wsServer.on("connection", (ws: WebSocket, request: http.IncomingMessage) => {
            // extract userToken from query parameters
            const url = new URL(request.url!, `ws://${request.headers.host}`);
            const userToken = url.searchParams.get("userToken");

            // require userToken if running in multi-user mode
            if (this.mcpServer.isMultiUserMode() && !userToken) {
                this.logger.warn("Connection attempt without userToken in multi-user mode - rejecting");
                ws.close(1008, "Missing userToken parameter");
                return;
            }

            if (userToken) {
                this.logger.info("New WebSocket connection established (token provided)");
            } else {
                this.logger.info("New WebSocket connection established");
            }

            // register the client connection with both indexes
            const connection: ClientConnection = { socket: ws, userToken };
            this.connectedClients.set(ws, connection);
            if (userToken) {
                // ensure only one connection per userToken
                if (this.clientsByToken.has(userToken)) {
                    this.logger.warn("Duplicate connection for given user token; rejecting new connection");
                    ws.close(1008, "Duplicate connection for given user token; close previous connection first.");
                }

                this.clientsByToken.set(userToken, connection);
            }

            ws.on("message", (data: Buffer) => {
                this.logger.debug("Received WebSocket message: %s", data.toString());
                try {
                    const response: PluginTaskResponse<any> = JSON.parse(data.toString());
                    this.handlePluginTaskResponse(response);
                } catch (error) {
                    this.logger.error(error, "Failure while processing WebSocket message");
                }
            });

            ws.on("close", () => {
                this.logger.info("WebSocket connection closed");
                const connection = this.connectedClients.get(ws);
                this.connectedClients.delete(ws);
                if (connection?.userToken) {
                    this.clientsByToken.delete(connection.userToken);
                }
                if (interval) {
                    clearInterval(interval);
                }
            });

            ws.on("error", (error) => {
                this.logger.error(error, "WebSocket connection error");
                const connection = this.connectedClients.get(ws);
                this.connectedClients.delete(ws);
                if (connection?.userToken) {
                    this.clientsByToken.delete(connection.userToken);
                }
                if (interval) {
                    clearInterval(interval);
                }
            });

            interval = setInterval(() => {
                ws?.ping();
            }, KEEP_ALIVE_TIME);
        });

        this.logger.info("WebSocket mcpServer started on port %d", this.port);
    }

    /**
     * Handles responses from the plugin for completed tasks.
     *
     * Finds the pending task by ID and resolves or rejects its promise
     * based on the execution result.
     *
     * @param response - The plugin task response containing ID and result
     */
    private handlePluginTaskResponse(response: PluginTaskResponse<any>): void {
        const task = this.pendingTasks.get(response.id);
        if (!task) {
            this.logger.info(`Received response for unknown task ID: ${response.id}`);
            return;
        }

        // Clear the timeout and remove the task from pending tasks
        const timeoutHandle = this.taskTimeouts.get(response.id);
        if (timeoutHandle) {
            clearTimeout(timeoutHandle);
            this.taskTimeouts.delete(response.id);
        }
        this.pendingTasks.delete(response.id);

        // Resolve or reject the task's promise based on the result
        if (response.success) {
            task.resolveWithResult({ data: response.data });
        } else {
            const error = new Error(response.error || "Task execution failed (details not provided)");
            task.rejectWithError(error);
        }

        this.logger.info(`Task ${response.id} completed: success=${response.success}`);
    }

    /**
     * Determines the client connection to use for executing a task.
     *
     * In single-user mode, returns the single connected client.
     * In multi-user mode, returns the client matching the session's userToken.
     *
     * @returns The client connection to use
     * @throws Error if no suitable connection is found or if configuration is invalid
     */
    private getClientConnection(): ClientConnection {
        if (this.mcpServer.isMultiUserMode()) {
            const sessionContext = this.mcpServer.getSessionContext();
            if (!sessionContext?.userToken) {
                throw new Error("No userToken found in session context. Multi-user mode requires authentication.");
            }

            const connection = this.clientsByToken.get(sessionContext.userToken);
            if (!connection) {
                throw new Error(
                    `No plugin instance connected for user token. Please ensure the plugin is running and connected with the correct token.`
                );
            }

            return connection;
        } else {
            // single-user mode: return the single connected client
            if (this.connectedClients.size === 0) {
                throw new Error(
                    `No Penpot plugin instances are currently connected. Please ensure the plugin is running and connected.`
                );
            }
            if (this.connectedClients.size > 1) {
                throw new Error(
                    `Multiple (${this.connectedClients.size}) Penpot MCP Plugin instances are connected. ` +
                        `Ask the user to ensure that only one instance is connected at a time.`
                );
            }

            // return the first (and only) connection
            const connection = this.connectedClients.values().next().value;
            return <ClientConnection>connection;
        }
    }

    /**
     * Executes a plugin task by sending it to connected clients.
     *
     * Registers the task for result correlation and returns a promise
     * that resolves when the plugin responds with the execution result.
     *
     * @param task - The plugin task to execute
     * @throws Error if no plugin instances are connected or available
     */
    /**
     * 执行插件任务
     *
     * 将任务发送到已连接的插件客户端执行，并返回执行结果：
     * 1. 根据模式（单用户/多用户）获取合适的客户端连接
     * 2. 将任务加入待处理队列
     * 3. 通过 WebSocket 发送任务到插件
     * 4. 设置超时处理
     * 5. 返回 Promise，等待插件响应结果
     *
     * @param task - 要执行的插件任务
     * @returns Promise<TResult> - 任务执行结果
     * @throws Error 如果没有插件连接或连接不可用
     */
    public async executePluginTask<TResult extends PluginTaskResult<any>>(
        task: PluginTask<any, TResult>
    ): Promise<TResult> {
        // get the appropriate client connection based on mode
        const connection = this.getClientConnection();

        // register the task for result correlation
        this.pendingTasks.set(task.id, task);

        // send task to the selected client
        const requestMessage = JSON.stringify(task.toRequest());
        if (connection.socket.readyState !== 1) {
            // WebSocket is not open
            this.pendingTasks.delete(task.id);
            throw new Error(`Plugin instance is disconnected. Task could not be sent.`);
        }

        connection.socket.send(requestMessage);

        // Set up a timeout to reject the task if no response is received
        const timeoutHandle = setTimeout(() => {
            const pendingTask = this.pendingTasks.get(task.id);
            if (pendingTask) {
                this.pendingTasks.delete(task.id);
                this.taskTimeouts.delete(task.id);
                pendingTask.rejectWithError(
                    new Error(`Task ${task.id} timed out after ${this.taskTimeoutSecs} seconds`)
                );
            }
        }, this.taskTimeoutSecs * 1000);

        this.taskTimeouts.set(task.id, timeoutHandle);
        this.logger.info(`Sent task ${task.id} to connected client`);

        return await task.getResultPromise();
    }
}
