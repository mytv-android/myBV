//! Opaque, non-pointer session IDs prevent use-after-free across JNI.
use crate::adaptive::{AdaptiveUpmixer, UpmixSettings};
use crate::jni_bridge::{pcm_encoding, with_direct_buffers};
use jni::{
    objects::{JByteBuffer, JClass},
    sys::{jboolean, jdouble, jint, jlong},
    JNIEnv,
};
use std::{
    collections::HashMap,
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{
        atomic::{AtomicI64, Ordering},
        Mutex, OnceLock,
    },
};

fn sessions() -> &'static Mutex<HashMap<i64, AdaptiveUpmixer>> {
    static SESSIONS: OnceLock<Mutex<HashMap<i64, AdaptiveUpmixer>>> = OnceLock::new();
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

#[no_mangle]
pub extern "system" fn Java_com_github_mytv_bv_player_audio_NativeAdaptiveUpmix_nativeApiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    1
}

#[no_mangle]
pub extern "system" fn Java_com_github_mytv_bv_player_audio_NativeAdaptiveUpmix_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
    rate: jint,
    centre: jdouble,
    surround: jdouble,
    decorrelation: jdouble,
    trim_db: jdouble,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let state = AdaptiveUpmixer::new(
            u32::try_from(rate).ok()?,
            UpmixSettings {
                centre,
                surround,
                decorrelation,
                trim_db,
            },
        )
        .ok()?;
        let mut states = sessions().lock().ok()?;
        if states.len() >= 64 {
            return None;
        }
        static NEXT: AtomicI64 = AtomicI64::new(1);
        let id = NEXT
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |n| n.checked_add(1))
            .ok()?;
        states.insert(id, state);
        Some(id)
    }))
    .ok()
    .flatten()
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_github_mytv_bv_player_audio_NativeAdaptiveUpmix_nativeReset(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        sessions().lock().ok()?.get_mut(&id)?.reset();
        Some(1)
    }))
    .ok()
    .flatten()
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_github_mytv_bv_player_audio_NativeAdaptiveUpmix_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut states) = sessions().lock() {
            states.remove(&id);
        }
    }));
}

/// # Safety
/// VM buffers must remain live, exclusively borrowed, and output writable for
/// this synchronous call. Bounds and overlapping views are checked centrally.
#[no_mangle]
pub unsafe extern "system" fn Java_com_github_mytv_bv_player_audio_NativeAdaptiveUpmix_nativeProcess(
    env: JNIEnv,
    _class: JClass,
    id: jlong,
    input: JByteBuffer,
    offset: jint,
    length: jint,
    output: JByteBuffer,
    output_offset: jint,
    encoding: jint,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let format = pcm_encoding(encoding)?;
        with_direct_buffers(
            &env,
            &input,
            offset,
            length,
            &output,
            output_offset,
            usize::try_from(length).ok()?.checked_mul(3)?,
            |source, destination| {
                sessions()
                    .lock()
                    .ok()?
                    .get_mut(&id)?
                    .process(source, destination, format)
                    .ok()
            },
        )
        .and_then(|frames| jint::try_from(frames).ok())
    }))
    .ok()
    .flatten()
    .unwrap_or(-1)
}
