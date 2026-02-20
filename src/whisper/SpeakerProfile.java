package whisper;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 話者照合プロファイル v4
 * v3→v4 変更点:
 *   - c0（エネルギー）除去: c1-c12 の12係数を使用（声道構造のみ）
 *   - デルタMFCC追加: フレーム間差分で発話ダイナミクスを捕捉
 *   - 特徴量: static_mean(12)+static_std(12)+delta_mean(12)+delta_std(12) = 48次元
 *   - L2正規化廃止 → ガウス距離スコアに変更（コサイン類似度では弁別不能だった問題の修正）
 *   - エンロール分散（enrollSpread）でスコアを自動キャリブレーション
 * なぜ効くか:
 *   v3のコサイン類似度は「ベクトルの角度」だけ見る → 異なる話者でも0.85-0.95になり弁別不能。
 *   v4のガウス距離スコアは「ユークリッド距離 / エンロール時の分散」で評価するため、
 *   エンロール時の自分の声のバラツキを基準に「どれだけ離れているか」を正しく測れる。
 */
public class SpeakerProfile {

    // ---- 定数 ----
    private static final int FFT_SIZE = 512;
    private static final int HOP_SIZE = 256;
    private static final int MEL_BANDS = 26;
    private static final int FULL_MFCC = 13;       // 算出するMFCC数（c0-c12）
    private static final int USED_MFCC = 12;        // 使用するMFCC数（c1-c12、c0除去）
    private static final int FEATURE_DIM = USED_MFCC * 4; // 48次元
    private static final float SAMPLE_RATE = 16000.0f;

    // ---- 状態 ----
    private double[] avgFeature;         // 話者の中心特徴量 [FEATURE_DIM]
    private double enrollmentSpread;     // ★エンロール時の平均分散（スコア正規化用）
    private int enrollCount;
    private int requiredSamples;
    private double threshold;
    private double initialThreshold;
    private double targetThreshold;
    private int totalAccepted;
    private int totalRejected;

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
        this.enrollmentSpread = 1.0;
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

        // 中心特徴量を再計算
        avgFeature = new double[FEATURE_DIM];
        for (double[] f : enrolledFeatures) {
            for (int i = 0; i < FEATURE_DIM; i++) {
                avgFeature[i] += f[i];
            }
        }
        for (int i = 0; i < FEATURE_DIM; i++) {
            avgFeature[i] /= enrolledFeatures.size();
        }

        // ★エンロール分散を計算（中心からの平均ユークリッド距離）
        if (enrolledFeatures.size() >= 2) {
            double sumDist = 0;
            for (double[] f : enrolledFeatures) {
                sumDist += euclideanDistance(avgFeature, f);
            }
            enrollmentSpread = sumDist / enrolledFeatures.size();
            if (enrollmentSpread < 2.0) enrollmentSpread = 2.0; // 最小値ガード（MFCC 48次元空間に合わせた値）
        }

        Config.logDebug(String.format("★Speaker enroll: #%d/%d spread=%.3f",
                enrollCount, requiredSamples, enrollmentSpread));
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

        double score = similarity(pcm16le);
        boolean match = score >= threshold;

        if (!match) {
            totalRejected++;
        }

        Config.logDebug(String.format("★Speaker: score=%.3f thr=%.3f spread=%.3f %s (ok=%d ng=%d)",
                score, threshold, enrollmentSpread, match ? "PASS" : "REJECT",
                totalAccepted, totalRejected));
        return match;
    }

    /**
     * ★ガウス距離スコア（0.0〜1.0）
     * dist=0 → 1.0, dist=spread → ≈0.61, dist=2*spread → ≈0.14
     */
    public synchronized double similarity(byte[] pcm16le) {
        if (avgFeature == null) return 1.0;
        double[] feat = computeFeatures(pcm16le);
        if (feat == null) return 0.0;

        double dist = euclideanDistance(avgFeature, feat);
        // ガウスカーネル: exp(-d²/(2σ²))
        double sigma = Math.max(enrollmentSpread * 2.5, 2.0); // ★同話者の自然なバラツキ幅に合わせて広げる
        return Math.exp(-(dist * dist) / (2.0 * sigma * sigma));
    }

    /**
     * 運用中のプロファイル更新（EMA）
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
        enrollmentSpread = 1.0;
        enrolledFeatures.clear();
    }

    // ===================================================================
    //  MFCC + Delta 特徴量算出（核心部分）
    // ===================================================================

    /**
     * PCM16LE → MFCC(c1-c12) + Delta MFCC → 48次元特徴量
     * 1. FFT → メル帯域 → log → DCT → MFCC c0-c12
     * 2. c0を除去（エネルギー成分は話者非依存）
     * 3. フレーム間差分（delta）を算出
     * 4. static/delta それぞれの mean + stddev を連結
     * 5. L2正規化しない（ガウス距離スコアで評価するため）
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

        // フレームごとにMFCC算出（c0-c12）
        List<double[]> allMfcc = new ArrayList<>();

        for (int offset = 0; offset + FFT_SIZE <= numSamples; offset += HOP_SIZE) {
            double[] real = new double[FFT_SIZE];
            double[] imag = new double[FFT_SIZE];

            for (int i = 0; i < FFT_SIZE; i++) {
                double hann = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
                real[i] = samples[offset + i] * hann;
            }

            fft(real, imag);

            double[] power = new double[FFT_SIZE / 2];
            for (int i = 0; i < FFT_SIZE / 2; i++) {
                power[i] = real[i] * real[i] + imag[i] * imag[i];
            }

            double[] melEnergy = computeMelBands(power);

            double[] logMel = new double[MEL_BANDS];
            for (int i = 0; i < MEL_BANDS; i++) {
                logMel[i] = Math.log(Math.max(1e-10, melEnergy[i]));
            }
            double[] fullMfcc = applyDCT(logMel, FULL_MFCC);

            // ★c0除去: c1-c12を取り出す
            double[] usedMfcc = new double[USED_MFCC];
            System.arraycopy(fullMfcc, 1, usedMfcc, 0, USED_MFCC);

            allMfcc.add(usedMfcc);
        }

        int nFrames = allMfcc.size();
        if (nFrames < 5) return null; // ★最低5フレーム（v3の3から引き上げ）

        // ★デルタMFCC算出（フレーム間差分）
        List<double[]> allDelta = new ArrayList<>();
        for (int t = 1; t < nFrames; t++) {
            double[] prev = allMfcc.get(t - 1);
            double[] curr = allMfcc.get(t);
            double[] delta = new double[USED_MFCC];
            for (int i = 0; i < USED_MFCC; i++) {
                delta[i] = curr[i] - prev[i];
            }
            allDelta.add(delta);
        }

        // static MFCC の mean + stddev
        double[] sMean = computeMean(allMfcc);
        double[] sStd = computeStddev(allMfcc, sMean);

        // delta MFCC の mean + stddev
        double[] dMean = computeMean(allDelta);
        double[] dStd = computeStddev(allDelta, dMean);

        // ★連結: [sMean(12), sStd(12), dMean(12), dStd(12)] = 48次元
        // ★L2正規化しない（ガウス距離で評価）
        double[] feature = new double[FEATURE_DIM];
        System.arraycopy(sMean, 0, feature, 0, USED_MFCC);
        System.arraycopy(sStd, 0, feature, USED_MFCC, USED_MFCC);
        System.arraycopy(dMean, 0, feature, USED_MFCC * 2, USED_MFCC);
        System.arraycopy(dStd, 0, feature, USED_MFCC * 3, USED_MFCC);

        return feature;
    }

    private double[] computeMean(List<double[]> frames) {
        int dim = frames.getFirst().length;
        double[] mean = new double[dim];
        for (double[] f : frames) {
            for (int i = 0; i < dim; i++) mean[i] += f[i];
        }
        for (int i = 0; i < dim; i++) mean[i] /= frames.size();
        return mean;
    }

    private double[] computeStddev(List<double[]> frames, double[] mean) {
        int dim = mean.length;
        double[] var = new double[dim];
        for (double[] f : frames) {
            for (int i = 0; i < dim; i++) {
                double d = f[i] - mean[i];
                var[i] += d * d;
            }
        }
        double[] std = new double[dim];
        for (int i = 0; i < dim; i++) {
            std[i] = Math.sqrt(var[i] / frames.size());
        }
        return std;
    }

    // ===================================================================
    //  メルフィルタバンク（三角フィルタ）
    // ===================================================================

    private double[] computeMelBands(double[] power) {
        double[] bands = new double[MEL_BANDS];
        double nyquist = SAMPLE_RATE / 2.0;
        double melMin = hz2mel(100.0);
        double melMax = hz2mel(nyquist);

        double[] melPoints = new double[MEL_BANDS + 2];
        for (int i = 0; i < melPoints.length; i++) {
            melPoints[i] = melMin + (melMax - melMin) * i / (MEL_BANDS + 1);
        }

        int[] bin = new int[melPoints.length];
        for (int i = 0; i < melPoints.length; i++) {
            double hz = mel2hz(melPoints[i]);
            bin[i] = (int) Math.floor(hz / nyquist * ((double) FFT_SIZE / 2));
            bin[i] = Math.max(0, Math.min(bin[i], power.length - 1));
        }

        for (int m = 0; m < MEL_BANDS; m++) {
            int left = bin[m], center = bin[m + 1], right = bin[m + 2];
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

    // ===================================================================
    //  DCT-II
    // ===================================================================

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

    /** ユークリッド距離 */
    private static double euclideanDistance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    // ===================================================================
    //  ファイル保存/復元
    // ===================================================================

    public synchronized void saveToFile(File file) {
        if (avgFeature == null) return;
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file))) {
            dos.writeInt(FEATURE_DIM);
            dos.writeInt(enrollCount);
            dos.writeInt(totalAccepted);
            dos.writeInt(totalRejected);
            dos.writeDouble(threshold);
            dos.writeDouble(initialThreshold);
            dos.writeDouble(targetThreshold);
            dos.writeDouble(enrollmentSpread);   // ★v4追加
            for (double v : avgFeature) {
                dos.writeDouble(v);
            }
            Config.logDebug(String.format("★Speaker saved: ok=%d ng=%d thr=%.3f spread=%.3f",
                    totalAccepted, totalRejected, threshold, enrollmentSpread));
        } catch (Exception e) {
            Config.logDebug("★Speaker save failed: " + e.getMessage());
        }
    }

    public synchronized boolean loadFromFile(File file) {
        if (!file.exists()) return false;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            int dim = dis.readInt();
            if (dim != FEATURE_DIM) {
                Config.log("★Speaker profile version mismatch (dim=" + dim
                        + " expected=" + FEATURE_DIM + ") → re-enroll needed");
                return false;
            }
            enrollCount = dis.readInt();
            totalAccepted = dis.readInt();
            totalRejected = dis.readInt();
            threshold = dis.readDouble();
            initialThreshold = dis.readDouble();
            targetThreshold = dis.readDouble();
            enrollmentSpread = dis.readDouble();  // ★v4追加
            avgFeature = new double[FEATURE_DIM];
            for (int i = 0; i < FEATURE_DIM; i++) {
                avgFeature[i] = dis.readDouble();
            }
            Config.logDebug(String.format("★Speaker loaded: enroll=%d ok=%d thr=%.3f spread=%.3f",
                    enrollCount, totalAccepted, threshold, enrollmentSpread));
            return true;
        } catch (Exception e) {
            Config.logDebug("★Speaker load failed: " + e.getMessage());
            return false;
        }
    }
}
