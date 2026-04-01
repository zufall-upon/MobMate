package whisper;

import java.util.Arrays;
import java.util.Locale;

public final class TtsProsodyAnalyzer {
    private static final int PCM_FRAME_MS = 20;
    private static final int MIN_PCM_MS = 280;
    private static final int MIN_SHORT_PCM_MS = 180;

    private TtsProsodyAnalyzer() {}

    public static TtsProsodyProfile analyzeText(String text) {
        if (text == null || text.isBlank()) return TtsProsodyProfile.neutral();

        String raw = text.trim();
        String lower = raw.toLowerCase(Locale.ROOT);

        if (looksLikeSingSong(raw, lower)) {
            return new TtsProsodyProfile("sing_song_lite", 0.09f, 1.30f, 1.00f, 1.03f, 0.84f);
        }
        if (containsAny(raw, TOK_WHISPER) || raw.contains("…") || lower.contains("...")) {
            return new TtsProsodyProfile("whisper", -0.03f, 0.86f, 0.94f, 0.90f, 0.72f);
        }
        if (isQuestion(raw, lower)) {
            return new TtsProsodyProfile("question", 0.02f, 1.08f, 1.00f, 1.00f, 0.66f);
        }
        if (containsAny(lower, TOK_EXCITED_LOWER) || containsAny(raw, TOK_EXCITED_RAW)) {
            return new TtsProsodyProfile("excited", 0.06f, 1.18f, 1.06f, 1.04f, 0.70f);
        }
        if (containsAny(raw, TOK_CALM) || raw.contains("…") || lower.contains("...")) {
            return new TtsProsodyProfile("calm", -0.01f, 0.92f, 0.97f, 0.98f, 0.58f);
        }
        return TtsProsodyProfile.neutral();
    }

    public static TtsProsodyProfile analyzePcm(byte[] pcm16le, int sampleRate) {
        if (pcm16le == null || pcm16le.length < 4 || sampleRate <= 0) return TtsProsodyProfile.neutral();
        int totalSamples = pcm16le.length / 2;
        if (totalSamples < (sampleRate * MIN_SHORT_PCM_MS / 1000)) return TtsProsodyProfile.neutral();
        if (totalSamples < (sampleRate * MIN_PCM_MS / 1000)) {
            return analyzeShortPcm(pcm16le, sampleRate);
        }
        short[] samples = decodePcm16Le(pcm16le);

        int frameSamples = Math.max(80, sampleRate * PCM_FRAME_MS / 1000);
        int frameCount = totalSamples / frameSamples;
        if (frameCount < 4) return TtsProsodyProfile.neutral();

        float[] rmsFrames = new float[frameCount];
        float[] zcrFrames = new float[frameCount];
        float peakAbs = 0f;
        for (int f = 0; f < frameCount; f++) {
            int start = f * frameSamples;
            long sumSq = 0L;
            int zeroCross = 0;
            int prev = 0;
            for (int i = 0; i < frameSamples; i++) {
                int sample = samples[start + i];
                if (i > 0 && ((sample >= 0 && prev < 0) || (sample < 0 && prev >= 0))) {
                    zeroCross++;
                }
                prev = sample;
                int abs = Math.abs(sample);
                if (abs > peakAbs) peakAbs = abs;
                sumSq += (long) sample * sample;
            }
            rmsFrames[f] = (float) Math.sqrt(sumSq / (double) frameSamples);
            zcrFrames[f] = zeroCross / (float) frameSamples;
        }

        float voicedThreshold = Math.max(140f, peakAbs * 0.10f);
        int voicedStart = -1;
        int voicedEnd = -1;
        for (int i = 0; i < frameCount; i++) {
            if (rmsFrames[i] >= voicedThreshold) {
                if (voicedStart < 0) voicedStart = i;
                voicedEnd = i;
            }
        }
        if (voicedStart < 0 || voicedEnd - voicedStart + 1 < 3) return TtsProsodyProfile.neutral();

        float[] pitchContour = estimatePitchContour(samples, sampleRate, frameSamples, rmsFrames, voicedStart, voicedEnd, voicedThreshold);
        float[] activityContour = estimateActivityContour(rmsFrames, meanOrZero(rmsFrames));
        float[] voicingContour = estimateVoicingContour(rmsFrames, voicedThreshold);
        int voicedFrames = voicedEnd - voicedStart + 1;
        int headFrames = Math.max(2, voicedFrames / 3);
        int tailFrames = Math.max(2, voicedFrames / 4);
        float rmsSum = 0f;
        float zcrSum = 0f;
        float maxRms = 0f;
        float headSum = 0f;
        float tailSum = 0f;
        int oscillation = 0;
        float prevDelta = 0f;

        for (int i = voicedStart; i <= voicedEnd; i++) {
            float rms = rmsFrames[i];
            rmsSum += rms;
            zcrSum += zcrFrames[i];
            if (rms > maxRms) maxRms = rms;

            int rel = i - voicedStart;
            if (rel < headFrames) headSum += rms;
            if (rel >= voicedFrames - tailFrames) tailSum += rms;

            if (i > voicedStart) {
                float delta = rmsFrames[i] - rmsFrames[i - 1];
                if (Math.abs(delta) > 120f && Math.signum(prevDelta) != 0f && Math.signum(prevDelta) != Math.signum(delta)) {
                    oscillation++;
                }
                if (Math.abs(delta) > 50f) prevDelta = delta;
            }
        }

        float meanRms = rmsSum / voicedFrames;
        float rmsVar = 0f;
        for (int i = voicedStart; i <= voicedEnd; i++) {
            float delta = rmsFrames[i] - meanRms;
            rmsVar += delta * delta;
        }
        float rmsStd = (float) Math.sqrt(rmsVar / Math.max(1, voicedFrames));
        float meanZcr = zcrSum / voicedFrames;
        float tailMean = tailSum / tailFrames;
        float headMean = headSum / headFrames;
        float normRms = meanRms / 32768f;
        float dynamic = (meanRms <= 1f) ? 1f : (maxRms / meanRms);
        float cv = (meanRms <= 1f) ? 0f : (rmsStd / meanRms);
        float tailRise = (headMean <= 1f) ? 1f : (tailMean / headMean);
        float contourRise = clampSigned((tailRise - 1.0f) / 0.35f);
        float melodyDepth = clamp01((((cv - 0.10f) / 0.45f) * 0.55f)
                + (((dynamic - 1.0f) / 1.30f) * 0.12f)
                + (oscillation * 0.08f));
        if (pitchContour.length >= 3) {
            contourRise = blendContourRise(contourRise, contourRiseFromPitchContour(pitchContour));
            melodyDepth = Math.max(melodyDepth, contourMelodyDepth(pitchContour));
        }
        if (contourRise <= -0.40f) {
            melodyDepth *= 0.72f;
        }
        float contourFallScore = clamp01(((-contourRise) - 0.12f) / 0.70f);
        float darkScore = clamp01((((0.030f - normRms) / 0.022f) * 0.32f)
                + (((0.96f - tailRise) / 0.22f) * 0.26f)
                + (((1.28f - dynamic) / 0.40f) * 0.16f)
                + (((meanZcr - 0.060f) / 0.05f) * 0.10f)
                + (contourFallScore * 0.20f));
        float energyScore = clamp01((((normRms - 0.020f) / 0.060f) * 0.30f)
                + (((dynamic - 1.16f) / 0.75f) * 0.26f)
                + (((cv - 0.12f) / 0.34f) * 0.16f)
                + (Math.max(0.0f, contourRise) * 0.12f));
        float pitchScale = pitchScaleFromPitchContour(pitchContour, contourRise, darkScore, energyScore);

        if (oscillation >= 2 && cv >= 0.24f && dynamic >= 1.16f && normRms >= 0.030f) {
            return withContours(new TtsProsodyProfile("sing_song_lite", Math.max(0.05f, pitchScale), 1.14f, 1.00f, 1.02f, 0.72f,
                    contourRise, Math.max(0.58f, melodyDepth), darkScore * 0.35f, energyScore), pitchContour, activityContour, voicingContour);
        }
        if (darkScore >= 0.58f
                && energyScore <= 0.18f
                && dynamic <= 1.22f
                && (meanZcr >= 0.060f || tailRise < 0.88f || contourRise <= -0.22f)) {
            return withContours(new TtsProsodyProfile("whisper", Math.min(-0.04f, pitchScale - 0.02f), 0.82f, 0.94f, 0.99f, 0.62f,
                    Math.min(contourRise, 0.0f), melodyDepth * 0.25f, Math.max(0.62f, darkScore), energyScore * 0.45f), pitchContour, activityContour, voicingContour);
        }
        if (darkScore >= 0.34f
                && energyScore <= 0.34f
                && dynamic <= 1.34f
                && contourRise <= 0.10f) {
            return withContours(new TtsProsodyProfile("calm", Math.min(-0.02f, pitchScale), 0.88f, 0.96f, 1.00f, 0.60f,
                    Math.min(contourRise, 0.0f), melodyDepth * 0.20f, Math.max(0.48f, darkScore), energyScore * 0.55f), pitchContour, activityContour, voicingContour);
        }
        if (tailRise >= 1.20f
                && contourRise >= 0.18f
                && voicedFrames >= 6
                && normRms >= 0.022f
                && dynamic >= 1.18f
                && darkScore < 0.30f) {
            return withContours(new TtsProsodyProfile("question", Math.max(0.01f, pitchScale), 1.04f, 1.00f, 1.00f, 0.54f,
                    contourRise, melodyDepth * 0.30f, darkScore * 0.20f, energyScore), pitchContour, activityContour, voicingContour);
        }
        if (darkScore >= 0.50f && energyScore <= 0.26f) {
            return withContours(new TtsProsodyProfile("calm", Math.min(-0.015f, pitchScale * 0.9f), 0.90f, 0.97f, 1.00f, 0.42f,
                    Math.min(contourRise, 0.0f), melodyDepth * 0.20f, darkScore, energyScore * 0.45f), pitchContour, activityContour, voicingContour);
        }
        if (((normRms >= 0.10f && dynamic >= 1.34f && energyScore >= 0.58f)
                || (energyScore >= 0.80f && dynamic >= 1.52f))
                && darkScore < 0.22f
                && melodyDepth >= 0.22f
                && contourRise > -0.18f) {
            return withContours(new TtsProsodyProfile("excited", Math.max(0.02f, pitchScale + 0.01f), 1.03f, 1.03f, 1.02f, 0.52f,
                    contourRise, melodyDepth, darkScore * 0.18f, Math.max(0.55f, energyScore)), pitchContour, activityContour, voicingContour);
        }
        if (melodyDepth >= 0.52f && energyScore >= 0.36f && cv >= 0.16f) {
            return withContours(new TtsProsodyProfile("sing_song_lite", pitchScale, 1.03f, 1.00f, 1.01f, 0.44f,
                    contourRise, melodyDepth, darkScore * 0.20f, energyScore), pitchContour, activityContour, voicingContour);
        }
        float neutralConf = (Math.abs(contourRise) >= 0.20f || melodyDepth >= 0.20f || darkScore >= 0.24f || energyScore >= 0.24f)
                ? 0.28f : 0.0f;
        return withContours(new TtsProsodyProfile("neutral", pitchScale * 0.85f, 1.0f, 1.0f, 1.0f, neutralConf,
                contourRise, melodyDepth, darkScore, energyScore), pitchContour, activityContour, voicingContour);
    }

    private static TtsProsodyProfile analyzeShortPcm(byte[] pcm16le, int sampleRate) {
        int totalSamples = pcm16le.length / 2;
        if (totalSamples <= 0) return TtsProsodyProfile.neutral();
        short[] samples = decodePcm16Le(pcm16le);

        long sumSq = 0L;
        int zeroCross = 0;
        int prev = 0;
        float peakAbs = 0f;
        int half = Math.max(1, totalSamples / 2);
        long headSq = 0L;
        long tailSq = 0L;
        int frameSamples = Math.max(80, sampleRate * PCM_FRAME_MS / 1000);
        int shortFrameCount = Math.max(2, totalSamples / frameSamples);
        float[] frameRms = new float[shortFrameCount];
        int framePos = 0;
        int frameFilled = 0;
        long frameSq = 0L;

        for (int i = 0; i < totalSamples; i++) {
            int sample = samples[i];
            if (i > 0 && ((sample >= 0 && prev < 0) || (sample < 0 && prev >= 0))) {
                zeroCross++;
            }
            prev = sample;
            int abs = Math.abs(sample);
            if (abs > peakAbs) peakAbs = abs;
            sumSq += (long) sample * sample;
            if (i < half) {
                headSq += (long) sample * sample;
            } else {
                tailSq += (long) sample * sample;
            }
            frameSq += (long) sample * sample;
            frameFilled++;
            if (frameFilled >= frameSamples || i == totalSamples - 1) {
                frameRms[framePos] = (float) Math.sqrt(frameSq / (double) frameFilled);
                framePos = Math.min(framePos + 1, shortFrameCount - 1);
                frameSq = 0L;
                frameFilled = 0;
            }
        }

        float meanRms = (float) Math.sqrt(sumSq / (double) totalSamples);
        float maxFrameRms = 0f;
        float rmsVar = 0f;
        int usedFrames = Math.max(1, framePos == shortFrameCount - 1 ? shortFrameCount : framePos);
        for (int i = 0; i < usedFrames; i++) {
            float rms = frameRms[i];
            if (rms > maxFrameRms) maxFrameRms = rms;
        }
        for (int i = 0; i < usedFrames; i++) {
            float delta = frameRms[i] - meanRms;
            rmsVar += delta * delta;
        }
        float rmsStd = (float) Math.sqrt(rmsVar / usedFrames);
        float headRms = (float) Math.sqrt(headSq / (double) half);
        float tailRms = (float) Math.sqrt(tailSq / (double) Math.max(1, totalSamples - half));
        float meanZcr = zeroCross / (float) totalSamples;
        float normRms = meanRms / 32768f;
        float dynamic = (meanRms <= 1f) ? 1f : (maxFrameRms / meanRms);
        float cv = (meanRms <= 1f) ? 0f : (rmsStd / meanRms);
        float tailRise = (headRms <= 1f) ? 1f : (tailRms / headRms);
        float contourRise = clampSigned((tailRise - 1.0f) / 0.40f);
        float melodyDepth = clamp01((((cv - 0.08f) / 0.42f) * 0.52f)
                + (((dynamic - 1.0f) / 1.10f) * 0.10f)
                + (Math.max(0.0f, tailRise - 1.0f) * 0.16f));
        float voicedThreshold = Math.max(120f, peakAbs * 0.10f);
        float[] pitchContour = estimatePitchContour(samples, sampleRate, frameSamples, frameRms, 0, Math.max(0, usedFrames - 1), voicedThreshold);
        float[] activityContour = estimateActivityContour(frameRms, meanRms);
        float[] voicingContour = estimateVoicingContour(frameRms, voicedThreshold);
        if (pitchContour.length >= 3) {
            contourRise = blendContourRise(contourRise, contourRiseFromPitchContour(pitchContour));
            melodyDepth = Math.max(melodyDepth, contourMelodyDepth(pitchContour));
        }
        if (contourRise <= -0.35f) {
            melodyDepth *= 0.68f;
        }
        float contourFallScore = clamp01(((-contourRise) - 0.12f) / 0.70f);
        float darkScore = clamp01((((0.028f - normRms) / 0.020f) * 0.30f)
                + (((0.96f - tailRise) / 0.20f) * 0.28f)
                + (((1.24f - dynamic) / 0.35f) * 0.18f)
                + (((meanZcr - 0.060f) / 0.05f) * 0.10f)
                + (contourFallScore * 0.20f));
        float energyScore = clamp01((((normRms - 0.018f) / 0.055f) * 0.30f)
                + (((dynamic - 1.12f) / 0.62f) * 0.24f)
                + (((cv - 0.10f) / 0.30f) * 0.16f)
                + (Math.max(0.0f, contourRise) * 0.12f));
        float pitchScale = pitchScaleFromPitchContour(pitchContour, contourRise, darkScore, energyScore);

        if (darkScore >= 0.56f
                && energyScore <= 0.16f
                && dynamic <= 1.20f
                && (meanZcr >= 0.060f || tailRise < 0.88f || contourRise <= -0.18f)) {
            return withContours(new TtsProsodyProfile("whisper", Math.min(-0.04f, pitchScale - 0.02f), 0.82f, 0.95f, 0.99f, 0.54f,
                    Math.min(contourRise, 0.0f), melodyDepth * 0.20f, Math.max(0.60f, darkScore), energyScore * 0.40f), pitchContour, activityContour, voicingContour);
        }
        if (darkScore >= 0.32f
                && energyScore <= 0.32f
                && dynamic <= 1.30f
                && contourRise <= 0.08f) {
            return withContours(new TtsProsodyProfile("calm", Math.min(-0.02f, pitchScale), 0.88f, 0.97f, 1.00f, 0.50f,
                    Math.min(contourRise, 0.0f), melodyDepth * 0.18f, Math.max(0.46f, darkScore), energyScore * 0.50f), pitchContour, activityContour, voicingContour);
        }
        if (tailRise >= 1.24f
                && contourRise >= 0.20f
                && normRms >= 0.020f
                && dynamic >= 1.18f
                && darkScore < 0.28f) {
            return withContours(new TtsProsodyProfile("question", Math.max(0.01f, pitchScale), 1.03f, 1.00f, 1.00f, 0.30f,
                    contourRise, melodyDepth * 0.25f, darkScore * 0.20f, energyScore), pitchContour, activityContour, voicingContour);
        }
        if (darkScore >= 0.48f && energyScore <= 0.24f) {
            return withContours(new TtsProsodyProfile("calm", Math.min(-0.015f, pitchScale * 0.9f), 0.90f, 0.98f, 1.00f, 0.34f,
                    Math.min(contourRise, 0.0f), melodyDepth * 0.15f, darkScore, energyScore * 0.45f), pitchContour, activityContour, voicingContour);
        }
        if (((normRms >= 0.10f && dynamic >= 1.28f && energyScore >= 0.56f)
                || (energyScore >= 0.78f && dynamic >= 1.42f))
                && darkScore < 0.20f
                && melodyDepth >= 0.20f
                && contourRise > -0.15f) {
            return withContours(new TtsProsodyProfile("excited", Math.max(0.02f, pitchScale + 0.01f), 1.03f, 1.02f, 1.02f, 0.34f,
                    contourRise, melodyDepth, darkScore * 0.15f, Math.max(0.50f, energyScore)), pitchContour, activityContour, voicingContour);
        }
        if (melodyDepth >= 0.48f && energyScore >= 0.34f && cv >= 0.14f) {
            return withContours(new TtsProsodyProfile("sing_song_lite", pitchScale, 1.02f, 1.00f, 1.01f, 0.34f,
                    contourRise, melodyDepth, darkScore * 0.15f, energyScore), pitchContour, activityContour, voicingContour);
        }
        float neutralConf = (Math.abs(contourRise) >= 0.18f || melodyDepth >= 0.18f || darkScore >= 0.22f || energyScore >= 0.22f)
                ? 0.26f : 0.0f;
        return withContours(new TtsProsodyProfile("neutral", pitchScale * 0.85f, 1.0f, 1.0f, 1.0f, neutralConf,
                contourRise, melodyDepth, darkScore, energyScore), pitchContour, activityContour, voicingContour);
    }

    public static TtsProsodyProfile analyzeTextAndPcm(String text, byte[] pcm16le, int sampleRate) {
        TtsProsodyProfile textProfile = analyzeText(text);
        TtsProsodyProfile pcmProfile = analyzePcm(pcm16le, sampleRate);
        if (pcmProfile.hasPitchContour()) return pcmProfile.blendWith(textProfile);
        if (!textProfile.isConfident()) return pcmProfile;
        if (!pcmProfile.isConfident()) return textProfile;
        if ("question".equals(textProfile.mood())) return textProfile.blendWith(pcmProfile);
        if ("sing_song_lite".equals(pcmProfile.mood())) return pcmProfile.blendWith(textProfile);
        return textProfile.blendWith(pcmProfile);
    }

    private static TtsProsodyProfile withContours(TtsProsodyProfile base, float[] contour, float[] activityContour, float[] voicingContour) {
        if (base == null) return null;
        return new TtsProsodyProfile(
                base.mood(),
                base.pitchScale(),
                base.intonationScale(),
                base.speedScale(),
                base.volumeScale(),
                base.confidence(),
                base.contourRise(),
                base.melodyDepth(),
                base.darkScore(),
                base.energyScore(),
                (contour == null || contour.length < 3) ? new float[0] : contour,
                (activityContour == null || activityContour.length < 3) ? new float[0] : activityContour,
                (voicingContour == null || voicingContour.length < 3) ? new float[0] : voicingContour,
                PCM_FRAME_MS
        );
    }

    private static float[] estimateActivityContour(float[] rmsFrames, float meanRms) {
        if (rmsFrames == null || rmsFrames.length == 0) return new float[0];
        float denom = Math.max(120.0f, meanRms * 1.15f);
        float[] contour = new float[rmsFrames.length];
        for (int i = 0; i < rmsFrames.length; i++) {
            contour[i] = clamp01(rmsFrames[i] / denom);
        }
        smoothContour(contour);
        return contour;
    }

    private static float[] estimateVoicingContour(float[] rmsFrames, float voicedThreshold) {
        if (rmsFrames == null || rmsFrames.length == 0) return new float[0];
        float lo = voicedThreshold * 0.70f;
        float hi = voicedThreshold * 1.05f;
        float span = Math.max(1.0f, hi - lo);
        float[] contour = new float[rmsFrames.length];
        for (int i = 0; i < rmsFrames.length; i++) {
            contour[i] = clamp01((rmsFrames[i] - lo) / span);
        }
        smoothContour(contour);
        return contour;
    }

    private static float meanOrZero(float[] values) {
        if (values == null || values.length == 0) return 0.0f;
        float sum = 0.0f;
        for (float v : values) sum += v;
        return sum / values.length;
    }

    private static short[] decodePcm16Le(byte[] pcm16le) {
        short[] samples = new short[pcm16le.length / 2];
        for (int i = 0; i < samples.length; i++) {
            int idx = i * 2;
            samples[i] = (short) (((pcm16le[idx + 1] & 0xFF) << 8) | (pcm16le[idx] & 0xFF));
        }
        return samples;
    }

    private static float[] estimatePitchContour(short[] samples, int sampleRate, int frameSamples, float[] rmsFrames,
                                                int voicedStart, int voicedEnd, float voicedThreshold) {
        int voicedFrames = voicedEnd - voicedStart + 1;
        if (voicedFrames < 3) return new float[0];

        int analysisSamples = Math.max(frameSamples * 2, sampleRate / 40);
        int minLag = Math.max(18, sampleRate / 360);
        int maxLag = Math.min(Math.max(minLag + 4, analysisSamples / 2), sampleRate / 85);
        float[] hzFrames = new float[voicedFrames];
        Arrays.fill(hzFrames, Float.NaN);
        int accepted = 0;

        for (int i = voicedStart; i <= voicedEnd; i++) {
            int rel = i - voicedStart;
            if (rmsFrames[i] < voicedThreshold * 0.85f) continue;
            int start = i * frameSamples;
            int len = Math.min(analysisSamples, samples.length - start);
            if (len <= maxLag + 8) continue;
            float f0 = estimateFramePitchHz(samples, start, len, sampleRate, minLag, maxLag);
            if (f0 > 0f) {
                hzFrames[rel] = f0;
                accepted++;
            }
        }
        if (accepted < 3) return new float[0];

        float[] voicedHz = new float[accepted];
        for (int i = 0, k = 0; i < hzFrames.length; i++) {
            if (!Float.isNaN(hzFrames[i])) voicedHz[k++] = hzFrames[i];
        }
        float medianHz = median(voicedHz);
        if (!(medianHz > 0f)) return new float[0];

        float[] contour = new float[hzFrames.length];
        Arrays.fill(contour, Float.NaN);
        for (int i = 0; i < hzFrames.length; i++) {
                if (!Float.isNaN(hzFrames[i])) {
                    float octaveDelta = (float) (Math.log(hzFrames[i] / medianHz) / Math.log(2.0d));
                    float semitoneDelta = octaveDelta * 12.0f;
                    contour[i] = clampSemitoneDelta(semitoneDelta, -4.0f, 4.0f);
                }
            }
        interpolateContourGaps(contour);
        smoothContour(contour);
        return contour;
    }

    private static float estimateFramePitchHz(short[] samples, int start, int len, int sampleRate, int minLag, int maxLag) {
        double mean = 0.0d;
        for (int i = 0; i < len; i++) mean += samples[start + i];
        mean /= Math.max(1, len);

        double bestScore = 0.0d;
        int bestLag = -1;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double num = 0.0d;
            double denA = 1.0e-9;
            double denB = 1.0e-9;
            for (int i = 0; i < len - lag; i++) {
                double a = samples[start + i] - mean;
                double b = samples[start + i + lag] - mean;
                num += a * b;
                denA += a * a;
                denB += b * b;
            }
            double score = num / Math.sqrt(denA * denB);
            if (score > bestScore) {
                bestScore = score;
                bestLag = lag;
            }
        }
        if (bestLag <= 0 || bestScore < 0.58d) return -1.0f;
        return sampleRate / (float) bestLag;
    }

    private static void interpolateContourGaps(float[] contour) {
        int first = -1;
        for (int i = 0; i < contour.length; i++) {
            if (!Float.isNaN(contour[i])) {
                first = i;
                break;
            }
        }
        if (first < 0) return;
        for (int i = 0; i < first; i++) contour[i] = contour[first];

        int prev = first;
        for (int i = first + 1; i < contour.length; i++) {
            if (!Float.isNaN(contour[i])) {
                if (i - prev > 1) {
                    float a = contour[prev];
                    float b = contour[i];
                    int gap = i - prev;
                    for (int j = 1; j < gap; j++) {
                        float t = j / (float) gap;
                        contour[prev + j] = a + ((b - a) * t);
                    }
                }
                prev = i;
            }
        }
        for (int i = prev + 1; i < contour.length; i++) contour[i] = contour[prev];
    }

    private static void smoothContour(float[] contour) {
        if (contour.length < 3) return;
        float[] copy = contour.clone();
        for (int i = 1; i < contour.length - 1; i++) {
            contour[i] = (copy[i - 1] * 0.25f) + (copy[i] * 0.50f) + (copy[i + 1] * 0.25f);
        }
    }

    private static float contourRiseFromPitchContour(float[] contour) {
        if (contour == null || contour.length < 3) return 0.0f;
        int span = Math.max(1, contour.length / 3);
        float head = 0.0f;
        float tail = 0.0f;
        for (int i = 0; i < span; i++) head += contour[i];
        for (int i = contour.length - span; i < contour.length; i++) tail += contour[i];
        return clampSigned(((tail / span) - (head / span)) / 1.6f);
    }

    private static float contourMelodyDepth(float[] contour) {
        if (contour == null || contour.length < 3) return 0.0f;
        float sum = 0.0f;
        for (float v : contour) sum += v;
        float mean = sum / contour.length;
        float var = 0.0f;
        float maxAbs = 0.0f;
        for (float v : contour) {
            float d = v - mean;
            var += d * d;
            maxAbs = Math.max(maxAbs, Math.abs(v));
        }
        float std = (float) Math.sqrt(var / contour.length);
        return clamp01((std / 1.05f) * 0.78f + (maxAbs / 2.8f) * 0.22f);
    }

    private static float pitchScaleFromPitchContour(float[] contour, float contourRise, float darkScore, float energyScore) {
        float contourMean = 0.0f;
        float contourSpan = 0.0f;
        if (contour != null && contour.length >= 3) {
            float sum = 0.0f;
            float min = Float.POSITIVE_INFINITY;
            float max = Float.NEGATIVE_INFINITY;
            for (float v : contour) {
                sum += v;
                if (v < min) min = v;
                if (v > max) max = v;
            }
            contourMean = sum / contour.length;
            contourSpan = max - min;
        }
        float meanBias = clampSigned(contourMean / 2.4f) * 0.12f;
        float spanBias = clampSigned((contourSpan - 0.55f) / 1.9f) * 0.05f;
        float riseBias = clampSigned(contourRise) * 0.02f;
        float toneBias = clampSigned((energyScore - darkScore) * 1.9f) * 0.08f;
        float darkBias = clampSigned((0.24f - darkScore) * 2.1f) * 0.03f;
        return Math.max(-0.18f, Math.min(0.18f, meanBias + spanBias + riseBias + toneBias + darkBias));
    }

    private static float blendContourRise(float fallbackRise, float pitchRise) {
        if (Math.abs(pitchRise) < 0.05f) return fallbackRise;
        return clampSigned((fallbackRise * 0.30f) + (pitchRise * 0.70f));
    }

    private static float clampSemitoneDelta(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static float median(float[] values) {
        if (values == null || values.length == 0) return 0.0f;
        float[] copy = values.clone();
        Arrays.sort(copy);
        int mid = copy.length / 2;
        if ((copy.length & 1) == 1) return copy[mid];
        return (copy[mid - 1] + copy[mid]) * 0.5f;
    }

    private static boolean isQuestion(String raw, String lower) {
        return raw.endsWith("?")
                || raw.endsWith("？")
                || containsAny(raw, TOK_QUESTION_RAW)
                || containsAny(lower, TOK_QUESTION_LOWER);
    }

    private static boolean looksLikeSingSong(String raw, String lower) {
        if (containsAny(raw, TOK_SING_SONG_RAW) || containsAny(lower, TOK_SING_SONG_LOWER)) {
            return true;
        }
        int commaLike = countOf(raw, '、') + countOf(raw, ',') + countOf(raw, '，');
        if (commaLike < 2) return false;
        String[] chunks = raw.split("[、,，]");
        int shortChunks = 0;
        for (String chunk : chunks) {
            String s = chunk.trim();
            if (s.isEmpty()) continue;
            int cp = s.codePointCount(0, s.length());
            if (cp >= 2 && cp <= 8) shortChunks++;
        }
        return shortChunks >= 3;
    }

    private static int countOf(String text, char target) {
        int c = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == target) c++;
        }
        return c;
    }

    private static boolean containsAny(String text, String[] tokens) {
        if (text == null || tokens == null) return false;
        for (String token : tokens) {
            if (token != null && !token.isEmpty() && text.contains(token)) return true;
        }
        return false;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float clampSigned(float value) {
        return Math.max(-1.0f, Math.min(1.0f, value));
    }

    private static final String[] TOK_SING_SONG_RAW = {
            "♪", "〜", "～", "ちゃんちゃか", "ちゃかぽこ", "ララ", "랄라", "啦啦"
    };
    private static final String[] TOK_SING_SONG_LOWER = {
            "la la", "lalala", "humming", "chant", "sing-song", "sing song"
    };
    private static final String[] TOK_WHISPER = {
            "ひそひそ", "内緒", "小声", "そっと", "secret", "whisper", "悄悄", "小聲", "속삭"
    };
    private static final String[] TOK_EXCITED_RAW = {
            "!", "！", "笑", "草", "哈哈", "ㅋㅋ"
    };
    private static final String[] TOK_EXCITED_LOWER = {
            "lol", "haha", "yay", "wow", "omg"
    };
    private static final String[] TOK_QUESTION_RAW = {
            "かな", "ですか", "？", "吗", "嗎", "니", "냐"
    };
    private static final String[] TOK_QUESTION_LOWER = {
            "right?", "okay?", "really?", "is it", "do you", "are you"
    };
    private static final String[] TOK_CALM = {
            "ゆっくり", "落ち着", "calm", "slowly", "quietly", "静か", "천천히", "慢慢"
    };
}
