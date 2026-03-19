/**
 * =============================================================================
 * MCP 工具基类模块 (MCP Tool Base Class)
 * =============================================================================
 *
 * 【模块概述】
 * 本模块定义了所有 MCP 工具的基类，提供：
 * - 自动参数验证和类型安全
 * - 统一的执行框架和错误处理
 * - 日志记录和调试支持
 * - Schema 自动生成
 *
 * 【核心概念】
 * 1. Zod - TypeScript 优先的模式验证库
 * 2. ToolResponse - MCP 工具响应基类
 * 3. AsyncLocalStorage - 请求级会话上下文存储
 * 4. 工具执行计数器 - 用于追踪工具执行的唯一 ID
 *
 * 【依赖关系】
 * - zod - 参数模式验证
 * - ToolResponse - 工具响应类型
 * - PenpotMcpServer - MCP 服务器引用
 * - logger - 日志记录器
 *
 * =============================================================================
 */
import "reflect-metadata";
import { TextResponse, ToolResponse } from "./ToolResponse";
import type { PenpotMcpServer, SessionContext } from "./PenpotMcpServer";
import { createLogger } from "./logger";

/**
 * An empty arguments class for tools that do not require any parameters.
 */
export class EmptyToolArgs {
    static schema = {};
}

/**
 * Base class for type-safe tools with automatic schema generation and validation.
 *
 * This class provides type safety through automatic validation and strongly-typed
 * protected methods. All tools should extend this class.
 *
 * @template TArgs - The strongly-typed arguments class for this tool
 */
export abstract class Tool<TArgs extends object> {
    private readonly logger = createLogger("Tool");

    /** monotonically increasing counter for unique tool execution IDs */
    private static executionCounter = 0;

    /**
     * 创建工具实例
     *
     * @param mcpServer - MCP 服务器引用，用于获取会话上下文
     * @param inputSchema - Zod 参数验证 schema
     */
    protected constructor(
        protected mcpServer: PenpotMcpServer,
        private inputSchema: z.ZodRawShape
    ) {}

    /**
     * Executes the tool with automatic validation and type safety.
     *
     * This method handles the unknown args from the MCP protocol,
     * delegating to the type-safe implementation.
     */
    /**
     * 执行工具（统一入口）
     *
     * 提供工具执行的统一框架：
     * - 生成唯一执行 ID 用于追踪
     * - 记录开始和结束日志
     * - 调用子类的 executeCore 实现
     * - 捕获并处理执行中的错误
     *
     * @param args - 未知类型的工具参数（来自 MCP 协议）
     * @returns Promise<ToolResponse> - 工具执行结果
     */
    async execute(args: unknown): Promise<ToolResponse> {
        const executionId = ++Tool.executionCounter;
        try {
            let argsInstance: TArgs = args as TArgs;
            this.logger.info("Tool execution #%d starting: %s", executionId, this.getToolName());
            if (this.logger.isLevelEnabled("debug")) {
                this.logger.debug("Tool execution #%d arguments: %s", executionId, this.formatArgs(argsInstance));
            }

            // execute the actual tool logic
            let result = await this.executeCore(argsInstance);

            this.logger.info("Tool execution #%d complete: %s", executionId, this.getToolName());
            return result;
        } catch (error) {
            this.logger.error("Tool execution #%d failed: %s; error: %s", executionId, this.getToolName(), error);
            return new TextResponse(`Tool execution failed: ${String(error)}`);
        }
    }

    /**
     * Formats tool arguments for readable logging.
     *
     * Multi-line strings are preserved with proper indentation.
     */
    protected formatArgs(args: TArgs): string {
        const formatted: string[] = [];

        for (const [key, value] of Object.entries(args)) {
            if (typeof value === "string" && value.includes("\n")) {
                // multi-line string - preserve formatting with indentation
                const indentedValue = value
                    .split("\n")
                    .map((line, index) => (index === 0 ? line : "    " + line))
                    .join("\n");
                formatted.push(`  ${key}: ${indentedValue}`);
            } else if (typeof value === "string") {
                // single-line string
                formatted.push(`  ${key}: "${value}"`);
            } else if (value === null || value === undefined) {
                formatted.push(`  ${key}: ${value}`);
            } else {
                // other types (numbers, booleans, objects, arrays)
                const stringified = JSON.stringify(value, null, 2);
                if (stringified.includes("\n")) {
                    // multi-line JSON - indent it
                    const indented = stringified
                        .split("\n")
                        .map((line, index) => (index === 0 ? line : "    " + line))
                        .join("\n");
                    formatted.push(`  ${key}: ${indented}`);
                } else {
                    formatted.push(`  ${key}: ${stringified}`);
                }
            }
        }

        return formatted.length > 0 ? "\n" + formatted.join("\n") : "{}";
    }

    /**
     * Retrieves the current session context.
     *
     * @returns The session context for the current request, or undefined if not in a request context
     */
    protected getSessionContext(): SessionContext | undefined {
        return this.mcpServer.getSessionContext();
    }

    public getInputSchema() {
        return this.inputSchema;
    }

    /**
     * Returns the tool's unique name.
     */
    public abstract getToolName(): string;

    /**
     * Returns the tool's description.
     */
    public abstract getToolDescription(): string;

    /**
     * Executes the tool's core logic.
     *
     * @param args - The (typed) tool arguments
     */
    protected abstract executeCore(args: TArgs): Promise<ToolResponse>;
}
