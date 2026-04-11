package whisper;

/**
 * 軽量なASR前処理っす。
 * まずは最小構成として、DC blocker 兼ゆるい high-pass だけを入れるっす。
 * 将来ここへ band-pass / notch / denoise を足せるように、入口を1か所に寄せるっす。
 */
public final class AudioPrefilter {
    private static final double HUM_NOTCH_Q = 18.0;
    private static final int[] NOTCH_FREQS_HZ = {50, 60, 100, 120, 150, 180};
    private static final int STRONG_SPECTRAL_FRAME_SIZE = 128;
    private static final int STRONG_SPECTRAL_HOP_SIZE = 64;
    private static final double STRONG_SPECTRAL_SUBTRACT = 0.92;
    private static final double STRONG_SPECTRAL_FLOOR = 0.24;
    private static final double STRONG_SPECTRAL_NOISE_DOWN = 0.14;
    private static final double STRONG_SPECTRAL_NOISE_UP = 0.028;
    private static final double STRONG_DEREVERB_FACTOR = 0.045;
    private static final double STRONG_DEREVERB_DECAY = 0.72;
    private static final double[] STRONG_WINDOW = buildHannWindow(STRONG_SPECTRAL_FRAME_SIZE);
    private static final double[][] STRONG_COS = buildTrigTable(STRONG_SPECTRAL_FRAME_SIZE, true);
    private static final double[][] STRONG_SIN = buildTrigTable(STRONG_SPECTRAL_FRAME_SIZE, false);

    public static final class VoiceMetrics {
        public final double rms;
        public final double zcr;
        public final double voiceBandRatio;
        public final int peak;

        private VoiceMetrics(double rms, double zcr, double voiceBandRatio, int peak) {
            this.rms = rms;
            this.zcr = zcr;
            this.voiceBandRatio = voiceBandRatio;
            this.peak = peak;
        }
    }

    public enum Mode {
        TALK,
        HEARING
    }

    public enum Profile {
        OFF,
        NORMAL,
        STRONG;

        public static Profile fromKey(String key) {
            if (key == null) return NORMAL;
            return switch (key.trim().toLowerCase()) {
                case "off", "disabled", "none" -> OFF;
                case "strong", "aggressive" -> STRONG;
                default -> NORMAL;
            };
        }
    }

    public static final class State {
        private double hpPrevX = 0.0;
        private double hpPrevY = 0.0;
        private double prePrevX = 0.0;
        private double lpPrevY = 0.0;
        private double agcGain = 1.0;
        private int notchSampleRateHz = 0;
        private final double[] notchX1 = new double[NOTCH_FREQS_HZ.length];
        private final double[] notchX2 = new double[NOTCH_FREQS_HZ.length];
        private final double[] notchY1 = new double[NOTCH_FREQS_HZ.length];
        private final double[] notchY2 = new double[NOTCH_FREQS_HZ.length];
        private int spectralSampleRateHz = 0;
        private double[] spectralNoiseFloor = new double[0];
        private double[] spectralLateReverb = new double[0];

        public void reset() {
            hpPrevX = 0.0;
            hpPrevY = 0.0;
            prePrevX = 0.0;
            lpPrevY = 0.0;
            agcGain = 1.0;
            notchSampleRateHz = 0;
            spectralSampleRateHz = 0;
            java.util.Arrays.fill(notchX1, 0.0);
            java.util.Arrays.fill(notchX2, 0.0);
            java.util.Arrays.fill(notchY1, 0.0);
            java.util.Arrays.fill(notchY2, 0.0);
            spectralNoiseFloor = new double[0];
            spectralLateReverb = new double[0];
        }
    }

    private static final double DEFAULT_HP_CUTOFF_HZ = 80.0;
    private static final double DEFAULT_LP_CUTOFF_HZ = 6500.0;
    private static final double STRONG_HP_CUTOFF_HZ = 75.0;
    private static final double STRONG_LP_CUTOFF_HZ = 6000.0;
    private static final double STRONG_PRE_EMPHASIS_ALPHA = 0.42;
    private static final double NORMAL_LIMIT_DRIVE = 0.92;
    private static final double STRONG_LIMIT_DRIVE = 0.86;
    private static final double TARGET_FINAL_RMS_DBFS = -21.0;
    private static final double PEAK_CEILING_DBFS = -3.0;
    private static final double MIN_NORMALIZE_RMS = 220.0;
    private static final double STREAMING_AGC_TARGET_RMS_DBFS = -22.5;
    private static final double STREAMING_AGC_MIN_RMS = 140.0;
    private static final double STREAMING_AGC_GAIN_UP_ALPHA = 0.10;
    private static final double STREAMING_AGC_GAIN_DOWN_ALPHA = 0.28;
    private static final double STREAMING_AGC_IDLE_ALPHA = 0.04;
    private static final double STREAMING_AGC_MIN_GAIN = 0.72;
    private static final double NORMAL_STREAMING_AGC_MAX_GAIN = 2.4;
    private static final double STRONG_STREAMING_AGC_MAX_GAIN = 3.4;

    private AudioPrefilter() {}

    public static byte[] processForAsr(byte[] pcm16le,
                                       int bytes,
                                       int sampleRateHz,
                                       Mode mode) {
        State state = new State();
        return processForAsr(pcm16le, bytes, sampleRateHz, mode, Profile.NORMAL, state);
    }

    public static byte[] processForAsr(byte[] pcm16le,
                                       int bytes,
                                       int sampleRateHz,
                                       Mode mode,
                                       State state) {
        return processForAsr(pcm16le, bytes, sampleRateHz, mode, Profile.NORMAL, state);
    }

    public static byte[] processForAsr(byte[] pcm16le,
                                       int bytes,
                                       int sampleRateHz,
                                       Mode mode,
                                       String profileKey,
                                       State state) {
        return processForAsr(pcm16le, bytes, sampleRateHz, mode, Profile.fromKey(profileKey), state);
    }

    public static byte[] processForAsr(byte[] pcm16le,
                                       int bytes,
                                       int sampleRateHz,
                                       Mode mode,
                                       Profile profile,
                                       State state) {
        if (pcm16le == null || bytes <= 0) return pcm16le;
        if (bytes < 4) {
            byte[] tiny = new byte[bytes];
            System.arraycopy(pcm16le, 0, tiny, 0, bytes);
            return tiny;
        }

        State useState = (state != null) ? state : new State();
        Profile useProfile = (profile != null) ? profile : Profile.NORMAL;
        if (useProfile == Profile.OFF) {
            useState.reset();
            byte[] copy = new byte[bytes];
            System.arraycopy(pcm16le, 0, copy, 0, bytes);
            return copy;
        }
        return filterVoiceBand(pcm16le, bytes, Math.max(8000, sampleRateHz), useProfile, useState);
    }

    public static byte[] normalizeFinalChunkForAsr(byte[] pcm16le, int bytes) {
        if (pcm16le == null || bytes < 4) return pcm16le;
        long sumSq = 0L;
        int peak = 0;
        int samples = bytes / 2;
        for (int i = 0; i + 1 < bytes; i += 2) {
            short s = readPcm16le(pcm16le, i);
            int abs = Math.abs((int) s);
            if (abs > peak) peak = abs;
            sumSq += (long) s * (long) s;
        }
        if (samples <= 0) return pcm16le;
        double rms = Math.sqrt((double) sumSq / (double) samples);
        if (rms < MIN_NORMALIZE_RMS || peak <= 0) return pcm16le;

        double targetLinear = dbfsToLinear(TARGET_FINAL_RMS_DBFS) * Short.MAX_VALUE;
        double ceilingLinear = dbfsToLinear(PEAK_CEILING_DBFS) * Short.MAX_VALUE;
        double gain = targetLinear / Math.max(1.0, rms);
        gain = Math.min(gain, ceilingLinear / Math.max(1.0, peak));
        if (gain < 1.03 || !Double.isFinite(gain)) return pcm16le;

        byte[] out = new byte[bytes];
        for (int i = 0; i + 1 < bytes; i += 2) {
            short s = readPcm16le(pcm16le, i);
            int v = (int) Math.round(s * gain);
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
            writePcm16le(out, i, v);
        }
        return out;
    }

    public static byte[] processForMultimodal(byte[] pcm16le, int bytes, int sampleRateHz) {
        return processForMultimodal(pcm16le, bytes, sampleRateHz, Profile.NORMAL);
    }

    public static byte[] processForMultimodal(byte[] pcm16le,
                                              int bytes,
                                              int sampleRateHz,
                                              String profileKey) {
        return processForMultimodal(pcm16le, bytes, sampleRateHz, Profile.fromKey(profileKey));
    }

    public static byte[] processForMultimodal(byte[] pcm16le,
                                              int bytes,
                                              int sampleRateHz,
                                              Profile profile) {
        if (pcm16le == null || bytes <= 0) return pcm16le;
        if (bytes < 4) {
            byte[] tiny = new byte[bytes];
            System.arraycopy(pcm16le, 0, tiny, 0, bytes);
            return tiny;
        }

        Profile useProfile = (profile == null) ? Profile.NORMAL : profile;
        if (useProfile == Profile.OFF) {
            byte[] copy = new byte[bytes];
            System.arraycopy(pcm16le, 0, copy, 0, bytes);
            return copy;
        }

        int sampleRate = Math.max(8000, sampleRateHz);
        byte[] out = new byte[bytes];
        State state = new State();
        int samples = bytes / 2;
        double[] working = new double[samples];

        double dt = 1.0 / sampleRate;
        double hpCutoff = (useProfile == Profile.STRONG) ? 160.0 : 110.0;
        double lpCutoff = (useProfile == Profile.STRONG)
                ? Math.min(4200.0, sampleRate * 0.27)
                : Math.min(5600.0, sampleRate * 0.36);
        double preEmphasisAlpha = (useProfile == Profile.STRONG) ? 0.28 : 0.18;
        double limitDrive = (useProfile == Profile.STRONG) ? 0.84 : 0.90;
        double hpRc = 1.0 / (2.0 * Math.PI * hpCutoff);
        double hpAlpha = hpRc / (hpRc + dt);
        double lpRc = 1.0 / (2.0 * Math.PI * lpCutoff);
        double lpAlpha = dt / (lpRc + dt);

        double hpPrevX = 0.0;
        double hpPrevY = 0.0;
        double lpPrevY = 0.0;
        double prePrevX = 0.0;

        for (int i = 0; i < samples; i++) {
            int offset = i * 2;
            double x = readPcm16le(pcm16le, offset);
            working[i] = applyHumNotches(x, sampleRate, state);
        }
        if (samples >= STRONG_SPECTRAL_FRAME_SIZE) {
            working = applyStrongSpectralEnhance(working, sampleRate, state);
        }

        for (int i = 0; i < samples; i++) {
            int offset = i * 2;
            double x = working[i];
            double hp = hpAlpha * (hpPrevY + x - hpPrevX);
            double emphasized = hp - (preEmphasisAlpha * prePrevX);
            prePrevX = hp;
            hpPrevX = x;
            hpPrevY = hp;
            double y = lpPrevY + lpAlpha * (emphasized - lpPrevY);
            lpPrevY = y;
            int v = (int) Math.round(softLimit(y, limitDrive));
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
            writePcm16le(out, offset, v);
        }
        return out;
    }

    public static VoiceMetrics analyzeVoiceLike(byte[] pcm16le, int bytes, int sampleRateHz) {
        if (pcm16le == null || bytes < 4) {
            return new VoiceMetrics(0.0, 0.0, 0.0, 0);
        }
        int sampleRate = Math.max(8000, sampleRateHz);
        int samples = bytes / 2;
        if (samples <= 0) {
            return new VoiceMetrics(0.0, 0.0, 0.0, 0);
        }

        long sumSq = 0L;
        long bandSq = 0L;
        int peak = 0;
        int zeroCrossings = 0;
        short prev = 0;
        boolean hasPrev = false;

        double hpPrevX = 0.0;
        double hpPrevY = 0.0;
        double lpPrevY = 0.0;
        double dt = 1.0 / sampleRate;
        double hpRc = 1.0 / (2.0 * Math.PI * 150.0);
        double hpAlpha = hpRc / (hpRc + dt);
        double lpRc = 1.0 / (2.0 * Math.PI * 3800.0);
        double lpAlpha = dt / (lpRc + dt);

        for (int i = 0; i + 1 < bytes; i += 2) {
            short s = readPcm16le(pcm16le, i);
            int abs = Math.abs((int) s);
            if (abs > peak) peak = abs;
            sumSq += (long) s * (long) s;
            if (hasPrev && ((s >= 0) != (prev >= 0))) {
                zeroCrossings++;
            }
            prev = s;
            hasPrev = true;

            double x = s;
            double hp = hpAlpha * (hpPrevY + x - hpPrevX);
            hpPrevX = x;
            hpPrevY = hp;
            double band = lpPrevY + lpAlpha * (hp - lpPrevY);
            lpPrevY = band;
            int bandInt = (int) Math.round(band);
            bandSq += (long) bandInt * (long) bandInt;
        }

        double rms = Math.sqrt((double) sumSq / (double) samples);
        double zcr = (samples <= 1) ? 0.0 : (double) zeroCrossings / (double) (samples - 1);
        double voiceBandRatio = (sumSq <= 0L) ? 0.0 : Math.sqrt((double) bandSq / (double) sumSq);
        return new VoiceMetrics(rms, zcr, voiceBandRatio, peak);
    }

    public static boolean isVoiceLike(VoiceMetrics metrics, double noiseFloorRms, boolean recentVoiceHold) {
        if (metrics == null) return false;
        double floor = Math.max(80.0, noiseFloorRms);
        double requiredRms = recentVoiceHold ? floor * 1.35 : floor * 1.8;
        double minBandRatio = recentVoiceHold ? 0.26 : 0.32;
        double minPeak = recentVoiceHold ? 700.0 : 900.0;
        if (metrics.peak < minPeak) return false;
        if (metrics.rms < requiredRms) return false;
        if (metrics.zcr < 0.015 || metrics.zcr > 0.32) return false;
        return metrics.voiceBandRatio >= minBandRatio;
    }

    private static byte[] filterVoiceBand(byte[] pcm16le,
                                          int bytes,
                                          int sampleRateHz,
                                          Profile profile,
                                          State state) {
        byte[] out = new byte[bytes];
        int samples = bytes / 2;
        double[] working = new double[samples];

        double hpCutoff = (profile == Profile.STRONG) ? STRONG_HP_CUTOFF_HZ : DEFAULT_HP_CUTOFF_HZ;
        double lpCutoff = (profile == Profile.STRONG) ? STRONG_LP_CUTOFF_HZ : DEFAULT_LP_CUTOFF_HZ;
        double preAlpha = (profile == Profile.STRONG) ? STRONG_PRE_EMPHASIS_ALPHA : 0.0;

        double dt = 1.0 / sampleRateHz;
        double hpRc = 1.0 / (2.0 * Math.PI * hpCutoff);
        double hpAlpha = hpRc / (hpRc + dt);
        double lpRc = 1.0 / (2.0 * Math.PI * lpCutoff);
        double lpAlpha = dt / (lpRc + dt);

        double hpPrevX = state.hpPrevX;
        double hpPrevY = state.hpPrevY;
        double prePrevX = state.prePrevX;
        double lpPrevY = state.lpPrevY;

        if (state.notchSampleRateHz != sampleRateHz) {
            state.notchSampleRateHz = sampleRateHz;
            java.util.Arrays.fill(state.notchX1, 0.0);
            java.util.Arrays.fill(state.notchX2, 0.0);
            java.util.Arrays.fill(state.notchY1, 0.0);
            java.util.Arrays.fill(state.notchY2, 0.0);
        }
        double limitDrive = (profile == Profile.STRONG) ? STRONG_LIMIT_DRIVE : NORMAL_LIMIT_DRIVE;

        for (int i = 0; i + 1 < bytes; i += 2) {
            double x = readPcm16le(pcm16le, i);
            working[i / 2] = applyHumNotches(x, sampleRateHz, state);
        }
        if (profile == Profile.STRONG) {
            working = applyStrongSpectralEnhance(working, sampleRateHz, state);
        }

        double[] shaped = new double[samples];
        for (int i = 0; i < samples; i++) {
            double x = working[i];
            double hp = hpAlpha * (hpPrevY + x - hpPrevX);
            hpPrevX = x;
            hpPrevY = hp;

            double voice = hp;
            if (preAlpha > 0.0) {
                double preInput = voice;
                voice = preInput - (preAlpha * prePrevX);
                prePrevX = preInput;
            }

            double y = lpPrevY + lpAlpha * (voice - lpPrevY);
            lpPrevY = y;
            shaped[i] = y;
        }

        applyStreamingAgc(shaped, profile, state);

        for (int i = 0; i < samples; i++) {
            int v = (int) Math.round(softLimit(shaped[i], limitDrive));
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
            writePcm16le(out, i * 2, v);
        }

        state.hpPrevX = hpPrevX;
        state.hpPrevY = hpPrevY;
        state.prePrevX = prePrevX;
        state.lpPrevY = lpPrevY;
        return out;
    }

    private static void applyStreamingAgc(double[] samples, Profile profile, State state) {
        if (samples == null || samples.length == 0 || state == null) return;
        double sumSq = 0.0;
        double peak = 0.0;
        for (double sample : samples) {
            double abs = Math.abs(sample);
            if (abs > peak) peak = abs;
            sumSq += sample * sample;
        }
        double rms = Math.sqrt(sumSq / samples.length);
        double maxGain = (profile == Profile.STRONG)
                ? STRONG_STREAMING_AGC_MAX_GAIN
                : NORMAL_STREAMING_AGC_MAX_GAIN;
        double targetLinear = dbfsToLinear(STREAMING_AGC_TARGET_RMS_DBFS) * Short.MAX_VALUE;
        double targetGain = 1.0;
        if (rms >= STREAMING_AGC_MIN_RMS) {
            targetGain = targetLinear / Math.max(1.0, rms);
            targetGain = Math.max(STREAMING_AGC_MIN_GAIN, Math.min(maxGain, targetGain));
        }

        double currentGain = (state.agcGain > 0.0) ? state.agcGain : 1.0;
        double alpha;
        if (rms < STREAMING_AGC_MIN_RMS) {
            alpha = STREAMING_AGC_IDLE_ALPHA;
            targetGain = 1.0;
        } else {
            alpha = (targetGain > currentGain)
                    ? STREAMING_AGC_GAIN_UP_ALPHA
                    : STREAMING_AGC_GAIN_DOWN_ALPHA;
        }
        currentGain = currentGain + ((targetGain - currentGain) * alpha);

        double peakCeiling = dbfsToLinear(PEAK_CEILING_DBFS) * Short.MAX_VALUE;
        if (peak > 1.0) {
            currentGain = Math.min(currentGain, peakCeiling / peak);
        }
        currentGain = Math.max(STREAMING_AGC_MIN_GAIN, Math.min(maxGain, currentGain));
        state.agcGain = currentGain;

        for (int i = 0; i < samples.length; i++) {
            samples[i] *= currentGain;
        }
    }

    private static double[] applyStrongSpectralEnhance(double[] input, int sampleRateHz, State state) {
        int n = STRONG_SPECTRAL_FRAME_SIZE;
        int hop = STRONG_SPECTRAL_HOP_SIZE;
        int bins = (n / 2) + 1;
        if (input.length < 16) return input;
        if (state.spectralSampleRateHz != sampleRateHz || state.spectralNoiseFloor.length != bins) {
            state.spectralSampleRateHz = sampleRateHz;
            state.spectralNoiseFloor = new double[bins];
            state.spectralLateReverb = new double[bins];
        }

        double[] out = new double[input.length];
        double[] norm = new double[input.length];
        double[] frame = new double[n];
        double[] re = new double[n];
        double[] im = new double[n];
        double[] time = new double[n];

        for (int start = 0; start < input.length; start += hop) {
            java.util.Arrays.fill(frame, 0.0);
            java.util.Arrays.fill(re, 0.0);
            java.util.Arrays.fill(im, 0.0);
            for (int i = 0; i < n; i++) {
                int src = start + i;
                double sample = (src < input.length) ? input[src] : 0.0;
                frame[i] = sample * STRONG_WINDOW[i];
            }

            dft(frame, re, im);
            for (int k = 0; k < bins; k++) {
                double mag = Math.hypot(re[k], im[k]);
                double prevNoise = state.spectralNoiseFloor[k];
                if (prevNoise <= 0.0) {
                    state.spectralNoiseFloor[k] = mag;
                } else if (mag < prevNoise) {
                    state.spectralNoiseFloor[k] = (prevNoise * (1.0 - STRONG_SPECTRAL_NOISE_DOWN))
                            + (mag * STRONG_SPECTRAL_NOISE_DOWN);
                } else {
                    state.spectralNoiseFloor[k] = (prevNoise * (1.0 - STRONG_SPECTRAL_NOISE_UP))
                            + (mag * STRONG_SPECTRAL_NOISE_UP);
                }

                double suppressed = mag
                        - (STRONG_SPECTRAL_SUBTRACT * state.spectralNoiseFloor[k])
                        - (STRONG_DEREVERB_FACTOR * state.spectralLateReverb[k]);
                double cleanMag = Math.max(suppressed, STRONG_SPECTRAL_FLOOR * mag);
                double scale = cleanMag / Math.max(1e-9, mag);
                re[k] *= scale;
                im[k] *= scale;
                state.spectralLateReverb[k] = (state.spectralLateReverb[k] * STRONG_DEREVERB_DECAY)
                        + (cleanMag * (1.0 - STRONG_DEREVERB_DECAY));
            }
            mirrorSpectrum(re, im, bins);
            idft(re, im, time);

            for (int i = 0; i < n; i++) {
                int dst = start + i;
                if (dst >= input.length) break;
                double win = STRONG_WINDOW[i];
                out[dst] += time[i] * win;
                norm[dst] += win * win;
            }
        }

        double[] filtered = new double[input.length];
        for (int i = 0; i < input.length; i++) {
            filtered[i] = (norm[i] > 1e-9) ? (out[i] / norm[i]) : input[i];
        }
        return filtered;
    }

    private static double applyHumNotches(double x, int sampleRateHz, State state) {
        double y = x;
        for (int i = 0; i < NOTCH_FREQS_HZ.length; i++) {
            int hz = NOTCH_FREQS_HZ[i];
            if (hz >= sampleRateHz / 2) continue;
            double w0 = 2.0 * Math.PI * hz / sampleRateHz;
            double alpha = Math.sin(w0) / (2.0 * HUM_NOTCH_Q);
            double b0 = 1.0;
            double b1 = -2.0 * Math.cos(w0);
            double b2 = 1.0;
            double a0 = 1.0 + alpha;
            double a1 = -2.0 * Math.cos(w0);
            double a2 = 1.0 - alpha;
            double out = (b0 / a0) * y
                    + (b1 / a0) * state.notchX1[i]
                    + (b2 / a0) * state.notchX2[i]
                    - (a1 / a0) * state.notchY1[i]
                    - (a2 / a0) * state.notchY2[i];
            state.notchX2[i] = state.notchX1[i];
            state.notchX1[i] = y;
            state.notchY2[i] = state.notchY1[i];
            state.notchY1[i] = out;
            y = out;
        }
        return y;
    }

    private static double softLimit(double sample, double drive) {
        double normalized = sample / Short.MAX_VALUE;
        double driven = normalized / Math.max(0.1, drive);
        double limited = Math.tanh(driven) * drive;
        return limited * Short.MAX_VALUE;
    }

    private static void dft(double[] input, double[] re, double[] im) {
        int n = STRONG_SPECTRAL_FRAME_SIZE;
        for (int k = 0; k < n; k++) {
            double sumRe = 0.0;
            double sumIm = 0.0;
            double[] cosRow = STRONG_COS[k];
            double[] sinRow = STRONG_SIN[k];
            for (int t = 0; t < n; t++) {
                double sample = input[t];
                sumRe += sample * cosRow[t];
                sumIm -= sample * sinRow[t];
            }
            re[k] = sumRe;
            im[k] = sumIm;
        }
    }

    private static void idft(double[] re, double[] im, double[] out) {
        int n = STRONG_SPECTRAL_FRAME_SIZE;
        for (int t = 0; t < n; t++) {
            double sum = 0.0;
            for (int k = 0; k < n; k++) {
                sum += (re[k] * STRONG_COS[k][t]) - (im[k] * STRONG_SIN[k][t]);
            }
            out[t] = sum / n;
        }
    }

    private static void mirrorSpectrum(double[] re, double[] im, int bins) {
        int n = STRONG_SPECTRAL_FRAME_SIZE;
        for (int k = bins; k < n; k++) {
            re[k] = 0.0;
            im[k] = 0.0;
        }
        for (int k = 1; k < bins - 1; k++) {
            re[n - k] = re[k];
            im[n - k] = -im[k];
        }
    }

    private static double[] buildHannWindow(int n) {
        double[] window = new double[n];
        for (int i = 0; i < n; i++) {
            window[i] = 0.5 - (0.5 * Math.cos((2.0 * Math.PI * i) / n));
        }
        return window;
    }

    private static double[][] buildTrigTable(int n, boolean cos) {
        double[][] table = new double[n][n];
        for (int k = 0; k < n; k++) {
            for (int t = 0; t < n; t++) {
                double angle = (2.0 * Math.PI * k * t) / n;
                table[k][t] = cos ? Math.cos(angle) : Math.sin(angle);
            }
        }
        return table;
    }

    private static double dbfsToLinear(double dbfs) {
        return Math.pow(10.0, dbfs / 20.0);
    }

    private static short readPcm16le(byte[] pcm16le, int offset) {
        int lo = pcm16le[offset] & 0xFF;
        int hi = pcm16le[offset + 1];
        return (short) (lo | (hi << 8));
    }

    private static void writePcm16le(byte[] pcm16le, int offset, int value) {
        pcm16le[offset] = (byte) (value & 0xFF);
        pcm16le[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }
}
