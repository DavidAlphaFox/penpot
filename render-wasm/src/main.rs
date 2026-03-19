//! =============================================================================
//! 主入口模块 (Main Entry Module)
//! =============================================================================
//!
//! 【模块概述】
//! 本模块是 Penpot WASM 渲染引擎的主入口点，负责初始化全局状态、管理渲染循环、
//! 以及暴露所有可供 JavaScript 调用的 WASM 接口函数。
//!
//! 【核心概念】
//! 1. 全局状态 (STATE) - 通过 static mut 存储的全局渲染状态指针
//! 2. 状态访问宏 - with_state!/with_state_mut! 宏提供线程安全的可变/不可变状态访问
//! 3. 形状池 (ShapesPool) - 管理所有设计元素的内存池
//! 4. 瓦片渲染 (Tile Rendering) - 基于 512x512 瓦片的视口渲染优化
//! 5. 双阶段更新 - 先通过导出函数写入形状数据，再通过 render_* 函数触发 Skia 绘制
//!
//! 【依赖关系】
//! - error.rs - 错误类型定义
//! - state.rs - 全局 State 结构体定义
//! - shapes.rs - 形状类型和结构
//! - render.rs - 渲染管道和 Skia 表面管理
//! - tiles.rs - 瓦片系统和视口管理
//! - mem.rs - WASM 内存序列化
//! - math.rs - 数学运算（矩阵、边界）
//! - uuid.rs - UUID 类型封装
//! - performance.rs - 性能测量
//!
//! =============================================================================

#[cfg(target_arch = "wasm32")]
mod emscripten;
mod error;
mod math;
mod mem;
mod options;
mod performance;
mod render;
mod shapes;
mod state;
mod tiles;
mod utils;
mod uuid;
mod view;
mod wapi;
mod wasm;

use std::collections::HashMap;

#[allow(unused_imports)]
use crate::error::{Error, Result};
use macros::wasm_error;
use math::{Bounds, Matrix};
use mem::SerializableResult;
use shapes::{StructureEntry, StructureEntryType, TransformEntry};
use skia_safe as skia;
use state::State;
use utils::uuid_from_u32_quartet;
use uuid::Uuid;

/// 全局渲染状态指针 - 通过 Box 堆分配存储 State
///
/// # 安全性
/// 此 static mut 只能通过 with_state! 和 with_state_mut! 宏访问，
/// 这些宏内部使用 unsafe 块但提供了合理的访问模式。
pub(crate) static mut STATE: Option<Box<State>> = None;

/// 获取可变状态引用的宏
///
/// # 用法
/// ```
/// with_state_mut!(state, {
///     state.do_something();
/// });
/// ```
///
/// # 安全性
/// 宏内部使用 unsafe 访问 static mut STATE，但通过 expect 提供安全边界。
#[macro_export]
macro_rules! with_state_mut {
    ($state:ident, $block:block) => {{
        let $state = unsafe {
            #[allow(static_mut_refs)]
            STATE.as_mut()
        }
        .expect("Got an invalid state pointer");
        $block
    }};
}

/// 获取不可变状态引用的宏
///
/// # 用法
/// ```
/// with_state!(state, {
///     state.do_something();
/// });
/// ```
#[macro_export]
macro_rules! with_state {
    ($state:ident, $block:block) => {{
        let $state = unsafe {
            #[allow(static_mut_refs)]
            STATE.as_ref()
        }
        .expect("Got an invalid state pointer");
        $block
    }};
}

/// 获取当前形状的可变引用的宏
///
/// # 说明
/// 此宏首先标记当前形状为"已触碰"（需要重新渲染），
/// 然后提供对形状的可变访问。
#[macro_export]
macro_rules! with_current_shape_mut {
    ($state:ident, |$shape:ident: &mut Shape| $block:block) => {
        let $state = unsafe {
            #[allow(static_mut_refs)]
            STATE.as_mut()
        }
        .expect("Got an invalid state pointer");

        $state.touch_current();

        if let Some($shape) = $state.current_shape_mut() {
            $block
        }
    };
}

/// 获取当前形状的不可变引用的宏
#[macro_export]
macro_rules! with_current_shape {
    ($state:ident, |$shape:ident: &Shape| $block:block) => {
        let $state = unsafe {
            #[allow(static_mut_refs)]
            STATE.as_ref()
        }
        .expect("Got an invalid state pointer");
        if let Some($shape) = $state.current_shape() {
            $block
        }
    };
}

/// 获取状态和当前形状的宏（形状为不可变引用）
#[macro_export]
macro_rules! with_state_mut_current_shape {
    ($state:ident, |$shape:ident: &Shape| $block:block) => {
        let $state = unsafe {
            #[allow(static_mut_refs)]
            STATE.as_mut()
        }
        .expect("Got an invalid state pointer");
        if let Some($shape) = $state.current_shape() {
            $block
        }
    };
}

/// 初始化渲染引擎。
///
/// # 参数
/// - `width`: 画布初始宽度（像素）
/// - `height`: 画布初始高度（像素）
///
/// # 返回值
/// 成功返回 Ok(())，失败返回错误信息
///
/// # 示例
/// ```javascript
/// // JavaScript 调用
/// Module._init(800, 600);
/// ```
#[no_mangle]
#[wasm_error]
pub extern "C" fn init(width: i32, height: i32) -> Result<()> {
    let state_box = Box::new(State::new(width, height));
    unsafe {
        STATE = Some(state_box);
    }
    Ok(())
}

/// 设置浏览器类型。
///
/// # 参数
/// - `browser`: 浏览器类型代码 (0=Firefox, 1=Chrome, 2=Safari, 3=Edge, 4=Unknown)
///
/// # 说明
/// 不同浏览器可能有不同的渲染行为，此函数用于设置当前浏览器类型以便
/// 进行浏览器特定的适配处理。
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_browser(browser: u8) -> Result<()> {
    with_state_mut!(state, {
        state.set_browser(browser);
    });
    Ok(())
}

/// 清理渲染引擎资源。
///
/// # 说明
/// 此函数取消当前动画帧请求（如果存在），释放所有分配的内存。
/// 调用后全局状态将被设置为 None。
///
/// # 返回值
/// 成功返回 Ok(())，失败返回错误信息
#[no_mangle]
#[wasm_error]
pub extern "C" fn clean_up() -> Result<()> {
    with_state_mut!(state, {
        // Cancel the current animation frame if it exists so
        // it won't try to render without context
        let render_state = state.render_state_mut();
        render_state.cancel_animation_frame();
    });
    unsafe { STATE = None }
    mem::free_bytes()?;
    Ok(())
}

/// 设置渲染选项。
///
/// # 参数
/// - `debug`: 调试标志位 (DEBUG_VISIBLE=0x01, PROFILE_REBUILD_TILES=0x02, FAST_MODE=0x04, INFO_TEXT=0x08)
/// - `dpr`: 设备像素比 (Device Pixel Ratio)，用于高DPI屏幕渲染
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_render_options(debug: u32, dpr: f32) -> Result<()> {
    with_state_mut!(state, {
        let render_state = state.render_state_mut();
        render_state.set_debug_flags(debug);
        render_state.set_dpr(dpr);
    });
    Ok(())
}

/// 设置画布背景色。
///
/// # 参数
/// - `raw_color`: ARGB 格式的颜色值 (0xAARRGGBB)
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_canvas_background(raw_color: u32) -> Result<()> {
    with_state_mut!(state, {
        let color = skia::Color::new(raw_color);
        state.set_background_color(color);
        state.rebuild_tiles_shallow();
    });

    Ok(())
}

/// 启动异步渲染循环。
///
/// # 参数
/// - `_`: 保留参数（历史兼容性）
///
/// # 说明
/// 此函数触发异步渲染，通过 requestAnimationFrame 逐帧渲染已触碰的瓦片。
/// 渲染状态会通过回调通知 JavaScript。
#[no_mangle]
#[wasm_error]
pub extern "C" fn render(_: i32) -> Result<()> {
    with_state_mut!(state, {
        state.rebuild_touched_tiles();
        state
            .start_render_loop(performance::get_time())
            .expect("Error rendering");
    });
    Ok(())
}

/// 同步渲染所有内容。
///
/// # 说明
/// 执行完整的瓦片重建和渲染，阻塞直到渲染完成。
/// 通常用于需要立即看到渲染结果的场景（如导出）。
#[no_mangle]
#[wasm_error]
pub extern "C" fn render_sync() -> Result<()> {
    with_state_mut!(state, {
        state.rebuild_tiles();
        state
            .render_sync(performance::get_time())
            .expect("Error rendering");
    });
    Ok(())
}

/// 同步渲染指定形状。
///
/// # 参数
/// - `a`, `b`, `c`, `d`: 形状 UUID 的四个 32 位无符号整数部分
///
/// # 说明
/// 渲染特定形状及其子元素，用于增量更新单个元素。
/// 如果根形状不存在，会先创建根形状。
#[no_mangle]
#[wasm_error]
pub extern "C" fn render_sync_shape(a: u32, b: u32, c: u32, d: u32) -> Result<()> {
    with_state_mut!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        state.use_shape(id);

        // look for an existing root shape, and create it if missing
        let mut was_root_missing = false;
        if !state.shapes.has(&Uuid::nil()) {
            state.shapes.add_shape(Uuid::nil());
            was_root_missing = true;
        }

        if was_root_missing {
            state.set_parent_for_current_shape(Uuid::nil());
        }

        state.rebuild_tiles_from(Some(&id));
        state
            .render_sync_shape(&id, performance::get_time())
            .map_err(|e| Error::RecoverableError(e.to_string()))?;
    });
    Ok(())
}

/// 从缓存渲染。
///
/// # 参数
/// - `_`: 保留参数
///
/// # 说明
/// 使用缓存的视口数据进行渲染，用于导航时的快速重绘。
#[no_mangle]
#[wasm_error]
pub extern "C" fn render_from_cache(_: i32) -> Result<()> {
    with_state_mut!(state, {
        state.render_state.cancel_animation_frame();
        state.render_from_cache();
    });
    Ok(())
}

/// 设置预览模式。
///
/// # 参数
/// - `enabled`: 是否启用预览模式
///
/// # 说明
/// 预览模式使用简化的渲染路径，跳过一些昂贵的效果（如模糊、阴影）
/// 以实现更快的加载预览。
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_preview_mode(enabled: bool) -> Result<()> {
    with_state_mut!(state, {
        state.render_state.set_preview_mode(enabled);
    });
    Ok(())
}

/// 渲染预览。
///
/// # 说明
/// 在预览/加载阶段渲染简化的形状预览。
#[no_mangle]
#[wasm_error]
pub extern "C" fn render_preview() -> Result<()> {
    with_state_mut!(state, {
        state.render_preview(performance::get_time());
    });
    Ok(())
}

/// 处理动画帧回调。
///
/// # 参数
/// - `timestamp`: 来自 requestAnimationFrame 的时间戳
///
/// # 说明
/// 此函数由 JavaScript 的 requestAnimationFrame 回调调用，
/// 负责处理单个动画帧的渲染逻辑。如果渲染过程中发生 panic，
/// 错误信息会被打印到控制台但不会崩溃。
#[no_mangle]
#[wasm_error]
pub extern "C" fn process_animation_frame(timestamp: i32) -> Result<()> {
    let result = std::panic::catch_unwind(|| {
        with_state_mut!(state, {
            state
                .process_animation_frame(timestamp)
                .expect("Error processing animation frame");
        });
    });

    match result {
        Ok(_) => {}
        Err(err) => {
            match err.downcast_ref::<String>() {
                Some(message) => println!("process_animation_frame error: {}", message),
                None => println!("process_animation_frame error: {:?}", err),
            }
            std::panic::resume_unwind(err);
        }
    }
    Ok(())
}

/// 重置画布。
///
/// # 说明
/// 重置渲染表面到初始透明状态。
#[no_mangle]
#[wasm_error]
pub extern "C" fn reset_canvas() -> Result<()> {
    with_state_mut!(state, {
        state.render_state_mut().reset_canvas();
    });
    Ok(())
}

/// 调整视口大小。
///
/// # 参数
/// - `width`: 新的视口宽度（像素）
/// - `height`: 新的视口高度（像素）
#[no_mangle]
#[wasm_error]
pub extern "C" fn resize_viewbox(width: i32, height: i32) -> Result<()> {
    with_state_mut!(state, {
        state.resize(width, height);
    });
    Ok(())
}

/// 设置视图变换。
///
/// # 参数
/// - `zoom`: 缩放级别
/// - `x`: 平移 X 坐标
/// - `y`: 平移 Y 坐标
///
/// # 说明
/// 更新视口的缩放和平移状态，影响所有后续渲染。
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_view(zoom: f32, x: f32, y: f32) -> Result<()> {
    with_state_mut!(state, {
        performance::begin_measure!("set_view");
        let render_state = state.render_state_mut();
        render_state.set_view(zoom, x, y);
        performance::end_measure!("set_view");
    });
    Ok(())
}

#[cfg(feature = "profile-macros")]
static mut VIEW_INTERACTION_START: i32 = 0;

/// 开始视图交互。
///
/// # 说明
/// 标记用户开始与视图进行交互（拖拽、缩放等）。
/// 启用快速模式并开始性能测量。
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_view_start() -> Result<()> {
    with_state_mut!(state, {
        #[cfg(feature = "profile-macros")]
        unsafe {
            VIEW_INTERACTION_START = performance::get_time();
        }
        performance::begin_measure!("set_view_start");
        state.render_state.options.set_fast_mode(true);
        performance::end_measure!("set_view_start");
    });
    Ok(())
}

/// 结束视图交互。
///
/// # 说明
/// 标记用户结束与视图的交互。
/// 禁用快速模式，重新构建瓦片索引，并同步缓存的视口。
/// 这是视图交互结束的标准处理流程。
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_view_end() -> Result<()> {
    with_state_mut!(state, {
        let _end_start = performance::begin_timed_log!("set_view_end");
        performance::begin_measure!("set_view_end");
        state.render_state.options.set_fast_mode(false);
        state.render_state.cancel_animation_frame();

        // Update tile_viewbox first so that get_tiles_for_shape uses the correct interest area
        // This is critical because we limit tiles to the interest area for optimization
        let scale = state.render_state.get_scale();
        state
            .render_state
            .tile_viewbox
            .update(state.render_state.viewbox, scale);

        // We rebuild the tile index on both pan and zoom because `get_tiles_for_shape`
        // clips each shape to the current `TileViewbox::interest_rect` (viewport-dependent).
        let _rebuild_start = performance::begin_timed_log!("rebuild_tiles");
        performance::begin_measure!("set_view_end::rebuild_tiles");
        if state.render_state.options.is_profile_rebuild_tiles() {
            state.rebuild_tiles();
        } else {
            state.rebuild_tiles_shallow();
        }
        performance::end_measure!("set_view_end::rebuild_tiles");
        performance::end_timed_log!("rebuild_tiles", _rebuild_start);

        state.render_state.sync_cached_viewbox();
        performance::end_measure!("set_view_end");
        performance::end_timed_log!("set_view_end", _end_start);
        #[cfg(feature = "profile-macros")]
        {
            let total_time = performance::get_time() - unsafe { VIEW_INTERACTION_START };
            performance::console_log!("[PERF] view_interaction: {}ms", total_time);
        }
    });
    Ok(())
}

/// 清除焦点模式。
///
/// # 说明
/// 禁用焦点模式，所有形状都将正常渲染。
#[no_mangle]
#[wasm_error]
pub extern "C" fn clear_focus_mode() -> Result<()> {
    with_state_mut!(state, {
        state.clear_focus_mode();
    });
    Ok(())
}

/// 设置焦点模式。
///
/// # 说明
/// 启用焦点模式，仅渲染指定形状及其后代。
/// 形状 UUID 从内存缓冲区读取。
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_focus_mode() -> Result<()> {
    let bytes = mem::bytes();

    let entries: Vec<Uuid> = bytes
        .chunks(size_of::<<Uuid as SerializableResult>::BytesType>())
        .map(|data| Uuid::try_from(data).unwrap())
        .collect();

    with_state_mut!(state, {
        state.set_focus_mode(entries);
    });
    Ok(())
}

/// 初始化形状池。
///
/// # 参数
/// - `capacity`: 预分配的形状数量容量
///
/// # 说明
/// 预先为形状池分配内存，避免后续动态分配的性能开销。
#[no_mangle]
#[wasm_error]
pub extern "C" fn init_shapes_pool(capacity: usize) -> Result<()> {
    with_state_mut!(state, {
        state.init_shapes_pool(capacity);
    });
    Ok(())
}

/// 指定当前操作的形状。
///
/// # 参数
/// - `a`, `b`, `c`, `d`: 形状 UUID 的四个 32 位无符号整数部分
///
/// # 说明
/// 设置"当前形状"为指定 UUID，后续的 set_* 函数将作用于此形状。
/// 如果形状不存在，则先创建。
#[no_mangle]
#[wasm_error]
pub extern "C" fn use_shape(a: u32, b: u32, c: u32, d: u32) -> Result<()> {
    with_state_mut!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        state.use_shape(id);
    });
    Ok(())
}

/// 标记形状为已触碰。
///
/// # 参数
/// - `a`, `b`, `c`, `d`: 形状 UUID 的四个 32 位无符号整数部分
///
/// # 说明
/// 标记形状需要重新渲染，下次渲染循环时会更新其关联的瓦片。
#[no_mangle]
#[wasm_error]
pub extern "C" fn touch_shape(a: u32, b: u32, c: u32, d: u32) -> Result<()> {
    with_state_mut!(state, {
        let shape_id = uuid_from_u32_quartet(a, b, c, d);
        state.touch_shape(shape_id);
    });
    Ok(())
}

/// 设置当前形状的父级。
///
/// # 参数
/// - `a`, `b`, `c`, `d`: 父级 UUID 的四个 32 位无符号整数部分
///
/// # 说明
/// 将当前形状的父级设置为指定的 UUID，同时使父级的扩展矩形失效以便重新计算。
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_parent(a: u32, b: u32, c: u32, d: u32) -> Result<()> {
    with_state_mut!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        state.set_parent_for_current_shape(id);
    });
    Ok(())
}

/// 设置形状是否为遮罩组。
///
/// # 参数
/// - `masked`: 是否为遮罩组
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_masked_group(masked: bool) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_masked(masked);
    });
    Ok(())
}

/// 设置形状的选择矩形。
///
/// # 参数
/// - `left`, `top`, `right`, `bottom`: 选择矩形的左、上、右、下坐标
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_selrect(left: f32, top: f32, right: f32, bottom: f32) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_selrect(left, top, right, bottom);
    });
    Ok(())
}

/// 设置形状是否裁剪内容。
///
/// # 参数
/// - `clip_content`: 是否裁剪内容
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_clip_content(clip_content: bool) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_clip(clip_content);
    });
    Ok(())
}

/// 设置形状旋转角度。
///
/// # 参数
/// - `rotation`: 旋转角度（弧度）
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_rotation(rotation: f32) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_rotation(rotation);
    });
    Ok(())
}

/// 设置形状的变换矩阵。
///
/// # 参数
/// - `a`, `b`, `c`, `d`, `e`, `f`: 2D仿射变换矩阵的六个分量
///   矩阵格式: | a c e |
///            | b d f |
///            | 0 0 1 |
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_transform(
    a: f32,
    b: f32,
    c: f32,
    d: f32,
    e: f32,
    f: f32,
) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_transform(a, b, c, d, e, f);
    });
    Ok(())
}

/// 添加子形状到当前形状。
///
/// # 参数
/// - `a`, `b`, `c`, `d`: 子形状 UUID 的四个 32 位无符号整数部分
#[no_mangle]
#[wasm_error]
pub extern "C" fn add_shape_child(a: u32, b: u32, c: u32, d: u32) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let id = uuid_from_u32_quartet(a, b, c, d);
        shape.add_child(id);
    });
    Ok(())
}

/// 设置子形状列表的内部实现函数。
///
/// # 参数
/// - `entries`: 要设置的子形状 UUID 列表
///
/// # 说明
/// 计算当前子形状与新子形状列表的差异，删除不再需要的子形状，
/// 并标记所有相关形状为"已触碰"以便重新渲染。
fn set_children_set(entries: Vec<Uuid>) -> Result<()> {
    let mut deleted = Vec::new();
    let mut parent_id = None;

    with_current_shape_mut!(state, |shape: &mut Shape| {
        parent_id = Some(shape.id);
        (_, deleted) = shape.compute_children_differences(&entries);
        shape.children = entries.clone();

        for id in entries {
            state.touch_shape(id);
            if let Some(children_shape) = state.shapes.get_mut(&id) {
                children_shape.set_deleted(false);
            }
        }
    });

    with_state_mut!(state, {
        let Some(parent_id) = parent_id else {
            return Err(Error::RecoverableError(
                "set_children_set: Parent ID not found".to_string(),
            ));
        };

        for id in deleted {
            state.delete_shape_children(parent_id, id);
            state.touch_shape(id);
        }
    });
    Ok(())
}

/// 设置 0 个子形状（清空子形状列表）。
///
/// # 说明
/// 将当前形状的子形状列表设置为空，等同于移除所有子元素。
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_children_0() -> Result<()> {
    let entries = vec![];
    set_children_set(entries)?;
    Ok(())
}

/// 设置 1 个子形状。
///
/// # 参数
/// - `a1`, `b1`, `c1`, `d1`: 子形状 UUID 的四个 32 位无符号整数部分
///
/// # 说明
/// 设置当前形状的单个子形状。
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_children_1(a1: u32, b1: u32, c1: u32, d1: u32) -> Result<()> {
    let entries = vec![uuid_from_u32_quartet(a1, b1, c1, d1)];
    set_children_set(entries)?;
    Ok(())
}

/// 设置 2 个子形状。
///
/// # 参数
/// - `a1`, `b1`, `c1`, `d1`: 第一个子形状 UUID 的四个 32 位无符号整数部分
/// - `a2`, `b2`, `c2`, `d2`: 第二个子形状 UUID 的四个 32 位无符号整数部分
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_children_2(
    a1: u32,
    b1: u32,
    c1: u32,
    d1: u32,
    a2: u32,
    b2: u32,
    c2: u32,
    d2: u32,
) -> Result<()> {
    let entries = vec![
        uuid_from_u32_quartet(a1, b1, c1, d1),
        uuid_from_u32_quartet(a2, b2, c2, d2),
    ];
    set_children_set(entries)?;
    Ok(())
}

/// 设置 3 个子形状。
///
/// # 参数
/// - `a1`, `b1`, `c1`, `d1`: 第一个子形状 UUID 的四个 32 位无符号整数部分
/// - `a2`, `b2`, `c2`, `d2`: 第二个子形状 UUID 的四个 32 位无符号整数部分
/// - `a3`, `b3`, `c3`, `d3`: 第三个子形状 UUID 的四个 32 位无符号整数部分
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_children_3(
    a1: u32,
    b1: u32,
    c1: u32,
    d1: u32,
    a2: u32,
    b2: u32,
    c2: u32,
    d2: u32,
    a3: u32,
    b3: u32,
    c3: u32,
    d3: u32,
) -> Result<()> {
    let entries = vec![
        uuid_from_u32_quartet(a1, b1, c1, d1),
        uuid_from_u32_quartet(a2, b2, c2, d2),
        uuid_from_u32_quartet(a3, b3, c3, d3),
    ];
    set_children_set(entries)?;
    Ok(())
}

/// 设置 4 个子形状。
///
/// # 参数
/// - `a1`, `b1`, `c1`, `d1`: 第一个子形状 UUID 的四个 32 位无符号整数部分
/// - `a2`, `b2`, `c2`, `d2`: 第二个子形状 UUID 的四个 32 位无符号整数部分
/// - `a3`, `b3`, `c3`, `d3`: 第三个子形状 UUID 的四个 32 位无符号整数部分
/// - `a4`, `b4`, `c4`, `d4`: 第四个子形状 UUID 的四个 32 位无符号整数部分
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_children_4(
    a1: u32,
    b1: u32,
    c1: u32,
    d1: u32,
    a2: u32,
    b2: u32,
    c2: u32,
    d2: u32,
    a3: u32,
    b3: u32,
    c3: u32,
    d3: u32,
    a4: u32,
    b4: u32,
    c4: u32,
    d4: u32,
) -> Result<()> {
    let entries = vec![
        uuid_from_u32_quartet(a1, b1, c1, d1),
        uuid_from_u32_quartet(a2, b2, c2, d2),
        uuid_from_u32_quartet(a3, b3, c3, d3),
        uuid_from_u32_quartet(a4, b4, c4, d4),
    ];
    set_children_set(entries)?;
    Ok(())
}

/// 设置 5 个子形状。
///
/// # 参数
/// - `a1`, `b1`, `c1`, `d1`: 第一个子形状 UUID 的四个 32 位无符号整数部分
/// - `a2`, `b2`, `c2`, `d2`: 第二个子形状 UUID 的四个 32 位无符号整数部分
/// - `a3`, `b3`, `c3`, `d3`: 第三个子形状 UUID 的四个 32 位无符号整数部分
/// - `a4`, `b4`, `c4`, `d4`: 第四个子形状 UUID 的四个 32 位无符号整数部分
/// - `a5`, `b5`, `c5`, `d5`: 第五个子形状 UUID 的四个 32 位无符号整数部分
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_children_5(
    a1: u32,
    b1: u32,
    c1: u32,
    d1: u32,
    a2: u32,
    b2: u32,
    c2: u32,
    d2: u32,
    a3: u32,
    b3: u32,
    c3: u32,
    d3: u32,
    a4: u32,
    b4: u32,
    c4: u32,
    d4: u32,
    a5: u32,
    b5: u32,
    c5: u32,
    d5: u32,
) -> Result<()> {
    let entries = vec![
        uuid_from_u32_quartet(a1, b1, c1, d1),
        uuid_from_u32_quartet(a2, b2, c2, d2),
        uuid_from_u32_quartet(a3, b3, c3, d3),
        uuid_from_u32_quartet(a4, b4, c4, d4),
        uuid_from_u32_quartet(a5, b5, c5, d5),
    ];
    set_children_set(entries)?;
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_children() -> Result<()> {
    let bytes = mem::bytes_or_empty();

    let entries: Vec<Uuid> = bytes
        .chunks(size_of::<<Uuid as SerializableResult>::BytesType>())
        .map(|data| Uuid::try_from(data).unwrap())
        .collect();

    set_children_set(entries)?;

    if !bytes.is_empty() {
        mem::free_bytes()?;
    }

    Ok(())
}

/// 检查图像是否已缓存。
///
/// # 参数
/// - `a`, `b`, `c`, `d`: 图像 UUID 的四个 32 位无符号整数部分
/// - `is_thumbnail`: 是否为缩略图
///
/// # 返回值
/// 如果图像已缓存返回 true，否则返回 false
#[no_mangle]
#[wasm_error]
pub extern "C" fn is_image_cached(
    a: u32,
    b: u32,
    c: u32,
    d: u32,
    is_thumbnail: bool,
) -> Result<bool> {
    with_state_mut!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        let result = state.render_state().has_image(&id, is_thumbnail);
        Ok(result)
    })
}

/// 设置形状的 SVG 原始内容。
///
/// # 说明
/// 从内存缓冲区读取 SVG 内容字符串并设置到当前形状。
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_svg_raw_content() -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        let bytes = mem::bytes();
        let svg_raw_content = String::from_utf8(bytes)
            .map_err(|e| Error::RecoverableError(e.to_string()))?
            .trim_end_matches('\0')
            .to_string();
        shape.set_svg_raw_content(svg_raw_content);
    });

    Ok(())
}

/// 设置形状的不透明度。
///
/// # 参数
/// - `opacity`: 不透明度值 (0.0 - 1.0)
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_opacity(opacity: f32) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_opacity(opacity);
    });
    Ok(())
}

/// 设置形状是否隐藏。
///
/// # 参数
/// - `hidden`: 是否隐藏
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_hidden(hidden: bool) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_hidden(hidden);
    });
    Ok(())
}

/// 设置形状的圆角半径。
///
/// # 参数
/// - `r1`, `r2`, `r3`, `r4`: 四个角的圆角半径（顺时针从左上开始）
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_corners(r1: f32, r2: f32, r3: f32, r4: f32) -> Result<()> {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.set_corners((r1, r2, r3, r4));
    });
    Ok(())
}

/// 获取选择区域的矩形边界。
///
/// # 说明
/// 从内存缓冲区读取一组形状 UUID，计算这些形状的联合边界框，
/// 并返回包含边界信息的字节数组。
///
/// # 返回值
/// 返回包含以下数据的字节数组（40字节）：
/// - 宽高 (width, height): 各 4 字节
/// - 中心点 (center x, y): 各 4 字节
/// - 变换矩阵 (6个分量): 各 4 字节
///
/// # 内存管理
/// 返回的指针指向 WASM 内存中的数据，使用完毕后无需手动释放。
#[no_mangle]
#[wasm_error]
pub extern "C" fn get_selection_rect() -> Result<*mut u8> {
    let bytes = mem::bytes();

    let entries: Vec<Uuid> = bytes
        .chunks(16)
        .map(|bytes| {
            uuid_from_u32_quartet(
                u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
                u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]),
                u32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]),
                u32::from_le_bytes([bytes[12], bytes[13], bytes[14], bytes[15]]),
            )
        })
        .collect();

    let result_bound = with_state_mut!(state, {
        let bbs: Vec<_> = entries
            .iter()
            .flat_map(|id| state.shapes.get(id).map(|b| b.bounds()))
            .collect();

        if bbs.len() == 1 {
            bbs[0]
        } else {
            Bounds::join_bounds(&bbs)
        }
    });

    let width = result_bound.width();
    let height = result_bound.height();
    let center = result_bound.center();
    let transform = result_bound.transform_matrix().unwrap_or(Matrix::default());

    let mut bytes = vec![0; 40];
    bytes[0..4].clone_from_slice(&width.to_le_bytes());
    bytes[4..8].clone_from_slice(&height.to_le_bytes());
    bytes[8..12].clone_from_slice(&center.x.to_le_bytes());
    bytes[12..16].clone_from_slice(&center.y.to_le_bytes());
    bytes[16..20].clone_from_slice(&transform[0].to_le_bytes());
    bytes[20..24].clone_from_slice(&transform[3].to_le_bytes());
    bytes[24..28].clone_from_slice(&transform[1].to_le_bytes());
    bytes[28..32].clone_from_slice(&transform[4].to_le_bytes());
    bytes[32..36].clone_from_slice(&transform[2].to_le_bytes());
    bytes[36..40].clone_from_slice(&transform[5].to_le_bytes());
    Ok(mem::write_bytes(bytes))
}

/// 设置结构修饰符。
///
/// # 说明
/// 从内存缓冲区读取 StructureEntry 数据并应用到形状结构。
/// 包括 AddChild、RemoveChild 等结构操作。
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_structure_modifiers() -> Result<()> {
    let bytes = mem::bytes();

    let entries: Vec<_> = bytes
        .chunks(44)
        .map(|data| StructureEntry::from_bytes(data.try_into().unwrap()))
        .collect();

    with_state_mut!(state, {
        let mut structure = HashMap::new();
        let mut scale_content = HashMap::new();
        for entry in entries {
            match entry.entry_type {
                StructureEntryType::ScaleContent => {
                    let Some(shape) = state.shapes.get(&entry.id) else {
                        continue;
                    };
                    for id in shape.all_children(&state.shapes, true, true) {
                        scale_content.insert(id, entry.value);
                    }
                }
                _ => {
                    structure.entry(entry.parent).or_insert_with(Vec::new);
                    structure
                        .get_mut(&entry.parent)
                        .expect("Parent not found for entry")
                        .push(entry);
                }
            }
        }
        if !scale_content.is_empty() {
            state.shapes.set_scale_content(scale_content);
        }
        if !structure.is_empty() {
            state.shapes.set_structure(structure);
        }
    });

    mem::free_bytes()?;
    Ok(())
}

/// 清除所有修饰符。
///
/// # 说明
/// 清除所有形状的修饰符（变换、效果等），重置为默认状态。
#[no_mangle]
#[wasm_error]
pub extern "C" fn clean_modifiers() -> Result<()> {
    with_state_mut!(state, {
        state.shapes.clean_all();
    });
    Ok(())
}

/// 设置修饰符变换。
///
/// # 说明
/// 从内存缓冲区读取 TransformEntry 数据并应用到形状。
/// 包括缩放、旋转等变换修饰符。
#[no_mangle]
#[wasm_error]
pub extern "C" fn set_modifiers() -> Result<()> {
    let bytes = mem::bytes();

    let entries: Vec<_> = bytes
        .chunks(size_of::<<TransformEntry as SerializableResult>::BytesType>())
        .map(|data| TransformEntry::try_from(data).unwrap())
        .collect();

    let mut modifiers = HashMap::new();
    let mut ids = Vec::<Uuid>::new();
    for entry in entries {
        modifiers.insert(entry.id, entry.transform);
        ids.push(entry.id);
    }

    with_state_mut!(state, {
        state.set_modifiers(modifiers);
        state.rebuild_modifier_tiles(ids);
    });
    Ok(())
}

/// 开始临时对象模式。
///
/// # 说明
/// 保存当前形状池为临时快照，并创建一个新的空形状池。
/// 用于需要临时操作不影响主文档的场景（如拖拽预览）。
/// 如果之前已有临时对象，则会 panic。
#[no_mangle]
#[wasm_error]
pub extern "C" fn start_temp_objects() -> Result<()> {
    unsafe {
        #[allow(static_mut_refs)]
        let mut state = STATE.take().expect("Got an invalid state pointer");
        state = Box::new(state.start_temp_objects());
        STATE = Some(state);
    }
    Ok(())
}

/// 结束临时对象模式。
///
/// # 说明
/// 恢复之前保存的形状池，丢弃临时形状池。
/// 必须与 start_temp_objects 配对使用。
#[no_mangle]
#[wasm_error]
pub extern "C" fn end_temp_objects() -> Result<()> {
    unsafe {
        #[allow(static_mut_refs)]
        let mut state = STATE.take().expect("Got an invalid state pointer");
        state = Box::new(state.end_temp_objects());
        STATE = Some(state);
    }
    Ok(())
}

fn main() {
    #[cfg(target_arch = "wasm32")]
    init_gl!();
}
