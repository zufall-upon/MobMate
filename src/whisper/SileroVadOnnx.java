package whisper;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class SileroVadOnnx implements AutoCloseable {
    static final int SAMPLE_RATE = 16000;
    static final int FRAME_SAMPLES = 512;

    private final Object lock = new Object();
    private OrtEnvironment env;
    private OrtSession session;
    private String inputName;
    private String stateName;
    private String sampleRateName;
    private String outputName;
    private String stateOutputName;
    private float[][][] state = new float[2][1][128];
    private float[] pending = new float[0];
    private boolean loadAttempted = false;
    private boolean available = false;

    double score(byte[] pcm16le, int bytes, int sampleRateHz) {
        if (pcm16le == null || bytes < 2 || sampleRateHz != SAMPLE_RATE) return Double.NaN;
        synchronized (lock) {
            if (!loadIfNeeded()) return Double.NaN;
            int sampleCount = Math.max(0, Math.min(bytes, pcm16le.length) / 2);
            if (sampleCount <= 0) return Double.NaN;
            float[] samples = new float[pending.length + sampleCount];
            System.arraycopy(pending, 0, samples, 0, pending.length);
            for (int i = 0; i < sampleCount; i++) {
                int off = i * 2;
                short s = (short) (((pcm16le[off + 1] & 0xFF) << 8) | (pcm16le[off] & 0xFF));
                samples[pending.length + i] = Math.max(-1.0f, Math.min(1.0f, s / 32768.0f));
            }

            double max = Double.NaN;
            int pos = 0;
            while (pos + FRAME_SAMPLES <= samples.length) {
                float[] frame = new float[FRAME_SAMPLES];
                System.arraycopy(samples, pos, frame, 0, FRAME_SAMPLES);
                double p = runFrame(frame);
                if (!Double.isNaN(p)) max = Double.isNaN(max) ? p : Math.max(max, p);
                pos += FRAME_SAMPLES;
            }
            int remain = samples.length - pos;
            pending = new float[Math.max(0, remain)];
            if (remain > 0) System.arraycopy(samples, pos, pending, 0, remain);
            return max;
        }
    }

    void resetStream() {
        synchronized (lock) {
            state = new float[2][1][128];
            pending = new float[0];
        }
    }

    boolean isAvailable() {
        synchronized (lock) {
            return loadIfNeeded();
        }
    }

    private boolean loadIfNeeded() {
        if (available) return true;
        if (loadAttempted) return false;
        loadAttempted = true;
        File model = resolveModelFile();
        if (model == null || !model.isFile()) {
            Config.log("[SileroVAD] model not found; disabled");
            return false;
        }
        OrtSession.SessionOptions opts = null;
        try {
            env = OrtEnvironment.getEnvironment();
            opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.max(1, Config.getInt("talk.vad.silero.threads", 1)));
            session = env.createSession(model.getAbsolutePath(), opts);
            resolveIoNames();
            available = inputName != null && stateName != null && sampleRateName != null
                    && outputName != null && stateOutputName != null;
            Config.log("[SileroVAD] loaded: " + model.getAbsolutePath()
                    + " inputs=" + session.getInputInfo().keySet()
                    + " outputs=" + session.getOutputInfo().keySet()
                    + " available=" + available);
            if (!available) close();
            return available;
        } catch (Throwable t) {
            Config.logError("[SileroVAD] load failed", t);
            close();
            return false;
        } finally {
            if (opts != null) {
                try { opts.close(); } catch (Exception ignore) {}
            }
        }
    }

    private File resolveModelFile() {
        String configured = Config.getString("talk.vad.silero.model", "").trim();
        if (!configured.isEmpty()) {
            File f = new File(configured);
            if (f.isFile()) return f;
        }
        File cwdModel = new File("models/silero_vad/silero_vad_int8.onnx");
        if (cwdModel.isFile()) return cwdModel;
        File devModel = new File("app/models/silero_vad/silero_vad_int8.onnx");
        if (devModel.isFile()) return devModel;
        return cwdModel;
    }

    private void resolveIoNames() throws OrtException {
        for (String name : session.getInputInfo().keySet()) {
            String n = name.toLowerCase(Locale.ROOT);
            if (inputName == null && n.equals("input")) inputName = name;
            else if (stateName == null && n.contains("state")) stateName = name;
            else if (sampleRateName == null && (n.equals("sr") || n.contains("sample"))) sampleRateName = name;
        }
        for (String name : session.getOutputInfo().keySet()) {
            String n = name.toLowerCase(Locale.ROOT);
            if (outputName == null && (n.equals("output") || n.contains("prob"))) outputName = name;
            else if (stateOutputName == null && n.contains("state")) stateOutputName = name;
        }
        if (inputName == null && !session.getInputInfo().isEmpty()) inputName = session.getInputInfo().keySet().iterator().next();
        if (outputName == null && !session.getOutputInfo().isEmpty()) outputName = session.getOutputInfo().keySet().iterator().next();
    }

    private double runFrame(float[] frame) {
        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, new float[][]{frame});
             OnnxTensor stateTensor = OnnxTensor.createTensor(env, state);
             OnnxTensor srTensor = OnnxTensor.createTensor(env, new long[]{SAMPLE_RATE})) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put(inputName, inputTensor);
            inputs.put(stateName, stateTensor);
            inputs.put(sampleRateName, srTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                double p = extractProbability(result.get(outputName).orElse(null));
                Object nextState = result.get(stateOutputName).orElse(null);
                if (nextState instanceof OnnxTensor tensor && tensor.getValue() instanceof float[][][] s) {
                    state = s;
                }
                return p;
            }
        } catch (Throwable t) {
            Config.logDebug("[SileroVAD] frame failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return Double.NaN;
        }
    }

    private double extractProbability(Object value) throws OrtException {
        if (value instanceof OnnxTensor tensor) value = tensor.getValue();
        if (value instanceof float[][] a && a.length > 0 && a[0].length > 0) return a[0][0];
        if (value instanceof float[] a && a.length > 0) return a[0];
        if (value instanceof Number n) return n.doubleValue();
        return Double.NaN;
    }

    @Override
    public void close() {
        available = false;
        try { if (session != null) session.close(); } catch (Exception ignore) {}
        session = null;
    }
}
