/**
 * Base class for plugin tasks that are sent over WebSocket.
 *
 * Each task defines a specific operation for the plugin to execute
 * along with strongly-typed parameters.
 *
 * @template TParams - The strongly-typed parameters for this task
 */
/**
 * =============================================================================
 * 插件任务基类模块 (Plugin Task Base Class)
 * =============================================================================
 *
 * 【模块概述】
 * 本模块定义了所有插件任务的基类，通过 WebSocket 发送任务到插件执行：
 * - 统一的任务 ID 生成和请求/响应关联
 * - Promise 化的任务结果获取
 * - 任务序列化以便网络传输
 *
 * 【核心概念】
 * 1. UUID - 使用 crypto.randomUUID 生成唯一任务标识
 * 2. Promise 模式 - 任务结果通过 Promise 异步返回
 * 3. 请求/响应关联 - 通过 ID 将响应与原始请求匹配
 *
 * 【依赖关系】
 * - @penpot/mcp-common - 共享的类型定义（PluginTaskRequest, PluginTaskResult）
 * - crypto - Node.js 加密模块（用于 UUID 生成）
 *
 * =============================================================================
 */
import { randomUUID } from "crypto";

/**
 * Base class for plugin tasks that are sent over WebSocket.
 *
 * Each task defines a specific operation for the plugin to execute
 * along with strongly-typed parameters and request/response correlation.
 *
 * @template TParams - The strongly-typed parameters for this task
 * @template TResult - The expected result type from task execution
 */
export abstract class PluginTask<TParams = any, TResult extends PluginTaskResult<any> = PluginTaskResult<any>> {
    /**
     * Unique identifier for request/response correlation.
     */
    public readonly id: string;

    /**
     * The name of the task to execute on the plugin side.
     */
    public readonly task: string;

    /**
     * The parameters for this task execution.
     */
    public readonly params: TParams;

    /**
     * Promise that resolves when the task execution completes.
     */
    private readonly result: Promise<TResult>;

    /**
     * Resolver function for the result promise.
     */
    private resolveResult?: (result: TResult) => void;

    /**
     * Rejector function for the result promise.
     */
    private rejectResult?: (error: Error) => void;

    /**
     * Creates a new plugin task instance.
     *
     * @param task - The name of the task to execute
     * @param params - The parameters for task execution
     */
    /**
     * 创建插件任务实例
     *
     * @param task - 任务名称
     * @param params - 任务参数
     */
    constructor(task: string, params: TParams) {
        this.id = randomUUID();
        this.task = task;
        this.params = params;
        this.result = new Promise<TResult>((resolve, reject) => {
            this.resolveResult = resolve;
            this.rejectResult = reject;
        });
    }

    /**
     * Gets the result promise for this task.
     *
     * @returns Promise that resolves when the task execution completes
     */
    /**
     * 获取任务结果 Promise
     *
     * @returns Promise<TResult> - 任务结果 Promise
     * @throws Error 如果结果 Promise 未初始化
     */
    getResultPromise(): Promise<TResult> {
        if (!this.result) {
            throw new Error("Result promise not initialized");
        }
        return this.result;
    }

    /**
     * Resolves the task with the given result.
     *
     * This method should be called when a task response is received
     * from the plugin with matching ID.
     *
     * @param result - The task execution result
     */
    /**
     * 使用结果解析任务 Promise
     *
     * 当收到插件返回的成功响应时调用此方法。
     *
     * @param result - 任务执行结果
     * @throws Error 如果结果解析器未初始化
     */
    resolveWithResult(result: TResult): void {
        if (!this.resolveResult) {
            throw new Error("Result promise not initialized");
        }
        this.resolveResult(result);
    }

    /**
     * Rejects the task with the given error.
     *
     * This method should be called when task execution fails
     * or times out.
     *
     * @param error - The error that occurred during task execution
     */
    /**
     * 使用错误拒绝任务 Promise
     *
     * 当任务执行失败或超时时调用此方法。
     *
     * @param error - 执行过程中发生的错误
     * @throws Error 如果错误拒绝器未初始化
     */
    rejectWithError(error: Error): void {
        if (!this.rejectResult) {
            throw new Error("Result promise not initialized");
        }
        this.rejectResult(error);
    }

    /**
     * Serializes the task to a request message for WebSocket transmission.
     *
     * @returns The request message containing ID, task name, and parameters
     */
    /**
     * 将任务序列化为请求消息
     *
     * @returns PluginTaskRequest - 包含 ID、任务名称和参数的请求对象
     */
    toRequest(): PluginTaskRequest {
        return {
            id: this.id,
            task: this.task,
            params: this.params,
        };
    }
}
