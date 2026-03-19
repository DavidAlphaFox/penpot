//! =============================================================================
//! WASM API 宏模块 (WASM API Macros Module)
//! =============================================================================
//!
//! 【模块概述】
//! 本模块提供与 JavaScript 动画帧（Animation Frame）交互的宏。
//! 用于控制渲染循环的调度和取消。
//!
//! 【核心概念】
//! 1. requestAnimationFrame - 浏览器 API，用于同步刷新率动画
//! 2. cancelAnimationFrame - 取消之前请求的动画帧
//!
//! 【依赖关系】
//! - 依赖于 Emscripten 在 WASM32 架构下提供的 wapi_requestAnimationFrame
//!   和 wapi_cancelAnimationFrame 函数
//!
//! =============================================================================

/// 请求下一动画帧。
///
/// # 用法
/// ```
/// let frame_id = request_animation_frame!();
/// ```
///
/// # 说明
/// 在 WASM32 架构下调用浏览器的 requestAnimationFrame，
/// 返回帧 ID 可用于后续取消请求。在非 WASM 架构下返回 0。
#[macro_export]
macro_rules! request_animation_frame {
    () => {{
        #[cfg(target_arch = "wasm32")]
        unsafe extern "C" {
            pub fn wapi_requestAnimationFrame() -> i32;
        }

        #[cfg(target_arch = "wasm32")]
        let result = unsafe { wapi_requestAnimationFrame() };
        #[cfg(not(target_arch = "wasm32"))]
        let result = 0;

        result
    }};
}

/// 取消已请求的动画帧。
///
/// # 参数
/// - `$frame_id`: 要取消的帧 ID（由 request_animation_frame! 返回）
///
/// # 说明
/// 通知浏览器取消指定的动画帧请求，防止回调执行。
#[macro_export]
macro_rules! cancel_animation_frame {
    ($frame_id:expr) => {
        #[cfg(target_arch = "wasm32")]
        unsafe extern "C" {
            pub fn wapi_cancelAnimationFrame(frame_id: i32);
        }

        {
            let frame_id = $frame_id;
            #[cfg(target_arch = "wasm32")]
            unsafe {
                wapi_cancelAnimationFrame(frame_id)
            };
            #[cfg(not(target_arch = "wasm32"))]
            let _ = frame_id;
        }
    };
}

pub use cancel_animation_frame;
pub use request_animation_frame;
