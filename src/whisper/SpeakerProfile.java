package whisper;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 話者照合プロファイル（Speaker Verification Profile）v3
 *
 * MFCCベースの話者照合。メル帯域にDCTを適用してケプストラム係数を算出し、
 * 平均＋標準偏差の26次元特徴量で話者を識別する。
 *
 * v2→v3変更点:
 *   - 20メル帯域 → 26メル帯域 + DCT → 13 MFCC
 *   - 特徴量: MFCC平均13 + MFCC標準偏差13 = 26次元（話者の声道構造＋動態を捉える）
 *   - profile.datフォーマット変更（v2プロファイルは自動再エンロール）
 */
public class SpeakerProfile {

    // ---- 定数 ----
    private static final int FFT_SIZE = 512;           // 32ms @ 16kHz
    private static final int HOP_SIZE = 256;            // 16ms hop
    private static final int MEL_BANDS = 26;            // メル帯域数（DCT入力）
    private static final int NUM_MFCC = 13;             // MFCC係数数（c0〜c12）
    private static final int FEATURE_DIM = NUM_MFCC * 2; // 特徴量次元（mean + stddev）
    private static final float SAMPLE_RATE = 16000.0f;

    // ---- 状態 ----
    private double[] avgFeature;        // 話者の平均特徴量 [FEATURE_DIM]
    private int enrollCount;
    private int requiredSamples;
    private double threshold;
    private double initialThreshold;
    private double targetThreshold;
    private int totalAccepted;
    private int totalRejected;          // ★リジェクト数（ステータス表示用）

    private final List<double[]> enrolledFeatures = new ArrayList<>();

    public SpeakerProfile(int requiredSamples, double initialThreshold, double targetThreshold) {
        this.requiredSamples = Math.max(1, requiredSamples);
        this.initialThreshold = initialThreshold;
        this.targetThreshold = targetThreshold;
        this.threshold = initialThreshold;
        this.enrollCount = 0;
        this.totalAccepted = 0;
        this.totalRejected = 0;
        this.avgFeature = null;
    }

    public synchronized void updateSettings(int requiredSamples, double initialThreshold, double targetThreshold) {
        this.requiredSamples = Math.max(1, requiredSamples);
        this.initialThreshold = initialThreshold;
        this.targetThreshold = targetThreshold;
        if (totalAccepted > 0) {
            double progress = Math.min(1.0, totalAccepted / 20.0);
            threshold = initialThreshold + (targetThreshold - initialThreshold) * progress;
        } else {
            threshold = initialThreshold;
        }
    }

    /** エンロール用: VADで発話と判定されたPCMチャンクを登録 */
    public synchronized void enrollSample(byte[] pcm16le) {
        if (pcm16le == null || pcm16le.length < FFT_SIZE * 2) return;

        double[] feat = computeFeatures(pcm16le);
        if (feat == null) return;

        enrolledFeatures.add(feat);
        enrollCount++;

        // 平均特徴量を再計算
        avgFeature = new double[FEATURE_DIM];
        for (double[] f : enrolledFeatures) {
            for (int i = 0; i < FEATURE_DIM; i++) {
                avgFeature[i] += f[i];
            }
        }
        for (int i = 0; i < FEATURE_DIM; i++) {
            avgFeature[i] /= enrolledFeatures.size();
        }

        Config.logDebug("★Speaker enroll: sample #" + enrollCount + "/" + requiredSamples);
    }

    public boolean isReady() {
        return enrollCount >= requiredSamples && avgFeature != null;
    }

    public boolean isEnrolling() {
        return enrollCount > 0 && enrollCount < requiredSamples;
    }

    public int getEnrollCount() { return enrollCount; }
    public int getRequiredSamples() { return requiredSamples; }
    public double getThreshold() { return threshold; }
    public int getTotalAccepted() { return totalAccepted; }
    public int getTotalRejected() { return totalRejected; }

    /**
     * 話者判定
     * @return true = 話者一致（またはプロファイル未完成）
     */
    public synchronized boolean isMatchingSpeaker(byte[] pcm16le) {
        if (!isReady()) return true;
        if (pcm16le == null || pcm16le.length < FFT_SIZE * 2) return false;

        double sim = similarity(pcm16le);
        boolean match = sim >= threshold;

        if (match) {
            // totalAcceptedはrefineSampleで増やすのでここでは増やさない
        } else {
            totalRejected++;
        }

        Config.logDebug(String.format("★Speaker: sim=%.3f thr=%.3f %s (ok=%d ng=%d)",
                sim, threshold, match ? "PASS" : "REJECT", totalAccepted, totalRejected));
        return match;
    }

    /** 類似度を返す（0.0〜1.0） */
    public synchronized double similarity(byte[] pcm16le) {
        if (avgFeature == null) return 1.0;
        double[] feat = computeFeatures(pcm16le);
        if (feat == null) return 0.0;
        return cosineSimilarity(avgFeature, feat);
    }

    /**
     * 運用中のプロファイル更新
     * @return true = 保存推奨タイミング（10回ごと）
     */
    public synchronized boolean refineSample(byte[] pcm16le) {
        if (!isReady()) return false;
        if (pcm16le == null || pcm16le.length < FFT_SIZE * 2) return false;

        double[] feat = computeFeatures(pcm16le);
        if (feat == null) return false;

        double alpha = Math.max(0.02, 0.15 / (1.0 + totalAccepted * 0.05));
        for (int i = 0; i < FEATURE_DIM; i++) {
            avgFeature[i] = avgFeature[i] * (1.0 - alpha) + feat[i] * alpha;
        }

        totalAccepted++;

        double progress = Math.min(1.0, totalAccepted / 20.0);
        threshold = initialThreshold + (targetThreshold - initialThreshold) * progress;

        return (totalAccepted % 10 == 0);
    }

    public synchronized void reset() {
        avgFeature = null;
        enrollCount = 0;
        totalAccepted = 0;
        totalRejected = 0;
        threshold = initialThreshold;
        enrolledFeatures.clear();
    }

    // ===================================================================
    //  MFCC特徴量算出（核心部分）
    // ===================================================================

    /**
     * PCM16LE → FFT → メル帯域 → DCT → MFCC
     * @return [13 MFCC平均, 13 MFCC標準偏差] = 26次元、データ不足時はnull
     */
    private double[] computeFeatures(byte[] pcm16le) {
        int numSamples = pcm16le.length / 2;
        if (numSamples < FFT_SIZE) return null;

        // PCM16LE → double[]
        double[] samples = new double[numSamples];
        for (int i = 0; i < numSamples; i++) {
            int lo = pcm16le[i * 2] & 0xFF;
            int hi = pcm16le[i * 2 + 1];
            samples[i] = (short) (lo | (hi << 8));
        }

        // フレームごとにMFCCを算出して蓄積
        List<double[]> frameMfccs = new ArrayList<>();

        for (int offset = 0; offset + FFT_SIZE <= numSamples; offset += HOP_SIZE) {
            double[] real = new double[FFT_SIZE];
            double[] imag = new double[FFT_SIZE];

            // Hann窓適用
            for (int i = 0; i < FFT_SIZE; i++) {
                double hann = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
                real[i] = samples[offset + i] * hann;
            }

            fft(real, imag);

            // パワースペクトル
            double[] power = new double[FFT_SIZE / 2];
            for (int i = 0; i < FFT_SIZE / 2; i++) {
                power[i] = real[i] * real[i] + imag[i] * imag[i];
            }

            // メル帯域エネルギー
            double[] melEnergy = computeMelBands(power);

            // log → DCT → MFCC
            double[] logMel = new double[MEL_BANDS];
            for (int i = 0; i < MEL_BANDS; i++) {
                logMel[i] = Math.log(Math.max(1e-10, melEnergy[i]));
            }
            double[] mfcc = applyDCT(logMel, NUM_MFCC);

            frameMfccs.add(mfcc);
        }

        if (frameMfccs.size() < 3) return null; // 最低3フレーム必要

        // MFCC平均と標準偏差を算出
        double[] mean = new double[NUM_MFCC];
        double[] variance = new double[NUM_MFCC];
        int n = frameMfccs.size();

        for (double[] mfcc : frameMfccs) {
            for (int i = 0; i < NUM_MFCC; i++) {
                mean[i] += mfcc[i];
            }
        }
        for (int i = 0; i < NUM_MFCC; i++) {
            mean[i] /= n;
        }

        for (double[] mfcc : frameMfccs) {
            for (int i = 0; i < NUM_MFCC; i++) {
                double diff = mfcc[i] - mean[i];
                variance[i] += diff * diff;
            }
        }

        // 特徴ベクトル: [mean_mfcc(13), stddev_mfcc(13)]
        double[] feature = new double[FEATURE_DIM];
        for (int i = 0; i < NUM_MFCC; i++) {
            feature[i] = mean[i];
            feature[NUM_MFCC + i] = Math.sqrt(variance[i] / n);
        }

        // L2正規化
        double norm = 0;
        for (double v : feature) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 1e-9) {
            for (int i = 0; i < feature.length; i++) feature[i] /= norm;
        }

        return feature;
    }

    /** パワースペクトルからメル帯域エネルギーを算出（三角フィルタ） */
    private double[] computeMelBands(double[] power) {
        double[] bands = new double[MEL_BANDS];

        int nFft = FFT_SIZE;
        double nyquist = SAMPLE_RATE / 2.0;

        double melMin = hz2mel(100.0);
        double melMax = hz2mel(nyquist);

        // メル境界（MEL_BANDS + 2）
        double[] melPoints = new double[MEL_BANDS + 2];
        for (int i = 0; i < melPoints.length; i++) {
            melPoints[i] = melMin + (melMax - melMin) * i / (MEL_BANDS + 1);
        }

        // Hz → FFT bin
        int[] bin = new int[melPoints.length];
        for (int i = 0; i < melPoints.length; i++) {
            double hz = mel2hz(melPoints[i]);
            bin[i] = (int) Math.floor(hz / nyquist * (nFft / 2));
            bin[i] = Math.max(0, Math.min(bin[i], power.length - 1));
        }

        // 三角フィルタ適用
        for (int m = 0; m < MEL_BANDS; m++) {
            int left = bin[m];
            int center = bin[m + 1];
            int right = bin[m + 2];

            double sum = 0.0;

            for (int i = left; i < center; i++) {
                double w = (i - left) / (double) (center - left + 1e-9);
                sum += power[i] * w;
            }
            for (int i = center; i < right; i++) {
                double w = (right - i) / (double) (right - center + 1e-9);
                sum += power[i] * w;
            }

            bands[m] = sum;
        }

        return bands;
    }


    /**
     * DCT-II（離散コサイン変換）でMFCCを算出
     * @param logMel log メルスペクトル [MEL_BANDS]
     * @param numCoeff 取得するMFCC係数数
     * @return MFCC [numCoeff]
     */
    private static double[] applyDCT(double[] logMel, int numCoeff) {
        int N = logMel.length;
        double[] mfcc = new double[numCoeff];
        double factor = Math.PI / N;
        for (int k = 0; k < numCoeff; k++) {
            double sum = 0;
            for (int n = 0; n < N; n++) {
                sum += logMel[n] * Math.cos(factor * (n + 0.5) * k);
            }
            mfcc[k] = sum;
        }
        return mfcc;
    }

    // ===================================================================
    //  FFT (Cooley-Tukey radix-2)
    // ===================================================================

    private static void fft(double[] real, double[] imag) {
        int n = real.length;
        if (n == 0) return;

        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) {
            int j = Integer.reverse(i) >>> (32 - bits);
            if (j > i) {
                double tmp = real[i]; real[i] = real[j]; real[j] = tmp;
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp;
            }
        }

        for (int size = 2; size <= n; size *= 2) {
            int half = size / 2;
            double angle = -2.0 * Math.PI / size;
            double wR = Math.cos(angle);
            double wI = Math.sin(angle);

            for (int start = 0; start < n; start += size) {
                double curR = 1.0, curI = 0.0;
                for (int k = 0; k < half; k++) {
                    int a = start + k;
                    int b = a + half;
                    double tR = curR * real[b] - curI * imag[b];
                    double tI = curR * imag[b] + curI * real[b];
                    real[b] = real[a] - tR;
                    imag[b] = imag[a] - tI;
                    real[a] += tR;
                    imag[a] += tI;
                    double newR = curR * wR - curI * wI;
                    curI = curR * wI + curI * wR;
                    curR = newR;
                }
            }
        }
    }

    // ===================================================================
    //  ユーティリティ
    // ===================================================================

    private static double hz2mel(double hz) {
        return 2595.0 * Math.log10(1.0 + hz / 700.0);
    }

    private static double mel2hz(double mel) {
        return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0);
    }

    private static double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom > 1e-9 ? dot / denom : 0;
    }

    // ===================================================================
    //  ファイル保存/復元
    // ===================================================================

    public synchronized void saveToFile(File file) {
        if (avgFeature == null) return;
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file))) {
            dos.writeInt(FEATURE_DIM);       // ★v2の20とは違う値→自動再エンロール
            dos.writeInt(enrollCount);
            dos.writeInt(totalAccepted);
            dos.writeInt(totalRejected);
            dos.writeDouble(threshold);
            dos.writeDouble(initialThreshold);
            dos.writeDouble(targetThreshold);
            for (double v : avgFeature) {
                dos.writeDouble(v);
            }
            Config.logDebug("★Speaker profile saved: accepted=" + totalAccepted
                    + " rejected=" + totalRejected
                    + " threshold=" + String.format("%.3f", threshold));
        } catch (Exception e) {
            Config.logDebug("★Speaker profile save failed: " + e.getMessage());
        }
    }

    public synchronized boolean loadFromFile(File file) {
        if (!file.exists()) return false;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            int dim = dis.readInt();
            if (dim != FEATURE_DIM) {
                Config.log("★Speaker profile version mismatch (dim=" + dim
                        + " expected=" + FEATURE_DIM + ") → re-enroll needed");
                return false; // v2(20dim)プロファイルは読み込まない→再エンロール
            }
            enrollCount = dis.readInt();
            totalAccepted = dis.readInt();
            totalRejected = dis.readInt();
            threshold = dis.readDouble();
            initialThreshold = dis.readDouble();
            targetThreshold = dis.readDouble();
            avgFeature = new double[FEATURE_DIM];
            for (int i = 0; i < FEATURE_DIM; i++) {
                avgFeature[i] = dis.readDouble();
            }
            Config.logDebug("★Speaker profile loaded: enroll=" + enrollCount
                    + " accepted=" + totalAccepted
                    + " threshold=" + String.format("%.3f", threshold));
            return true;
        } catch (Exception e) {
            Config.logDebug("★Speaker profile load failed: " + e.getMessage());
            return false;
        }
    }
}
