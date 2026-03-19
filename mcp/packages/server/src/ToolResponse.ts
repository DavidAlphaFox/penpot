/**
 * =============================================================================
 * MCP 工具响应类型模块 (MCP Tool Response Types)
 * =============================================================================
 *
 * 【模块概述】
 * 本模块定义了 MCP 工具返回的响应类型，包括：
 * - 文本内容响应
 * - 图片内容响应（PNG 等）
 * - 工具响应的统一封装
 *
 * 【核心概念】
 * 1. CallToolResult - MCP 协议定义的工具调用结果结构
 * 2. TextContent - 文本内容类型，包含字符串数据
 * 3. ImageContent - 图片内容类型，包含 Base64 编码的数据和 MIME 类型
 * 4. PNGImageContent - PNG 格式图片的专门处理类
 * 5. Uint8Array/Base64 转换 - 处理插件端传来的字节数组序列化
 *
 * 【依赖关系】
 * - @modelcontextprotocol/sdk/types - MCP 协议类型定义
 *
 * =============================================================================
 */

type CallToolContent = CallToolResult["content"][number];
type TextItem = Extract<CallToolContent, { type: "text" }>;
type ImageItem = Extract<CallToolContent, { type: "image" }>;

/**
 * TextContent - 文本内容类
 *
 * 实现 MCP 协议的文本内容类型，用于返回文本数据。
 * 支持从字符串或特殊对象格式（字符码映射）创建文本内容。
 */
export class TextContent implements TextItem {
    [x: string]: unknown;
    readonly type = "text" as const;

    /**
     * 创建文本内容实例
     *
     * @param text - 文本内容
     */
    constructor(public text: string) {}

    /**
     * 从字符串或特殊对象格式创建文本数据
     *
     * 当从 JSON 转换 Uint8Array 时，字符串会被转换为字符码映射对象，
     * 此方法用于将其转换回原始字符串。
     *
     * @param data - 文本数据（字符串或字符码映射对象）
     * @returns 原始字符串
     */
    public static textData(data: string | object): string {
        if (typeof data === "object") {
            // convert object containing character codes (as obtained from JSON conversion of string) back to string
            return String.fromCharCode(...(Object.values(data) as number[]));
        } else {
            return data;
        }
    }
}

/**
 * ImageContent - 图片内容基类
 *
 * 实现 MCP 协议的图片内容类型，用于返回图片数据。
 * 包含 Base64 编码的图片数据和 MIME 类型。
 */
export class ImageContent implements ImageItem {
    [x: string]: unknown;
    readonly type = "image" as const;

    /**
     * 创建图片内容实例
     *
     * @param data - Base64 编码的图片数据
     * @param mimeType - 图片 MIME 类型（如 "image/png"）
     */
    constructor(
        public data: string,
        public mimeType: string
    ) {}

    /**
     * 确保字节数据以 Uint8Array 格式返回
     *
     * 处理从插件传来的 Uint8Array JSON 序列化格式。
     *
     * @param data - 字节数据（Uint8Array 或 JSON 转换后的对象）
     * @returns Uint8Array 格式的字节数据
     */
    public static byteData(data: Uint8Array | object): Uint8Array {
        if (typeof data === "object") {
            // convert object (as obtained from JSON conversion of Uint8Array) back to Uint8Array
            return new Uint8Array(Object.values(data) as number[]);
        } else {
            return data;
        }
    }
}

/**
 * PNGImageContent - PNG 图片内容类
 *
 * 专门处理 PNG 格式的图片内容类，
 * 继承自 ImageContent，自动设置 MIME 类型为 image/png。
 */
export class PNGImageContent extends ImageContent {
    /**
     * 创建 PNG 图片内容实例
     *
     * @param data - PNG 图片数据（Uint8Array 或 JSON 转换后的对象）
     */
    constructor(data: Uint8Array | object) {
        let array = ImageContent.byteData(data);
        super(Buffer.from(array).toString("base64"), "image/png");
    }
}

/**
 * ToolResponse - 工具响应基类
 *
 * MCP 协议定义的工具响应结构，包含内容数组。
 * 内容可以是文本、图片等多种类型。
 */
export class ToolResponse implements CallToolResult {
    [x: string]: unknown;
    content: CallToolContent[]; // MCP 协议的内容联合类型

    /**
     * 创建工具响应实例
     *
     * @param content - 内容数组（TextContent 或 ImageContent 等）
     */
    constructor(content: CallToolContent[]) {
        this.content = content;
    }
}

/**
 * TextResponse - 文本响应类
 *
 * 便捷的文本响应构建类，自动封装文本内容。
 */
export class TextResponse extends ToolResponse {
    /**
     * 创建文本响应实例
     *
     * @param text - 要返回的文本内容
     */
    constructor(text: string) {
        super([new TextContent(text)]);
    }

    /**
     * 从文本数据创建 TextResponse
     *
     * 支持从字符串或特殊对象格式（字符码映射）创建响应。
     *
     * @param data - 文本数据（字符串或字符码映射对象）
     * @returns TextResponse 实例
     */
    public static fromData(data: string | object): TextResponse {
        return new TextResponse(TextContent.textData(data));
    }
}

/**
 * PNGResponse - PNG 响应类
 *
 * 便捷的 PNG 图片响应构建类，自动处理字节数组转换。
 */
export class PNGResponse extends ToolResponse {
    /**
     * 创建 PNG 响应实例
     *
     * @param data - PNG 图片数据（Uint8Array 或 JSON 转换后的对象）
     */
    constructor(data: Uint8Array | object) {
        super([new PNGImageContent(data)]);
    }
}
