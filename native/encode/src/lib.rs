//! Opaque, serialized encoder handles. PCM/output pointers are borrowed only for one JNI call.
use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;

#[cfg(target_os = "android")]
mod codec {
    use std::collections::HashMap;
    use std::ffi::c_void;
    use std::sync::{Mutex, OnceLock};
    extern "C" {
        fn mybv_encoder_create(rate: i32, bitrate: i32) -> *mut c_void;
        fn mybv_encoder_free(encoder: *mut c_void);
        fn mybv_encoder_push(encoder: *mut c_void, pcm: *const u8, frames: i32) -> i32;
        fn mybv_encoder_read(encoder: *mut c_void, output: *mut u8, capacity: i32) -> i32;
        fn mybv_encoder_finish(encoder: *mut c_void);
        fn mybv_encoder_delay(encoder: *mut c_void) -> i32;
    }
    struct Handles {
        next: u64,
        entries: HashMap<u64, usize>,
    }
    fn handles() -> &'static Mutex<Handles> {
        static HANDLES: OnceLock<Mutex<Handles>> = OnceLock::new();
        HANDLES.get_or_init(|| {
            Mutex::new(Handles {
                next: 1,
                entries: HashMap::new(),
            })
        })
    }
    pub fn create(rate: i32, bitrate: i32) -> Option<u64> {
        let pointer = unsafe { mybv_encoder_create(rate, bitrate) };
        if pointer.is_null() {
            return None;
        }
        let mut registry = match handles().lock() {
            Ok(value) => value,
            Err(_) => {
                unsafe { mybv_encoder_free(pointer) };
                return None;
            }
        };
        let handle = registry.next;
        registry.next = match handle.checked_add(1) {
            Some(next) if next <= i64::MAX as u64 => next,
            _ => {
                unsafe { mybv_encoder_free(pointer) };
                return None;
            }
        };
        registry.entries.insert(handle, pointer as usize);
        Some(handle)
    }
    pub fn free(handle: u64) {
        let pointer = handles()
            .lock()
            .ok()
            .and_then(|mut h| h.entries.remove(&handle));
        if let Some(pointer) = pointer {
            unsafe { mybv_encoder_free(pointer as *mut c_void) };
        }
    }
    pub fn push(handle: u64, pcm: &[u8], frames: i32) -> Option<i32> {
        let registry = handles().lock().ok()?;
        let pointer = *registry.entries.get(&handle)?;
        Some(unsafe { mybv_encoder_push(pointer as *mut c_void, pcm.as_ptr(), frames) })
    }
    pub fn read(handle: u64, output: &mut [u8]) -> Option<i32> {
        let registry = handles().lock().ok()?;
        let pointer = *registry.entries.get(&handle)?;
        Some(unsafe {
            mybv_encoder_read(
                pointer as *mut c_void,
                output.as_mut_ptr(),
                i32::try_from(output.len()).ok()?,
            )
        })
    }
    pub fn finish(handle: u64) {
        if let Ok(registry) = handles().lock() {
            if let Some(pointer) = registry.entries.get(&handle) {
                unsafe { mybv_encoder_finish(*pointer as *mut c_void) };
            }
        }
    }
    pub fn delay(handle: u64) -> i32 {
        handles()
            .lock()
            .ok()
            .and_then(|registry| {
                registry
                    .entries
                    .get(&handle)
                    .map(|p| unsafe { mybv_encoder_delay(*p as *mut c_void) })
            })
            .unwrap_or(0)
    }
}

#[no_mangle]
pub extern "system" fn Java_com_github_mytv_bv_player_audio_NativeEac3_nativeAvailable(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    u8::from(cfg!(target_os = "android"))
}
#[no_mangle]
pub extern "system" fn Java_com_github_mytv_bv_player_audio_NativeEac3_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
    _rate: jint,
    _bitrate: jint,
) -> jlong {
    #[cfg(target_os = "android")]
    {
        codec::create(_rate, _bitrate)
            .map(|h| h as i64)
            .unwrap_or(0)
    }
    #[cfg(not(target_os = "android"))]
    {
        0
    }
}
#[no_mangle]
pub extern "system" fn Java_com_github_mytv_bv_player_audio_NativeEac3_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    _handle: jlong,
) {
    #[cfg(target_os = "android")]
    if let Ok(handle) = u64::try_from(_handle) {
        codec::free(handle);
    }
}
#[no_mangle]
pub extern "system" fn Java_com_github_mytv_bv_player_audio_NativeEac3_nativeFinish(
    _env: JNIEnv,
    _class: JClass,
    _handle: jlong,
) {
    #[cfg(target_os = "android")]
    if let Ok(handle) = u64::try_from(_handle) {
        codec::finish(handle);
    }
}
#[no_mangle]
pub extern "system" fn Java_com_github_mytv_bv_player_audio_NativeEac3_nativeDelay(
    _env: JNIEnv,
    _class: JClass,
    _handle: jlong,
) -> jint {
    #[cfg(target_os = "android")]
    {
        u64::try_from(_handle).map(codec::delay).unwrap_or(0)
    }
    #[cfg(not(target_os = "android"))]
    {
        0
    }
}

/// # Safety
/// The caller owns a live direct PCM buffer for this synchronous call.
#[no_mangle]
pub unsafe extern "system" fn Java_com_github_mytv_bv_player_audio_NativeEac3_nativePush(
    _env: JNIEnv,
    _class: JClass,
    _handle: jlong,
    _input: JByteBuffer,
    _offset: jint,
    _frames: jint,
) -> jint {
    #[cfg(target_os = "android")]
    {
        let operation = || -> Option<i32> {
            if !(1..=1024).contains(&_frames) {
                return None;
            }
            let offset = usize::try_from(_offset).ok()?;
            let length = usize::try_from(_frames).ok()?.checked_mul(12)?;
            if offset.checked_add(length)? > _env.get_direct_buffer_capacity(&_input).ok()? {
                return None;
            }
            let base = _env.get_direct_buffer_address(&_input).ok()?;
            if base.is_null() {
                return None;
            }
            // SAFETY: borrowed direct buffer, validated non-null and byte bounds.
            let input = unsafe { std::slice::from_raw_parts(base.add(offset), length) };
            codec::push(u64::try_from(_handle).ok()?, input, _frames)
        };
        operation().unwrap_or(-22)
    }
    #[cfg(not(target_os = "android"))]
    {
        -22
    }
}

/// # Safety
/// The caller supplies an exclusively writable direct output range.
#[no_mangle]
pub unsafe extern "system" fn Java_com_github_mytv_bv_player_audio_NativeEac3_nativeRead(
    _env: JNIEnv,
    _class: JClass,
    _handle: jlong,
    _output: JByteBuffer,
    _offset: jint,
    _length: jint,
) -> jint {
    #[cfg(target_os = "android")]
    {
        let operation = || -> Option<i32> {
            let offset = usize::try_from(_offset).ok()?;
            let length = usize::try_from(_length).ok()?;
            if length == 0
                || length > 32768
                || offset.checked_add(length)? > _env.get_direct_buffer_capacity(&_output).ok()?
            {
                return None;
            }
            let base = _env.get_direct_buffer_address(&_output).ok()?;
            if base.is_null() {
                return None;
            }
            // SAFETY: live exclusive direct output, checked non-null and byte bounds.
            let output = unsafe { std::slice::from_raw_parts_mut(base.add(offset), length) };
            codec::read(u64::try_from(_handle).ok()?, output)
        };
        operation().unwrap_or(-22)
    }
    #[cfg(not(target_os = "android"))]
    {
        -22
    }
}
