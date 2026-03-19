/**
 * =============================================================================
 * REPL 开发调试服务器模块 (REPL Development Server)
 * =============================================================================
 *
 * 【模块概述】
 * 本模块提供了一个基于 Web 的 REPL（Read-Eval-Print-Loop）界面，
 * 允许开发者通过浏览器输入 JavaScript 代码并通过 PluginBridge 执行。
 * 主要用于开发和调试 Penpot 插件 API 调用。
 *
 * 【核心概念】
 * 1. Express - 轻量级 Web 服务器框架
 * 2. REPL 界面 - 交互式代码执行界面，包含命令历史和日志显示
 * 3. ExecuteCodePluginTask - 通过插件执行代码的任务
 *
 * 【依赖关系】
 * - express - Web 服务器框架
 * - PluginBridge - 插件桥接器，用于执行代码任务
 * - ExecuteCodePluginTask - 代码执行任务类
 *
 * =============================================================================
 */
import path from "path";
import { fileURLToPath } from "url";
import { PluginBridge } from "./PluginBridge";
import { ExecuteCodePluginTask } from "./tasks/ExecuteCodePluginTask";
import { createLogger } from "./logger";

/**
 * Web-based REPL server for executing code through the PluginBridge.
 *
 * Provides a REPL-style HTML interface that allows users to input
 * JavaScript code and execute it via ExecuteCodePluginTask instances.
 * The interface maintains command history, displays logs in &lt;pre&gt; tags,
 * and shows results in visually separated blocks.
 */
export class ReplServer {
    private readonly logger = createLogger("ReplServer");
    private readonly app: express.Application;
    private readonly port: number;
    private server: any;

    /**
     * 创建 REPL 服务器实例
     *
     * @param pluginBridge - 插件桥接器实例，用于执行代码任务
     * @param port - REPL 服务器监听端口（默认：4403）
     */
    constructor(
        private readonly pluginBridge: PluginBridge,
        port: number = 4403
    ) {
        this.port = port;
        this.app = express();
        this.setupMiddleware();
        this.setupRoutes();
    }

    /**
     * Sets up Express middleware for request parsing and static content.
     */
    private setupMiddleware(): void {
        this.app.use(express.json());
    }

    /**
     * Sets up HTTP routes for the REPL interface and API endpoints.
     */
    private setupRoutes(): void {
        // serve the main REPL interface
        this.app.get("/", (req, res) => {
            const __filename = fileURLToPath(import.meta.url);
            const __dirname = path.dirname(__filename);
            const htmlPath = path.join(__dirname, "static", "repl.html");
            res.sendFile(htmlPath);
        });

        // API endpoint for executing code
        this.app.post("/execute", async (req, res) => {
            try {
                const { code } = req.body;

                if (!code || typeof code !== "string") {
                    return res.status(400).json({
                        error: "Code parameter is required and must be a string",
                    });
                }

                const task = new ExecuteCodePluginTask({ code });
                const result = await this.pluginBridge.executePluginTask(task);

                // extract the result member from ExecuteCodeTaskResultData
                const executeResult = result.data?.result;

                res.json({
                    success: true,
                    result: executeResult,
                    log: result.data?.log || "",
                });
            } catch (error) {
                this.logger.error(error, "Failed to execute code in REPL");
                res.status(500).json({
                    error: error instanceof Error ? error.message : "Unknown error occurred",
                });
            }
        });
    }

    /**
     * Starts the REPL web server.
     *
     * Begins listening on the configured port and logs server startup information.
     */
    /**
     * 启动 REPL Web 服务器
     *
     * 在指定端口启动 Express 服务器，监听 REPL 界面和 API 端点请求。
     *
     * @returns Promise<void> - 服务器启动完成后解析
     */
    public async start(): Promise<void> {
        return new Promise((resolve) => {
            this.server = this.app.listen(this.port, () => {
                this.logger.info(`REPL server started on port ${this.port}`);
                this.logger.info(`REPL interface URL: http://${this.pluginBridge.mcpServer.host}:${this.port}`);
                resolve();
            });
        });
    }

    /**
     * Stops the REPL web server.
     */
    /**
     * 停止 REPL Web 服务器
     *
     * 关闭 Express 服务器，停止接受新的连接请求。
     *
     * @returns Promise<void> - 服务器关闭完成后解析
     */
    public async stop(): Promise<void> {
        if (this.server) {
            return new Promise((resolve) => {
                this.server.close(() => {
                    this.logger.info("REPL server stopped");
                    resolve();
                });
            });
        }
    }
}
