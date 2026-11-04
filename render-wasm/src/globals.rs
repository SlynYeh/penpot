use macros::wasm_error;

#[cfg(target_arch = "wasm32")]
use crate::emscripten::init_gl;

use crate::mem;
use crate::render::{gpu_state::GpuState, RenderState};
use crate::state::{State, TextEditorState, UIState};

static mut DESIGN_STATE: *mut State = std::ptr::null_mut();

/// Design State.
pub(crate) fn get_design_state() -> &'static mut State {
    unsafe {
        debug_assert!(!DESIGN_STATE.is_null(), "Design State is null");
        &mut *DESIGN_STATE
    }
}

/// GPU State.
static mut GPU_STATE: *mut GpuState = std::ptr::null_mut();

#[inline(always)]
pub(crate) fn get_gpu_state() -> &'static mut GpuState {
    unsafe {
        debug_assert!(!GPU_STATE.is_null(), "GPU State is null");
        &mut *GPU_STATE
    }
}

/// Render State.
static mut RENDER_STATE: *mut RenderState = std::ptr::null_mut();

#[inline(always)]
pub(crate) fn get_render_state() -> &'static mut RenderState {
    unsafe {
        debug_assert!(!RENDER_STATE.is_null(), "Render State is null");
        &mut *RENDER_STATE
    }
}

/// Text Editor State
static mut TEXT_EDITOR_STATE: *mut TextEditorState = std::ptr::null_mut();

#[inline(always)]
pub(crate) fn get_text_editor_state() -> &'static mut TextEditorState {
    unsafe {
        debug_assert!(!TEXT_EDITOR_STATE.is_null(), "Text Editor state is null");
        &mut *TEXT_EDITOR_STATE
    }
}

/// UI State
static mut UI_STATE: *mut UIState = std::ptr::null_mut();

#[inline(always)]
pub(crate) fn get_ui_state() -> &'static mut UIState {
    unsafe {
        debug_assert!(!UI_STATE.is_null(), "UI State is null");
        &mut *UI_STATE
    }
}

// FIXME: These with_state* macros should be using our CriticalError instead of expect.
// But to do that, we need to not use them at domain-level (i.e. in business logic), just
// in the context of the wasm call.
#[macro_export]
macro_rules! with_state {
    ($state:ident, $block:block) => {{
        use $crate::globals::get_design_state;
        let $state = get_design_state();
        $block
    }};
}

#[macro_export]
macro_rules! with_current_shape_mut {
    ($state:ident, |$shape:ident: &mut Shape| $block:block) => {
        use $crate::globals::get_design_state;
        let $state = get_design_state();
        $state.touch_current();
        if let Some($shape) = $state.current_shape_mut() {
            $block
        }
    };
}

#[macro_export]
macro_rules! with_current_shape {
    ($state:ident, |$shape:ident: &Shape| $block:block) => {
        use $crate::globals::get_design_state;
        let $state = get_design_state();
        if let Some($shape) = $state.current_shape() {
            $block
        }
    };
}

/// Drops a previously allocated state, making the slot null again.
///
/// # Safety
///
/// Must only be called from the single-threaded wasm call context, when no
/// references derived from the corresponding `get_*_state` are alive (all of
/// them end within the same exported call).
unsafe fn drop_state<T>(slot: &mut *mut T) {
    if !(*slot).is_null() {
        drop(Box::from_raw(*slot));
        *slot = std::ptr::null_mut();
    }
}

/// Initializes GPUState.
fn gpu_init() {
    unsafe {
        // Abandon defensively: in the normal flow `clean_up` already did it
        // (abandon is idempotent), but on a double `init` without a previous
        // cleanup (e.g. hot reload or a context-restore retry) this
        // guarantees the drop below never issues GL commands against
        // whichever context `init_gl` just made current.
        #[allow(static_mut_refs)]
        if !GPU_STATE.is_null() {
            (*GPU_STATE).context.abandon();
        }

        let gpu_state = GpuState::try_new().expect("Cannot initialize GPU State");
        // Free the previous generation only after the new one is built, so a
        // failed initialization keeps the old state alive (same as before).
        #[allow(static_mut_refs)]
        drop_state(&mut GPU_STATE);
        GPU_STATE = Box::into_raw(Box::new(gpu_state));
    }
}

/// Initializes RenderState.
fn render_init(width: i32, height: i32) {
    unsafe {
        let render_state =
            RenderState::try_new(width, height).expect("Cannot initialize RenderState");
        // Safe to drop the previous generation here: `gpu_init` above already
        // abandoned the GL context its GPU-backed resources belong to, so
        // destructors won't issue GL commands.
        #[allow(static_mut_refs)]
        drop_state(&mut RENDER_STATE);
        RENDER_STATE = Box::into_raw(Box::new(render_state));
    }
}

/// Initializes DesignState.
fn design_init() {
    unsafe {
        let design_state = State::new();
        #[allow(static_mut_refs)]
        drop_state(&mut DESIGN_STATE);
        DESIGN_STATE = Box::into_raw(Box::new(design_state));
    }
}

/// Initializes TextEditorState.
fn text_editor_init() {
    unsafe {
        let text_editor_state = TextEditorState::new();
        #[allow(static_mut_refs)]
        drop_state(&mut TEXT_EDITOR_STATE);
        TEXT_EDITOR_STATE = Box::into_raw(Box::new(text_editor_state));
    }
}

/// Initializes UIState.
fn ui_init() {
    unsafe {
        let ui_state = UIState::new();
        #[allow(static_mut_refs)]
        drop_state(&mut UI_STATE);
        UI_STATE = Box::into_raw(Box::new(ui_state));
    }
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn init(width: i32, height: i32) -> Result<()> {
    #[cfg(target_arch = "wasm32")]
    init_gl!();
    gpu_init();
    render_init(width, height);
    text_editor_init();
    design_init();
    ui_init();
    Ok(())
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn clean_up() -> Result<()> {
    // Cancel the current animation frame if it exists so
    // it won't try to render without context
    let render_state = get_render_state();
    render_state.prepare_context_loss_cleanup();
    // Drop the design state instead of leaking it by nulling the pointer.
    // GPU/render/text-editor/ui states are intentionally left
    // allocated-but-stale here: the window between clean_up and the next
    // init must keep behaving like today for stray unguarded wasm calls;
    // the next `init` drops them right before replacing.
    #[allow(static_mut_refs)]
    unsafe {
        drop_state(&mut DESIGN_STATE)
    }
    mem::free_bytes()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::sync::atomic::{AtomicUsize, Ordering};

    static DROPS: AtomicUsize = AtomicUsize::new(0);

    struct DropCounter;

    impl Drop for DropCounter {
        fn drop(&mut self) {
            DROPS.fetch_add(1, Ordering::SeqCst);
        }
    }

    #[test]
    fn drop_state_drops_previous_generation_and_ignores_null() {
        static mut SLOT: *mut DropCounter = std::ptr::null_mut();

        unsafe {
            // A null slot is a no-op.
            #[allow(static_mut_refs)]
            drop_state(&mut SLOT);
            assert_eq!(DROPS.load(Ordering::SeqCst), 0);

            // Dropping a live slot runs the value's destructor and nulls it.
            SLOT = Box::into_raw(Box::new(DropCounter));
            #[allow(static_mut_refs)]
            drop_state(&mut SLOT);
            assert!(SLOT.is_null());
            assert_eq!(DROPS.load(Ordering::SeqCst), 1);

            // Dropping again after null is still a no-op (no double free).
            #[allow(static_mut_refs)]
            drop_state(&mut SLOT);
            assert_eq!(DROPS.load(Ordering::SeqCst), 1);
        }
    }
}
