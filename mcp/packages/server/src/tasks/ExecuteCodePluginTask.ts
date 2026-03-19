/**
 * =============================================================================
 * 执行代码插件任务模块 (Execute Code Plugin Task)
 * =============================================================================
 *
 * 【模块概述】
 * 本模块定义了在插件上下文中执行 JavaScript 代码的任务类型：
 * - 继承自 PluginTask 基类
 * - 使用 "executeCode" 作为任务名称
 * - 包含要执行的代码作为参数
 *
 * 【核心概念】
 * 1. PluginTask - 插件任务基类，提供 ID 生成和 Promise 化结果
 * 2. ExecuteCodeTaskParams - 包含 code 字段的参数字段
 * 3. ExecuteCodeTaskResultData - 结果数据结构，包含 result 和 log 字段
 *
 * 【依赖关系】
 * - PluginTask - 任务基类
 * - @penpot/mcp-common - 共享类型定义
 *
 * =============================================================================
 */
import { ExecuteCodeTaskParams, ExecuteCodeTaskResultData, PluginTaskResult } from "@penpot/mcp-common";

/**
 * Task for executing JavaScript code in the plugin context.
 *
 * This task instructs the plugin to execute arbitrary JavaScript code
 * and return the result of execution.
 */
export class ExecuteCodePluginTask extends PluginTask<
    ExecuteCodeTaskParams,
    PluginTaskResult<ExecuteCodeTaskResultData<any>>
> {
    /**
     * Creates a new execute code task.
     *
     * @param params - The parameters containing the code to execute
     */
    constructor(params: ExecuteCodeTaskParams) {
        super("executeCode", params);
    }
}
