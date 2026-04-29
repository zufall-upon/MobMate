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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MoonshineBenchmarkCli {
    private static final int TARGET_SAMPLE_RATE = 16000;

    private record Preset(String id,
                          String language,
                          String scene,
                          double vadWindowSec,
                          int vadLookBehindSamples,
                          double vadThreshold,
                          double maxTokensPerSecond,
                          AudioPrefilter.Profile prefilter,
                          int trimAbsThreshold,
                          int trimPadMs,
                          int maxInputMs,
                          int squashSilenceAbsThreshold,
                          int squashSilenceMinMs,
                          int squashSilenceKeepMs,
                          double normalizeDbfs,
                          double slowDownRatio,
                          int leadingPaddingMs,
                          int tailPaddingMs,
                          int passes,
                          int settleSleepMs,
                          boolean withoutStreaming) {}

    private record CaseItem(Path wavPath, String expected, String language, String scene) {}
    private record AudioData(byte[] pcm16le, int sampleRateHz) {}

    private MoonshineBenchmarkCli() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        if (opts.containsKey("help") || (!opts.containsKey("model") && !opts.containsKey("model-root"))) {
            printUsage();
            return;
        }

        int[] archCandidates = parseArchCandidates(opts.getOrDefault("arch", "auto"));
        List<CaseItem> cases = loadCases(opts);
        if (cases.isEmpty()) throw new IllegalArgumentException("No wav cases found.");

        String language = opts.getOrDefault("language", "ja").trim();
        List<String> sceneFilter = splitFilter(opts.getOrDefault("scene", "all"));
        List<String> presetFilter = splitFilter(opts.getOrDefault("preset", "all"));
        List<Preset> presets = defaultPresets().stream()
                .filter(p -> "all".equals(language) || p.language.equalsIgnoreCase(language))
                .filter(p -> sceneFilter.contains("all") || sceneFilter.contains(p.scene))
                .filter(p -> presetFilter.contains("all") || presetFilter.contains(p.scene) || presetFilter.contains(p.id))
                .map(p -> applyPresetOverrides(p, opts))
                .toList();
        if (presets.isEmpty()) throw new IllegalArgumentException("No presets selected.");

        Path out = Path.of(opts.getOrDefault("out", "build/reports/moonshine-benchmark/results.jsonl"))
                .toAbsolutePath().normalize();
        Path history = Path.of(opts.getOrDefault("history", "C:\\@work\\@SourceTree\\MobMate\\app\\workbenchi\\moonshine-benchmark-history.jsonl"))
                .toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        if (history.getParent() != null) Files.createDirectories(history.getParent());
        String runId = opts.getOrDefault("run-id", defaultRunId());

        try (var writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8);
             var historyWriter = Files.newBufferedWriter(history, StandardCharsets.UTF_8,
                     java.nio.file.StandardOpenOption.CREATE,
                     java.nio.file.StandardOpenOption.APPEND)) {
            for (Preset preset : presets) {
                Path modelPath = resolveModelPath(opts, preset.language);
                LoadedStt loaded = loadStt(modelPath, archCandidates, preset);
                try {
                    for (CaseItem item : cases) {
                        if (!item.language.isBlank() && !item.language.equalsIgnoreCase(preset.language)) continue;
                        if (!item.scene.isBlank() && !item.scene.equalsIgnoreCase(preset.scene)) continue;
                        JSONObject row = runCase(loaded.stt, preset, item, modelPath, loaded.arch, opts);
                        row.put("run_id", runId);
                        writer.write(row.toString());
                        writer.newLine();
                        historyWriter.write(row.toString());
                        historyWriter.newLine();
                        writer.flush();
                        historyWriter.flush();
                        System.out.println(row);
                    }
                } finally {
                    loaded.stt.close();
                }
            }
        }

        System.out.println("wrote " + out);
        System.out.println("appended history " + history);
    }

    private static JSONObject runCase(LocalMoonshineSTT stt,
                                      Preset preset,
                                      CaseItem item,
                                      Path modelPath,
                                      int arch,
                                      Map<String, String> opts) throws Exception {
        long startedNs = System.nanoTime();
        AudioData audio = readWavAsPcm16Mono(item.wavPath);
        byte[] mono16k = resamplePcm16Mono(audio.pcm16le, audio.sampleRateHz, TARGET_SAMPLE_RATE);
        byte[] trimmed = trimPcmSilence16le(mono16k, preset.trimAbsThreshold, preset.trimPadMs);
        byte[] cropped = cropPcm16leToMaxMs(trimmed, preset.maxInputMs);
        byte[] squashed = squashInternalSilence16le(
                cropped,
                preset.squashSilenceAbsThreshold,
                preset.squashSilenceMinMs,
                preset.squashSilenceKeepMs);
        byte[] processed = AudioPrefilter.processForAsr(
                squashed, squashed.length, TARGET_SAMPLE_RATE, AudioPrefilter.Mode.TALK,
                preset.prefilter, new AudioPrefilter.State());
        processed = AudioPrefilter.normalizeFinalChunkForAsr(processed, processed.length, preset.normalizeDbfs);
        if (preset.slowDownRatio > 1.01d) {
            processed = slowDownPcm16le(processed, preset.slowDownRatio);
        }
        Path dumpedAudio = dumpProcessedAudioIfRequested(opts, preset, item, processed);

        long decodeStartedNs = System.nanoTime();
        LocalMoonshineSTT.OneShotTranscriptResult result = stt.transcribeOneShotDetailed(
                pcm16leToFloat(processed),
                preset.leadingPaddingMs,
                preset.tailPaddingMs,
                preset.passes,
                preset.settleSleepMs,
                preset.withoutStreaming,
                "bench:" + preset.id);
        long finishedNs = System.nanoTime();

        String text = result.text == null ? "" : result.text.trim();
        String expected = item.expected == null ? "" : item.expected.trim();
        String metricExpected = normalizeForMetric(expected, preset.language);
        String metricText = normalizeForMetric(text, preset.language);
        AudioPrefilter.VoiceMetrics metrics =
                AudioPrefilter.analyzeVoiceLike(processed, processed.length, TARGET_SAMPLE_RATE);

        JSONObject row = new JSONObject();
        row.put("wav", item.wavPath.toString());
        row.put("expected", expected);
        row.put("text", text);
        row.put("metric_expected", metricExpected);
        row.put("metric_text", metricText);
        row.put("metric_normalizer", metricNormalizerName(preset.language));
        row.put("language", preset.language);
        row.put("scene", preset.scene);
        row.put("preset_id", preset.id);
        row.put("model_path", modelPath.toString());
        row.put("arch", arch);
        row.put("exact", !expected.isBlank() && metricExpected.equals(metricText));
        row.put("cer", expected.isBlank() ? JSONObject.NULL : cer(expected, text, preset.language));
        row.put("raw_cer", expected.isBlank() ? JSONObject.NULL : rawCer(expected, text));
        row.put("latency_ms", (finishedNs - decodeStartedNs) / 1_000_000.0d);
        row.put("total_ms", (finishedNs - startedNs) / 1_000_000.0d);
        row.put("input_ms", mono16k.length / 32.0d);
        row.put("processed_ms", processed.length / 32.0d);
        if (dumpedAudio != null) row.put("dumped_audio", dumpedAudio.toString());
        row.put("rms", metrics.rms);
        row.put("peak", metrics.peak);
        row.put("voice_band_ratio", metrics.voiceBandRatio);
        row.put("line_confidence", Float.isNaN(result.lineConfidence) ? JSONObject.NULL : result.lineConfidence);
        row.put("word_count", result.wordCount);
        row.put("preset", new JSONObject()
                .put("id", preset.id)
                .put("vad_window_sec", preset.vadWindowSec)
                .put("vad_look_behind_samples", preset.vadLookBehindSamples)
                .put("vad_threshold", preset.vadThreshold)
                .put("max_tokens_per_second", preset.maxTokensPerSecond)
                .put("prefilter", preset.prefilter.name().toLowerCase(Locale.ROOT))
                .put("trim_abs_threshold", preset.trimAbsThreshold)
                .put("trim_pad_ms", preset.trimPadMs)
                .put("max_input_ms", preset.maxInputMs)
                .put("squash_silence_abs_threshold", preset.squashSilenceAbsThreshold)
                .put("squash_silence_min_ms", preset.squashSilenceMinMs)
                .put("squash_silence_keep_ms", preset.squashSilenceKeepMs)
                .put("normalize_dbfs", preset.normalizeDbfs)
                .put("slow_down_ratio", preset.slowDownRatio)
                .put("leading_padding_ms", preset.leadingPaddingMs)
                .put("tail_padding_ms", preset.tailPaddingMs)
                .put("passes", preset.passes)
                .put("settle_sleep_ms", preset.settleSleepMs)
                .put("without_streaming", preset.withoutStreaming));
        return row;
    }

    private static List<Preset> defaultPresets() {
        List<Preset> ja = List.of(
                new Preset("ja_talk_clean_v1", "ja", "talk_clean", 0.15, 20000, 0.30, 13.0, AudioPrefilter.Profile.NORMAL, 260, 120, 0, 0, 0, 0, -22.8, 1.00, 300, 300, 2, 35, false),
                new Preset("ja_talk_noisy_v1", "ja", "talk_noisy", 0.15, 26000, 0.34, 13.0, AudioPrefilter.Profile.STRONG, 320, 160, 0, 0, 0, 0, -22.3, 1.00, 320, 420, 3, 45, true),
                new Preset("ja_talk_low_voice_v1", "ja", "talk_low_voice", 0.12, 32000, 0.24, 13.0, AudioPrefilter.Profile.NORMAL, 180, 180, 0, 0, 0, 0, -24.0, 1.00, 360, 480, 3, 45, true),
                new Preset("ja_short_command_v1", "ja", "short_command", 0.10, 32000, 0.26, 13.0, AudioPrefilter.Profile.NORMAL, 180, 220, 0, 0, 0, 0, -23.4, 1.08, 420, 520, 3, 45, true),
                new Preset("ja_desktop_hearing_v1", "ja", "desktop_hearing", 0.18, 22000, 0.32, 13.0, AudioPrefilter.Profile.STRONG, 260, 180, 0, 0, 0, 0, -22.6, 1.00, 300, 450, 3, 45, true)
        );
        List<Preset> out = new ArrayList<>(ja);
        out.addAll(List.of(
                new Preset("en_talk_clean_v1", "en", "talk_clean", 0.15, 20000, 0.30, 6.5, AudioPrefilter.Profile.NORMAL, 260, 120, 0, 0, 0, 0, -22.8, 1.00, 300, 300, 2, 35, false),
                new Preset("en_talk_noisy_v1", "en", "talk_noisy", 0.15, 26000, 0.34, 6.5, AudioPrefilter.Profile.STRONG, 320, 160, 0, 0, 0, 0, -22.3, 1.00, 320, 420, 3, 45, true),
                new Preset("en_talk_low_voice_v1", "en", "talk_low_voice", 0.12, 32000, 0.24, 6.5, AudioPrefilter.Profile.NORMAL, 180, 180, 0, 0, 0, 0, -24.0, 1.00, 360, 480, 3, 45, true),
                new Preset("en_short_command_v1", "en", "short_command", 0.10, 32000, 0.26, 6.5, AudioPrefilter.Profile.NORMAL, 180, 220, 0, 0, 0, 0, -23.4, 1.08, 420, 520, 3, 45, true),
                new Preset("en_desktop_hearing_v1", "en", "desktop_hearing", 0.18, 22000, 0.32, 6.5, AudioPrefilter.Profile.STRONG, 260, 180, 0, 0, 0, 0, -22.6, 1.00, 300, 450, 3, 45, true),

                new Preset("zh_talk_clean_v1", "zh", "talk_clean", 0.16, 24000, 0.27, 13.0, AudioPrefilter.Profile.NORMAL, 140, 220, 0, 0, 0, 0, -24.0, 1.08, 420, 620, 4, 55, true),
                new Preset("zh_talk_noisy_v1", "zh", "talk_noisy", 0.16, 30000, 0.31, 13.0, AudioPrefilter.Profile.NORMAL, 120, 260, 0, 0, 0, 0, -25.2, 1.18, 620, 900, 4, 65, true),
                new Preset("zh_talk_low_voice_v1", "zh", "talk_low_voice", 0.14, 34000, 0.23, 13.0, AudioPrefilter.Profile.OFF, 90, 300, 0, 0, 0, 0, -24.8, 1.12, 520, 820, 4, 65, true),
                new Preset("zh_short_command_v1", "zh", "short_command", 0.12, 36000, 0.24, 13.0, AudioPrefilter.Profile.NORMAL, 120, 240, 0, 0, 0, 0, -24.8, 1.00, 460, 760, 4, 70, true),
                new Preset("zh_desktop_hearing_v1", "zh", "desktop_hearing", 0.18, 28000, 0.29, 13.0, AudioPrefilter.Profile.NORMAL, 140, 260, 0, 0, 0, 0, -23.8, 1.08, 460, 760, 4, 65, true),

                new Preset("ko_talk_clean_v1", "ko", "talk_clean", 0.16, 26000, 0.27, 13.0, AudioPrefilter.Profile.NORMAL, 120, 240, 0, 0, 0, 0, -24.2, 1.12, 480, 760, 4, 60, true),
                new Preset("ko_talk_noisy_v1", "ko", "talk_noisy", 0.16, 32000, 0.31, 13.0, AudioPrefilter.Profile.NORMAL, 160, 280, 0, 0, 0, 0, -23.8, 1.10, 500, 840, 4, 65, true),
                new Preset("ko_talk_low_voice_v1", "ko", "talk_low_voice", 0.14, 36000, 0.23, 13.0, AudioPrefilter.Profile.OFF, 80, 320, 0, 0, 0, 0, -25.0, 1.16, 620, 960, 4, 70, true),
                new Preset("ko_short_command_v1", "ko", "short_command", 0.12, 36000, 0.23, 13.0, AudioPrefilter.Profile.OFF, 80, 360, 0, 0, 0, 0, -25.0, 1.20, 700, 980, 4, 75, true),
                new Preset("ko_desktop_hearing_v1", "ko", "desktop_hearing", 0.18, 30000, 0.28, 13.0, AudioPrefilter.Profile.NORMAL, 120, 280, 0, 0, 0, 0, -24.0, 1.12, 520, 840, 4, 65, true)
        ));
        return out;
    }

    private static Preset applyPresetOverrides(Preset p, Map<String, String> opts) {
        AudioPrefilter.Profile prefilter = p.prefilter;
        if (opts.containsKey("override-prefilter")) {
            prefilter = AudioPrefilter.Profile.fromKey(opts.get("override-prefilter"));
        }
        return new Preset(
                p.id + presetOverrideSuffix(opts),
                p.language,
                p.scene,
                parseDoubleOpt(opts, "override-vad-window-sec", p.vadWindowSec),
                parseIntOpt(opts, "override-vad-look-behind-samples", p.vadLookBehindSamples),
                parseDoubleOpt(opts, "override-vad-threshold", p.vadThreshold),
                parseDoubleOpt(opts, "override-max-tokens-per-second", p.maxTokensPerSecond),
                prefilter,
                parseIntOpt(opts, "override-trim-abs-threshold", p.trimAbsThreshold),
                parseIntOpt(opts, "override-trim-pad-ms", p.trimPadMs),
                parseIntOpt(opts, "override-max-input-ms", p.maxInputMs),
                parseIntOpt(opts, "override-squash-silence-abs-threshold", p.squashSilenceAbsThreshold),
                parseIntOpt(opts, "override-squash-silence-min-ms", p.squashSilenceMinMs),
                parseIntOpt(opts, "override-squash-silence-keep-ms", p.squashSilenceKeepMs),
                parseDoubleOpt(opts, "override-normalize-dbfs", p.normalizeDbfs),
                parseDoubleOpt(opts, "override-slow-down-ratio", p.slowDownRatio),
                parseIntOpt(opts, "override-leading-padding-ms", p.leadingPaddingMs),
                parseIntOpt(opts, "override-tail-padding-ms", p.tailPaddingMs),
                parseIntOpt(opts, "override-passes", p.passes),
                parseIntOpt(opts, "override-settle-sleep-ms", p.settleSleepMs),
                parseBooleanOpt(opts, "override-without-streaming", p.withoutStreaming));
    }

    private static String presetOverrideSuffix(Map<String, String> opts) {
        return opts.keySet().stream()
                .filter(k -> k.startsWith("override-"))
                .sorted()
                .findAny()
                .isPresent() ? "_override" : "";
    }

    private static int parseIntOpt(Map<String, String> opts, String key, int fallback) {
        return opts.containsKey(key) ? Integer.parseInt(opts.get(key)) : fallback;
    }

    private static double parseDoubleOpt(Map<String, String> opts, String key, double fallback) {
        return opts.containsKey(key) ? Double.parseDouble(opts.get(key)) : fallback;
    }

    private static boolean parseBooleanOpt(Map<String, String> opts, String key, boolean fallback) {
        return opts.containsKey(key) ? Boolean.parseBoolean(opts.get(key)) : fallback;
    }

    private static List<CaseItem> loadCases(Map<String, String> opts) throws IOException {
        if (opts.containsKey("manifest")) return readManifest(Path.of(opts.get("manifest")));
        Path input = Path.of(opts.getOrDefault("input", ".")).toAbsolutePath().normalize();
        List<Path> wavs = new ArrayList<>();
        if (Files.isRegularFile(input)) {
            wavs.add(input);
        } else {
            try (var stream = Files.walk(input)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav"))
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(wavs::add);
            }
        }
        String language = opts.getOrDefault("language", "");
        String scene = opts.getOrDefault("scene", "");
        List<CaseItem> out = new ArrayList<>();
        for (Path wav : wavs) out.add(new CaseItem(wav, "", language, scene));
        return out;
    }

    private static List<CaseItem> readManifest(Path manifest) throws IOException {
        List<CaseItem> out = new ArrayList<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            List<String> cols = splitCsvLine(trimmed);
            if (!cols.isEmpty() && "wav".equalsIgnoreCase(cols.get(0))) continue;
            out.add(new CaseItem(
                    Path.of(cols.get(0)).toAbsolutePath().normalize(),
                    cols.size() > 1 ? cols.get(1) : "",
                    cols.size() > 2 ? cols.get(2) : "",
                    cols.size() > 3 ? cols.get(3) : ""));
        }
        return out;
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

    private static byte[] trimPcmSilence16le(byte[] pcm16le, int absThreshold, int padMs) {
        if (pcm16le == null || pcm16le.length < 4) return pcm16le;
        int samples = pcm16le.length / 2;
        int first = 0;
        int last = samples - 1;
        while (first < samples && Math.abs(readLe16(pcm16le, first * 2)) < absThreshold) first++;
        while (last > first && Math.abs(readLe16(pcm16le, last * 2)) < absThreshold) last--;
        int pad = Math.max(0, (TARGET_SAMPLE_RATE * padMs) / 1000);
        first = Math.max(0, first - pad);
        last = Math.min(samples - 1, last + pad);
        if (first == 0 && last == samples - 1) return pcm16le;
        return Arrays.copyOfRange(pcm16le, first * 2, (last + 1) * 2);
    }

    private static byte[] cropPcm16leToMaxMs(byte[] pcm16le, int maxInputMs) {
        if (pcm16le == null || pcm16le.length < 4 || maxInputMs <= 0) return pcm16le;
        int maxBytes = Math.max(2, ((TARGET_SAMPLE_RATE * maxInputMs) / 1000) * 2);
        maxBytes -= maxBytes % 2;
        if (pcm16le.length <= maxBytes) return pcm16le;
        return Arrays.copyOfRange(pcm16le, 0, maxBytes);
    }

    private static byte[] squashInternalSilence16le(byte[] pcm16le, int absThreshold, int minSilenceMs, int keepMs) {
        if (pcm16le == null || pcm16le.length < 4 || absThreshold <= 0 || minSilenceMs <= 0 || keepMs < 0) {
            return pcm16le;
        }
        int minSamples = Math.max(1, (TARGET_SAMPLE_RATE * minSilenceMs) / 1000);
        int keepSamples = Math.max(0, (TARGET_SAMPLE_RATE * keepMs) / 1000);
        int samples = pcm16le.length / 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream(pcm16le.length);
        int i = 0;
        while (i < samples) {
            int start = i;
            while (i < samples && Math.abs(readLe16(pcm16le, i * 2)) < absThreshold) i++;
            int silent = i - start;
            if (silent >= minSamples) {
                int copySamples = Math.min(silent, keepSamples);
                out.write(pcm16le, start * 2, copySamples * 2);
            } else if (silent > 0) {
                out.write(pcm16le, start * 2, silent * 2);
            }
            start = i;
            while (i < samples && Math.abs(readLe16(pcm16le, i * 2)) >= absThreshold) i++;
            if (i > start) out.write(pcm16le, start * 2, (i - start) * 2);
        }
        byte[] squashed = out.toByteArray();
        return squashed.length >= 4 ? squashed : pcm16le;
    }

    private static Path dumpProcessedAudioIfRequested(Map<String, String> opts,
                                                      Preset preset,
                                                      CaseItem item,
                                                      byte[] pcm16le) throws IOException {
        String dumpDir = opts.get("dump-audio-dir");
        if (dumpDir == null || dumpDir.isBlank() || pcm16le == null || pcm16le.length < 4) return null;
        Path dir = Path.of(dumpDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String source = item.wavPath.getFileName().toString().replaceAll("[^A-Za-z0-9._-]+", "_");
        Path out = dir.resolve(preset.id + "_" + source);
        writePcm16MonoWav(out, pcm16le, TARGET_SAMPLE_RATE);
        return out;
    }

    private static void writePcm16MonoWav(Path out, byte[] pcm16le, int sampleRate) throws IOException {
        int dataSize = pcm16le.length;
        ByteArrayOutputStream wav = new ByteArrayOutputStream(44 + dataSize);
        wav.write(new byte[] {'R', 'I', 'F', 'F'});
        writeLe32(wav, 36 + dataSize);
        wav.write(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        writeLe32(wav, 16);
        writeLe16(wav, 1);
        writeLe16(wav, 1);
        writeLe32(wav, sampleRate);
        writeLe32(wav, sampleRate * 2);
        writeLe16(wav, 2);
        writeLe16(wav, 16);
        wav.write(new byte[] {'d', 'a', 't', 'a'});
        writeLe32(wav, dataSize);
        wav.write(pcm16le);
        Files.write(out, wav.toByteArray());
    }

    private static void writeLe16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeLe32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private static byte[] slowDownPcm16le(byte[] pcm16le, double slowdownRatio) {
        if (pcm16le == null || pcm16le.length < 4 || slowdownRatio <= 1.01d) return pcm16le;
        int inSamples = pcm16le.length / 2;
        int outSamples = Math.max(inSamples + 1, (int) Math.round(inSamples * slowdownRatio));
        byte[] out = new byte[outSamples * 2];
        for (int i = 0; i < outSamples; i++) {
            double srcPos = i / slowdownRatio;
            int lo = Math.min(inSamples - 1, (int) Math.floor(srcPos));
            int hi = Math.min(inSamples - 1, lo + 1);
            double frac = srcPos - lo;
            int mixed = (int) Math.round(readLe16(pcm16le, lo * 2) * (1.0d - frac)
                    + readLe16(pcm16le, hi * 2) * frac);
            writeLe16(out, i * 2, clamp16(mixed));
        }
        return out;
    }

    private static float[] pcm16leToFloat(byte[] pcm16le) {
        int samples = pcm16le.length / 2;
        float[] out = new float[samples];
        for (int i = 0; i < samples; i++) out[i] = readLe16(pcm16le, i * 2) / 32768.0f;
        return out;
    }

    private static double cer(String expected, String actual, String language) {
        String a = normalizeForMetric(expected, language);
        String b = normalizeForMetric(actual, language);
        if (a.isEmpty()) return b.isEmpty() ? 0.0d : 1.0d;
        return levenshteinByCodePoint(a, b) / (double) a.codePointCount(0, a.length());
    }

    private static double rawCer(String expected, String actual) {
        String a = expected == null ? "" : expected.strip();
        String b = actual == null ? "" : actual.strip();
        if (a.isEmpty()) return b.isEmpty() ? 0.0d : 1.0d;
        return levenshteinByCodePoint(a, b) / (double) a.codePointCount(0, a.length());
    }

    private static String normalizeForMetric(String text, String language) {
        String normalized = normalizeCommon(text);
        String lang = language == null ? "" : language.toLowerCase(Locale.ROOT);
        if ("ja".equals(lang)) {
            return normalizeJapaneseMetric(normalized);
        }
        if ("en".equals(lang)) {
            return normalized.toLowerCase(Locale.ROOT);
        }
        return normalized;
    }

    private static String normalizeCommon(String text) {
        if (text == null) return "";
        return text.strip()
                .replaceAll("[\\s\\u3000]+", "")
                .replaceAll("[。、，,.!?！？:：;；\"'`´‘’“”()（）\\[\\]{}「」『』…・･]", "");
    }

    private static String normalizeJapaneseMetric(String text) {
        String s = text;
        String[][] replacements = {
                {"私", "わたし"}, {"声", "こえ"}, {"聞", "き"}, {"話", "はな"}, {"周", "まわ"},
                {"小", "ちい"}, {"今", "いま"}, {"左", "ひだり"}, {"移動", "いどう"},
                {"画面", "がめん"}, {"確認", "かくにん"}, {"下さい", "ください"},
                {"下さい", "ください"}, {"この", "この"}, {"テスト", "てすと"},
                {"周り", "まわり"}, {"うるさい", "うるさい"}, {"まだ", "まだ"}
        };
        for (String[] pair : replacements) {
            s = s.replace(pair[0], pair[1]);
        }
        s = s.replace("はっきり", "はっきり")
                .replace("しゃべ", "はな")
                .replace("喋", "はな")
                .replace("ください", "ください");
        return katakanaToHiragana(s);
    }

    private static String katakanaToHiragana(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u30A1' && c <= '\u30F6') {
                out.append((char) (c - 0x60));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String metricNormalizerName(String language) {
        String lang = language == null ? "" : language.toLowerCase(Locale.ROOT);
        return "ja".equals(lang) ? "common+japanese_kana_v1" : "common_v1";
    }

    private static int levenshteinByCodePoint(String a, String b) {
        int[] acp = a.codePoints().toArray();
        int[] bcp = b.codePoints().toArray();
        int[] prev = new int[bcp.length + 1];
        int[] cur = new int[bcp.length + 1];
        for (int j = 0; j <= bcp.length; j++) prev[j] = j;
        for (int i = 1; i <= acp.length; i++) {
            cur[0] = i;
            for (int j = 1; j <= bcp.length; j++) {
                int cost = acp[i - 1] == bcp[j - 1] ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[bcp.length];
    }

    private static int readLe16(byte[] pcm, int off) {
        return (short) (((pcm[off + 1] & 0xFF) << 8) | (pcm[off] & 0xFF));
    }

    private static void writeLe16(byte[] pcm, int off, int value) {
        pcm[off] = (byte) (value & 0xFF);
        pcm[off + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private static int clamp16(int v) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    private record LoadedStt(LocalMoonshineSTT stt, int arch) {}

    private static LoadedStt loadStt(Path modelPath, int[] archCandidates, Preset preset) {
        IllegalStateException last = null;
        for (int arch : orderArchCandidatesForModel(modelPath, archCandidates)) {
            LocalMoonshineSTT stt = new LocalMoonshineSTT();
            stt.setVadWindowDuration(preset.vadWindowSec);
            stt.setVadLookBehindSamples(preset.vadLookBehindSamples);
            stt.setVadThreshold(preset.vadThreshold);
            stt.setMaxTokensPerSecond(preset.maxTokensPerSecond);
            try {
                if (stt.load(modelPath.toString(), arch)) return new LoadedStt(stt, arch);
                last = new IllegalStateException("Moonshine model load returned false: " + modelPath + " arch=" + arch);
            } catch (Throwable t) {
                last = new IllegalStateException("Moonshine model load failed: " + modelPath + " arch=" + arch, t);
            }
            try { stt.close(); } catch (Throwable ignore) {}
        }
        throw last == null ? new IllegalStateException("Moonshine model load failed: " + modelPath) : last;
    }

    private static int[] orderArchCandidatesForModel(Path modelPath, int[] archCandidates) {
        String lower = modelPath.toString().toLowerCase(Locale.ROOT).replace('\\', '/');
        if (lower.contains("tiny")) {
            return preferArchOrder(archCandidates,
                    LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_TINY,
                    LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_TINY_STREAMING);
        }
        if (lower.contains("base")) {
            return preferArchOrder(archCandidates,
                    LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_BASE,
                    LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_BASE_STREAMING);
        }
        if (Files.isRegularFile(modelPath.resolve("streaming_config.json"))) {
            return preferArchOrder(archCandidates,
                    LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_BASE_STREAMING,
                    LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_SMALL_STREAMING,
                    LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING,
                    LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_TINY_STREAMING);
        }
        return archCandidates;
    }

    private static int[] preferArchOrder(int[] archCandidates, int... preferred) {
        ArrayList<Integer> ordered = new ArrayList<>();
        for (int p : preferred) {
            for (int c : archCandidates) {
                if (c == p && !ordered.contains(c)) ordered.add(c);
            }
        }
        for (int c : archCandidates) {
            if (!ordered.contains(c)) ordered.add(c);
        }
        return ordered.stream().mapToInt(Integer::intValue).toArray();
    }

    private static Path resolveModelPath(Map<String, String> opts, String language) throws IOException {
        if (opts.containsKey("model")) return Path.of(opts.get("model")).toAbsolutePath().normalize();
        Path langDir = Path.of(opts.get("model-root")).toAbsolutePath().normalize().resolve(language);
        Path found = findDeepestModelDir(langDir);
        if (found == null) throw new IOException("No Moonshine model files found for language: " + language + " under " + langDir);
        return found;
    }

    private static Path findDeepestModelDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        Path best = null;
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.filter(Files::isDirectory).toList()) {
                if (hasMoonshineModelFile(p)) best = p;
            }
        }
        return best;
    }

    private static boolean hasMoonshineModelFile(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".ort") || n.endsWith(".onnx") || n.endsWith(".bin");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static int[] parseArchCandidates(String arch) {
        if ("auto".equalsIgnoreCase(arch)) {
            return new int[] {
                    LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_BASE,
                    LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_BASE_STREAMING,
                    LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_TINY,
                    LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_TINY_STREAMING,
                    LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_SMALL_STREAMING,
                    LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING
            };
        }
        return new int[] { parseArch(arch) };
    }

    private static int parseArch(String arch) {
        return switch (arch.toLowerCase(Locale.ROOT).replace("-", "_")) {
            case "tiny" -> LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_TINY;
            case "base" -> LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_BASE;
            case "tiny_streaming" -> LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_TINY_STREAMING;
            case "base_streaming" -> LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_BASE_STREAMING;
            case "small_streaming", "small" -> LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_SMALL_STREAMING;
            case "medium_streaming", "medium" -> LocalMoonshineSTT.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING;
            default -> Integer.parseInt(arch);
        };
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

    private static String defaultRunId() {
        return java.time.OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static List<String> splitFilter(String raw) {
        String v = raw == null || raw.isBlank() ? "all" : raw;
        return Arrays.stream(v.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .toList();
    }

    private static List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out;
    }

    private static void printUsage() {
        System.out.println("""
                Moonshine benchmark
                  --model <dir>                 Single Moonshine model directory
                  --model-root <dir>            Root containing language model folders
                  --arch <auto|small_streaming|...>  Default: auto
                  --input <wav-or-dir>          Wav file or directory
                  --manifest <csv>              wav,expected,language,scene
                  --language <ja|all>           Default: ja
                  --scene <name|all>            talk_clean,talk_noisy,talk_low_voice,short_command,desktop_hearing
                  --preset <name|all>           Alias for scene filter
                  --out <jsonl>                 Default: build/reports/moonshine-benchmark/results.jsonl
                  --history <jsonl>             Append all result rows to one history file
                  --run-id <id>                 Optional stable run id for result rows
                """);
    }
}
