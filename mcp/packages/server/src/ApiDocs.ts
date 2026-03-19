/**
 * =============================================================================
 * API 文档模块 (API Documentation)
 * =============================================================================
 *
 * 【模块概述】
 * 本模块负责加载和管理 Penpot API 文档：
 * - 从 YAML 文件加载 API 类型定义
 * - 提供类型和成员的文档查询
 * - 支持大小写不敏感的检索
 * - 文档缓存以提高性能
 *
 * 【核心概念】
 * 1. ApiType - 单个 API 类型的封装，包含名称、概述和成员
 * 2. YAML 格式 - API 类型数据使用 YAML 格式存储在 data/api_types.yml
 * 3. 成员类型 - 属性（properties）、方法（methods）等
 * 4. 文本缓存 - 避免重复格式化文档文本
 *
 * 【依赖关系】
 * - js-yaml - YAML 解析库
 * - fs - 文件系统模块
 * - path - 路径处理模块
 *
 * =============================================================================
 */
import * as fs from "fs";
import * as path from "path";

/**
 * ApiType - API 类型封装类
 *
 * 表示 Penpot API 中的一个类型定义，包含类型名称、概述和成员信息。
 * 提供类型文档的查询和格式化功能。
 */
export class ApiType {
    private readonly name: string;
    private readonly overview: string;
    private readonly members: Record<string, Record<string, string>>;
    private cachedFullText: string | null = null;

    /**
     * 创建 API 类型实例
     *
     * @param name - 类型名称
     * @param overview - 类型概述文本
     * @param members - 类型成员（属性、方法等）的文档
     */
    constructor(name: string, overview: string, members: Record<string, Record<string, string>>) {
        this.name = name;
        this.overview = overview;
        this.members = members;
    }

    /**
     * 获取 API 类型的原始名称
     *
     * @returns 类型名称
     */
    getName(): string {
        return this.name;
    }

    /**
     * 获取 API 类型的概述文本
     *
     * @returns 类型概述（包含签名和类型声明）
     */
    getOverviewText() {
        return this.overview;
    }

    /**
     * 获取完整文档文本
     *
     * 将类型概述和所有成员的文档合并为单个 Markdown 文本。
     * 结果会被缓存以提高性能。
     *
     * @returns 格式化的完整文档文本
     */
    getFullText(): string {
        if (this.cachedFullText === null) {
            let text = this.overview;

            for (const [memberType, memberEntries] of Object.entries(this.members)) {
                text += `\n\n## ${memberType}\n`;

                for (const [memberName, memberDescription] of Object.entries(memberEntries)) {
                    text += `\n### ${memberName}\n\n${memberDescription}`;
                }
            }

            this.cachedFullText = text;
        }

        return this.cachedFullText;
    }

    /**
     * 获取指定成员的文档
     *
     * 成员类型不影响搜索结果，因为成员名称在类型内是唯一的。
     *
     * @param memberName - 成员名称
     * @returns 成员文档文本，如果不存在则返回 null
     */
    getMember(memberName: string): string | null {
        for (const memberEntries of Object.values(this.members)) {
            if (memberName in memberEntries) {
                return memberEntries[memberName];
            }
        }
        return null;
    }
}

/**
 * ApiDocs - API 文档管理器类
 *
 * 从 YAML 文件加载 Penpot API 类型文档，提供大小写不敏感的检索功能。
 */
export class ApiDocs {
    private readonly apiTypes: Map<string, ApiType> = new Map();

    /**
     * 创建 ApiDocs 实例并从 YAML 文件加载 API 类型
     */
    constructor() {
        this.loadApiTypes();
    }

    /**
     * 加载 API 类型文档
     *
     * 从 data/api_types.yml 文件加载类型定义，
     * 并以小写键名存储以支持大小写不敏感检索。
     */
    private loadApiTypes(): void {
        const yamlPath = path.join(process.cwd(), "data", "api_types.yml");
        const yamlContent = fs.readFileSync(yamlPath, "utf8");
        const data = yaml.load(yamlContent) as Record<string, any>;

        for (const [typeName, typeData] of Object.entries(data)) {
            const overview = typeData.overview || "";
            const members = typeData.members || {};

            const apiType = new ApiType(typeName, overview, members);

            // store with lower-case key for case-insensitive retrieval
            this.apiTypes.set(typeName.toLowerCase(), apiType);
        }
    }

    /**
     * 根据名称获取 API 类型（大小写不敏感）
     *
     * @param typeName - 类型名称
     * @returns ApiType 实例，如果不存在则返回 null
     */
    getType(typeName: string): ApiType | null {
        return this.apiTypes.get(typeName.toLowerCase()) || null;
    }

    /**
     * Returns all available type names.
     */
    getTypeNames(): string[] {
        return Array.from(this.apiTypes.values()).map((type) => type.getName());
    }

    /**
     * Returns the number of loaded API types.
     */
    getTypeCount(): number {
        return this.apiTypes.size;
    }
}
