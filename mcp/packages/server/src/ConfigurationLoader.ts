/**
 * =============================================================================
 * 配置加载器模块 (Configuration Loader)
 * =============================================================================
 *
 * 【模块概述】
 * 本模块负责从文件系统加载 MCP 服务器的配置和指令文件：
 * - 初始指令（initial_instructions.md）- 包含 Penpot 高级概述
 * - 基础指令（base_instructions.md）- 客户端连接时提供的说明
 *
 * 【核心概念】
 * 1. 指令模板 - 包含 $api_types 占位符，会被 API 类型名称替换
 * 2. 工作目录 - 配置路径基于当前工作目录解析
 *
 * 【依赖关系】
 * - fs - 文件系统模块
 * - path - 路径处理模块
 * - logger - 日志记录器
 *
 * =============================================================================
 */
import { join } from "path";
import { createLogger } from "./logger.js";

/**
 * Configuration loader for prompts and server settings.
 */
export class ConfigurationLoader {
    private readonly logger = createLogger("ConfigurationLoader");
    private readonly baseDir: string;
    private readonly initialInstructions: string;
    private readonly baseInstructions: string;

    /**
     * 创建配置加载器实例
     *
     * @param baseDir - 基础目录，用于解析配置文件路径
     * @throws Error 如果配置文件不存在
     */
    constructor(baseDir: string) {
        this.baseDir = baseDir;
        this.initialInstructions = this.loadFileContent(join(this.baseDir, "data", "initial_instructions.md"));
        this.baseInstructions = this.loadFileContent(join(this.baseDir, "data", "base_instructions.md"));
    }

    private loadFileContent(filePath: string): string {
        if (!existsSync(filePath)) {
            throw new Error(`Configuration file not found at ${filePath}`);
        }
        return readFileSync(filePath, "utf8");
    }

    /**
     * 获取初始指令文本
     *
     * 包含 Penpot 高级概述的指令内容，其中的 $api_types 占位符
     * 会被 API 类型名称替换。
     *
     * @returns 初始指令字符串
     */
    public getInitialInstructions(): string {
        return this.initialInstructions;
    }

    /**
     * 获取基础指令文本
     *
     * 客户端连接 MCP 服务器时提供的基础说明。
     *
     * @returns 基础指令字符串
     */
    public getBaseInstructions(): string {
        return this.baseInstructions;
    }
}
