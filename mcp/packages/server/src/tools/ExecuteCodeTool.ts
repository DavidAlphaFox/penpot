/**
 * =============================================================================
 * 执行代码工具模块 (Execute Code Tool)
 * =============================================================================
 *
 * 【模块概述】
 * 本工具允许 AI 客户端在 Penpot 插件上下文中执行任意 JavaScript 代码：
 * - 提供对 Penpot API 的完全访问
 * - 支持通过 storage 对象存储和检索数据
 * - 支持 console 对象用于调试输出
 * - 返回代码的执行结果或错误信息
 *
 * 【核心概念】
 * 1. 插件上下文 - 代码在 Penpot 插件环境中执行，拥有完整的 Penpot API 访问权限
 * 2. penpot 对象 - Penpot API 的主要入口点
 * 3. penpotUtils 对象 - 工具函数集合
 * 4. storage 对象 - 跨调用持久化存储的键值空间
 * 5. console 对象 - 代码执行过程中的日志输出
 *
 * 【依赖关系】
 * - Tool - 工具基类
 * - ExecuteCodePluginTask - 代码执行任务
 * - @penpot/mcp-common - 共享类型定义
 *
 * =============================================================================
 */
import { Tool } from "../Tool";
import type { ToolResponse } from "../ToolResponse";
import { TextResponse } from "../ToolResponse";
import "reflect-metadata";
import { PenpotMcpServer } from "../PenpotMcpServer";
import { ExecuteCodePluginTask } from "../tasks/ExecuteCodePluginTask";
import { ExecuteCodeTaskParams } from "@penpot/mcp-common";

/**
 * Arguments class for ExecuteCodeTool
 */
export class ExecuteCodeArgs {
    static schema = {
        code: z
            .string()
            .min(1, "Code cannot be empty")
            .describe("The JavaScript code to execute in the plugin context."),
    };

    /**
     * The JavaScript code to execute in the plugin context.
     */
    code!: string;
}

/**
 * Tool for executing JavaScript code in the Penpot plugin context
 */
export class ExecuteCodeTool extends Tool<ExecuteCodeArgs> {
    /**
     * Creates a new ExecuteCode tool instance.
     *
     * @param mcpServer - The MCP server instance
     */
    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, ExecuteCodeArgs.schema);
    }

    public getToolName(): string {
        return "execute_code";
    }

    public getToolDescription(): string {
        return (
            "Executes JavaScript code in the Penpot plugin context.\n" +
            "IMPORTANT: Before using this tool, make sure you have read the 'Penpot High-Level Overview' and know " +
            "which Penpot API functionality is necessary and how to use it.\n" +
            "You have access two main objects: `penpot` (the Penpot API, of type `Penpot`), `penpotUtils`, " +
            "and `storage`.\n" +
            "`storage` is an object in which arbitrary data can be stored, simply by adding a new attribute; " +
            "stored attributes can be referenced in future calls to this tool, so any intermediate results that " +
            "could come in handy later should be stored in `storage` instead of just a fleeting variable; " +
            "you can also store functions and thus build up a library).\n" +
            "Think of the code being executed as the body of a function: " +
            "The tool call returns whatever you return in the applicable `return` statement, if any. " +
            "You can return arbitrary JS objects; no need to apply JSON.stringify.\n" +
            "If an exception occurs, the exception's message will be returned to you.\n" +
            "Any output that you generate via the `console` object will be returned to you separately; so you may use it " +
            "to track what your code is doing, but you should *only* do so only if there is an ACTUAL NEED for this! " +
            "VERY IMPORTANT: Don't use logging prematurely! NEVER log the data you are returning, as you will otherwise receive it twice!\n" +
            "VERY IMPORTANT: In general, try a simple approach first, and only if it fails, try more complex code that involves " +
            "handling different cases (in particular error cases) and that applies logging."
        );
    }

    protected async executeCore(args: ExecuteCodeArgs): Promise<ToolResponse> {
        const taskParams: ExecuteCodeTaskParams = { code: args.code };
        const task = new ExecuteCodePluginTask(taskParams);
        const result = await this.mcpServer.pluginBridge.executePluginTask(task);

        if (result.data !== undefined) {
            return new TextResponse(JSON.stringify(result.data, null, 2));
        } else {
            return new TextResponse("Code executed successfully with no return value.");
        }
    }
}
