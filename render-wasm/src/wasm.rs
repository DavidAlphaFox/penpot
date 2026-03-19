//! =============================================================================
//! WASM 子模块聚合 (WASM Submodules Aggregation)
//! =============================================================================
//!
//! 【模块概述】
//! 本模块是 WASM 相关子模块的聚合声明点，将渲染引擎的各个功能模块
//! 统一暴露给 JavaScript/WASM 运行时调用。
//!
//! 【核心概念】
//! 1. 混合模式 (Blends) - 形状的混合模式（叠加、混合等）
//! 2. 模糊效果 (Blurs) - 高斯模糊和其他模糊效果
//! 3. 填充 (Fills) - 纯色、渐变等填充方式
//! 4. 字体 (Fonts) - 字体加载和文本渲染
//! 5. 布局 (Layouts) - Flex 和 Grid 布局
//! 6. 路径 (Paths) - SVG 路径操作
//! 7. 阴影 (Shadows) - 投影和内阴影
//! 8. 形状 (Shapes) - 各种形状类型
//! 9. 描边 (Strokes) - 边框和轮廓
//! 10. SVG 属性 (Svg Attrs) - SVG 元素属性
//! 11. 文本 (Text) - 文本布局和渲染
//! 12. 变换 (Transforms) - 矩阵变换操作
//!
//! 【依赖关系】
//! 这些子模块大多依赖于 skia_safe 库进行图形渲染，
//! 并通过 mem 模块进行 WASM 内存管理。
//!
//! =============================================================================

pub mod blend;
pub mod blurs;
pub mod fills;
pub mod fonts;
pub mod layouts;
pub mod mem;
pub mod paths;
pub mod shadows;
pub mod shapes;
pub mod strokes;
pub mod svg_attrs;
pub mod text;
pub mod text_editor;
pub mod transforms;
