/**
 * =============================================================================
 * MCP 通用类型定义模块 (MCP Common Types)
 * =============================================================================
 *
 * 【模块概述】
 * 本模块定义了 MCP 服务器和插件之间共享的类型定义：
 * - 插件任务请求/响应结构
 * - 执行代码任务的参数和结果类型
 *
 * 【核心概念】
 * 1. PluginTaskRequest - 从服务器发送到插件的请求消息
 * 2. PluginTaskResponse - 从插件返回到服务器的响应消息
 * 3. PluginTaskResult - 任务执行结果的包装器
 * 4. ExecuteCodeTaskParams - 执行代码任务的参数（包含 JavaScript 代码）
 * 5. ExecuteCodeTaskResultData - 执行代码任务的结果数据（包含返回值和控制台日志）
 *
 * 【依赖关系】
 * - 无外部依赖，仅为类型定义
 *
 * =============================================================================
 */
 *
 * Contains the outcome status of a task and any additional result data.
 */
export interface PluginTaskResult<T> {
    /**
     * Optional result data from the task execution.
     */
    data?: T;
}

/**
 * Request message sent from server to plugin.
 *
 * Contains a unique identifier, task name, and parameters for execution.
 */
export interface PluginTaskRequest {
    /**
     * Unique identifier for request/response correlation.
     */
    id: string;

    /**
     * The name of the task to execute.
     */
    task: string;

    /**
     * The parameters for task execution.
     */
    params: any;
}

/**
 * Response message sent from plugin back to server.
 *
 * Contains the original request ID and the execution result.
 */
export interface PluginTaskResponse<T> {
    /**
     * Unique identifier matching the original request.
     */
    id: string;

    /**
     * Whether the task completed successfully.
     */
    success: boolean;

    /**
     * Optional error message if the task failed.
     */
    error?: string;

    /**
     * The result of the task execution.
     */
    data?: T;
}

/**
 * Parameters for the executeCode task.
 */
export interface ExecuteCodeTaskParams {
    /**
     * The JavaScript code to be executed.
     */
    code: string;
}

/**
 * Result data for the executeCode task.
 */
export interface ExecuteCodeTaskResultData<T> {
    /**
     * The result of the executed code, if any.
     */
    result: T;

    /**
     * Captured console output during code execution.
     */
    log: string;
}
