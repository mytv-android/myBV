//! Small PCM kernels independent of Android, decoding, and audio output.
//!
//! Android owns the buffers and playback clock. A call borrows one complete
//! block; it does not retain pointers, allocate buffers, or create threads.

#[cfg(feature = "jni-bridge")]
mod jni_bridge;

pub mod spatial;

/// PCM byte order is little-endian, as used by supported Android ABIs.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PcmEncoding {
    Signed16,
    Float32,
}

impl PcmEncoding {
    fn bytes_per_sample(self) -> usize {
        match self {
            Self::Signed16 => 2,
            Self::Float32 => 4,
        }
    }
}

/// Snapshot of the existing player's gain ramp for one audio block.
#[derive(Clone, Copy, Debug)]
pub struct GainRamp {
    pub current: f64,
    pub target: f64,
    pub step_per_frame: f64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ProcessError {
    BufferLengthMismatch,
    InvalidChannels,
    InvalidGain,
}

/// Applies one gain to all channels in a frame, advancing before each frame.
///
/// This preserves the Kotlin processor's clipping and truncation semantics.
/// A trailing partial frame is copied unchanged. All validation happens before
/// writing, so callers can safely retry with another backend on failure.
pub fn process_gain(
    input: &[u8],
    output: &mut [u8],
    encoding: PcmEncoding,
    channels: usize,
    mut ramp: GainRamp,
) -> Result<f64, ProcessError> {
    if input.len() != output.len() {
        return Err(ProcessError::BufferLengthMismatch);
    }
    let frame_bytes = channels
        .checked_mul(encoding.bytes_per_sample())
        .filter(|&size| size != 0)
        .ok_or(ProcessError::InvalidChannels)?;
    if !ramp.current.is_finite()
        || !ramp.target.is_finite()
        || !ramp.step_per_frame.is_finite()
        || ramp.current < 0.0
        || ramp.target < 0.0
    {
        return Err(ProcessError::InvalidGain);
    }

    if ramp.current == 1.0 && ramp.target == 1.0 {
        output.copy_from_slice(input);
        return Ok(ramp.current);
    }

    let complete_bytes = input.len() / frame_bytes * frame_bytes;
    for (source, destination) in input[..complete_bytes]
        .chunks_exact(frame_bytes)
        .zip(output[..complete_bytes].chunks_exact_mut(frame_bytes))
    {
        if ramp.current != ramp.target {
            ramp.current = if ramp.step_per_frame >= 0.0 {
                ramp.target.min(ramp.current + ramp.step_per_frame)
            } else {
                ramp.target.max(ramp.current + ramp.step_per_frame)
            };
        }
        match encoding {
            PcmEncoding::Signed16 => {
                for (sample, result) in source.chunks_exact(2).zip(destination.chunks_exact_mut(2))
                {
                    let value = i16::from_le_bytes([sample[0], sample[1]]) as f64;
                    let scaled = (value * ramp.current).clamp(i16::MIN as f64, i16::MAX as f64);
                    result.copy_from_slice(&(scaled as i16).to_le_bytes());
                }
            }
            PcmEncoding::Float32 => {
                for (sample, result) in source.chunks_exact(4).zip(destination.chunks_exact_mut(4))
                {
                    let value = f32::from_le_bytes([sample[0], sample[1], sample[2], sample[3]]);
                    let scaled = ((value as f64 * ramp.current).clamp(-1.0, 1.0)) as f32;
                    result.copy_from_slice(&scaled.to_le_bytes());
                }
            }
        }
    }
    output[complete_bytes..].copy_from_slice(&input[complete_bytes..]);
    Ok(ramp.current)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pcm16(values: &[i16]) -> Vec<u8> {
        values
            .iter()
            .flat_map(|sample| sample.to_le_bytes())
            .collect()
    }

    fn gain(current: f64, target: f64, step_per_frame: f64) -> GainRamp {
        GainRamp {
            current,
            target,
            step_per_frame,
        }
    }

    #[test]
    fn unity_is_bit_exact_even_for_nan_float_payloads_and_partial_frames() {
        let input = [0x45, 0x23, 0xc1, 0x7f, 0, 0, 0, 0x80, 0xff];
        let mut output = [0; 9];
        assert_eq!(
            process_gain(
                &input,
                &mut output,
                PcmEncoding::Float32,
                2,
                gain(1.0, 1.0, 0.0)
            ),
            Ok(1.0)
        );
        assert_eq!(input, output);
    }

    #[test]
    fn stereo_channels_share_the_same_ramp_step_and_clip_to_pcm_limits() {
        let input = pcm16(&[1000, -1000, 20000, -20000, i16::MAX, i16::MIN]);
        let mut output = vec![0; input.len()];
        let final_gain = process_gain(
            &input,
            &mut output,
            PcmEncoding::Signed16,
            2,
            gain(1.0, 2.0, 0.5),
        )
        .unwrap();
        assert_eq!(
            output,
            pcm16(&[1500, -1500, i16::MAX, i16::MIN, i16::MAX, i16::MIN])
        );
        assert_eq!(final_gain, 2.0);
    }

    #[test]
    fn attenuation_truncates_toward_zero_and_preserves_partial_frame() {
        let mut input = pcm16(&[3, -3, 5, -5]);
        input.extend_from_slice(&[0xa5, 0x5a, 0x7f]);
        let mut output = vec![0; input.len()];
        process_gain(
            &input,
            &mut output,
            PcmEncoding::Signed16,
            2,
            gain(0.5, 0.5, 0.0),
        )
        .unwrap();
        let mut expected = pcm16(&[1, -1, 2, -2]);
        expected.extend_from_slice(&[0xa5, 0x5a, 0x7f]);
        assert_eq!(output, expected);
    }

    #[test]
    fn ramp_is_continuous_across_arbitrary_block_boundaries() {
        let input = pcm16(&[1000; 32]);
        let mut combined = vec![0; input.len()];
        let mut partitioned = vec![0; input.len()];
        let end = process_gain(
            &input,
            &mut combined,
            PcmEncoding::Signed16,
            2,
            gain(1.0, 0.25, -0.075),
        )
        .unwrap();
        let mut current = 1.0;
        for (source, output) in input.chunks(12).zip(partitioned.chunks_mut(12)) {
            current = process_gain(
                source,
                output,
                PcmEncoding::Signed16,
                2,
                gain(current, 0.25, -0.075),
            )
            .unwrap();
        }
        assert_eq!(combined, partitioned);
        assert_eq!(current, end);
    }

    #[test]
    fn float_output_clips_without_changing_nan_semantics() {
        let values = [
            0.25_f32,
            -0.25,
            0.75,
            -0.75,
            f32::INFINITY,
            f32::NEG_INFINITY,
            f32::NAN,
            0.0,
        ];
        let input: Vec<_> = values
            .iter()
            .flat_map(|value| value.to_le_bytes())
            .collect();
        let mut output = vec![0; input.len()];
        process_gain(
            &input,
            &mut output,
            PcmEncoding::Float32,
            2,
            gain(2.0, 2.0, 0.0),
        )
        .unwrap();
        let samples: Vec<_> = output
            .chunks_exact(4)
            .map(|bytes| f32::from_le_bytes(bytes.try_into().unwrap()))
            .collect();
        assert_eq!(&samples[..6], &[0.5, -0.5, 1.0, -1.0, 1.0, -1.0]);
        assert!(samples[6].is_nan());
        assert_eq!(samples[7], 0.0);
    }

    #[test]
    fn rejects_invalid_input_before_writing_any_output() {
        let input = [0; 8];
        let mut output = [0xa5; 8];
        for invalid in [
            gain(f64::NAN, 1.0, 0.0),
            gain(1.0, f64::INFINITY, 0.0),
            gain(-1.0, 1.0, 0.0),
            gain(1.0, 2.0, f64::NAN),
        ] {
            assert_eq!(
                process_gain(&input, &mut output, PcmEncoding::Signed16, 2, invalid),
                Err(ProcessError::InvalidGain)
            );
            assert_eq!(output, [0xa5; 8]);
        }
        assert_eq!(
            process_gain(
                &input,
                &mut output,
                PcmEncoding::Signed16,
                0,
                gain(1.0, 1.0, 0.0)
            ),
            Err(ProcessError::InvalidChannels)
        );
        assert_eq!(output, [0xa5; 8]);
        assert_eq!(
            process_gain(
                &input[..4],
                &mut output,
                PcmEncoding::Signed16,
                2,
                gain(1.0, 1.0, 0.0)
            ),
            Err(ProcessError::BufferLengthMismatch)
        );
        assert_eq!(output, [0xa5; 8]);
    }

    #[test]
    fn empty_and_multichannel_blocks_are_supported() {
        assert_eq!(
            process_gain(&[], &mut [], PcmEncoding::Signed16, 8, gain(0.5, 0.5, 0.0)),
            Ok(0.5)
        );
        let input = pcm16(&[1000; 16]);
        let mut output = vec![0; input.len()];
        process_gain(
            &input,
            &mut output,
            PcmEncoding::Signed16,
            8,
            gain(1.0, 0.5, -0.25),
        )
        .unwrap();
        assert_eq!(
            output,
            pcm16(&[
                750, 750, 750, 750, 750, 750, 750, 750, 500, 500, 500, 500, 500, 500, 500, 500
            ])
        );
    }
}
