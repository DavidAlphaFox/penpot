#!/usr/bin/env node

import { PenpotMcpServer } from "./PenpotMcpServer";
import { createLogger, logFilePath } from "./logger";

/**
 * =============================================================================
 * Penpot MCP 服务器入口模块 (Penpot MCP Server Entry Point)
 * =============================================================================
 *
 * 【模块概述】
 * 本模块是 Penpot MCP 服务器的主入口点，负责初始化和启动 MCP 服务器实例。
 * 处理服务器启动过程中的错误，确保进程在收到终止信号时能够优雅地关闭。
 *
 * 【核心概念】
 * 1. MCP Server - Model Context Protocol 服务器，提供 AI 客户端与 Penpot 的集成能力
 * 2. 单用户/多用户模式 - 支持单用户模式（默认）和多用户模式（通过 --multi-user 参数启用）
 * 3. 环境变量配置 - 服务器配置主要通过环境变量进行管理
 *
 * 【依赖关系】
 * - PenpotMcpServer - MCP 服务器核心类
 * - logger - 日志模块，用于记录服务器运行状态
 *
 * =============================================================================
 */

/**
 * 主入口函数 - 初始化并启动 Penpot MCP 服务器
 *
 * 该函数执行以下步骤：
 * 1. 创建日志记录器
 * 2. 解析命令行参数（支持 --multi-user 和 --help）
 * 3. 创建 PenpotMcpServer 实例
 * 4. 启动服务器
 * 5. 设置信号处理器（SIGINT/SIGTERM）以支持优雅关闭
 *
 * @returns Promise<void> - 异步操作，完成后服务器开始监听连接
 *
 * @example
 * ```bash
 * # 启动单用户模式服务器（默认）
 * node dist/index.js
 *
 * # 启动多用户模式服务器
 * node dist/index.js --multi-user
 *
 * # 显示帮助信息
 * node dist/index.js --help
 * ```
 */
async function main(): Promise<void> {
    const logger = createLogger("main");

    // log the file path early so it appears before any potential errors
    logger.info(`Logging to file: ${logFilePath}`);

    try {
        const args = process.argv.slice(2);
        let multiUser = false; // default to single-user mode

        // parse command line arguments
        for (let i = 0; i < args.length; i++) {
            if (args[i] === "--multi-user") {
                multiUser = true;
            } else if (args[i] === "--help" || args[i] === "-h") {
                logger.info("Usage: node dist/index.js [options]");
                logger.info("Options:");
                logger.info("  --multi-user           Enable multi-user mode (default: single-user)");
                logger.info("  --help, -h             Show this help message");
                logger.info("");
                logger.info("Note that configuration is mostly handled through environment variables.");
                logger.info("Refer to the README for more information.");
                process.exit(0);
            }
        }

        const server = new PenpotMcpServer(multiUser);
        await server.start();

        // keep the process alive
        process.on("SIGINT", async () => {
            logger.info("Received SIGINT, shutting down gracefully...");
            await server.stop();
            process.exit(0);
        });

        process.on("SIGTERM", async () => {
            logger.info("Received SIGTERM, shutting down gracefully...");
            await server.stop();
            process.exit(0);
        });
    } catch (error) {
        logger.error(error, "Failed to start MCP server");
        process.exit(1);
    }
}

// =============================================================================
// 入口点检查 - 当直接运行此文件时启动服务器
// =============================================================================
// 检查当前模块是否作为主程序直接运行（而非被导入）
// 如果是主程序，则调用 main() 函数启动服务器
// import.meta.url.endsWith(process.argv[1]) 用于 ES 模块
// process.argv[1].endsWith("index.js") 用于 CommonJS 模块
if (import.meta.url.endsWith(process.argv[1]) || process.argv[1].endsWith("index.js")) {
    main().catch((error) => {
        createLogger("main").error(error, "Unhandled error in main");
        process.exit(1);
    });
}
