//! JNI owns no playback state and never changes Java buffer positions.
//! A failed call returns NaN; the caller recomputes the entire output block.

use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jdouble, jint};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::spatial::{upmix_51, SpatialParameters};
use crate::{process_gain, GainRamp, PcmEncoding};

#[no_mangle]
pub extern "system" fn Java_com_github_mytv_bv_player_audio_NativePcmGain_nativeApiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    1
}

#[no_mangle]
/// # Safety
/// The VM must supply a valid JNI environment and live direct-buffer objects.
/// The caller exclusively owns the byte ranges during the call and supplies a
/// writable output buffer (checked by the Kotlin bridge before entering JNI).
pub unsafe extern "system" fn Java_com_github_mytv_bv_player_audio_NativePcmGain_nativeProcess(
    env: JNIEnv,
    _class: JClass,
    input: JByteBuffer,
    input_offset: jint,
    length: jint,
    output: JByteBuffer,
    output_offset: jint,
    encoding: jint,
    channels: jint,
    current: jdouble,
    target: jdouble,
    step: jdouble,
) -> jdouble {
    catch_unwind(AssertUnwindSafe(|| {
        process_direct_buffers(
            env,
            input,
            input_offset,
            length,
            output,
            output_offset,
            encoding,
            channels,
            GainRamp {
                current,
                target,
                step_per_frame: step,
            },
        )
    }))
    .ok()
    .flatten()
    .unwrap_or(f64::NAN)
}

#[allow(clippy::too_many_arguments)]
fn process_direct_buffers(
    env: JNIEnv,
    input: JByteBuffer,
    input_offset: jint,
    length: jint,
    output: JByteBuffer,
    output_offset: jint,
    encoding: jint,
    channels: jint,
    ramp: GainRamp,
) -> Option<f64> {
    let channels = usize::try_from(channels).ok()?;
    let encoding = pcm_encoding(encoding)?;
    with_direct_buffers(
        &env,
        &input,
        input_offset,
        length,
        &output,
        output_offset,
        usize::try_from(length).ok()?,
        |source, destination| process_gain(source, destination, encoding, channels, ramp).ok(),
    )
}

pub(crate) fn pcm_encoding(value: jint) -> Option<PcmEncoding> {
    match value {
        1 => Some(PcmEncoding::Signed16),
        2 => Some(PcmEncoding::Float32),
        _ => None,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_github_mytv_bv_player_audio_NativeSpatialAudio_nativeApiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    1
}

/// # Safety
/// Same live, exclusive, writable direct-buffer contract as nativeProcess.
#[no_mangle]
pub unsafe extern "system" fn Java_com_github_mytv_bv_player_audio_NativeSpatialAudio_nativeUpmix51(
    env: JNIEnv,
    _class: JClass,
    input: JByteBuffer,
    input_offset: jint,
    length: jint,
    output: JByteBuffer,
    output_offset: jint,
    encoding: jint,
    surround: jdouble,
    start_pan: jdouble,
    end_pan: jdouble,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let format = pcm_encoding(encoding)?;
        let output_length = usize::try_from(length).ok()?.checked_mul(3)?;
        with_direct_buffers(
            &env,
            &input,
            input_offset,
            length,
            &output,
            output_offset,
            output_length,
            |source, destination| {
                upmix_51(
                    source,
                    destination,
                    format,
                    SpatialParameters {
                        surround,
                        start_pan,
                        end_pan,
                    },
                )
                .ok()
            },
        )
        .and_then(|frames| jint::try_from(frames).ok())
    }))
    .ok()
    .flatten()
    .unwrap_or(-1)
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn with_direct_buffers<R>(
    env: &JNIEnv,
    input: &JByteBuffer,
    input_offset: jint,
    length: jint,
    output: &JByteBuffer,
    output_offset: jint,
    output_length: usize,
    operation: impl FnOnce(&[u8], &mut [u8]) -> Option<R>,
) -> Option<R> {
    let input_offset = usize::try_from(input_offset).ok()?;
    let output_offset = usize::try_from(output_offset).ok()?;
    let length = usize::try_from(length).ok()?;
    let input_capacity = env.get_direct_buffer_capacity(input).ok()?;
    let output_capacity = env.get_direct_buffer_capacity(output).ok()?;
    if input_offset.checked_add(length)? > input_capacity
        || output_offset.checked_add(output_length)? > output_capacity
        || length > isize::MAX as usize
        || output_length > isize::MAX as usize
    {
        return None;
    }
    let input_base = env.get_direct_buffer_address(input).ok()?;
    let output_base = env.get_direct_buffer_address(output).ok()?;
    if input_base.is_null() || output_base.is_null() {
        return None;
    }
    let input_start = (input_base as usize).checked_add(input_offset)?;
    let output_start = (output_base as usize).checked_add(output_offset)?;
    let input_end = input_start.checked_add(length)?;
    let output_end = output_start.checked_add(output_length)?;
    // Distinct ByteBuffer objects can still be views of the same allocation.
    if input_start < output_end && output_start < input_end {
        return None;
    }
    // SAFETY: JNI has supplied live direct buffers. The caller owns the two
    // objects for this synchronous call, supplies writable output, and must not
    // access their memory from another thread. Bounds and disjointness were
    // checked above; no pointer is stored after this call. Byte slices impose no
    // sample-alignment requirement.
    let (source, destination) = unsafe {
        (
            std::slice::from_raw_parts(input_base.add(input_offset), length),
            std::slice::from_raw_parts_mut(output_base.add(output_offset), output_length),
        )
    };
    operation(source, destination)
}
