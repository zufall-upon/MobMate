package whisper;

public record TtsProsodyProfile(
        String mood,
        float pitchScale,
        float intonationScale,
        float speedScale,
        float volumeScale,
        float confidence,
        float contourRise,
        float melodyDepth,
        float darkScore,
        float energyScore,
        float[] pitchContour,
        float[] activityContour,
        float[] voicingContour,
        int contourStepMs
) {
    public TtsProsodyProfile {
        pitchContour = (pitchContour == null || pitchContour.length == 0) ? new float[0] : pitchContour.clone();
        activityContour = (activityContour == null || activityContour.length == 0) ? new float[0] : activityContour.clone();
        voicingContour = (voicingContour == null || voicingContour.length == 0) ? new float[0] : voicingContour.clone();
        contourStepMs = Math.max(0, contourStepMs);
    }

    public TtsProsodyProfile(
            String mood,
            float pitchScale,
            float intonationScale,
            float speedScale,
            float volumeScale,
            float confidence
    ) {
        this(mood, pitchScale, intonationScale, speedScale, volumeScale, confidence,
                0.0f, 0.0f, 0.0f, 0.0f, new float[0], new float[0], new float[0], 0);
    }

    public TtsProsodyProfile(
            String mood,
            float pitchScale,
            float intonationScale,
            float speedScale,
            float volumeScale,
            float confidence,
            float contourRise,
            float melodyDepth,
            float darkScore,
            float energyScore
    ) {
        this(mood, pitchScale, intonationScale, speedScale, volumeScale, confidence,
                contourRise, melodyDepth, darkScore, energyScore, new float[0], new float[0], new float[0], 0);
    }

    public static TtsProsodyProfile neutral() {
        return new TtsProsodyProfile("neutral", 0.0f, 1.0f, 1.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 0.0f, new float[0], new float[0], new float[0], 0);
    }

    public boolean isUsable() {
        return confidence >= 0.25f || hasContinuousHint();
    }

    public boolean isConfident() {
        return confidence >= 0.55f;
    }

    public TtsProsodyProfile blendWith(TtsProsodyProfile other) {
        if (other == null) return this;
        if (!this.isUsable()) return other;
        if (!other.isUsable()) return this;

        String mergedMood = (other.confidence > this.confidence + 0.08f) ? other.mood : this.mood;
        float total = Math.max(0.001f, this.confidence + other.confidence);
        float wThis = this.confidence / total;
        float wOther = other.confidence / total;
        return new TtsProsodyProfile(
                mergedMood,
                (this.pitchScale * wThis) + (other.pitchScale * wOther),
                (this.intonationScale * wThis) + (other.intonationScale * wOther),
                (this.speedScale * wThis) + (other.speedScale * wOther),
                (this.volumeScale * wThis) + (other.volumeScale * wOther),
                Math.max(this.confidence, other.confidence),
                (this.contourRise * wThis) + (other.contourRise * wOther),
                (this.melodyDepth * wThis) + (other.melodyDepth * wOther),
                (this.darkScore * wThis) + (other.darkScore * wOther),
                (this.energyScore * wThis) + (other.energyScore * wOther),
                selectContour(this, other),
                selectTimingContour(this.activityContour, other.activityContour),
                selectTimingContour(this.voicingContour, other.voicingContour),
                Math.max(this.contourStepMs, other.contourStepMs)
        );
    }

    public boolean hasContinuousHint() {
        return Math.abs(contourRise) >= 0.08f
                || melodyDepth >= 0.12f
                || darkScore >= 0.12f
                || energyScore >= 0.12f
                || hasPitchContour()
                || hasTimingContour();
    }

    public boolean isDarkLike() {
        return "whisper".equals(mood) || "calm".equals(mood) || darkScore >= 0.38f;
    }

    public boolean isMelodicLike() {
        return "sing_song_lite".equals(mood) || melodyDepth >= 0.45f || hasPitchContour();
    }

    public boolean hasPitchContour() {
        return pitchContour != null && pitchContour.length >= 3;
    }

    public boolean hasTimingContour() {
        return (activityContour != null && activityContour.length >= 3)
                || (voicingContour != null && voicingContour.length >= 3);
    }

    public TtsProsodyProfile asContourDriven() {
        if (!hasPitchContour()) return this;
        float contourConf = Math.max(0.72f, confidence);
        return new TtsProsodyProfile(
                "contour_transfer",
                0.0f,
                1.0f,
                1.0f,
                1.0f,
                contourConf,
                contourRise,
                melodyDepth,
                darkScore,
                energyScore,
                pitchContour,
                activityContour,
                voicingContour,
                contourStepMs
        );
    }

    private static float[] selectContour(TtsProsodyProfile a, TtsProsodyProfile b) {
        boolean aHas = a.hasPitchContour();
        boolean bHas = b.hasPitchContour();
        if (aHas && bHas) {
            float aScore = (a.pitchContour.length * 0.5f) + (a.confidence * 2.0f) + a.melodyDepth;
            float bScore = (b.pitchContour.length * 0.5f) + (b.confidence * 2.0f) + b.melodyDepth;
            return (aScore >= bScore) ? a.pitchContour : b.pitchContour;
        }
        if (aHas) return a.pitchContour;
        if (bHas) return b.pitchContour;
        return new float[0];
    }

    private static float[] selectTimingContour(float[] a, float[] b) {
        boolean aHas = a != null && a.length >= 3;
        boolean bHas = b != null && b.length >= 3;
        if (aHas && bHas) return (a.length >= b.length) ? a : b;
        if (aHas) return a;
        if (bHas) return b;
        return new float[0];
    }
}
