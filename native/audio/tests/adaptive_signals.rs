use mybv_audio::{
    adaptive::{AdaptiveUpmixer, UpmixSettings},
    PcmEncoding,
};

fn noise(frames: usize, level: f32) -> Vec<[f32; 2]> {
    let mut seed = 7_u32;
    (0..frames)
        .map(|_| {
            std::array::from_fn(|_| {
                seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
                (seed as f64 / u32::MAX as f64 * 2.0 - 1.0) as f32 * level
            })
        })
        .collect()
}
fn bytes(input: &[[f32; 2]]) -> Vec<u8> {
    input
        .iter()
        .flatten()
        .flat_map(|v| v.to_le_bytes())
        .collect()
}
fn process(state: &mut AdaptiveUpmixer, input: &[[f32; 2]]) -> Vec<[f32; 6]> {
    let input = bytes(input);
    let mut output = vec![0; input.len() * 3];
    assert_eq!(
        state
            .process(&input, &mut output, PcmEncoding::Float32)
            .unwrap(),
        input.len() / 8
    );
    output
        .chunks_exact(24)
        .map(|f| {
            std::array::from_fn(|c| f32::from_le_bytes(f[c * 4..c * 4 + 4].try_into().unwrap()))
        })
        .collect()
}
fn state() -> AdaptiveUpmixer {
    AdaptiveUpmixer::new(48000, UpmixSettings::default()).unwrap()
}

#[test]
fn arbitrary_complete_frame_blocks_do_not_change_control_timing_or_samples() {
    let input = noise(15001, 0.2);
    let expected = process(&mut state(), &input);
    let mut split = state();
    let mut actual = vec![];
    for chunk in input.chunks(37) {
        actual.extend(process(&mut split, chunk));
    }
    assert!(actual == expected, "PCM differs across block boundaries");
}

#[test]
fn mono_stays_in_front_and_centre_without_synthetic_surround_or_lfe() {
    let input: Vec<_> = (0..12000)
        .map(|i| {
            let x = (i as f32 * 0.17).sin() * 0.2;
            [x, x]
        })
        .collect();
    let output = process(&mut state(), &input);
    assert!(output.iter().any(|s| s[2].abs() > 0.04));
    assert!(output.iter().all(|s| s[0] == s[1] && s[3..] == [0.0; 3]));
}

#[test]
fn hard_pan_is_not_mistaken_for_ambience_or_centre() {
    let input: Vec<_> = (0..12000)
        .map(|i| [(i as f32 * 0.3).sin() * 0.2, 0.0])
        .collect();
    assert!(process(&mut state(), &input)
        .iter()
        .all(|s| s[1..] == [0.0; 5]));
}

#[test]
fn anti_phase_stereo_is_kept_in_front_instead_of_sent_strongly_to_rears() {
    let input: Vec<_> = (0..12000)
        .map(|i| {
            let x = (i as f32 * 0.21).sin() * 0.2;
            [x, -x]
        })
        .collect();
    assert!(process(&mut state(), &input)
        .iter()
        .all(|s| s[2..] == [0.0; 4]));
}

#[test]
fn diffuse_stereo_generates_two_non_identical_non_inverted_surrounds() {
    let output = process(&mut state(), &noise(24000, 0.2));
    let (mut l, mut r, mut cross) = (0.0_f64, 0.0_f64, 0.0_f64);
    for s in &output[8000..] {
        l += f64::from(s[4]).powi(2);
        r += f64::from(s[5]).powi(2);
        cross += f64::from(s[4]) * f64::from(s[5]);
        assert_eq!(s[3], 0.0);
    }
    assert!(l > 0.1 && r > 0.1, "surround energy: {l}, {r}");
    assert!((cross / (l * r).sqrt()).abs() < 0.4);
}

#[test]
fn conventional_fold_down_reconstructs_input_at_the_selected_trim_when_not_limited() {
    let input = noise(18000, 0.15);
    let output = process(&mut state(), &input);
    let trim = 10.0_f64.powf(-3.0 / 20.0);
    for (original, s) in input.iter().zip(output) {
        let l = f64::from(s[0]) + std::f64::consts::FRAC_1_SQRT_2 * f64::from(s[2] + s[4]);
        let r = f64::from(s[1]) + std::f64::consts::FRAC_1_SQRT_2 * f64::from(s[2] + s[5]);
        assert!((l - f64::from(original[0]) * trim).abs() < 1e-6);
        assert!((r - f64::from(original[1]) * trim).abs() < 1e-6);
    }
}

#[test]
fn linked_sample_peak_limiter_keeps_loud_and_nonfinite_input_bounded() {
    let input = [
        [3.0, -3.0],
        [f32::NAN, f32::INFINITY],
        [-4.0, 4.0],
        [0.0, 0.0],
    ];
    let output = process(&mut state(), &input);
    assert!(output
        .iter()
        .flatten()
        .all(|x| x.is_finite() && x.abs() <= 0.980001));
    assert_eq!(output[0][0], -output[0][1]);
    assert!(output.iter().all(|s| s[3] == 0.0));
}

#[test]
fn reset_discards_delay_lines_and_detector_history() {
    let mut used = state();
    process(&mut used, &noise(8000, 0.2));
    used.reset();
    let silence = [[0.0, 0.0]; 1000];
    assert_eq!(process(&mut used, &silence), vec![[0.0; 6]; 1000]);
    let input = noise(5000, 0.1);
    let mut fresh = state();
    process(&mut fresh, &silence);
    assert!(
        process(&mut used, &input) == process(&mut fresh, &input),
        "reset retains old stream history"
    );
}

#[test]
fn invalid_blocks_leave_both_output_and_history_untouched() {
    let mut used = state();
    let mut sentinel = [0x55; 24];
    assert!(used
        .process(&[0; 7], &mut sentinel, PcmEncoding::Float32)
        .is_err());
    assert_eq!(sentinel, [0x55; 24]);
    let input = noise(1000, 0.1);
    assert!(
        process(&mut used, &input) == process(&mut state(), &input),
        "invalid input advances state"
    );
}

#[test]
fn disabled_centre_and_surround_preserve_front_stereo_at_zero_trim() {
    let mut dry = AdaptiveUpmixer::new(
        48000,
        UpmixSettings {
            centre: 0.0,
            surround: 0.0,
            trim_db: 0.0,
            ..UpmixSettings::default()
        },
    )
    .unwrap();
    let input = noise(5000, 0.1);
    for (original, output) in input.iter().zip(process(&mut dry, &input)) {
        assert_eq!(&output[..2], original);
        assert_eq!(output[2..], [0.0; 4]);
    }
}

#[test]
fn low_frequency_stereo_is_sent_to_surrounds_less_than_midrange_stereo() {
    let energy = |a: f32, b: f32| {
        let input: Vec<_> = (0..24000)
            .map(|i| {
                [
                    (i as f32 * a * std::f32::consts::TAU / 48000.0).sin() * 0.1,
                    (i as f32 * b * std::f32::consts::TAU / 48000.0).sin() * 0.1,
                ]
            })
            .collect();
        process(&mut state(), &input)[8000..]
            .iter()
            .map(|s| f64::from(s[4]).powi(2) + f64::from(s[5]).powi(2))
            .sum::<f64>()
    };
    let low = energy(30.0, 47.0);
    let mid = energy(1800.0, 2333.0);
    assert!(low < mid * 0.25, "low={low}, mid={mid}");
}

#[test]
fn abrupt_onset_suppresses_new_rear_energy_without_delaying_fronts() {
    let mut used = AdaptiveUpmixer::new(
        48000,
        UpmixSettings {
            decorrelation: 0.0,
            ..UpmixSettings::default()
        },
    )
    .unwrap();
    process(&mut used, &noise(10000, 0.005));
    let impulse = process(&mut used, &[[0.8, 0.8]]);
    assert_eq!(impulse[0][4..], [0.0, 0.0]);
    assert!(impulse[0][0] > 0.1 && impulse[0][1] > 0.1);
}

#[test]
fn supported_sample_rates_and_parameter_bounds_are_explicit() {
    for rate in [8000, 44100, 48000, 96000, 192000] {
        let mut used = AdaptiveUpmixer::new(rate, UpmixSettings::default()).unwrap();
        assert!(process(&mut used, &noise(65, 0.1))
            .iter()
            .flatten()
            .all(|x| x.is_finite()));
    }
    assert!(AdaptiveUpmixer::new(0, UpmixSettings::default()).is_err());
    assert!(AdaptiveUpmixer::new(384000, UpmixSettings::default()).is_err());
    for settings in [
        UpmixSettings {
            centre: f64::NAN,
            ..UpmixSettings::default()
        },
        UpmixSettings {
            surround: 1.0,
            ..UpmixSettings::default()
        },
        UpmixSettings {
            decorrelation: -0.1,
            ..UpmixSettings::default()
        },
        UpmixSettings {
            trim_db: f64::INFINITY,
            ..UpmixSettings::default()
        },
    ] {
        assert!(AdaptiveUpmixer::new(48000, settings).is_err());
    }
}

#[test]
fn pcm16_matches_float_processing_within_one_quantization_step() {
    let pcm: Vec<_> = noise(6000, 0.3)
        .iter()
        .flatten()
        .map(|x| (x * 32768.0) as i16)
        .collect();
    let normalized: Vec<_> = pcm
        .chunks_exact(2)
        .map(|s| [s[0] as f32 / 32768.0, s[1] as f32 / 32768.0])
        .collect();
    let reference = process(&mut state(), &normalized);
    let input: Vec<_> = pcm.iter().flat_map(|s| s.to_le_bytes()).collect();
    let mut output = vec![0; input.len() * 3];
    state()
        .process(&input, &mut output, PcmEncoding::Signed16)
        .unwrap();
    for (encoded, reference) in output.chunks_exact(2).zip(reference.iter().flatten()) {
        let sample = i16::from_le_bytes(encoded.try_into().unwrap()) as f32 / 32768.0;
        assert!((sample - reference).abs() <= 1.0 / 32768.0 + 1e-7);
    }
}
