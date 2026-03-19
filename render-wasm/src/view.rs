//! =============================================================================
//! 视口模块 (Viewport Module)
//! =============================================================================
//!
//! 【模块概述】
//! 本模块定义了视口（Viewport）数据结构，用于管理画布的显示区域。
//! 视口包含位置（平移）、缩放和尺寸信息，用于将世界坐标映射到屏幕坐标。
//!
//! 【核心概念】
//! 1. 视口 (Viewport) - 用户可见的画布区域
//! 2. 平移 (Pan) - 视口的平移偏移量
//! 3. 缩放 (Zoom) - 视口的缩放级别
//! 4. 视图矩阵 (View Matrix) - 用于坐标变换的矩阵
//!
//! 【依赖关系】
//! - math::Matrix - 矩阵运算
//! - skia_safe::Rect - Skia 矩形类型
//!
//! =============================================================================

use skia_safe::Rect;

use crate::math::{Matrix, Point};

/// 视口结构体
///
/// # 字段说明
/// - `pan_x`, `pan_y`: 平移偏移量
/// - `width`, `height`: 视口尺寸
/// - `zoom`: 缩放级别
/// - `area`: 视口区域（Skia Rect）
#[derive(Debug, Copy, Clone)]
pub(crate) struct Viewbox {
    /// X 轴平移偏移量
    pub pan_x: f32,
    /// Y 轴平移偏移量
    pub pan_y: f32,
    /// 视口宽度
    pub width: f32,
    /// 视口高度
    pub height: f32,
    /// 缩放级别
    pub zoom: f32,
    /// 视口区域（世界坐标）
    pub area: Rect,
}

impl Default for Viewbox {
    fn default() -> Self {
        Self {
            pan_x: 0.,
            pan_y: 0.,
            width: 0.0,
            height: 0.0,
            zoom: 1.0,
            area: Rect::new_empty(),
        }
    }
}

impl Viewbox {
    /// 创建新的视口
    ///
    /// # 参数
    /// - `width`: 视口宽度
    /// - `height`: 视口高度
    pub fn new(width: f32, height: f32) -> Self {
        let area = Rect::from_xywh(0., 0., width, height);
        Self {
            width,
            height,
            area,
            ..Self::default()
        }
    }

    /// 设置完整的视口参数
    ///
    /// # 参数
    /// - `zoom`: 缩放级别
    /// - `pan_x`: X 轴平移
    /// - `pan_y`: Y 轴平移
    pub fn set_all(&mut self, zoom: f32, pan_x: f32, pan_y: f32) {
        self.pan_x = pan_x;
        self.pan_y = pan_y;
        self.zoom = zoom;
        self.area.set_xywh(
            -self.pan_x,
            -self.pan_y,
            self.width / self.zoom,
            self.height / self.zoom,
        );
    }

    /// 设置视口尺寸
    ///
    /// # 参数
    /// - `width`: 新宽度
    /// - `height`: 新高度
    pub fn set_wh(&mut self, width: f32, height: f32) {
        self.width = width;
        self.height = height;
        self.area
            .set_wh(self.width / self.zoom, self.height / self.zoom);
    }

    /// 获取平移点
    ///
    /// # 返回值
    /// 包含平移量的 Point
    pub fn pan(&self) -> Point {
        Point::new(self.pan_x, self.pan_y)
    }

    /// 获取缩放级别
    pub fn zoom(&self) -> f32 {
        self.zoom
    }

    /// 获取视图变换矩阵
    ///
    /// # 返回值
    /// 先平移后缩放的 2D 变换矩阵
    pub fn get_matrix(&self) -> Matrix {
        let mut matrix = Matrix::new_identity();
        matrix.post_translate(self.pan());
        matrix.post_scale((self.zoom, self.zoom), None);
        matrix
    }
}
