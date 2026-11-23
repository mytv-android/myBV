//! Conservative, stateful stereo ambience extraction, not object recovery.
//! Four complementary bands telescope to the dry input. Correlation is a
//! heuristic: incoherent content is not evidence of a rear or overhead source.

use crate::{PcmEncoding, ProcessError};
use std::f64::consts::{FRAC_1_SQRT_2, TAU};

const BANDS: usize = 4;
const CONTROL_FRAMES: usize = 32;
const CEILING: f64 = 0.98;

#[derive(Clone, Copy, Debug)]
pub struct UpmixSettings {
    /// Fraction of positively correlated, balanced content sent to centre.
    pub centre: f64,
    pub surround: f64,
    pub decorrelation: f64,
    /// Includes user-selectable headroom; applied equally to all channels.
    pub trim_db: f64,
}

impl Default for UpmixSettings {
    fn default() -> Self {
        Self {
            centre: 0.45,
            surround: 0.30,
            decorrelation: 0.65,
            trim_db: -3.0,
        }
    }
}

impl UpmixSettings {
    fn valid(self) -> bool {
        [
            (self.centre, 0.75),
            (self.surround, 0.60),
            (self.decorrelation, 1.0),
        ]
        .into_iter()
        .all(|(v, max)| v.is_finite() && (0.0..=max).contains(&v))
            && self.trim_db.is_finite()
            && (-12.0..=6.0).contains(&self.trim_db)
    }
}

#[derive(Clone, Copy, Default)]
struct Band {
    left_power: f64,
    right_power: f64,
    cross: f64,
    centre: f64,
    ambience: f64,
    target_centre: f64,
    target_ambience: f64,
    diffuse: bool,
}

struct AllPass {
    delay: Vec<f64>,
    cursor: usize,
}

impl AllPass {
    fn new(rate: u32, ms: f64) -> Self {
        Self {
            delay: vec![0.0; (rate as f64 * ms / 1000.0).round().max(1.0) as usize],
            cursor: 0,
        }
    }

    fn process(&mut self, input: f64) -> f64 {
        let output = self.delay[self.cursor] - 0.45 * input;
        self.delay[self.cursor] = denormal(input + 0.45 * output);
        self.cursor = (self.cursor + 1) % self.delay.len();
        output
    }

    fn reset(&mut self) {
        self.delay.fill(0.0);
        self.cursor = 0;
    }
}

/// Owned by one playback stream. Construction allocates delay lines; process
/// and reset allocate nothing, retain no borrowed buffers and perform no I/O.
pub struct AdaptiveUpmixer {
    settings: UpmixSettings,
    crossover: [f64; BANDS - 1],
    low: [[f64; BANDS - 1]; 2],
    bands: [Band; BANDS],
    allpass: [[AllPass; 2]; 2],
    detector_step: f64,
    steering_step: f64,
    transient_step: f64,
    limiter_step: f64,
    transient_gain: f64,
    limiter_gain: f64,
    trim: f64,
    control_frame: usize,
}

impl AdaptiveUpmixer {
    pub fn new(rate: u32, settings: UpmixSettings) -> Result<Self, ProcessError> {
        if !(8000..=192000).contains(&rate) || !settings.valid() {
            return Err(ProcessError::InvalidGain);
        }
        let step = |seconds: f64| 1.0 - (-1.0 / (rate as f64 * seconds)).exp();
        Ok(Self {
            settings,
            crossover: [200.0_f64, 1200.0, 5000.0]
                .map(|f| 1.0 - (-TAU * f.min(rate as f64 * 0.4) / rate as f64).exp()),
            low: [[0.0; BANDS - 1]; 2],
            bands: [Band::default(); BANDS],
            allpass: [
                [AllPass::new(rate, 6.7), AllPass::new(rate, 11.3)],
                [AllPass::new(rate, 8.9), AllPass::new(rate, 13.1)],
            ],
            detector_step: step(0.025),
            steering_step: step(0.020),
            transient_step: step(0.020),
            limiter_step: step(0.200),
            transient_gain: 1.0,
            limiter_gain: 1.0,
            trim: 10.0_f64.powf(settings.trim_db / 20.0),
            control_frame: 0,
        })
    }

    pub fn reset(&mut self) {
        self.low = [[0.0; BANDS - 1]; 2];
        self.bands = [Band::default(); BANDS];
        for channel in &mut self.allpass {
            for filter in channel {
                filter.reset();
            }
        }
        self.transient_gain = 1.0;
        self.limiter_gain = 1.0;
        self.control_frame = 0;
    }

    /// Complete stereo frames -> FL, FR, FC, LFE, SL, SR. Validate before any
    /// output or state changes. Arbitrary block boundaries produce identical PCM.
    pub fn process(
        &mut self,
        input: &[u8],
        output: &mut [u8],
        encoding: PcmEncoding,
    ) -> Result<usize, ProcessError> {
        let width = encoding.bytes_per_sample();
        if input.len() % (width * 2) != 0 || input.len().checked_mul(3) != Some(output.len()) {
            return Err(ProcessError::BufferLengthMismatch);
        }
        for (source, destination) in input
            .chunks_exact(width * 2)
            .zip(output.chunks_exact_mut(width * 6))
        {
            let left = read(&source[..width], encoding);
            let right = read(&source[width..], encoding);
            let mut split = [[0.0; BANDS]; 2];
            for (channel, value) in [left, right].into_iter().enumerate() {
                let mut previous = 0.0;
                for (i, band_sample) in split[channel].iter_mut().enumerate().take(BANDS - 1) {
                    self.low[channel][i] = denormal(
                        self.low[channel][i] + self.crossover[i] * (value - self.low[channel][i]),
                    );
                    *band_sample = self.low[channel][i] - previous;
                    previous = self.low[channel][i];
                }
                split[channel][BANDS - 1] = value - previous;
            }
            let mut centre = 0.0;
            let mut ambience = [0.0; 2];
            let mut transient = false;
            for (i, band) in self.bands.iter_mut().enumerate() {
                let [l, r] = [split[0][i], split[1][i]];
                let power = l * l + r * r;
                transient |= power > (band.left_power + band.right_power) * 16.0 + 1e-5;
                band.left_power =
                    denormal(band.left_power + self.detector_step * (l * l - band.left_power));
                band.right_power =
                    denormal(band.right_power + self.detector_step * (r * r - band.right_power));
                band.cross = denormal(band.cross + self.detector_step * (l * r - band.cross));
                if self.control_frame == 0 {
                    let sum = band.left_power + band.right_power;
                    let product = (band.left_power * band.right_power).sqrt();
                    let correlation = (band.cross / (product + 1e-12)).clamp(-1.0, 1.0);
                    let balance = (2.0 * product / (sum + 1e-12)).clamp(0.0, 1.0);
                    band.target_centre = self.settings.centre
                        * balance
                        * ((correlation - 0.2) / 0.6).clamp(0.0, 1.0);
                    // Reject hard-panned, near-mono, anti-phase and near-silent
                    // content. Low band stays in front for AVR bass management.
                    if band.diffuse {
                        if correlation.abs() > 0.90 || balance < 0.15 || sum < 1e-10 {
                            band.diffuse = false;
                        }
                    } else if correlation.abs() < 0.80 && balance > 0.25 && sum > 1e-9 {
                        band.diffuse = true;
                    }
                    band.target_ambience = if i == 0 || !band.diffuse {
                        0.0
                    } else {
                        self.settings.surround
                            * balance
                            * ((0.90 - correlation.abs()) / 0.90).clamp(0.0, 1.0).powi(2)
                    };
                }
                band.centre =
                    denormal(band.centre + self.steering_step * (band.target_centre - band.centre));
                band.ambience = denormal(
                    band.ambience + self.steering_step * (band.target_ambience - band.ambience),
                );
                centre += (l + r) * 0.5 * band.centre;
                ambience[0] += l * band.ambience;
                ambience[1] += r * band.ambience;
            }
            self.control_frame = (self.control_frame + 1) % CONTROL_FRAMES;
            if transient {
                self.transient_gain = 0.0;
            } else {
                self.transient_gain += self.transient_step * (1.0 - self.transient_gain);
            }
            for (channel, value) in ambience.iter_mut().enumerate() {
                *value *= self.transient_gain;
                let first = self.allpass[channel][0].process(*value);
                let wet = self.allpass[channel][1].process(first);
                *value += self.settings.decorrelation * (wet - *value);
            }
            // Conventional -3 dB fold-down reconstructs dry stereo before the
            // common trim/limiter. Decorrelated subtraction can colour fronts;
            // this is an adjustable tradeoff, not recovery of original channels.
            let mut frame = [
                left - FRAC_1_SQRT_2 * (centre + ambience[0]),
                right - FRAC_1_SQRT_2 * (centre + ambience[1]),
                centre,
                0.0,
                ambience[0],
                ambience[1],
            ];
            let peak = frame
                .iter()
                .fold(0.0_f64, |p, x| p.max(x.abs() * self.trim));
            self.limiter_gain += self.limiter_step * (1.0 - self.limiter_gain);
            if peak > CEILING {
                self.limiter_gain = self.limiter_gain.min(CEILING / peak);
            }
            for (value, bytes) in frame.iter_mut().zip(destination.chunks_exact_mut(width)) {
                *value *= self.trim * self.limiter_gain;
                match encoding {
                    PcmEncoding::Float32 => bytes.copy_from_slice(&(*value as f32).to_le_bytes()),
                    PcmEncoding::Signed16 => bytes.copy_from_slice(
                        &((*value * 32768.0).clamp(i16::MIN as f64, i16::MAX as f64) as i16)
                            .to_le_bytes(),
                    ),
                }
            }
        }
        Ok(input.len() / (width * 2))
    }
}

fn denormal(value: f64) -> f64 {
    if value.abs() < 1e-20 {
        0.0
    } else {
        value
    }
}
fn read(bytes: &[u8], encoding: PcmEncoding) -> f64 {
    match encoding {
        PcmEncoding::Signed16 => i16::from_le_bytes(bytes.try_into().unwrap()) as f64 / 32768.0,
        PcmEncoding::Float32 => {
            let value = f32::from_le_bytes(bytes.try_into().unwrap()) as f64;
            if value.is_finite() {
                value.clamp(-4.0, 4.0)
            } else {
                0.0
            }
        }
    }
}
