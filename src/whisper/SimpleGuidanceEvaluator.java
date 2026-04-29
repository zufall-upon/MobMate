package whisper;

import java.util.ArrayList;
import java.util.List;

final class SimpleGuidanceEvaluator {

    enum Severity {
        CAUTION,
        GUIDE,
        TIP
    }

    record Context(
            String uiLanguage,
            boolean calibrationComplete,
            boolean recording,
            boolean transcribing,
            boolean speakerSampling,
            int speakerSampleCount,
            int speakerSampleRequired,
            String speakerSamplePrompt,
            int inputLevel,
            boolean pendingMode,
            int pendingRemainingSec,
            int pendingQueueCount,
            boolean inputConfigured,
            boolean outputConfigured,
            boolean translateEnabled,
            String translateTarget,
            boolean radioConfigured,
            boolean voiceVoxRecommended,
            String featureAnnouncement
    ) {}

    record GuidanceMessage(
            String key,
            Severity severity,
            String text,
            boolean animated
    ) {}

    record Result(
            GuidanceMessage immediate,
            List<GuidanceMessage> passive
    ) {}

    Result evaluate(Context ctx) {
        if (!ctx.calibrationComplete()) {
            return new Result(msg("calibrating", Severity.GUIDE, tr(ctx, "calibrating"), true), List.of());
        }
        if (!ctx.inputConfigured()) {
            return new Result(msg("input_missing", Severity.CAUTION, tr(ctx, "input_missing"), false), List.of());
        }
        if (!ctx.outputConfigured()) {
            return new Result(msg("output_missing", Severity.CAUTION, tr(ctx, "output_missing"), false), List.of());
        }
        if (ctx.speakerSampling()) {
            return new Result(msg("speaker_sampling", Severity.GUIDE, tr(ctx, "speaker_sampling"), false), List.of());
        }
        if (ctx.transcribing()) {
            return new Result(msg("transcribing", Severity.GUIDE, tr(ctx, "transcribing"), true), List.of());
        }
        if (ctx.recording() && ctx.inputLevel() <= 8) {
            return new Result(msg("input_low", Severity.CAUTION, tr(ctx, "input_low"), false), List.of());
        }
        if (ctx.pendingQueueCount() > 0 || ctx.pendingRemainingSec() > 0) {
            return new Result(msg("pending_active", Severity.GUIDE, tr(ctx, "pending_active"), true), List.of());
        }
        if (!ctx.recording()) {
            return new Result(msg("not_recording", Severity.GUIDE, tr(ctx, "not_recording"), false), buildPassive(ctx));
        }
        return new Result(null, buildPassive(ctx));
    }

    private List<GuidanceMessage> buildPassive(Context ctx) {
        List<GuidanceMessage> list = new ArrayList<>();
        if (ctx.voiceVoxRecommended()) {
            list.add(msg("voicevox_recommend", Severity.TIP, tr(ctx, "voicevox_recommend"), false));
        }
        if (ctx.translateEnabled()) {
            list.add(msg("translate_on", Severity.TIP, tr(ctx, "translate_on"), false));
        } else {
            list.add(msg("translate_off", Severity.TIP, tr(ctx, "translate_off"), false));
        }
        if (!ctx.radioConfigured()) {
            list.add(msg("radio_unused", Severity.TIP, tr(ctx, "radio_unused"), false));
        }
        if (ctx.featureAnnouncement() != null && !ctx.featureAnnouncement().isBlank()) {
            list.add(msg("feature_announcement", Severity.TIP, ctx.featureAnnouncement().trim(), false));
        }
        list.add(msg("ready", Severity.TIP, tr(ctx, "ready"), false));
        return list;
    }

    private GuidanceMessage msg(String key, Severity severity, String text, boolean animated) {
        return new GuidanceMessage(key, severity, text, animated);
    }

    private String tr(Context ctx, String key) {
        String resourceKey = "simple.guidance." + key;
        if ("speaker_sampling".equals(key)
                && ctx.speakerSampleRequired() > 0
                && ctx.speakerSamplePrompt() != null
                && !ctx.speakerSamplePrompt().isBlank()) {
            return UiText.t("simple.guidance.speaker_sampling_prompt")
                    .formatted(ctx.speakerSamplePrompt(), Math.max(0, ctx.speakerSampleCount()), ctx.speakerSampleRequired());
        }
        if ("speaker_sampling".equals(key) && ctx.speakerSampleRequired() > 0) {
            return UiText.t("simple.guidance.speaker_sampling_progress")
                    .formatted(Math.max(0, ctx.speakerSampleCount()), ctx.speakerSampleRequired());
        }
        return UiText.t(resourceKey);
    }
}
