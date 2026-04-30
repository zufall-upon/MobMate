package whisper;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MoonshinePresetSweepCli {
    private record Candidate(String id, Map<String, String> overrides) {}

    private MoonshinePresetSweepCli() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        if (opts.containsKey("help")) {
            printUsage();
            return;
        }

        String runPrefix = opts.getOrDefault("run-id", "sweep-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()));
        Path out = Path.of(opts.getOrDefault("out", "C:\\@work\\@SourceTree\\MobMate\\app\\workbenchi\\moonshine-preset-sweep-results.jsonl"))
                .toAbsolutePath().normalize();
        Path history = Path.of(opts.getOrDefault("history", "C:\\@work\\@SourceTree\\MobMate\\app\\workbenchi\\moonshine-preset-sweep-history.jsonl"))
                .toAbsolutePath().normalize();
        Path summary = Path.of(opts.getOrDefault("summary", "C:\\@work\\@SourceTree\\MobMate\\app\\workbenchi\\moonshine-preset-sweep-summary.jsonl"))
                .toAbsolutePath().normalize();
        Path recommendations = Path.of(opts.getOrDefault("recommendations", "C:\\@work\\@SourceTree\\MobMate\\app\\workbenchi\\moonshine-preset-recommendations.jsonl"))
                .toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.createDirectories(history.getParent());
        Files.createDirectories(summary.getParent());
        Files.createDirectories(recommendations.getParent());
        Files.deleteIfExists(out);
        Path detailDir = out.getParent().resolve(".moonshine-preset-sweep-work");
        Files.createDirectories(detailDir);

        List<Candidate> candidates = candidates(opts.getOrDefault("profile", "quick"));
        String onlyCandidate = opts.getOrDefault("candidate", "all").trim().toLowerCase(Locale.ROOT);
        if (!"all".equals(onlyCandidate)) {
            candidates = candidates.stream()
                    .filter(c -> c.id.equalsIgnoreCase(onlyCandidate))
                    .toList();
        }
        int limit = parseInt(opts.get("limit"), 0);
        if (limit > 0 && candidates.size() > limit) {
            candidates = new ArrayList<>(candidates.subList(0, limit));
        }
        if (candidates.isEmpty()) throw new IllegalArgumentException("No sweep candidates selected.");

        List<String> commonArgs = new ArrayList<>();
        copyArg(opts, commonArgs, "model");
        copyArg(opts, commonArgs, "model-root");
        copyArg(opts, commonArgs, "manifest");
        commonArgs.add("--language");
        commonArgs.add(opts.getOrDefault("language", "all"));
        commonArgs.add("--scene");
        commonArgs.add(opts.getOrDefault("scene", "all"));
        commonArgs.add("--preset");
        commonArgs.add(opts.getOrDefault("preset", "all"));
        copyArg(opts, commonArgs, "arch");
        copyArg(opts, commonArgs, "dump-audio-dir");

        System.out.println("SWEEP run_prefix=" + runPrefix + " candidates=" + candidates.size()
                + " out=" + out + " history=" + history);
        for (Candidate candidate : candidates) {
            String candidateRunId = runPrefix + "_" + candidate.id;
            Path candidateOut = detailDir.resolve(candidateRunId + ".jsonl");
            Files.deleteIfExists(candidateOut);
            List<String> benchArgs = new ArrayList<>(commonArgs);
            benchArgs.add("--out");
            benchArgs.add(candidateOut.toString());
            benchArgs.add("--history");
            benchArgs.add(history.toString());
            benchArgs.add("--run-id");
            benchArgs.add(candidateRunId);
            candidate.overrides.forEach((k, v) -> {
                benchArgs.add("--" + k);
                benchArgs.add(v);
            });
            System.out.println("SWEEP_CANDIDATE id=" + candidate.id + " run_id=" + candidateRunId);
            MoonshineBenchmarkCli.main(benchArgs.toArray(String[]::new));
            if (Files.exists(candidateOut)) {
                Files.write(out, Files.readAllLines(candidateOut, StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            }
        }

        List<JSONObject> summaries = summarize(out, runPrefix, candidates);
        try (var writer = Files.newBufferedWriter(summary, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)) {
            for (JSONObject row : summaries) {
                writer.write(row.toString());
                writer.newLine();
            }
        }
        summaries.stream()
                .sorted((a, b) -> Double.compare(a.optDouble("avg_cer", 999.0), b.optDouble("avg_cer", 999.0)))
                .forEach(row -> System.out.println(String.format(
                        Locale.ROOT,
                        "SWEEP_RESULT candidate=%s rows=%d exact=%d avg_cer=%.3f avg_raw_cer=%.3f avg_latency_ms=%.1f",
                        row.optString("candidate", ""),
                        row.optInt("rows", 0),
                        row.optInt("exact", 0),
                        row.optDouble("avg_cer", 999.0),
                        row.optDouble("avg_raw_cer", 999.0),
                        row.optDouble("avg_latency_ms", 0.0))));
        List<JSONObject> recs = recommend(out, runPrefix);
        try (var writer = Files.newBufferedWriter(recommendations, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)) {
            for (JSONObject row : recs) {
                writer.write(row.toString());
                writer.newLine();
            }
        }
        for (JSONObject row : recs) {
            System.out.println(String.format(
                    Locale.ROOT,
                    "SWEEP_RECOMMEND language=%s scene=%s candidate=%s exact=%d/%d avg_cer=%.3f avg_latency_ms=%.1f",
                    row.optString("language", ""),
                    row.optString("scene", ""),
                    row.optString("candidate", ""),
                    row.optInt("exact", 0),
                    row.optInt("rows", 0),
                    row.optDouble("avg_cer", 999.0),
                    row.optDouble("avg_latency_ms", 0.0)));
        }
    }

    private static List<Candidate> candidates(String profile) {
        List<Candidate> out = new ArrayList<>();
        out.add(new Candidate("baseline", Map.of()));
        out.add(new Candidate("tail_bridge", linked(
                "override-trim-pad-ms", "260",
                "override-leading-padding-ms", "520",
                "override-tail-padding-ms", "840",
                "override-passes", "4",
                "override-settle-sleep-ms", "65",
                "override-slow-down-ratio", "1.12",
                "override-prefilter", "normal")));
        out.add(new Candidate("pause_bridge", linked(
                "override-trim-abs-threshold", "120",
                "override-trim-pad-ms", "300",
                "override-squash-silence-abs-threshold", "140",
                "override-squash-silence-min-ms", "220",
                "override-squash-silence-keep-ms", "110",
                "override-leading-padding-ms", "620",
                "override-tail-padding-ms", "980",
                "override-passes", "4",
                "override-settle-sleep-ms", "70",
                "override-slow-down-ratio", "1.12",
                "override-prefilter", "normal")));
        out.add(new Candidate("soft_voice", linked(
                "override-trim-abs-threshold", "80",
                "override-trim-pad-ms", "340",
                "override-leading-padding-ms", "700",
                "override-tail-padding-ms", "1100",
                "override-normalize-dbfs", "-24.8",
                "override-passes", "4",
                "override-settle-sleep-ms", "75",
                "override-prefilter", "off")));
        out.add(new Candidate("clear_fast", linked(
                "override-trim-pad-ms", "180",
                "override-leading-padding-ms", "360",
                "override-tail-padding-ms", "560",
                "override-passes", "3",
                "override-settle-sleep-ms", "45",
                "override-slow-down-ratio", "1.00",
                "override-prefilter", "normal")));
        if (!"quick".equalsIgnoreCase(profile)) {
            out.add(new Candidate("strong_noise", linked(
                    "override-trim-abs-threshold", "220",
                    "override-trim-pad-ms", "220",
                    "override-leading-padding-ms", "460",
                    "override-tail-padding-ms", "760",
                    "override-passes", "4",
                    "override-settle-sleep-ms", "70",
                    "override-prefilter", "strong")));
            out.add(new Candidate("slow_confirm", linked(
                    "override-trim-abs-threshold", "100",
                    "override-trim-pad-ms", "300",
                    "override-leading-padding-ms", "640",
                    "override-tail-padding-ms", "1020",
                    "override-passes", "5",
                    "override-settle-sleep-ms", "85",
                    "override-slow-down-ratio", "1.18",
                    "override-prefilter", "normal")));
        }
        return out;
    }

    private static List<JSONObject> summarize(Path out, String runPrefix, List<Candidate> candidates) throws Exception {
        Map<String, Acc> accs = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            accs.put(c.id, new Acc(c.id));
        }
        if (!Files.exists(out)) return List.of();
        for (String line : Files.readAllLines(out, StandardCharsets.UTF_8)) {
            if (line == null || line.isBlank()) continue;
            JSONObject row = new JSONObject(line);
            String runId = row.optString("run_id", "");
            if (!runId.startsWith(runPrefix + "_")) continue;
            String candidate = runId.substring((runPrefix + "_").length());
            Acc acc = accs.computeIfAbsent(candidate, Acc::new);
            acc.add(row);
        }
        List<JSONObject> rows = new ArrayList<>();
        for (Acc acc : accs.values()) {
            if (acc.rows <= 0) continue;
            rows.add(acc.toJson(runPrefix));
        }
        return rows;
    }

    private static List<JSONObject> recommend(Path out, String runPrefix) throws Exception {
        Map<String, Acc> accs = new LinkedHashMap<>();
        if (!Files.exists(out)) return List.of();
        for (String line : Files.readAllLines(out, StandardCharsets.UTF_8)) {
            if (line == null || line.isBlank()) continue;
            JSONObject row = new JSONObject(line);
            String runId = row.optString("run_id", "");
            if (!runId.startsWith(runPrefix + "_")) continue;
            String candidate = runId.substring((runPrefix + "_").length());
            String language = row.optString("language", "");
            String scene = row.optString("scene", "");
            String key = language + "\t" + scene + "\t" + candidate;
            Acc acc = accs.computeIfAbsent(key, ignored -> new Acc(candidate, language, scene));
            acc.add(row);
        }
        Map<String, Acc> bestByScene = new LinkedHashMap<>();
        for (Acc acc : accs.values()) {
            if (acc.rows <= 0) continue;
            String key = acc.language + "\t" + acc.scene;
            Acc current = bestByScene.get(key);
            if (current == null || isBetterRecommendation(acc, current)) {
                bestByScene.put(key, acc);
            }
        }
        List<JSONObject> rows = new ArrayList<>();
        for (Acc acc : bestByScene.values()) {
            JSONObject row = acc.toJson(runPrefix);
            row.put("type", "moonshine_preset_recommendation");
            row.put("selection_rule", "min_avg_cer_then_max_exact_then_min_latency");
            rows.add(row);
        }
        rows.sort((a, b) -> {
            int lang = a.optString("language", "").compareTo(b.optString("language", ""));
            if (lang != 0) return lang;
            return a.optString("scene", "").compareTo(b.optString("scene", ""));
        });
        return rows;
    }

    private static boolean isBetterRecommendation(Acc a, Acc b) {
        double acer = a.avgCer();
        double bcer = b.avgCer();
        if (Math.abs(acer - bcer) > 0.000001d) return acer < bcer;
        if (a.exact != b.exact) return a.exact > b.exact;
        return a.avgLatency() < b.avgLatency();
    }

    private static final class Acc {
        final String candidate;
        final String language;
        final String scene;
        int rows;
        int exact;
        double cerSum;
        int cerRows;
        double rawCerSum;
        int rawCerRows;
        double latencySum;
        int latencyRows;

        Acc(String candidate) {
            this(candidate, "", "");
        }

        Acc(String candidate, String language, String scene) {
            this.candidate = candidate;
            this.language = language == null ? "" : language;
            this.scene = scene == null ? "" : scene;
        }

        void add(JSONObject row) {
            rows++;
            if (row.optBoolean("exact", false)) exact++;
            Object cer = row.opt("cer");
            if (cer instanceof Number n) {
                cerSum += n.doubleValue();
                cerRows++;
            }
            Object rawCer = row.opt("raw_cer");
            if (rawCer instanceof Number n) {
                rawCerSum += n.doubleValue();
                rawCerRows++;
            }
            Object latency = row.opt("latency_ms");
            if (latency instanceof Number n) {
                latencySum += n.doubleValue();
                latencyRows++;
            }
        }

        double avgCer() {
            return cerRows == 0 ? 999.0d : cerSum / cerRows;
        }

        double avgLatency() {
            return latencyRows == 0 ? 999999.0d : latencySum / latencyRows;
        }

        JSONObject toJson(String runPrefix) {
            return new JSONObject()
                    .put("ts", LocalDateTime.now().toString())
                    .put("type", "moonshine_preset_sweep_summary")
                    .put("run_prefix", runPrefix)
                    .put("candidate", candidate)
                    .put("language", language)
                    .put("scene", scene)
                    .put("rows", rows)
                    .put("exact", exact)
                    .put("avg_cer", cerRows == 0 ? JSONObject.NULL : cerSum / cerRows)
                    .put("avg_raw_cer", rawCerRows == 0 ? JSONObject.NULL : rawCerSum / rawCerRows)
                    .put("avg_latency_ms", latencyRows == 0 ? JSONObject.NULL : latencySum / latencyRows);
        }
    }

    private static void copyArg(Map<String, String> opts, List<String> args, String key) {
        String value = opts.get(key);
        if (value == null || value.isBlank()) return;
        args.add("--" + key);
        args.add(value);
    }

    private static Map<String, String> linked(String... kv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(kv[i], kv[i + 1]);
        }
        return out;
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
                Moonshine preset sweep
                  --model-root <dir>            Root containing language model folders
                  --manifest <csv>              Benchmark manifest
                  --language <ja|en|zh|ko|all>  Default: all
                  --scene <name|all>            Default: all
                  --profile <quick|full>        Default: quick
                  --candidate <id|all>          baseline,tail_bridge,pause_bridge,soft_voice,clear_fast
                  --out <jsonl>                 Sweep detail rows
                  --summary <jsonl>             Sweep summary rows
                  --recommendations <jsonl>     Best candidate by language/scene
                  --history <jsonl>             Sweep history rows
                """);
    }
}
