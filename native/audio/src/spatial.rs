//! Conservative channel-based upmixing. This produces PCM, not an Atmos stream.

use crate::{PcmEncoding, ProcessError};

const SQRT_HALF: f64 = std::f64::consts::FRAC_1_SQRT_2;

#[derive(Clone, Copy, Debug)]
pub struct SpatialParameters {
    /// Side-signal contribution to surrounds, 0..0.5.
    pub surround: f64,
    /// Slow control-rate position for the centre-derived component, -1..1.
    pub start_pan: f64,
    pub end_pan: f64,
}

/// Converts stereo to interleaved FL, FR, FC, LFE, surround-left/right PCM
/// using Android's CHANNEL_OUT_5POINT1 channel order.
///
/// LFE stays silent: bass management belongs to the receiver. At neutral pan,
/// a conventional -3 dB centre/surround fold-down reconstructs the source, up
/// to output clipping/quantization. Visual control is interpolated per frame.
/// Inputs must contain complete stereo frames; validation precedes all writes.
pub fn upmix_51(
    input: &[u8],
    output: &mut [u8],
    encoding: PcmEncoding,
    parameters: SpatialParameters,
) -> Result<usize, ProcessError> {
    let width = encoding.bytes_per_sample();
    let input_frame = width * 2;
    if input.len() % input_frame != 0 || input.len().checked_mul(3) != Some(output.len()) {
        return Err(ProcessError::BufferLengthMismatch);
    }
    if !parameters.surround.is_finite()
        || !(0.0..=0.5).contains(&parameters.surround)
        || !parameters.start_pan.is_finite()
        || !parameters.end_pan.is_finite()
        || !(-1.0..=1.0).contains(&parameters.start_pan)
        || !(-1.0..=1.0).contains(&parameters.end_pan)
    {
        return Err(ProcessError::InvalidGain);
    }
    let frames = input.len() / input_frame;
    for (index, (source, destination)) in input
        .chunks_exact(input_frame)
        .zip(output.chunks_exact_mut(width * 6))
        .enumerate()
    {
        let left = read_sample(&source[..width], encoding);
        let right = read_sample(&source[width..], encoding);
        // Corrupted float samples must not contaminate every output channel.
        let left = if left.is_finite() { left } else { 0.0 };
        let right = if right.is_finite() { right } else { 0.0 };
        let mid = (left + right) * 0.5;
        let side = (left - right) * 0.5;
        let pan = parameters.start_pan
            + (parameters.end_pan - parameters.start_pan) * (index + 1) as f64
                / frames.max(1) as f64;
        let centre_base = mid * 0.5;
        let centre = centre_base * (1.0 - pan.abs());
        let surround_left = side * parameters.surround;
        let surround_right = -surround_left;
        let steer_left = centre_base * (-pan).max(0.0);
        let steer_right = centre_base * pan.max(0.0);
        let samples = [
            left - centre_base * SQRT_HALF - surround_left * SQRT_HALF + steer_left,
            right - centre_base * SQRT_HALF - surround_right * SQRT_HALF + steer_right,
            centre,
            0.0,
            surround_left,
            surround_right,
        ];
        for (sample, bytes) in samples.into_iter().zip(destination.chunks_exact_mut(width)) {
            write_sample(bytes, encoding, sample);
        }
    }
    Ok(frames)
}

fn read_sample(bytes: &[u8], encoding: PcmEncoding) -> f64 {
    match encoding {
        PcmEncoding::Signed16 => i16::from_le_bytes([bytes[0], bytes[1]]) as f64 / 32768.0,
        PcmEncoding::Float32 => f32::from_le_bytes(bytes.try_into().unwrap()) as f64,
    }
}

fn write_sample(bytes: &mut [u8], encoding: PcmEncoding, value: f64) {
    match encoding {
        PcmEncoding::Signed16 => {
            let sample = (value * 32768.0).clamp(i16::MIN as f64, i16::MAX as f64) as i16;
            bytes.copy_from_slice(&sample.to_le_bytes());
        }
        PcmEncoding::Float32 => {
            bytes.copy_from_slice(&(value.clamp(-1.0, 1.0) as f32).to_le_bytes())
        }
    }
}

/// A model's result is a hint, never proof that a visible object emitted sound.
#[derive(Clone, Copy, Debug)]
pub struct VisualAudioHint {
    pub timestamp_us: i64,
    pub horizontal_position: f64,
    pub visual_confidence: f64,
    pub audio_match_confidence: f64,
}

/// Rejects stale/future hints and weak audio-visual associations. Screen x/y
/// never imply a rear or overhead position; this hint controls front pan only.
pub fn trusted_front_pan(hint: VisualAudioHint, audio_timestamp_us: i64) -> Option<f64> {
    let age = audio_timestamp_us.checked_sub(hint.timestamp_us)?;
    if !(0..=500_000).contains(&age)
        || !hint.horizontal_position.is_finite()
        || !(-1.0..=1.0).contains(&hint.horizontal_position)
        || !hint.visual_confidence.is_finite()
        || !(0.8..=1.0).contains(&hint.visual_confidence)
        || !hint.audio_match_confidence.is_finite()
        || !(0.85..=1.0).contains(&hint.audio_match_confidence)
    {
        return None;
    }
    Some(hint.horizontal_position)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn params() -> SpatialParameters {
        SpatialParameters {
            surround: 0.35,
            start_pan: 0.0,
            end_pan: 0.0,
        }
    }

    #[test]
    fn mono_sits_in_front_and_never_creates_lfe_or_surround_content() {
        let input: Vec<_> = [0.25_f32, 0.25]
            .into_iter()
            .flat_map(f32::to_le_bytes)
            .collect();
        let mut output = [0_u8; 24];
        assert_eq!(
            upmix_51(&input, &mut output, PcmEncoding::Float32, params()),
            Ok(1)
        );
        let samples: Vec<_> = output
            .chunks_exact(4)
            .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
            .collect();
        assert_eq!(samples[2], 0.125);
        assert_eq!(&samples[3..], &[0.0, 0.0, 0.0]);
        assert_eq!(samples[0], samples[1]);
    }

    #[test]
    fn neutral_fold_down_reconstructs_the_stereo_signal() {
        for (l, r) in [(0.1_f32, -0.2_f32), (0.4, 0.3), (-0.25, -0.25), (0.0, 0.4)] {
            let input: Vec<_> = [l, r].into_iter().flat_map(f32::to_le_bytes).collect();
            let mut output = [0_u8; 24];
            upmix_51(&input, &mut output, PcmEncoding::Float32, params()).unwrap();
            let s: Vec<_> = output
                .chunks_exact(4)
                .map(|b| f32::from_le_bytes(b.try_into().unwrap()) as f64)
                .collect();
            assert!((s[0] + SQRT_HALF * (s[2] + s[4]) - l as f64).abs() < 1e-7);
            assert!((s[1] + SQRT_HALF * (s[2] + s[5]) - r as f64).abs() < 1e-7);
        }
    }

    #[test]
    fn malformed_blocks_and_parameters_do_not_touch_output() {
        let mut output = [0x55_u8; 24];
        assert_eq!(
            upmix_51(&[0; 7], &mut output, PcmEncoding::Float32, params()),
            Err(ProcessError::BufferLengthMismatch)
        );
        let mut invalid = params();
        invalid.end_pan = f64::NAN;
        assert_eq!(
            upmix_51(&[0; 8], &mut output, PcmEncoding::Float32, invalid),
            Err(ProcessError::InvalidGain)
        );
        assert_eq!(output, [0x55; 24]);
    }

    #[test]
    fn non_finite_samples_do_not_poison_other_channels() {
        let input: Vec<_> = [f32::NAN, f32::INFINITY]
            .into_iter()
            .flat_map(f32::to_le_bytes)
            .collect();
        let mut output = [0_u8; 24];
        upmix_51(&input, &mut output, PcmEncoding::Float32, params()).unwrap();
        assert!(output
            .chunks_exact(4)
            .all(|b| f32::from_le_bytes(b.try_into().unwrap()).is_finite()));
    }

    #[test]
    fn visual_hints_require_audio_matching_and_the_correct_media_clock() {
        let hint = VisualAudioHint {
            timestamp_us: 1_000_000,
            horizontal_position: -0.6,
            visual_confidence: 0.95,
            audio_match_confidence: 0.9,
        };
        assert_eq!(trusted_front_pan(hint, 1_100_000), Some(-0.6));
        assert_eq!(trusted_front_pan(hint, 900_000), None);
        assert_eq!(trusted_front_pan(hint, 1_600_000), None);
        assert_eq!(
            trusted_front_pan(
                VisualAudioHint {
                    audio_match_confidence: 0.2,
                    ..hint
                },
                1_100_000
            ),
            None
        );
        assert_eq!(
            trusted_front_pan(
                VisualAudioHint {
                    visual_confidence: f64::NAN,
                    ..hint
                },
                1_100_000
            ),
            None
        );
    }
}
