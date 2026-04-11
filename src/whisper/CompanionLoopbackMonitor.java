package whisper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Arrays;

final class CompanionLoopbackMonitor {

    private static final String ROUTE = "companion_owned";
    private static final long LOOPBACK_SE_COOLDOWN_MS = 4500L;
    private static final long LOOPBACK_STATUS_LOG_MS = 10000L;
    private static final int WASAPI_MAX_RETRIES = 5;
    private static final long WASAPI_CRASH_WINDOW_MS = 15_000L;
    private static final double NOISE_FLOOR_MAX_RMS = 280.0;
    private static final int VOICE_HOLD_MAX_CHUNKS = 2;
    private static final int RECOGNITION_CHUNK_MS = 2000;
    private static final int LOOPBACK_SAMPLE_RATE = 16000;
    private static final int LOOPBACK_BYTES_PER_SECOND = LOOPBACK_SAMPLE_RATE * 2;
    private static final int MULTIMODAL_AUDIO_WINDOW_MS = 12_000;

    private final MobMateWhisp host;
    private final Object loopLock = new Object();
    private final AudioPrefilter.State prefilterState = new AudioPrefilter.State();
    private final Object voiceGateLock = new Object();
    private final ByteArrayOutputStream pcmAcc = new ByteArrayOutputStream(16000 * 2 * 2);
    private final Object recentAudioLock = new Object();

    private volatile boolean running = false;
    private volatile boolean intentionalStop = false;
    private volatile boolean transcribing = false;
    private volatile Process loopProc;
    private volatile Thread loopProcThread;
    private volatile Thread loopErrThread;
    private volatile int wasapiCrashCount = 0;
    private volatile long wasapiFirstCrashMs = 0L;
    private volatile double noiseFloorRms = 120.0;
    private volatile int voiceHoldChunks = 0;
    private volatile int gateRejectStreak = 0;
    private volatile long lastLoopbackStatusLogMs = 0L;
    private volatile long lastSeAtMs = 0L;
    private volatile String lastSeKind = "";
    private volatile byte[] recentAudioPcm = new byte[0];

    CompanionLoopbackMonitor(MobMateWhisp host) {
        this.host = host;
    }

    boolean isRunning() {
        return running;
    }

    void startIfNeeded() {
        synchronized (loopLock) {
            if (running) return;
            running = true;
            intentionalStop = false;
            wasapiCrashCount = 0;
            resetAnalysisState();
        }
        host.noteCompanionDesktopAudioRouteChanged(ROUTE, "companion-loopback-start");
        startWasapiLoopbackProc();
    }

    void stop(String reason) {
        synchronized (loopLock) {
            if (!running && loopProc == null) return;
            intentionalStop = true;
            running = false;
        }
        stopWasapiProc();
        resetRuntimeState();
        host.noteCompanionDesktopAudioRouteChanged("off", reason == null ? "companion-loopback-stop" : reason);
    }

    private void resetAnalysisState() {
        prefilterState.reset();
        noiseFloorRms = 120.0;
        voiceHoldChunks = 0;
        gateRejectStreak = 0;
        lastLoopbackStatusLogMs = 0L;
        lastSeAtMs = 0L;
        lastSeKind = "";
    }

    private void resetRuntimeState() {
        synchronized (pcmAcc) {
            pcmAcc.reset();
        }
        synchronized (recentAudioLock) {
            recentAudioPcm = new byte[0];
        }
        transcribing = false;
        resetAnalysisState();
    }

    private void startWasapiLoopbackProc() {
        File helperExe = new File("./MobMateLoopbackPcm.exe");
        File ps1 = new File("./wasapi_loopback.ps1");
        boolean useExe = helperExe.exists();
        if (!useExe && !ps1.exists()) {
            Config.log("[Companion][Loopback] WASAPI helper not found: "
                    + helperExe.getAbsolutePath() + " / " + ps1.getAbsolutePath());
            synchronized (loopLock) {
                running = false;
            }
            host.noteCompanionDesktopAudioRouteChanged("off", "helper-missing");
            return;
        }

        try {
            ProcessBuilder pb;
            if (useExe) {
                pb = new ProcessBuilder(helperExe.getAbsolutePath());
                Config.logDebug("[Companion][Loopback] start helper exe: " + helperExe.getAbsolutePath());
            } else {
                pb = new ProcessBuilder(
                        "powershell",
                        "-NoProfile",
                        "-ExecutionPolicy", "Bypass",
                        "-File", ps1.getAbsolutePath()
                );
                Config.logDebug("[Companion][Loopback] start ps1: " + ps1.getAbsolutePath());
            }
            pb.redirectErrorStream(false);
            Process started = pb.start();
            loopProc = started;

            loopErrThread = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(started.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Config.log("[Companion][Loopback][WASAPI] " + line);
                    }
                } catch (Exception ignore) {
                }
            }, "companion-loopback-stderr");
            loopErrThread.setDaemon(true);
            loopErrThread.start();
        } catch (Exception ex) {
            Config.logError("[Companion][Loopback] WASAPI helper start failed", ex);
            synchronized (loopLock) {
                running = false;
            }
            host.noteCompanionDesktopAudioRouteChanged("off", "helper-start-failed");
            return;
        }

        final Process proc = loopProc;
        loopProcThread = new Thread(() -> {
            long lastPcmArrivedMs = 0L;
            int lastPeak = 0;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                long lastDbgMs = 0L;
                while (running && (line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    if (line.startsWith("PEAK ")) {
                        try {
                            lastPeak = Integer.parseInt(line.substring(5).trim());
                        } catch (Exception ignore) {
                        }
                        continue;
                    }

                    if (line.startsWith("PCM ")) {
                        byte[] pcm;
                        try {
                            pcm = Base64.getDecoder().decode(line.substring(4).trim());
                        } catch (Exception ignore) {
                            continue;
                        }

                        lastPcmArrivedMs = System.currentTimeMillis();
                        appendRecentAudio(pcm);
                        synchronized (pcmAcc) {
                            pcmAcc.write(pcm, 0, pcm.length);
                            int triggerBytes = (int) ((LOOPBACK_SAMPLE_RATE * 2L * RECOGNITION_CHUNK_MS) / 1000L);
                            if (!transcribing && pcmAcc.size() >= triggerBytes) {
                                transcribing = true;
                                byte[] chunk = pcmAcc.toByteArray();
                                pcmAcc.reset();
                                Config.logDebug("[Companion][Loopback][ASR] trigger bytes=" + chunk.length
                                        + " chunkMs=" + RECOGNITION_CHUNK_MS + " peak=" + lastPeak);
                                Thread t = new Thread(() -> {
                                    try {
                                        submitCompanionChunk(chunk);
                                    } catch (Exception ex) {
                                        Config.logError("[Companion][Loopback][ASR] failed", ex);
                                    } finally {
                                        transcribing = false;
                                    }
                                }, "companion-loopback-asr");
                                t.setDaemon(true);
                                t.start();
                            }
                        }
                        continue;
                    }

                    long now = System.currentTimeMillis();
                    if (now - lastDbgMs > 10000L) {
                        lastDbgMs = now;
                        long age = (lastPcmArrivedMs == 0L) ? -1L : (now - lastPcmArrivedMs);
                        Config.logDebug("[Companion][Loopback][WASAPI] pcmAgeMs=" + age
                                + " accBytes=" + pcmAcc.size());
                    }
                }
            } catch (Exception ex) {
                if (intentionalStop) {
                    Config.log("[Companion][Loopback][WASAPI] stopped (intentional).");
                } else {
                    long now = System.currentTimeMillis();
                    if ((now - wasapiFirstCrashMs) > WASAPI_CRASH_WINDOW_MS) {
                        wasapiCrashCount = 0;
                        wasapiFirstCrashMs = now;
                    }
                    wasapiCrashCount++;

                    if (wasapiCrashCount > WASAPI_MAX_RETRIES) {
                        Config.logError("[Companion][Loopback] helper crashed "
                                + wasapiCrashCount + " times in "
                                + (WASAPI_CRASH_WINDOW_MS / 1000) + "s — giving up", ex);
                        synchronized (loopLock) {
                            running = false;
                        }
                    } else {
                        Config.logError("[Companion][Loopback] helper crashed ("
                                + wasapiCrashCount + "/" + WASAPI_MAX_RETRIES + ")", ex);
                        Config.log("[Companion][Loopback][WASAPI] restarting helper in 500ms...");
                        Thread restart = new Thread(() -> {
                            try {
                                Thread.sleep(500L);
                            } catch (Exception ignore) {
                            }
                            synchronized (loopLock) {
                                if (running && !intentionalStop) {
                                    startWasapiLoopbackProc();
                                }
                            }
                        }, "companion-loopback-restart");
                        restart.setDaemon(true);
                        restart.start();
                    }
                }
            } finally {
                if (!intentionalStop) {
                    synchronized (loopLock) {
                        running = false;
                    }
                }
                try {
                    if (proc != null) proc.destroyForcibly();
                } catch (Exception ignore) {
                }
                resetRuntimeState();
                host.noteCompanionDesktopAudioRouteChanged("off",
                        intentionalStop ? "companion-loopback-finalize" : "helper-exit");
            }
        }, "companion-loopback-stdout");

        loopProcThread.setDaemon(true);
        loopProcThread.start();
    }

    private void stopWasapiProc() {
        try {
            Process p = loopProc;
            loopProc = null;
            if (p != null) {
                long pid = -1L;
                try {
                    pid = p.pid();
                } catch (Throwable ignore) {
                }
                try {
                    p.destroy();
                } catch (Exception ignore) {
                }
                try {
                    p.destroyForcibly();
                } catch (Exception ignore) {
                }
                if (pid > 0L) {
                    try {
                        new ProcessBuilder("cmd", "/c", "taskkill", "/PID", String.valueOf(pid), "/T", "/F")
                                .redirectErrorStream(true)
                                .start();
                    } catch (Exception ignore) {
                    }
                }
            }
        } catch (Exception ignore) {
        }
        try {
            Thread t = loopProcThread;
            loopProcThread = null;
            if (t != null) {
                t.join(300L);
            }
        } catch (Exception ignore) {
        }
    }

    byte[] snapshotRecentAudioPcm(int maxWindowMs) {
        int safeWindowMs = Math.max(1000, Math.min(MULTIMODAL_AUDIO_WINDOW_MS, maxWindowMs));
        int wantedBytes = (int) ((LOOPBACK_BYTES_PER_SECOND * (long) safeWindowMs) / 1000L);
        synchronized (recentAudioLock) {
            if (recentAudioPcm.length == 0) {
                return new byte[0];
            }
            int start = Math.max(0, recentAudioPcm.length - wantedBytes);
            return Arrays.copyOfRange(recentAudioPcm, start, recentAudioPcm.length);
        }
    }

    private void appendRecentAudio(byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return;
        }
        int maxBytes = (int) ((LOOPBACK_BYTES_PER_SECOND * (long) MULTIMODAL_AUDIO_WINDOW_MS) / 1000L);
        synchronized (recentAudioLock) {
            int keepBytes = Math.min(maxBytes, recentAudioPcm.length + pcm.length);
            byte[] merged = new byte[keepBytes];
            int oldBytes = Math.min(recentAudioPcm.length, Math.max(0, keepBytes - pcm.length));
            if (oldBytes > 0) {
                System.arraycopy(
                        recentAudioPcm,
                        recentAudioPcm.length - oldBytes,
                        merged,
                        0,
                        oldBytes
                );
            }
            int newBytes = keepBytes - oldBytes;
            if (newBytes > 0) {
                System.arraycopy(
                        pcm,
                        Math.max(0, pcm.length - newBytes),
                        merged,
                        oldBytes,
                        newBytes
                );
            }
            recentAudioPcm = merged;
        }
    }

    private void submitCompanionChunk(byte[] pcm16k16mono) {
        if (pcm16k16mono == null || pcm16k16mono.length == 0 || !running) {
            return;
        }
        if (host.isHearingBlockedByTts()) {
            Config.logDebug("[Companion][Loopback][REC] skip chunk (TTS guard)");
            return;
        }
        if (host.isTranscribing()) {
            Config.logDebug("[Companion][Loopback][REC] skip chunk (main busy)");
            return;
        }

        byte[] filteredChunk = AudioPrefilter.processForAsr(
                pcm16k16mono,
                pcm16k16mono.length,
                16000,
                AudioPrefilter.Mode.HEARING,
                MobMateWhisp.getHearingAudioPrefilterMode(),
                prefilterState
        );

        AudioPrefilter.VoiceMetrics metrics =
                AudioPrefilter.analyzeVoiceLike(pcm16k16mono, pcm16k16mono.length, 16000);
        boolean voiceLike;
        double floorSnapshot;
        synchronized (voiceGateLock) {
            boolean recentVoiceHold = voiceHoldChunks > 0;
            voiceLike = AudioPrefilter.isVoiceLike(metrics, noiseFloorRms, recentVoiceHold);
            if (voiceLike) {
                gateRejectStreak = 0;
                voiceHoldChunks = VOICE_HOLD_MAX_CHUNKS;
                gentlyRecoverNoiseFloor(metrics.rms);
            } else {
                gateRejectStreak++;
                if (voiceHoldChunks > 0) voiceHoldChunks--;
                updateNoiseFloor(metrics);
            }
            floorSnapshot = noiseFloorRms;
        }

        maybeLogLoopbackChunkStatus(metrics, voiceLike, floorSnapshot);
        maybeDetectLoopbackSeEvent(metrics, floorSnapshot);
        if (!voiceLike) {
            Config.logDebug(String.format(
                    Locale.ROOT,
                    "[Companion][Loopback][REC] skip chunk (gate) rms=%.1f zcr=%.3f vbr=%.3f floor=%.1f peak=%d",
                    metrics.rms, metrics.zcr, metrics.voiceBandRatio, floorSnapshot, metrics.peak
            ));
            return;
        }

        host.noteDesktopAudioPresenceForCompanion(ROUTE, metrics.rms, metrics.voiceBandRatio, metrics.peak);
        if (!host.shouldCompanionLoopbackUseCaptionPath()) {
            Config.logDebug(String.format(
                    Locale.ROOT,
                    "[Companion][Loopback][REC] direct-audio-only route=%s rms=%.1f vbr=%.3f peak=%d",
                    ROUTE, metrics.rms, metrics.voiceBandRatio, metrics.peak
            ));
            return;
        }

        filteredChunk = AudioPrefilter.normalizeFinalChunkForAsr(filteredChunk, filteredChunk.length);
        String caption = host.transcribeCompanionSpeechSemanticRaw(filteredChunk);
        if (!running || caption == null) {
            return;
        }
        String trimmed = caption.trim();
        if (trimmed.isBlank()) {
            return;
        }
        Config.log("[Companion][Loopback][Caption] accepted text=" + trimmed);
        host.noteDesktopAudioCaptionForCompanion(ROUTE, trimmed);
    }

    private void maybeLogLoopbackChunkStatus(AudioPrefilter.VoiceMetrics metrics, boolean voiceLike, double floorSnapshot) {
        if (metrics == null) return;
        long now = System.currentTimeMillis();
        if ((now - lastLoopbackStatusLogMs) < LOOPBACK_STATUS_LOG_MS) {
            return;
        }
        lastLoopbackStatusLogMs = now;
        Config.log(String.format(
                Locale.ROOT,
                "[Companion][Loopback] chunk voiceLike=%s rms=%.1f zcr=%.3f vbr=%.3f peak=%d floor=%.1f running=%s",
                voiceLike, metrics.rms, metrics.zcr, metrics.voiceBandRatio, metrics.peak, floorSnapshot, running
        ));
    }

    private void maybeDetectLoopbackSeEvent(AudioPrefilter.VoiceMetrics metrics, double floorSnapshot) {
        if (metrics == null) return;
        String eventKind = classifyLoopbackSeEvent(metrics, floorSnapshot);
        if (eventKind.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if ((now - lastSeAtMs) < LOOPBACK_SE_COOLDOWN_MS && eventKind.equals(lastSeKind)) {
            return;
        }
        lastSeAtMs = now;
        lastSeKind = eventKind;
        Config.log(String.format(
                Locale.ROOT,
                "[Companion][Loopback][SE] detected kind=%s rms=%.1f zcr=%.3f vbr=%.3f peak=%d floor=%.1f",
                eventKind, metrics.rms, metrics.zcr, metrics.voiceBandRatio, metrics.peak, floorSnapshot
        ));
        host.noteDesktopAudioEventForCompanion(
                ROUTE,
                eventKind,
                metrics.rms,
                metrics.zcr,
                metrics.voiceBandRatio,
                metrics.peak,
                floorSnapshot
        );
    }

    private String classifyLoopbackSeEvent(AudioPrefilter.VoiceMetrics metrics, double floorSnapshot) {
        double rms = metrics.rms;
        double zcr = metrics.zcr;
        double vbr = metrics.voiceBandRatio;
        int peak = metrics.peak;
        double floor = Math.max(40.0, floorSnapshot);

        if (peak >= 18500
                && rms >= Math.max(1500.0, floor * 6.0)
                && vbr <= 0.22
                && zcr <= 0.14) {
            return "impact_like";
        }
        if (peak >= 14000
                && rms >= Math.max(1200.0, floor * 5.0)
                && zcr >= 0.05
                && zcr <= 0.26
                && vbr >= 0.16
                && vbr <= 0.46) {
            return "crowd_like";
        }
        if (peak >= 12500
                && rms >= Math.max(1000.0, floor * 4.5)
                && zcr >= 0.16
                && vbr >= 0.08
                && vbr <= 0.34) {
            return "alarm_like";
        }
        if (peak >= 20000 && rms >= Math.max(1600.0, floor * 6.5)) {
            return "intense_nonvoice";
        }
        return "";
    }

    private void gentlyRecoverNoiseFloor(double speechRms) {
        if (!Double.isFinite(speechRms) || speechRms <= 0.0) return;
        double target = Math.max(80.0, Math.min(NOISE_FLOOR_MAX_RMS, speechRms * 0.42));
        if (target < noiseFloorRms) {
            noiseFloorRms = (noiseFloorRms * 0.92) + (target * 0.08);
        } else {
            noiseFloorRms = Math.min(NOISE_FLOOR_MAX_RMS, (noiseFloorRms * 0.75) + (target * 0.25));
        }
    }

    private void updateNoiseFloor(AudioPrefilter.VoiceMetrics metrics) {
        if (metrics == null) return;
        double rms = metrics.rms;
        if (!Double.isFinite(rms) || rms <= 0.0) return;
        double clamped = Math.max(60.0, Math.min(NOISE_FLOOR_MAX_RMS, rms));
        if (clamped > noiseFloorRms) {
            noiseFloorRms = Math.min(NOISE_FLOOR_MAX_RMS, (noiseFloorRms * 0.82) + (clamped * 0.18));
        } else {
            noiseFloorRms = (noiseFloorRms * 0.97) + (clamped * 0.03);
        }
    }
}
