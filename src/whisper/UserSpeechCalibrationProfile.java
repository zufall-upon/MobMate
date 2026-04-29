package whisper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class UserSpeechCalibrationProfile {
    private static final int FILE_MAGIC = 0x4D4D4341; // "MMCA"
    private static final int FILE_VERSION = 1;

    private int acceptedSamples;
    private double totalWeight;
    private double rawRmsMean;
    private double procRmsMean;
    private double peakMean;
    private double avgMean;
    private double speechDurationMeanMs;
    private double trailingSilenceMeanMs;
    private double trailingSilenceFramesMean;
    private double silenceThresholdFramesMean;
    private double procRawRatioMean;
    private double procVoiceBandRatioMean;
    private double procZcrMean;
    private long updatedAtMs;
    private String lastMicKey = "";

    public synchronized boolean hasData() {
        return acceptedSamples > 0 && totalWeight > 0.0d;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                acceptedSamples,
                totalWeight,
                rawRmsMean,
                procRmsMean,
                peakMean,
                avgMean,
                speechDurationMeanMs,
                trailingSilenceMeanMs,
                trailingSilenceFramesMean,
                silenceThresholdFramesMean,
                procRawRatioMean,
                procVoiceBandRatioMean,
                procZcrMean,
                updatedAtMs,
                lastMicKey == null ? "" : lastMicKey
        );
    }

    public synchronized void observeAccepted(double rawRms,
                                             double procRms,
                                             int peak,
                                             int avg,
                                             long speechDurationMs,
                                             long trailingSilenceMs,
                                             int trailingSilenceFrames,
                                             int silenceThresholdFrames,
                                             double procVoiceBandRatio,
                                             double procZcr,
                                             double weight,
                                             String micKey) {
        double w = Math.max(0.10d, Math.min(1.00d, weight));
        double nextWeight = totalWeight + w;
        rawRmsMean = weightedMean(rawRmsMean, totalWeight, sanitizePositive(rawRms), w);
        procRmsMean = weightedMean(procRmsMean, totalWeight, sanitizePositive(procRms), w);
        peakMean = weightedMean(peakMean, totalWeight, Math.max(0, peak), w);
        avgMean = weightedMean(avgMean, totalWeight, Math.max(0, avg), w);
        speechDurationMeanMs = weightedMean(speechDurationMeanMs, totalWeight, Math.max(0L, speechDurationMs), w);
        trailingSilenceMeanMs = weightedMean(trailingSilenceMeanMs, totalWeight, Math.max(0L, trailingSilenceMs), w);
        trailingSilenceFramesMean = weightedMean(trailingSilenceFramesMean, totalWeight, Math.max(0, trailingSilenceFrames), w);
        silenceThresholdFramesMean = weightedMean(silenceThresholdFramesMean, totalWeight, Math.max(0, silenceThresholdFrames), w);
        double ratio = sanitizeRatio(procRms / Math.max(1.0d, rawRms));
        procRawRatioMean = weightedMean(procRawRatioMean, totalWeight, ratio, w);
        procVoiceBandRatioMean = weightedMean(procVoiceBandRatioMean, totalWeight, sanitizeRatio(procVoiceBandRatio), w);
        procZcrMean = weightedMean(procZcrMean, totalWeight, Math.max(0.0d, procZcr), w);
        totalWeight = nextWeight;
        acceptedSamples++;
        updatedAtMs = System.currentTimeMillis();
        lastMicKey = micKey == null ? "" : micKey.trim();
    }

    public synchronized void saveToFile(File file) {
        if (file == null || !hasData()) return;
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file))) {
            dos.writeInt(FILE_MAGIC);
            dos.writeInt(FILE_VERSION);
            dos.writeInt(acceptedSamples);
            dos.writeDouble(totalWeight);
            dos.writeDouble(rawRmsMean);
            dos.writeDouble(procRmsMean);
            dos.writeDouble(peakMean);
            dos.writeDouble(avgMean);
            dos.writeDouble(speechDurationMeanMs);
            dos.writeDouble(trailingSilenceMeanMs);
            dos.writeDouble(trailingSilenceFramesMean);
            dos.writeDouble(silenceThresholdFramesMean);
            dos.writeDouble(procRawRatioMean);
            dos.writeDouble(procVoiceBandRatioMean);
            dos.writeDouble(procZcrMean);
            dos.writeLong(updatedAtMs);
            dos.writeUTF(lastMicKey == null ? "" : lastMicKey);
        } catch (Exception e) {
            Config.logDebug("★UserSpeechCalibration save failed: " + e.getMessage());
        }
    }

    public synchronized boolean loadFromFile(File file) {
        if (file == null || !file.exists()) return false;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            int magic = dis.readInt();
            if (magic != FILE_MAGIC) {
                Config.logDebug("★UserSpeechCalibration load skipped: magic mismatch");
                return false;
            }
            int version = dis.readInt();
            if (version != FILE_VERSION) {
                Config.logDebug("★UserSpeechCalibration load skipped: version mismatch " + version);
                return false;
            }
            acceptedSamples = Math.max(0, dis.readInt());
            totalWeight = Math.max(0.0d, dis.readDouble());
            rawRmsMean = sanitizePositive(dis.readDouble());
            procRmsMean = sanitizePositive(dis.readDouble());
            peakMean = sanitizePositive(dis.readDouble());
            avgMean = sanitizePositive(dis.readDouble());
            speechDurationMeanMs = sanitizePositive(dis.readDouble());
            trailingSilenceMeanMs = sanitizePositive(dis.readDouble());
            trailingSilenceFramesMean = sanitizePositive(dis.readDouble());
            silenceThresholdFramesMean = sanitizePositive(dis.readDouble());
            procRawRatioMean = sanitizeRatio(dis.readDouble());
            procVoiceBandRatioMean = sanitizeRatio(dis.readDouble());
            procZcrMean = Math.max(0.0d, dis.readDouble());
            updatedAtMs = Math.max(0L, dis.readLong());
            lastMicKey = dis.readUTF();
            return hasData();
        } catch (Exception e) {
            Config.logDebug("★UserSpeechCalibration load failed: " + e.getMessage());
            return false;
        }
    }

    private static double weightedMean(double currentMean, double currentWeight, double value, double addWeight) {
        if (currentWeight <= 0.0d) return value;
        double total = currentWeight + addWeight;
        return ((currentMean * currentWeight) + (value * addWeight)) / Math.max(0.0001d, total);
    }

    private static double sanitizePositive(double value) {
        if (!Double.isFinite(value)) return 0.0d;
        return Math.max(0.0d, value);
    }

    private static double sanitizeRatio(double value) {
        if (!Double.isFinite(value)) return 0.0d;
        return Math.max(0.0d, Math.min(1.5d, value));
    }

    public static final class Snapshot {
        public final int acceptedSamples;
        public final double totalWeight;
        public final double rawRmsMean;
        public final double procRmsMean;
        public final double peakMean;
        public final double avgMean;
        public final double speechDurationMeanMs;
        public final double trailingSilenceMeanMs;
        public final double trailingSilenceFramesMean;
        public final double silenceThresholdFramesMean;
        public final double procRawRatioMean;
        public final double procVoiceBandRatioMean;
        public final double procZcrMean;
        public final long updatedAtMs;
        public final String lastMicKey;

        private Snapshot(int acceptedSamples,
                         double totalWeight,
                         double rawRmsMean,
                         double procRmsMean,
                         double peakMean,
                         double avgMean,
                         double speechDurationMeanMs,
                         double trailingSilenceMeanMs,
                         double trailingSilenceFramesMean,
                         double silenceThresholdFramesMean,
                         double procRawRatioMean,
                         double procVoiceBandRatioMean,
                         double procZcrMean,
                         long updatedAtMs,
                         String lastMicKey) {
            this.acceptedSamples = acceptedSamples;
            this.totalWeight = totalWeight;
            this.rawRmsMean = rawRmsMean;
            this.procRmsMean = procRmsMean;
            this.peakMean = peakMean;
            this.avgMean = avgMean;
            this.speechDurationMeanMs = speechDurationMeanMs;
            this.trailingSilenceMeanMs = trailingSilenceMeanMs;
            this.trailingSilenceFramesMean = trailingSilenceFramesMean;
            this.silenceThresholdFramesMean = silenceThresholdFramesMean;
            this.procRawRatioMean = procRawRatioMean;
            this.procVoiceBandRatioMean = procVoiceBandRatioMean;
            this.procZcrMean = procZcrMean;
            this.updatedAtMs = updatedAtMs;
            this.lastMicKey = lastMicKey == null ? "" : lastMicKey;
        }

        public boolean isUsable() {
            return acceptedSamples >= 3 && totalWeight >= 1.0d;
        }

        public double deviceAffinity(String micKey) {
            if (lastMicKey == null || lastMicKey.isBlank()) return 1.0d;
            if (micKey == null || micKey.isBlank()) return 0.80d;
            return lastMicKey.equalsIgnoreCase(micKey.trim()) ? 1.0d : 0.55d;
        }
    }
}
