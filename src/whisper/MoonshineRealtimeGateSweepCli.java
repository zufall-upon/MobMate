package whisper;

import org.json.JSONObject;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MoonshineRealtimeGateSweepCli {
    private static final int TARGET_SAMPLE_RATE = 16000;

    private record AudioData(byte[] pcm16le, int sampleRateHz) {}

    private record Candidate(String id,
                             boolean enabled,
                             boolean lowGainMic,
                             double openRms,
                             double closeRms,
                             int openPeak,
                             int closePeak,
                             int releaseMs,
                             String source) {}

    private MoonshineRealtimeGateSweepCli() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        if (opts.containsKey("help")) {
            printUsage();
            return;
        }

        Path input = Path.of(opts.getOrDefault("input", "C:\\@work\\@SourceTree\\MobMate\\app\\workbenchi"))
                .toAbsolutePath().normalize();
        Path out = Path.of(opts.getOrDefault("out", "C:\\@work\\@SourceTree\\MobMate\\app\\workbenchi\\moonshine-realtime-gate-sweep.jsonl"))
                .toAbsolutePath().normalize();
        int frameMs = parseInt(opts.get("frame-ms"), 20);
        List<Candidate> candidates = candidates(opts.getOrDefault("profile", "quick"));
        String onlyCandidate = opts.getOrDefault("candidate", "all").trim();
        if (!onlyCandidate.equalsIgnoreCase("all")) {
            candidates = candidates.stream()
                    .filter(c -> c.id.equalsIgnoreCase(onlyCandidate))
                    .toList();
        }
        if (candidates.isEmpty()) throw new IllegalArgumentException("No gate candidates selected.");

        List<Path> wavs = collectWavs(input);
        if (wavs.isEmpty()) throw new IllegalArgumentException("No wav files found: " + input);
        Files.createDirectories(out.getParent());
        Files.deleteIfExists(out);

        List<JSONObject> rows = new ArrayList<>();
        for (Path wav : wavs) {
            AudioData audio = readWavAsPcm16Mono(wav);
            byte[] pcm = resamplePcm16Mono(audio.pcm16le, audio.sampleRateHz, TARGET_SAMPLE_RATE);
            for (Candidate candidate : candidates) {
                JSONObject row = analyze(wav, pcm, frameMs, candidate);
                rows.add(row);
                Files.writeString(out, row.toString() + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            }
        }

        summarize(rows);
        System.out.println("GATE_SWEEP rows=" + rows.size() + " wavs=" + wavs.size() + " out=" + out);
    }

    private static JSONObject analyze(Path wav, byte[] pcm16le, int frameMs, Candidate candidate) {
        int frameBytes = Math.max(2, ((TARGET_SAMPLE_RATE * Math.max(5, frameMs)) / 1000) * 2);
        frameBytes -= frameBytes % 2;
        AudioPrefilter.RealtimeNoiseGateState state = new AudioPrefilter.RealtimeNoiseGateState();
        AudioPrefilter.RealtimeNoiseGateCalibration calibration = candidate.enabled
                ? new AudioPrefilter.RealtimeNoiseGateCalibration(
                        candidate.openRms,
                        candidate.closeRms,
                        candidate.openPeak,
                        candidate.closePeak,
                        candidate.releaseMs,
                        candidate.source)
                : null;
        ByteArrayOutputStream gated = new ByteArrayOutputStream(pcm16le.length);
        int openFrames = 0;
        int segments = 0;
        boolean prevOpen = false;
        int firstOpenMs = -1;
        int lastOpenMs = -1;
        long rawEnergy = 0L;
        long gatedEnergy = 0L;

        for (int offset = 0, frame = 0; offset < pcm16le.length; offset += frameBytes, frame++) {
            int n = Math.min(frameBytes, pcm16le.length - offset);
            byte[] chunk = new byte[n];
            System.arraycopy(pcm16le, offset, chunk, 0, n);
            byte[] out = candidate.enabled
                    ? AudioPrefilter.applyRealtimeNoiseGateForVad(
                            chunk, n, TARGET_SAMPLE_RATE, candidate.lowGainMic, state, calibration)
                    : chunk;
            gated.write(out, 0, out.length);
            int outPeak = peak(out);
            boolean open = outPeak > 0;
            if (open) {
                openFrames++;
                int atMs = (frame * frameBytes * 1000) / (TARGET_SAMPLE_RATE * 2);
                if (firstOpenMs < 0) firstOpenMs = atMs;
                lastOpenMs = atMs;
            }
            if (open && !prevOpen) segments++;
            prevOpen = open;
            rawEnergy += energy(chunk);
            gatedEnergy += energy(out);
        }

        byte[] gatedPcm = gated.toByteArray();
        AudioPrefilter.VoiceMetrics raw = AudioPrefilter.analyzeVoiceLike(pcm16le, pcm16le.length, TARGET_SAMPLE_RATE);
        AudioPrefilter.VoiceMetrics processed = AudioPrefilter.analyzeVoiceLike(gatedPcm, gatedPcm.length, TARGET_SAMPLE_RATE);
        double retained = rawEnergy <= 0L ? 0.0d : Math.min(4.0d, gatedEnergy / (double) rawEnergy);
        int totalFrames = Math.max(1, (pcm16le.length + frameBytes - 1) / frameBytes);
        return new JSONObject()
                .put("ts", LocalDateTime.now().toString())
                .put("type", "moonshine_realtime_gate_sweep")
                .put("wav", wav.toString())
                .put("file", wav.getFileName().toString())
                .put("candidate", candidate.id)
                .put("gate_enabled", candidate.enabled)
                .put("low_gain_mic", candidate.lowGainMic)
                .put("open_rms", candidate.openRms)
                .put("close_rms", candidate.closeRms)
                .put("open_peak", candidate.openPeak)
                .put("close_peak", candidate.closePeak)
                .put("release_ms", candidate.releaseMs)
                .put("input_ms", pcm16le.length / 32.0d)
                .put("total_frames", totalFrames)
                .put("open_frames", openFrames)
                .put("open_ratio", openFrames / (double) totalFrames)
                .put("open_segments", segments)
                .put("first_open_ms", firstOpenMs)
                .put("last_open_ms", lastOpenMs)
                .put("raw_rms", raw.rms)
                .put("raw_peak", raw.peak)
                .put("raw_vbr", raw.voiceBandRatio)
                .put("gated_rms", processed.rms)
                .put("gated_peak", processed.peak)
                .put("gated_vbr", processed.voiceBandRatio)
                .put("retained_energy_ratio", retained)
                .put("likely_false_reject", candidate.enabled && openFrames == 0 && raw.peak >= 220);
    }

    private static List<Candidate> candidates(String profile) {
        List<Candidate> out = new ArrayList<>();
        out.add(new Candidate("gate_off", false, false, 0.0d, 0.0d, 0, 0, 0, "disabled"));
        out.add(new Candidate("normal_default", true, false, 90.0d, 55.0d, 300, 190, 135, "mobmate_default_normal"));
        out.add(new Candidate("low_gain_default", true, true, 70.0d, 42.0d, 220, 140, 170, "mobmate_default_low_gain"));
        out.add(new Candidate("soft_voice", true, true, 52.0d, 34.0d, 160, 105, 190, "soft_voice"));
        out.add(new Candidate("noise_hysteresis", true, false, 120.0d, 74.0d, 520, 320, 115, "noise_hysteresis"));
        if (!"quick".equalsIgnoreCase(profile)) {
            out.add(new Candidate("very_soft_voice", true, true, 42.0d, 28.0d, 120, 80, 220, "very_soft_voice"));
            out.add(new Candidate("strict_noise", true, false, 160.0d, 98.0d, 820, 520, 90, "strict_noise"));
        }
        return out;
    }

    private static void summarize(List<JSONObject> rows) {
        Map<String, Acc> accs = new LinkedHashMap<>();
        for (JSONObject row : rows) {
            accs.computeIfAbsent(row.optString("candidate", ""), Acc::new).add(row);
        }
        accs.values().stream()
                .sorted(Comparator.comparing(a -> a.candidate))
                .forEach(a -> System.out.println(String.format(
                        Locale.ROOT,
                        "GATE_RESULT candidate=%s rows=%d false_reject=%d avg_open_ratio=%.3f avg_retained=%.3f avg_segments=%.2f",
                        a.candidate,
                        a.rows,
                        a.falseRejects,
                        a.openRatioSum / Math.max(1, a.rows),
                        a.retainedSum / Math.max(1, a.rows),
                        a.segmentsSum / Math.max(1, a.rows))));
    }

    private static final class Acc {
        final String candidate;
        int rows;
        int falseRejects;
        double openRatioSum;
        double retainedSum;
        double segmentsSum;

        Acc(String candidate) {
            this.candidate = candidate;
        }

        void add(JSONObject row) {
            rows++;
            if (row.optBoolean("likely_false_reject", false)) falseRejects++;
            openRatioSum += row.optDouble("open_ratio", 0.0d);
            retainedSum += row.optDouble("retained_energy_ratio", 0.0d);
            segmentsSum += row.optInt("open_segments", 0);
        }
    }

    private static List<Path> collectWavs(Path input) throws IOException {
        if (Files.isRegularFile(input)) return List.of(input);
        try (var stream = Files.walk(input)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav"))
                    .sorted()
                    .toList();
        }
    }

    private static AudioData readWavAsPcm16Mono(Path wav) throws Exception {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(wav.toFile())) {
            AudioFormat src = source.getFormat();
            AudioFormat decodedFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    src.getSampleRate(),
                    16,
                    src.getChannels(),
                    Math.max(1, src.getChannels()) * 2,
                    src.getSampleRate(),
                    false);
            try (AudioInputStream decoded = AudioSystem.getAudioInputStream(decodedFormat, source)) {
                byte[] raw = readAll(decoded);
                return new AudioData(mixToMono16le(raw, decodedFormat.getChannels()),
                        Math.round(decodedFormat.getSampleRate()));
            }
        }
    }

    private static byte[] readAll(AudioInputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = stream.read(buf)) >= 0) {
            if (n > 0) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] mixToMono16le(byte[] pcm, int channels) {
        channels = Math.max(1, channels);
        if (channels == 1) return pcm;
        int frames = pcm.length / (channels * 2);
        byte[] out = new byte[frames * 2];
        for (int f = 0; f < frames; f++) {
            int sum = 0;
            for (int ch = 0; ch < channels; ch++) {
                sum += readLe16(pcm, (f * channels + ch) * 2);
            }
            writeLe16(out, f * 2, clamp16(Math.round((float) sum / channels)));
        }
        return out;
    }

    private static byte[] resamplePcm16Mono(byte[] pcm, int sourceRate, int targetRate) {
        if (sourceRate == targetRate) return pcm;
        int inSamples = pcm.length / 2;
        int outSamples = Math.max(1, (int) Math.round(inSamples * (targetRate / (double) sourceRate)));
        byte[] out = new byte[outSamples * 2];
        double ratio = sourceRate / (double) targetRate;
        for (int i = 0; i < outSamples; i++) {
            double src = i * ratio;
            int lo = Math.min(inSamples - 1, (int) Math.floor(src));
            int hi = Math.min(inSamples - 1, lo + 1);
            double frac = src - lo;
            int mixed = (int) Math.round(readLe16(pcm, lo * 2) * (1.0d - frac)
                    + readLe16(pcm, hi * 2) * frac);
            writeLe16(out, i * 2, clamp16(mixed));
        }
        return out;
    }

    private static long energy(byte[] pcm) {
        long sum = 0L;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int v = readLe16(pcm, i);
            sum += (long) v * v;
        }
        return sum;
    }

    private static int peak(byte[] pcm) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            peak = Math.max(peak, Math.abs(readLe16(pcm, i)));
        }
        return peak;
    }

    private static int readLe16(byte[] b, int off) {
        return (short) ((b[off] & 0xff) | (b[off + 1] << 8));
    }

    private static void writeLe16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >>> 8) & 0xff);
    }

    private static int clamp16(int v) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) continue;
            String key = arg.substring(2);
            if ("help".equals(key)) {
                opts.put("help", "true");
            } else if (i + 1 < args.length) {
                opts.put(key, args[++i]);
            }
        }
        return opts;
    }

    private static void printUsage() {
        System.out.println("""
                Moonshine realtime gate sweep
                  --input <wav-or-dir>          Default: app/workbenchi
                  --out <jsonl>                 Output rows
                  --profile <quick|full>        Default: quick
                  --candidate <id|all>          gate_off,normal_default,low_gain_default,soft_voice,noise_hysteresis
                  --frame-ms <ms>               Default: 20
                """);
    }
}
