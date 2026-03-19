//! =============================================================================
//! 状态管理模块 (State Management Module)
//! =============================================================================
//!
//! 【模块概述】
//! 本模块定义了渲染引擎的全局状态结构体 State，它在 JavaScript 调用之间
//! 保持渲染引擎的状态。State 是整个渲染系统的核心数据结构。
//!
//! 【核心概念】
//! 1. 渲染状态 (RenderState) - Skia 渲染表面和 GPU 状态管理
//! 2. 形状池 (ShapesPool) - 所有设计元素的存储
//! 3. 当前形状 (Current Shape) - 当前正在操作的形状
//! 4. 临时对象 (Temp Objects) - 支持撤销/重做的临时状态快照
//! 5. 文本编辑器状态 (TextEditorState) - 文本编辑状态
//!
//! 【依赖关系】
//! - render::RenderState - 渲染状态
//! - shapes::Shape - 形状定义
//! - tiles - 瓦片系统
//! - uuid::Uuid - UUID 类型
//!
//! =============================================================================

use skia_safe::{self as skia, textlayout::FontCollection, Path, Point};
use std::collections::HashMap;

mod shapes_pool;
mod text_editor;
pub use shapes_pool::{ShapesPool, ShapesPoolMutRef, ShapesPoolRef};
pub use text_editor::*;

use crate::render::RenderState;
use crate::shapes::Shape;
use crate::tiles;
use crate::uuid::Uuid;

use crate::shapes::modifiers::grid_layout::grid_cell_data;

/// 全局渲染状态结构体
///
/// 此结构体在 JavaScript 调用之间保持 Rust 应用程序的状态。
/// 它由 [init] 创建并传递给其他导出的函数。
/// 注意：rust-skia 数据结构不是线程安全的，因此状态不能在不同的 Web Workers 之间共享。
///
/// # 字段说明
/// - `render_state`: Skia 渲染表面和 GPU 状态
/// - `text_editor_state`: 文本编辑器状态
/// - `current_id`: 当前正在操作的形状 ID
/// - `current_browser`: 当前浏览器类型
/// - `shapes`: 形状池
/// - `saved_shapes`: 临时对象模式的形状池快照
pub(crate) struct State {
    /// 渲染状态
    pub render_state: RenderState,
    /// 文本编辑器状态
    pub text_editor_state: TextEditorState,
    /// 当前形状 ID
    pub current_id: Option<Uuid>,
    /// 当前浏览器类型
    pub current_browser: u8,
    /// 形状池
    pub shapes: ShapesPool,
    /// 临时对象模式的形状池快照
    pub saved_shapes: Option<ShapesPool>,
}

impl State {
    /// 创建新的状态实例
    ///
    /// # 参数
    /// - `width`: 初始视口宽度
    /// - `height`: 初始视口高度
    pub fn new(width: i32, height: i32) -> Self {
        State {
            render_state: RenderState::new(width, height),
            text_editor_state: TextEditorState::new(),
            current_id: None,
            current_browser: 0,
            shapes: ShapesPool::new(),
            // TODO: Maybe this can be moved to a different object
            saved_shapes: None,
        }
    }

    /// 开始临时对象模式
    ///
    /// # 说明
    /// 保存当前形状池为快照，并创建一个新的空形状池。
    /// 如果之前已有临时对象则会 panic。
    pub fn start_temp_objects(mut self) -> Self {
        if self.saved_shapes.is_some() {
            panic!("Tried to start a temp objects while the previous have not been restored");
        }
        self.saved_shapes = Some(self.shapes);
        self.shapes = ShapesPool::new();
        self
    }

    /// 结束临时对象模式
    ///
    /// # 说明
    /// 恢复之前保存的形状池。如果不存在临时池则会 panic。
    pub fn end_temp_objects(mut self) -> Self {
        self.shapes = self
            .saved_shapes
            .expect("Tried to end temp objects but not content to be restored is present");
        self.saved_shapes = None;
        self
    }

    /// 调整视口大小
    ///
    /// # 参数
    /// - `width`: 新宽度
    /// - `height`: 新高度
    pub fn resize(&mut self, width: i32, height: i32) {
        self.render_state.resize(width, height);
    }

    /// 获取可变渲染状态引用
    pub fn render_state_mut(&mut self) -> &mut RenderState {
        &mut self.render_state
    }

    /// 获取不可变渲染状态引用
    pub fn render_state(&self) -> &RenderState {
        &self.render_state
    }

    #[allow(dead_code)]
    pub fn text_editor_state_mut(&mut self) -> &mut TextEditorState {
        &mut self.text_editor_state
    }

    #[allow(dead_code)]
    pub fn text_editor_state(&self) -> &TextEditorState {
        &self.text_editor_state
    }

    /// 从缓存渲染
    ///
    /// # 说明
    /// 使用缓存的视口数据进行快速渲染。
    pub fn render_from_cache(&mut self) {
        self.render_state.render_from_cache(&self.shapes);
    }

    /// 同步渲染
    ///
    /// # 参数
    /// - `timestamp`: 渲染时间戳
    pub fn render_sync(&mut self, timestamp: i32) -> Result<(), String> {
        self.render_state
            .start_render_loop(None, &self.shapes, timestamp, true)?;
        Ok(())
    }

    /// 同步渲染指定形状
    ///
    /// # 参数
    /// - `id`: 要渲染的形状 ID
    /// - `timestamp`: 渲染时间戳
    pub fn render_sync_shape(&mut self, id: &Uuid, timestamp: i32) -> Result<(), String> {
        self.render_state
            .start_render_loop(Some(id), &self.shapes, timestamp, true)?;
        Ok(())
    }

    /// 启动渲染循环
    ///
    /// # 参数
    /// - `timestamp`: 渲染时间戳
    ///
    /// # 说明
    /// 如果缩放级别改变，必须在 使用瓦片索引之前重建它。
    /// 否则，索引将包含旧缩放级别的瓦片，导致可见瓦片显示为空。
    pub fn start_render_loop(&mut self, timestamp: i32) -> Result<(), String> {
        let zoom_changed = self.render_state.zoom_changed();
        if zoom_changed {
            self.rebuild_tiles_shallow();
        }

        self.render_state
            .start_render_loop(None, &self.shapes, timestamp, false)?;
        Ok(())
    }

    /// 处理动画帧
    ///
    /// # 参数
    /// - `timestamp`: 动画帧时间戳
    pub fn process_animation_frame(&mut self, timestamp: i32) -> Result<(), String> {
        self.render_state
            .process_animation_frame(None, &self.shapes, timestamp)?;
        Ok(())
    }

    /// 清除焦点模式
    pub fn clear_focus_mode(&mut self) {
        self.render_state.clear_focus_mode();
    }

    /// 设置焦点模式
    ///
    /// # 参数
    /// - `shapes`: 要聚焦的形状 UUID 列表
    pub fn set_focus_mode(&mut self, shapes: Vec<Uuid>) {
        self.render_state.set_focus_mode(shapes);
    }

    /// 初始化形状池容量
    ///
    /// # 参数
    /// - `capacity`: 预分配的形状数量
    pub fn init_shapes_pool(&mut self, capacity: usize) {
        self.shapes.initialize(capacity);
    }

    /// 设置当前形状
    ///
    /// # 参数
    /// - `id`: 形状 UUID
    ///
    /// # 说明
    /// 如果形状不存在则创建它。
    pub fn use_shape(&mut self, id: Uuid) {
        if !self.shapes.has(&id) {
            self.shapes.add_shape(id);
        }
        self.current_id = Some(id);
    }

    /// 删除形状的子元素
    ///
    /// # 参数
    /// - `parent_id`: 父级 ID
    /// - `id`: 要删除的子形状 ID
    ///
    /// # 说明
    /// 并不真正从形状池中移除，以便撤销/重做正常工作。
    pub fn delete_shape_children(&mut self, parent_id: Uuid, id: Uuid) {
        let Some(shape) = self.shapes.get(&id) else {
            return;
        };

        if shape.parent_id.is_none() || shape.parent_id == Some(parent_id) {
            let tiles::TileRect(rsx, rsy, rex, rey) =
                self.render_state.get_tiles_for_shape(shape, &self.shapes);
            for x in rsx..=rex {
                for y in rsy..=rey {
                    let tile = tiles::Tile(x, y);
                    self.render_state.remove_cached_tile(tile);
                    self.render_state.tiles.remove_shape_at(tile, shape.id);
                }
            }

            if let Some(shape_to_delete) = self.shapes.get_mut(&id) {
                shape_to_delete.set_deleted(true);
            }
        }
    }

    /// 获取当前形状的可变引用
    pub fn current_shape_mut(&mut self) -> Option<&mut Shape> {
        self.shapes.get_mut(&self.current_id?)
    }

    /// 获取当前形状的不可变引用
    pub fn current_shape(&self) -> Option<&Shape> {
        self.shapes.get(&self.current_id?)
    }

    /// 设置背景颜色
    ///
    /// # 参数
    /// - `color`: Skia 颜色值
    pub fn set_background_color(&mut self, color: skia::Color) {
        self.render_state.set_background_color(color);
    }

    /// 设置浏览器类型
    ///
    /// # 参数
    /// - `browser`: 浏览器类型代码
    pub fn set_browser(&mut self, browser: u8) {
        self.current_browser = browser;
    }

    /// 设置当前形状的父级并更新父级的扩展矩形
    ///
    /// # 参数
    /// - `id`: 父级 UUID
    ///
    /// # 说明
    /// 当形状被分配新父级时，需要使父级的扩展矩形失效并重新计算，
    /// 以确保框架和组正确包含其子元素。
    pub fn set_parent_for_current_shape(&mut self, id: Uuid) {
        let Some(shape) = self.current_shape_mut() else {
            panic!("Invalid current shape")
        };

        if shape.parent_id == Some(id) {
            return;
        }

        shape.set_parent(id);

        if let Some(parent) = self.shapes.get_mut(&id) {
            parent.invalidate_extrect();
        }
    }

    /// 浅层重建瓦片（仅重建索引）
    pub fn rebuild_tiles_shallow(&mut self) {
        self.render_state.rebuild_tiles_shallow(&self.shapes);
    }

    /// 完全重建瓦片
    pub fn rebuild_tiles(&mut self) {
        self.render_state.rebuild_tiles_from(&self.shapes, None);
    }

    /// 从指定形状重建瓦片
    ///
    /// # 参数
    /// - `base_id`: 起始形状 ID
    pub fn rebuild_tiles_from(&mut self, base_id: Option<&Uuid>) {
        self.render_state.rebuild_tiles_from(&self.shapes, base_id);
    }

    /// 重建已触碰的瓦片
    pub fn rebuild_touched_tiles(&mut self) {
        self.render_state.rebuild_touched_tiles(&self.shapes);
    }

    /// 渲染预览
    ///
    /// # 参数
    /// - `timestamp`: 时间戳
    pub fn render_preview(&mut self, timestamp: i32) {
        let _ = self.render_state.render_preview(&self.shapes, timestamp);
    }

    /// 重建修饰符瓦片
    ///
    /// # 参数
    /// - `ids`: 需要重建的形状 ID 列表
    pub fn rebuild_modifier_tiles(&mut self, ids: Vec<Uuid>) {
        self.render_state
            .rebuild_modifier_tiles(&mut self.shapes, ids);
    }

    /// 获取字体集合
    pub fn font_collection(&self) -> &FontCollection {
        self.render_state.fonts().font_collection()
    }

    /// 获取网格坐标
    ///
    /// # 参数
    /// - `pos_x`, `pos_y`: 查询点坐标
    ///
    /// # 返回值
    /// 如果点在某个网格单元格内，返回 (行号, 列号)
    pub fn get_grid_coords(&self, pos_x: f32, pos_y: f32) -> Option<(i32, i32)> {
        let shape = self.current_shape()?;
        let bounds = shape.bounds();
        let position = Point::new(pos_x, pos_y);

        let cells = grid_cell_data(shape, &self.shapes, true);

        for cell in cells {
            let points = &[
                cell.anchor,
                cell.anchor + bounds.hv(cell.width),
                cell.anchor + bounds.hv(cell.width) + bounds.vv(cell.height),
                cell.anchor + bounds.vv(cell.height),
            ];

            let polygon = Path::polygon(points, true, None, None);

            if polygon.contains(position) {
                return Some((cell.row as i32 + 1, cell.column as i32 + 1));
            }
        }

        None
    }

    /// 设置修饰符
    ///
    /// # 参数
    /// - `modifiers`: UUID 到变换矩阵的映射
    pub fn set_modifiers(&mut self, modifiers: HashMap<Uuid, skia::Matrix>) {
        self.shapes.set_modifiers(modifiers);
    }

    /// 标记当前形状为已触碰
    pub fn touch_current(&mut self) {
        if let Some(current_id) = self.current_id {
            self.render_state.mark_touched(current_id);
        }
    }

    /// 标记形状为已触碰
    ///
    /// # 参数
    /// - `id`: 形状 UUID
    pub fn touch_shape(&mut self, id: Uuid) {
        self.render_state.mark_touched(id);
    }
}
