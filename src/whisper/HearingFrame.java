package whisper;

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.prefs.Preferences;
import java.util.Base64;
import java.awt.GraphicsEnvironment;
import java.awt.GraphicsDevice;
import java.awt.GraphicsConfiguration;
import java.awt.DisplayMode;
import java.awt.font.TextAttribute;

public class HearingFrame extends JFrame {

    private final Preferences prefs;
    private final MobMateWhisp host;

    private JToggleButton toggle;
    private JComboBox<String> outputCombo;
    private JComboBox<String> langCombo;
    private JComboBox<String> translateTargetCombo;

    private GainMeter meter;
    private JButton prefButton;
    private final JPopupMenu prefMenu = new JPopupMenu();
    private JDialog settingsDialog;
    private JLabel hearingMoonshineAutoHintLabel;
    private JLabel overlayLangStatusLabel;

    private volatile boolean running = false;
    private volatile Thread worker;
    private volatile TargetDataLine line;

    // === waveform / overlay / transcribe ===
    private HearingOverlayWindow overlay;
    private final java.io.ByteArrayOutputStream pcmAcc = new java.io.ByteArrayOutputStream(16000 * 2 * 2);
    private volatile boolean transcribing = false;

    private volatile boolean ignoreToggleEvent = false;

    // 擬似ループバック選択用（Java標準でやれる範囲の現実案）
    private static final String LOOPBACK_TOKEN = "[System Output (Loopback)]";
    private static final String[] LOOPBACK_HINTS = new String[] {
            "stereo mix", "ステレオ", "what u hear", "loopback", "monitor", "モニター", "録音ミキサー"
    };
    // --- WASAPI loopback helper process ---
    private volatile Process loopProc;
    private volatile Thread loopProcThread;
    private volatile boolean intentionalStop = false;
    // WASAPI ps1 process guard
    private final Object loopLock = new Object();
    private volatile Thread loopErrThread;
    private volatile boolean ignoreOutputEvent = false;
    // ★WASAPI helper クラッシュ→再起動ループ抑止
    private volatile int wasapiCrashCount = 0;
    private volatile long wasapiFirstCrashMs = 0;
    private static final int WASAPI_MAX_RETRIES = 5;
    private static final long WASAPI_CRASH_WINDOW_MS = 15_000; // 15秒

    // PCMデバッグ用（1秒に1回だけログ）
    private Thread loopProcErrThread;
    private static final int HEARING_W = 380;
    private static final int HEARING_METER_W = 136;
    private static final int METER_H = 26;
    private static final long SHORT_REPEAT_SUPPRESS_MS = 2500L;
    private static final int SHORT_REPEAT_MAX_CP = 8;
    private static final int DEFAULT_TRANSLATED_KEEP_MS = 12000;
    private static final int DEFAULT_CHUNK_MERGE_MS = 2000;
    private static final int AUTO_DETECT_STABLE_LETTER_CP = 14;
    private static final int AUTO_DETECT_PROBE_MAX_CP = 96;
    private static final long AUTO_DETECT_RESET_MIN_GAP_MS = 3500L;
    private static final long AUTO_DETECT_RESET_MAX_GAP_MS = 6000L;
    private static final int AUTO_DETECT_VOTE_WINDOW = 5;
    private static final int AUTO_DETECT_INITIAL_VOTE_THRESHOLD = 2;
    private static final int AUTO_DETECT_SWITCH_VOTE_THRESHOLD = 3;
    private static final int AUTO_DETECT_LONG_MERGE_INITIAL_VOTE_THRESHOLD = 2;
    private static final int AUTO_DETECT_LONG_MERGE_SWITCH_VOTE_THRESHOLD = 3;
    private static final long AUTO_DETECT_LOCK_HOLD_MS = 5000L;
    private static final long AUTO_DETECT_SWITCH_COOLDOWN_MS = 2500L;
    private static final long AUTO_DETECT_MAX_STABILIZATION_WAIT_MS = 2500L;
    private static final int AUTO_DETECT_LONG_MERGE_PROBE_THRESHOLD_REDUCTION = 2;
    private static final int AUTO_DETECT_LONG_MERGE_PROBE_FORCE_EXTRA_CP = 6;
    private static final int AUTO_DETECT_BACKFILL_LIMIT = 3;
    private static final long AUTO_DETECT_BACKFILL_MAX_AGE_MS = 10_000L;
    private static final int CARD_FADE_IN_MS = 220;
    private static final int TRANSLATED_FADE_MS = 1800;
    private static final long DISPLAY_COLLAPSE_GAP_MS = 2000L;
    private static final int DISPLAY_COLLAPSE_MAX_CP = 320;
    private static final long DISPLAY_ACTIVE_TRANSLATION_HOLD_MS = 14000L;
    private static final long DISPLAY_ACTIVE_TRANSLATION_SOURCE_GAP_MS = 16000L;
    private static final long DISPLAY_STICKY_CARRY_MS = 2800L;
    private static final int RECENT_TRANSLATED_DISPLAY_BUFFER = 12;
    private static final int MERGE_FORCE_FLUSH_CODEPOINTS = 520;
    private static final int MERGE_FORCE_FLUSH_MIN_CHUNKS = 2;
    private static final int MERGE_FORCE_FLUSH_HARD_CODEPOINTS = 760;
    private static final int MERGE_FORCE_FLUSH_LOOKBACK_CODEPOINTS = 128;
    private static final float OVERLAY_TEXT_LINE_HEIGHT = 1.10f;
    private static final long HEARING_MAIN_BUSY_BYPASS_MS = 8000L;
    private static final String OVERLAY_POS_BOTTOM_LEFT = "bottom_left";
    private static final String OVERLAY_POS_BOTTOM_RIGHT = "bottom_right";
    private static final String OVERLAY_POS_TOP_CENTER = "top_center";
    private static final String OVERLAY_POS_CUSTOM = "custom";
    private static final String OVERLAY_FLOW_VERTICAL_UP = "vertical_up";
    private static final String OVERLAY_FLOW_VERTICAL_DOWN = "vertical_down";
    private static final String OVERLAY_FLOW_HORIZONTAL_LEFT = "horizontal_left";
    private static final String OVERLAY_FLOW_HORIZONTAL_RIGHT = "horizontal_right";
    private final java.util.LinkedHashMap<String, Long> recentShortCaptionMs = new java.util.LinkedHashMap<>();
    private final Timer translatedCaptionCleanupTimer;
    private final java.util.ArrayList<PendingMergeChunk> pendingMergeChunks = new java.util.ArrayList<>();
    private final Timer chunkMergeTimer;
    private final AudioPrefilter.State hearingPrefilterState = new AudioPrefilter.State();
    private final Object hearingVoiceGateLock = new Object();
    private static final double HEARING_NOISE_FLOOR_MAX_RMS = 280.0;
    private static final double HEARING_NOISE_FLOOR_UPTRACK_RATIO = 1.18;
    private static final int HEARING_VOICE_HOLD_MAX_CHUNKS = 2;
    private double hearingNoiseFloorRms = 120.0;
    private int hearingVoiceHoldChunks = 0;
    private int hearingGateRejectStreak = 0;
    private long hearingMainBusySinceMs = 0L;
    private int hearingMainBusySkipCount = 0;
    private long hearingMainBusyBypassLogMs = 0L;
    private long pendingMergeStartedAtMs = 0L;
    private final StringBuilder autoDetectProbeBuffer = new StringBuilder();
    private final java.util.ArrayDeque<AutoDetectVote> autoDetectVotes = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<PendingAutoDetectTranslation> pendingAutoDetectTranslations = new java.util.ArrayDeque<>();
    private String stableAutoDetectLang = "";
    private long stableAutoDetectLockedAtMs = 0L;
    private long autoDetectLastSwitchAtMs = 0L;
    private long lastAutoDetectInputMs = 0L;
    private boolean autoDetectBackfillRunning = false;
    private long autoDetectStateVersion = 0L;
    // Moonshine can end almost every chunk with punctuation, so we track consecutive
    // boundaries and only force-flush once we have real evidence of a split sentence.
    private volatile int consecutiveBoundaryChunks = 0;

    public HearingFrame(Preferences prefs, Image icon, MobMateWhisp host) {
        super();
        this.prefs = prefs;
        this.host = host;

        if (icon != null) setIconImage(icon);
        setResizable(false);

        translatedCaptionCleanupTimer = new Timer(1000, e -> cleanupExpiredTranslatedCaptions());
        translatedCaptionCleanupTimer.setRepeats(true);
        translatedCaptionCleanupTimer.setCoalesce(true);

        chunkMergeTimer = new Timer(getChunkMergeMs(), e -> flushMergeBuffer());
        chunkMergeTimer.setRepeats(false);
        chunkMergeTimer.setCoalesce(true);

        updateTitle();
        buildUi();
        restoreBounds();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                persistVisibleState(false);
                cleanup();  // ★変更: 処理を共通メソッドに
            }
        });
    }
    private void persistVisibleState(boolean visible) {
        try {
            prefs.putBoolean("ui.hearing.visible", visible);
            prefs.flush();
        } catch (Exception ignore) {}
    }
    public void cleanup() {
        stopMonitor();
        saveBounds();
        translatedCaptionCleanupTimer.stop();
        chunkMergeTimer.stop();
        clearMergeBuffer();
        hearingMainBusySinceMs = 0L;
        hearingMainBusySkipCount = 0;
        synchronized (recentShortCaptionMs) {
            recentShortCaptionMs.clear();
        }
        // ===== Overlay 破棄 =====
        if (overlay != null) {
            try {
                overlay.setVisible(false);
                overlay.dispose();
            } catch (Exception ignore) {}
            overlay = null;
        }
    }

    // ★JVM終了/メイン窓終了時に呼ぶ（Swing操作しない・プロセスだけ止める）
    public void shutdownForExit() {
        try { intentionalStop = true; } catch (Throwable ignore) {}
        try { running = false; } catch (Throwable ignore) {}
        try { stopWasapiProc(); } catch (Throwable ignore) {}

        // もし標準録音ラインを使う経路が残っていても安全に止める
        try {
            TargetDataLine l = line;
            line = null;
            if (l != null) {
                try { l.stop(); } catch (Exception ignore) {}
                try { l.close(); } catch (Exception ignore) {}
            }
            cleanup();
        } catch (Throwable ignore) {}
    }

    private void forceFullRepaint() {
        try {
            RepaintManager rm = RepaintManager.currentManager(this);
            rm.markCompletelyDirty(getRootPane());
            getRootPane().revalidate();
            getRootPane().repaint();
        } catch (Exception ignore) {}
    }
    private int uiFontSize() {
        Font f = getFont();
        if (f == null) f = UIManager.getFont("Label.font");
        if (f == null) f = UIManager.getFont("defaultFont");
        if (f == null) return 16;
        return Math.round(f.getSize2D());
    }
    private int fontExtra() {
        return Math.max(0, uiFontSize() - 16);
    }
    private int scaledWidth(int base, int perPoint) {
        return base + (fontExtra() * perPoint);
    }
    private int scaledHeight(int base) {
        return Math.max(base, uiFontSize() + 10);
    }
    private Font effectiveUiFont(Component c) {
        Font f = (c == null) ? null : c.getFont();
        if (f == null) f = getFont();
        if (f == null) f = UIManager.getFont("Label.font");
        if (f == null) f = UIManager.getFont("defaultFont");
        if (f == null) f = new Font(Font.SANS_SERIF, Font.PLAIN, 16);
        return f;
    }
    private int buttonWidth(AbstractButton button, String... labels) {
        Font font = effectiveUiFont(button);
        FontMetrics fm = button.getFontMetrics(font);
        int widest = 0;
        for (String label : labels) {
            if (label == null || label.isBlank()) continue;
            widest = Math.max(widest, fm.stringWidth(label));
        }
        if (widest <= 0) widest = fm.stringWidth(button.getText());
        Insets insets = button.getInsets();
        int insetW = (insets == null) ? 16 : (insets.left + insets.right);
        return widest + insetW + 22 + (fontExtra() * 3);
    }
    private int comboWidth(JComboBox<?> combo, int minWidth) {
        Font font = effectiveUiFont(combo);
        FontMetrics fm = combo.getFontMetrics(font);
        int widest = minWidth;
        ComboBoxModel<?> model = combo.getModel();
        if (model != null) {
            for (int i = 0; i < model.getSize(); i++) {
                Object item = model.getElementAt(i);
                if (item == null) continue;
                widest = Math.max(widest, fm.stringWidth(String.valueOf(item)));
            }
        }
        Insets insets = combo.getInsets();
        int insetW = (insets == null) ? 18 : (insets.left + insets.right);
        return widest + insetW + 34 + (fontExtra() * 2);
    }
    private int labelWidth(String text) {
        Font font = effectiveUiFont(this);
        FontMetrics fm = getFontMetrics(font);
        return fm.stringWidth(text);
    }
    private int computedWindowWidth() {
        int row1Need = 0;
        if (meter != null) row1Need += meter.getPreferredSize().width;
        if (toggle != null) row1Need += toggle.getPreferredSize().width;
        if (prefButton != null) row1Need += prefButton.getPreferredSize().width;
        if (row1Need > 0) row1Need += 8 + 8 + 24;

        int row2Need = labelWidth("Lang:") + 6 + labelWidth("To:") + 6 + 10 + 24;
        if (langCombo != null) row2Need += langCombo.getPreferredSize().width;
        if (translateTargetCombo != null) row2Need += translateTargetCombo.getPreferredSize().width;

        return Math.max(HEARING_W, Math.max(row1Need, row2Need));
    }
    private void applyMeterSize() {
        if (meter == null) return;
        Dimension meterSize = new Dimension(scaledWidth(HEARING_METER_W, 4), scaledHeight(METER_H));
        meter.setPreferredSize(meterSize);
        meter.setMinimumSize(meterSize);
        meter.setMaximumSize(meterSize);
        meter.revalidate();
        meter.repaint();
    }

    private void buildUi() {
        // ★既に構築済みなら何もしない
        if (toggle != null) return;
        Config.log("[Hearing] buildUi begin font=" + uiFontSize());
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        // 上段：メーター（メイン画面の GainMeter を流用）
        meter = new GainMeter();
        meter.setFont(root.getFont());
        meter.setCompactLabels(true);
        applyMeterSize();
        JPanel row1 = new JPanel();
        row1.setOpaque(true);
        row1.setLayout(new BoxLayout(row1, BoxLayout.X_AXIS));
        row1.setAlignmentX(Component.LEFT_ALIGNMENT);
        row1.add(meter);

        // 下段：Output選択 + ON/OFF + Prefs
        outputCombo = new JComboBox<>();
        outputCombo.setFocusable(false);
        Dimension comboSize = new Dimension(scaledWidth(200, 8), scaledHeight(26));
        outputCombo.setPreferredSize(comboSize);
        outputCombo.setMinimumSize(comboSize);
        outputCombo.setMaximumSize(comboSize);
        refreshOutputCombo();
        if (prefs.get("hearing.output", "").isEmpty()) {
            outputCombo.setSelectedItem(LOOPBACK_TOKEN);
            prefs.put("hearing.output", LOOPBACK_TOKEN);
        }

        String last = prefs.get("hearing.output", "");
        if (!last.isEmpty()) outputCombo.setSelectedItem(last);

        outputCombo.addActionListener(e -> {
            if (ignoreOutputEvent) return;
            Object sel = outputCombo.getSelectedItem();
            if (sel != null) prefs.put("hearing.output", sel.toString());
            if (running) { stopMonitor(false); startMonitor(); }
        });

        toggle = new JToggleButton("Hearing: OFF");
        toggle.setFocusable(false);
        Dimension tsize = new Dimension(Math.max(150, buttonWidth(toggle, "Hearing: OFF", "Hearing: ON")), scaledHeight(26));
        toggle.setPreferredSize(tsize);
        toggle.setMinimumSize(tsize);
        toggle.setMaximumSize(tsize);

        toggle.addActionListener(e -> {
            if (ignoreToggleEvent) return;

            if (toggle.isSelected()) {
                toggle.setText("Hearing: ON");
                MobMateWhisp.setHearingActive(true);   // ★追加
                ensureOverlayVisible();
                setOverlayText("Listening...");
                if (!translatedCaptionCleanupTimer.isRunning()) translatedCaptionCleanupTimer.start();
                Config.log("[Hearing][REC] ON: overlay visible");
                startMonitor();
            } else {
                toggle.setText("Hearing: OFF");
                MobMateWhisp.setHearingActive(false);  // ★追加
                stopMonitor();
                translatedCaptionCleanupTimer.stop();
                hideOverlay();
            }
            pack();
        });

        prefButton = new JButton("Prefs");
        prefButton.setFocusable(false);
        Dimension prefSize = new Dimension(Math.max(86, buttonWidth(prefButton, "Prefs")), scaledHeight(26));
        prefButton.setPreferredSize(prefSize);
        prefButton.setMinimumSize(prefSize);
        prefButton.setMaximumSize(prefSize);
        prefButton.addActionListener(e -> showSettingsDialog());
        row1.add(Box.createHorizontalGlue());
        row1.add(toggle);
        row1.add(Box.createHorizontalStrut(8));
        row1.add(prefButton);
        root.add(row1);


        langCombo = new JComboBox<>(LanguageOptions.whisperLangs());
        langCombo.setRenderer(LanguageOptions.whisperRenderer());
        langCombo.setFocusable(false);
        Dimension langSize = new Dimension(comboWidth(langCombo, scaledWidth(120, 6)), scaledHeight(26));
        langCombo.setPreferredSize(langSize);
        langCombo.setMinimumSize(langSize);
        langCombo.setMaximumSize(langSize);

        String lang = prefs.get("hearing.lang", "auto");
        langCombo.setSelectedItem(lang);

        langCombo.addActionListener(e -> {
            Object sel = langCombo.getSelectedItem();
            String v = (sel == null) ? "auto" : sel.toString();
            prefs.put("hearing.lang", v);
            try { prefs.sync(); } catch (Exception ignore) {}
            host.setHearingLanguage(v); // ★追加：Whisper側へ即反映
        });

        JPanel row2 = new JPanel();
        row2.setLayout(new BoxLayout(row2, BoxLayout.X_AXIS));
        row2.setAlignmentX(Component.LEFT_ALIGNMENT);

        row2.add(new JLabel("Lang:"));
        row2.add(Box.createHorizontalStrut(6));

        langCombo.setPreferredSize(langSize);
        langCombo.setMinimumSize(langSize);
        langCombo.setMaximumSize(langSize);
        row2.add(langCombo);

        row2.add(Box.createHorizontalStrut(10));

        row2.add(new JLabel("To:"));
        row2.add(Box.createHorizontalStrut(6));

        translateTargetCombo = new JComboBox<>(LanguageOptions.translationTargets());
        translateTargetCombo.setRenderer(LanguageOptions.translationRenderer());
        translateTargetCombo.setFocusable(false);
        Dimension translateSize = new Dimension(comboWidth(translateTargetCombo, scaledWidth(168, 8)), scaledHeight(26));
        translateTargetCombo.setPreferredSize(translateSize);
        translateTargetCombo.setMinimumSize(translateSize);
        translateTargetCombo.setMaximumSize(translateSize);
        translateTargetCombo.setSelectedItem(host.getHearingTranslateTarget());

        translateTargetCombo.addActionListener(e -> {
            Object sel = translateTargetCombo.getSelectedItem();
            String v = (sel == null) ? "OFF" : sel.toString();
            host.setHearingTranslateTarget(v);
        });

        row2.add(translateTargetCombo);



        root.add(Box.createVerticalStrut(6));
        root.add(row2);


        root.setOpaque(true);
        row1.setOpaque(true);
        row2.setOpaque(true);

        setContentPane(root);
        revalidate();
        repaint();
        pack();
        forceFullRepaint();
        ensureOverlayVisible();

        int minW = Math.max(computedWindowWidth(), getPreferredSize().width);
        int minH = Math.max(getHeight(), getPreferredSize().height);
        if (getWidth() < minW || getHeight() < minH) {
            setSize(Math.max(getWidth(), minW), Math.max(getHeight(), minH));
        }
        setMinimumSize(new Dimension(minW, minH));
        Config.log("[Hearing] buildUi done size=" + getWidth() + "x" + getHeight() + " minW=" + minW);
    }

    private void showSettingsDialog() {
        if (settingsDialog == null) {
            settingsDialog = buildSettingsDialog();
        }
        refreshMoonshineAutoHintLabel();
        if (overlay != null) {
            overlay.updateSettings();
        }
        settingsDialog.setLocationRelativeTo(this);
        settingsDialog.setVisible(true);
    }

    private void refreshMoonshineAutoHintLabel() {
        if (hearingMoonshineAutoHintLabel == null) return;
        boolean show = "moonshine".equalsIgnoreCase(host.getHearingEngine())
                && "auto".equalsIgnoreCase(host.getHearingSourceLangForUi());
        hearingMoonshineAutoHintLabel.setVisible(show);
    }

    private JDialog buildSettingsDialog() {
        JDialog dialog = new JDialog(this, uiText("hearing.pref.title"), false);
        dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);

        JPanel root = new JPanel(new GridBagLayout());
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridy = 0;

        addSettingsSection(root, gbc, uiText("hearing.pref.section.recognition"));

        String[] hearingEngineLabels = {
                uiText("hearing.pref.engine.whisper"),
                uiText("hearing.pref.engine.moonshine")
        };
        String[] hearingEngineValues = {"whisper", "moonshine"};
        JComboBox<String> hearingEngineCombo = new JComboBox<>(hearingEngineLabels);
        hearingEngineCombo.setSelectedIndex(indexOfValue(hearingEngineValues, host.getHearingEngine()));
        addSettingsRow(root, gbc, uiText("hearing.pref.engine"), hearingEngineCombo);
        hearingMoonshineAutoHintLabel = new JLabel(wrapDialogText(uiText("hearing.pref.engine.autoBaseHint"), 360));
        hearingMoonshineAutoHintLabel.setFont(hearingMoonshineAutoHintLabel.getFont().deriveFont(Font.PLAIN, 11f));
        hearingMoonshineAutoHintLabel.setForeground(new Color(170, 170, 170));
        hearingMoonshineAutoHintLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 2, 0));
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        root.add(hearingMoonshineAutoHintLabel, gbc);
        gbc.gridy++;
        refreshMoonshineAutoHintLabel();
        hearingEngineCombo.addActionListener(e -> {
            int idx = hearingEngineCombo.getSelectedIndex();
            if (idx < 0 || idx >= hearingEngineValues.length) return;
            host.setHearingEngine(hearingEngineValues[idx]);
            refreshMoonshineAutoHintLabel();
        });

        String[] hearingPrefilterLabels = {
                uiText("settings.audioPrefilter.off"),
                uiText("settings.audioPrefilter.normal"),
                uiText("settings.audioPrefilter.strong")
        };
        String[] hearingPrefilterValues = {"off", "normal", "strong"};
        JComboBox<String> hearingPrefilterCombo = new JComboBox<>(hearingPrefilterLabels);
        hearingPrefilterCombo.setSelectedIndex(indexOfValue(
                hearingPrefilterValues,
                host.getHearingAudioPrefilterModeForUi()));
        addSettingsRow(root, gbc, uiText("hearing.pref.audioPrefilter"), hearingPrefilterCombo);
        hearingPrefilterCombo.addActionListener(e -> {
            int idx = hearingPrefilterCombo.getSelectedIndex();
            if (idx < 0 || idx >= hearingPrefilterValues.length) return;
            host.setHearingAudioPrefilterModeForUi(hearingPrefilterValues[idx]);
        });

        String[] translateModeLabels = {
                uiText("hearing.pref.translateMode.latest"),
                uiText("hearing.pref.translateMode.preferNew"),
                uiText("hearing.pref.translateMode.realtime")
        };
        String[] translateModeValues = {
                MobMateWhisp.HEARING_TRANSLATE_MODE_LATEST,
                MobMateWhisp.HEARING_TRANSLATE_MODE_PREFER_NEW,
                MobMateWhisp.HEARING_TRANSLATE_MODE_REALTIME
        };
        JComboBox<String> translateModeCombo = new JComboBox<>(translateModeLabels);
        translateModeCombo.setSelectedIndex(indexOfValue(translateModeValues, host.getHearingTranslateQueueMode()));
        addSettingsRow(root, gbc, uiText("hearing.pref.translateMode"), translateModeCombo);
        translateModeCombo.addActionListener(e -> {
            int idx = translateModeCombo.getSelectedIndex();
            if (idx < 0 || idx >= translateModeValues.length) return;
            prefs.put("hearing.translate.queue_mode", translateModeValues[idx]);
            syncPrefsQuietly();
        });

        String[] chunkMergeValues = {"0", "1000", "2000", "3000", "4000", "5000"};
        String[] chunkMergeLabels = {
                uiText("hearing.pref.chunkMerge.disabled"),
                "1", "2", "3", "4", "5"
        };
        JComboBox<String> chunkMergeCombo = new JComboBox<>(chunkMergeLabels);
        chunkMergeCombo.setSelectedIndex(indexOfValue(chunkMergeValues, String.valueOf(getChunkMergeMs())));
        addSettingsRow(root, gbc, uiText("hearing.pref.chunkMerge"), chunkMergeCombo);
        chunkMergeCombo.addActionListener(e -> {
            int idx = chunkMergeCombo.getSelectedIndex();
            if (idx < 0 || idx >= chunkMergeValues.length) return;
            prefs.putInt("hearing.overlay.chunk_merge_ms", Integer.parseInt(chunkMergeValues[idx]));
            syncPrefsQuietly();
            chunkMergeTimer.setInitialDelay(getChunkMergeMs());
        });

        addSettingsSection(root, gbc, uiText("hearing.pref.section.overlay"));

        JComboBox<String> fontCombo = new JComboBox<>(new String[]{"14", "16", "18", "20", "24", "28", "32", "36", "40"});
        fontCombo.setSelectedItem(String.valueOf(prefs.getInt("hearing.overlay.font_size", 18)));
        addSettingsRow(root, gbc, uiText("hearing.pref.font"), fontCombo);
        fontCombo.addActionListener(e -> {
            Object sel = fontCombo.getSelectedItem();
            if (sel == null) return;
            prefs.putInt("hearing.overlay.font_size", Integer.parseInt(sel.toString()));
            syncPrefsQuietly();
            refreshOverlaySettings();
        });

        String[] bgValues = {"green", "blue", "gray", "red"};
        String[] bgLabels = {
                uiText("hearing.pref.bg.green"),
                uiText("hearing.pref.bg.blue"),
                uiText("hearing.pref.bg.gray"),
                uiText("hearing.pref.bg.red")
        };
        JComboBox<String> bgCombo = new JComboBox<>(bgLabels);
        bgCombo.setSelectedIndex(indexOfValue(bgValues, prefs.get("hearing.overlay.bg_color", "green")));
        addSettingsRow(root, gbc, uiText("hearing.pref.background"), bgCombo);
        bgCombo.addActionListener(e -> {
            int idx = bgCombo.getSelectedIndex();
            if (idx < 0 || idx >= bgValues.length) return;
            prefs.put("hearing.overlay.bg_color", bgValues[idx]);
            syncPrefsQuietly();
            refreshOverlaySettings();
        });

        JComboBox<String> opacityCombo = new JComboBox<>(new String[]{"50", "65", "72", "85", "95", "100"});
        opacityCombo.setSelectedItem(String.valueOf(prefs.getInt("hearing.overlay.opacity", 72)));
        addSettingsRow(root, gbc, uiText("hearing.pref.opacity"), opacityCombo);
        opacityCombo.addActionListener(e -> {
            Object sel = opacityCombo.getSelectedItem();
            if (sel == null) return;
            prefs.putInt("hearing.overlay.opacity", Integer.parseInt(sel.toString()));
            syncPrefsQuietly();
            refreshOverlaySettings();
        });

        JComboBox<String> historyCombo = new JComboBox<>(new String[]{"1", "3", "5", "6", "8", "10"});
        historyCombo.setSelectedItem(String.valueOf(prefs.getInt("hearing.overlay.history_size", 6)));
        addSettingsRow(root, gbc, uiText("hearing.pref.history"), historyCombo);
        historyCombo.addActionListener(e -> {
            Object sel = historyCombo.getSelectedItem();
            if (sel == null) return;
            prefs.putInt("hearing.overlay.history_size", Integer.parseInt(sel.toString()));
            syncPrefsQuietly();
            trimPartialHistory();
            refreshOverlaySettings();
        });

        JComboBox<String> translatedKeepCombo = new JComboBox<>(new String[]{"5", "8", "10", "12", "15", "20", "30", "60"});
        int translatedKeepSec = Math.max(1, prefs.getInt("hearing.overlay.translated_keep_ms", DEFAULT_TRANSLATED_KEEP_MS) / 1000);
        translatedKeepCombo.setSelectedItem(String.valueOf(translatedKeepSec));
        addSettingsRow(root, gbc, uiText("hearing.pref.doneKeep"), translatedKeepCombo);
        translatedKeepCombo.addActionListener(e -> {
            Object sel = translatedKeepCombo.getSelectedItem();
            if (sel == null) return;
            prefs.putInt("hearing.overlay.translated_keep_ms", Integer.parseInt(sel.toString()) * 1000);
            syncPrefsQuietly();
            cleanupExpiredTranslatedCaptions();
            refreshOverlaySettings();
        });

        String[] positionLabels = {
                uiText("hearing.pref.position.bottomLeft"),
                uiText("hearing.pref.position.bottomRight"),
                uiText("hearing.pref.position.topCenter"),
                uiText("hearing.pref.position.custom")
        };
        String[] positionValues = {OVERLAY_POS_BOTTOM_LEFT, OVERLAY_POS_BOTTOM_RIGHT, OVERLAY_POS_TOP_CENTER, OVERLAY_POS_CUSTOM};
        JComboBox<String> positionCombo = new JComboBox<>(positionLabels);
        positionCombo.setSelectedIndex(indexOfValue(positionValues, prefs.get("hearing.overlay.position", OVERLAY_POS_BOTTOM_LEFT)));
        addSettingsRow(root, gbc, uiText("hearing.pref.position"), positionCombo);
        positionCombo.addActionListener(e -> {
            int idx = positionCombo.getSelectedIndex();
            if (idx < 0 || idx >= positionValues.length) return;
            prefs.put("hearing.overlay.position", positionValues[idx]);
            syncPrefsQuietly();
            refreshOverlaySettings();
        });

        String[] flowLabels = {
                uiText("hearing.pref.flow.verticalUp"),
                uiText("hearing.pref.flow.verticalDown"),
                uiText("hearing.pref.flow.horizontalLeft"),
                uiText("hearing.pref.flow.horizontalRight")
        };
        String[] flowValues = {
                OVERLAY_FLOW_VERTICAL_UP,
                OVERLAY_FLOW_VERTICAL_DOWN,
                OVERLAY_FLOW_HORIZONTAL_LEFT,
                OVERLAY_FLOW_HORIZONTAL_RIGHT
        };
        JComboBox<String> flowCombo = new JComboBox<>(flowLabels);
        flowCombo.setSelectedIndex(indexOfValue(flowValues, prefs.get("hearing.overlay.flow", OVERLAY_FLOW_VERTICAL_UP)));
        addSettingsRow(root, gbc, uiText("hearing.pref.flow"), flowCombo);
        flowCombo.addActionListener(e -> {
            int idx = flowCombo.getSelectedIndex();
            if (idx < 0 || idx >= flowValues.length) return;
            prefs.put("hearing.overlay.flow", flowValues[idx]);
            syncPrefsQuietly();
            refreshOverlaySettings();
        });

        GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        String[] displayLabels = new String[screens.length];
        for (int i = 0; i < screens.length; i++) {
            String label = uiTextf("hearing.pref.display.option", i + 1);
            try {
                DisplayMode dm = screens[i].getDisplayMode();
                label += String.format(" (%dx%d)", dm.getWidth(), dm.getHeight());
            } catch (Exception ignore) {}
            displayLabels[i] = label;
        }
        JComboBox<String> displayCombo = new JComboBox<>(displayLabels);
        int displayIndex = Math.max(0, Math.min(prefs.getInt("hearing.overlay.display", 0), Math.max(0, displayLabels.length - 1)));
        if (displayLabels.length > 0) displayCombo.setSelectedIndex(displayIndex);
        addSettingsRow(root, gbc, uiText("hearing.pref.display"), displayCombo);
        displayCombo.addActionListener(e -> {
            int idx = Math.max(0, displayCombo.getSelectedIndex());
            prefs.putInt("hearing.overlay.display", idx);
            syncPrefsQuietly();
            refreshOverlaySettings();
        });

        JLabel tip = new JLabel(wrapDialogText(uiText("hearing.pref.tip"), 360));
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
        root.add(tip, gbc);

        JButton resetButton = new JButton(uiText("hearing.pref.resetPosition"));
        resetButton.addActionListener(e -> {
            prefs.put("hearing.overlay.position", OVERLAY_POS_BOTTOM_LEFT);
            prefs.remove("hearing.overlay.custom_x");
            prefs.remove("hearing.overlay.custom_y");
            syncPrefsQuietly();
            positionCombo.setSelectedIndex(0);
            refreshOverlaySettings();
        });

        gbc.gridy++;
        gbc.gridwidth = 2;
        root.add(resetButton, gbc);

        JScrollPane scrollPane = new JScrollPane(root,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        dialog.setContentPane(scrollPane);
        dialog.pack();
        dialog.setSize(Math.max(460, dialog.getWidth()), Math.min(Math.max(560, dialog.getHeight()), 860));
        dialog.setMinimumSize(new Dimension(Math.max(420, dialog.getWidth()), Math.min(520, dialog.getHeight())));
        dialog.setResizable(true);
        return dialog;
    }

    private void addSettingsRow(JPanel root, GridBagConstraints gbc, String labelText, JComponent field) {
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        root.add(new JLabel(formatDialogLabel(labelText)), gbc);
        gbc.gridx = 1;
        tuneSettingsFieldSize(field);
        root.add(field, gbc);
        gbc.gridy++;
    }

    private void addSettingsSection(JPanel root, GridBagConstraints gbc, String titleText) {
        JLabel heading = new JLabel(formatDialogLabel(titleText));
        Font font = heading.getFont();
        if (font != null) {
            heading.setFont(font.deriveFont(Font.BOLD));
        }

        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);
        panel.add(heading, BorderLayout.WEST);
        panel.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.CENTER);

        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(gbc.gridy == 0 ? 0 : 8, 4, 6, 4);
        root.add(panel, gbc);
        gbc.gridy++;
        gbc.insets = new Insets(4, 4, 4, 4);
    }

    private String formatDialogLabel(String labelText) {
        if (labelText == null || labelText.isEmpty()) return "";
        if (labelText.contains("\n")) {
            return "<html>" + labelText.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\n", "<br>") + "</html>";
        }
        return labelText;
    }

    private void tuneSettingsFieldSize(JComponent field) {
        if (field == null) return;
        Dimension pref = field.getPreferredSize();
        if (pref == null) return;
        int targetWidth = Math.max(scaledWidth(250, 8), Math.min(pref.width, scaledWidth(310, 10)));
        Dimension tuned = new Dimension(targetWidth, pref.height);
        field.setPreferredSize(tuned);
        field.setMinimumSize(tuned);
    }

    private String wrapDialogText(String text, int widthPx) {
        if (text == null || text.isEmpty()) return "";
        return "<html><div style='width:" + Math.max(180, widthPx) + "px;'>" + text + "</div></html>";
    }

    private int indexOfValue(String[] values, String target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) return i;
        }
        return 0;
    }

    private void syncPrefsQuietly() {
        try { prefs.sync(); } catch (Exception ignore) {}
    }

    private String uiText(String key) {
        return UiText.t(key);
    }

    private String uiTextf(String key, Object... args) {
        try {
            return String.format(UiText.t(key), args);
        } catch (Exception ignore) {
            return UiText.t(key);
        }
    }

    private void refreshOverlaySettings() {
        if (overlay != null) {
            overlay.updateSettings();
        }
    }

    public void showWindow() {
        if (isVisible()) {
            toFront();
            requestFocus();
            return;
        }
        persistVisibleState(true);
        setVisible(true);
        toFront();
        requestFocus();
    }
    private void updateTitle() {
        String mode = (host == null) ? "" : host.getCpuGpuMode();
        String demo = SteamHelper.isDemoMode() ? " [TRIAL]" : "";
        setTitle("MobMate Hearing [" + ((mode == null || mode.isEmpty()) ? "CPU MODE" : mode) + "]" + demo);
    }

    public void refreshOutputDevices() {
        refreshOutputCombo();
    }

    private java.util.List<String> queryWasapiRenderList() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        try {
            java.io.File ps1 = new java.io.File("./wasapi_loopback.ps1");
            if (!ps1.exists()) return out;

            ProcessBuilder pb = new ProcessBuilder(
                    "powershell",
                    "-NoProfile",
                    "-ExecutionPolicy", "Bypass",
                    "-File", ps1.getAbsolutePath(),
                    "-List",
                    "-AutoPick",
                    "-ChunkMs", "20"
            );
            pb.redirectErrorStream(true);

            Process p = pb.start();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) out.add(line);
                }
            }

            try { p.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Exception ignore) {}
            try { p.destroyForcibly(); } catch (Exception ignore) {}

            // 重複除去（順序維持）
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(out);
            out.clear();
            out.addAll(set);
        } catch (Exception ex) {
            Config.logError("[Hearing] queryWasapiRenderList failed", ex);
        }
        return out;
    }
    private void refreshOutputCombo() {
        if (outputCombo == null) return;

        final String keep = (String) outputCombo.getSelectedItem();

        ignoreOutputEvent = true;
        try {
            outputCombo.removeAllItems();

            // ★デフォルトはWASAPI loopback（AudioMeter方式）
            outputCombo.addItem(LOOPBACK_TOKEN);

            // ★重い列挙は別スレッドへ（UIフリーズ回避）
            outputCombo.addItem("(loading...)");
            outputCombo.setSelectedItem(LOOPBACK_TOKEN);

        } finally {
            ignoreOutputEvent = false;
        }

        new Thread(() -> {
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            try {
                for (javax.sound.sampled.Mixer.Info mi : javax.sound.sampled.AudioSystem.getMixerInfo()) {
                    String name = (mi.getName() != null) ? mi.getName() : "";
                    name = name.trim();
                    if (!name.isEmpty()) names.add(name);
                }
            } catch (Exception ex) {
                Config.logError("[Hearing] refreshOutputCombo mixer enumerate failed", ex);
            }

            SwingUtilities.invokeLater(() -> {
                if (outputCombo == null) return;

                ignoreOutputEvent = true;
                try {
                    Object current = outputCombo.getSelectedItem();

                    outputCombo.removeAllItems();
                    outputCombo.addItem(LOOPBACK_TOKEN);

                    for (String n : names) outputCombo.addItem(n);

                    // prefs復元優先
                    String last = "";
                    try { last = prefs.get("hearing.output", ""); } catch (Exception ignore) {}

                    if (last != null && !last.isEmpty()) {
                        outputCombo.setSelectedItem(last);
                    } else if (keep != null && !keep.isEmpty()) {
                        outputCombo.setSelectedItem(keep);
                    } else {
                        outputCombo.setSelectedItem(LOOPBACK_TOKEN);
                    }

                    // 選択が無効ならLoopbackに戻す
                    if (outputCombo.getSelectedItem() == null) {
                        outputCombo.setSelectedItem(LOOPBACK_TOKEN);
                    }

                } finally {
                    ignoreOutputEvent = false;
                }
            });

        }, "hearing-mixer-enum").start();
    }
    private String findLoopbackCaptureDeviceName() {
        String best = null;

        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            String name = (mi.getName() == null) ? "" : mi.getName();
            String lname = name.toLowerCase();

            boolean hint = false;
            for (String h : LOOPBACK_HINTS) {
                if (lname.contains(h.toLowerCase())) { hint = true; break; }
            }
            if (!hint) continue;

            try {
                Mixer m = AudioSystem.getMixer(mi);

                boolean ok = false;
                for (Line.Info li : m.getTargetLineInfo()) {
                    if (li instanceof DataLine.Info &&
                            TargetDataLine.class.isAssignableFrom(((DataLine.Info) li).getLineClass())) {
                        ok = true;
                        break;
                    }
                }
                if (ok) {
                    best = mi.getName();
                    break;
                }
            } catch (Exception ignore) {}
        }

        if (best == null) {
            Config.log("[Hearing] Loopback capture device not found. Enable 'Stereo Mix/What U Hear' in Windows Sound settings.");
            return "";
        }
        Config.log("[Hearing] Loopback device picked: " + best);
        return best;
    }

    private void stopWasapiProc() {
        Process p;
        Thread tOut;
        Thread tErr;

        synchronized (loopLock) {
            p = loopProc;
            tOut = loopProcThread;
            tErr = loopProcErrThread;
            loopProc = null;
            loopProcThread = null;
            loopProcErrThread = null;
        }

        if (p == null) return;

        try { p.getInputStream().close(); } catch (Exception ignore) {}
        try { p.getErrorStream().close(); } catch (Exception ignore) {}
        try { p.getOutputStream().close(); } catch (Exception ignore) {}

        // まず穏当に
        try { p.destroy(); } catch (Exception ignore) {}
        try { p.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Exception ignore) {}

        // まだ生きてたら強制
        if (p.isAlive()) {
            try { p.destroyForcibly(); } catch (Exception ignore) {}
            try { p.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Exception ignore) {}
        }

        // それでも残るなら taskkill（最終手段）
        if (p.isAlive()) {
            try {
                long pid = p.pid();
                new ProcessBuilder("cmd", "/c", "taskkill /PID " + pid + " /T /F").start().waitFor();
                Config.log("[Hearing][WASAPI] taskkill done pid=" + pid);
            } catch (Exception ex) {
                Config.log("[Hearing][WASAPI] taskkill failed: " + ex);
            }
        }

        // readerスレッドはタイムアウト付きで合流（無限待ち回避）
        try { if (tOut != null) tOut.join(200); } catch (Exception ignore) {}
        try { if (tErr != null) tErr.join(200); } catch (Exception ignore) {}

        Config.log("[Hearing][WASAPI] loopback process stopped.");
    }

    private void startMonitor() {
        wasapiCrashCount = 0;  // ★手動ON時にリトライカウンタリセット
        // ★二重起動防止：生きてたら再起動しない（必要なら stop→start に変える）
        synchronized (loopLock) {
            if (loopProc != null && loopProc.isAlive()) {
                Config.log("[Hearing] WASAPI already running, skip start");
                return;
            }
        }
        stopMonitor(false); // ★toggleをOFFにしない

        final String deviceName = LOOPBACK_TOKEN; // ★常にループバック固定

        // ★Loopback は専用プロセスへ
        synchronized (loopLock) {
            if (loopProc != null && loopProc.isAlive()) {
                Config.log("[Hearing][WASAPI] skip start: loopback process already running");
                return;
            }
        }
        if (host.isHearingTranslateEnabled()) {
            SwingUtilities.invokeLater(() -> {
                if (meter != null) meter.setValue(0, -60.0, false, 1f, 1f, false);
                setOverlayText("Loading filters...");
            });
            Thread preloadThread = new Thread(() -> {
                host.preloadIgnoreForHearingStartIfNeeded();
                SwingUtilities.invokeLater(() -> {
                    if (toggle != null && toggle.isSelected()) {
                        setOverlayText("Listening...");
                        startWasapiLoopbackProc();
                    }
                });
            }, "hearing-ignore-preload");
            preloadThread.setDaemon(true);
            preloadThread.start();
            return;
        }
        startWasapiLoopbackProc();
    }

    private void stopMonitor() {
        stopMonitor(true);
    }

    private void stopMonitor(boolean updateToggleUi) {
        intentionalStop = true;  // ★crash handler抑制
        running = false;
        hearingPrefilterState.reset();
        stopWasapiProc(); // ★WASAPI ps1 を確実に止める

        // ★WASAPI loopback helper stop（確実に落とす）
        try {
            Process p = loopProc;
            loopProc = null;
            if (p != null) {
                long pid = -1;
                try { pid = p.pid(); } catch (Throwable ignore) {}

                try { p.destroy(); } catch (Exception ignore) {}
                try { p.destroyForcibly(); } catch (Exception ignore) {}

                // Windowsは taskkill が一番確実（子プロセスもまとめて）
                if (pid > 0) {
                    try {
                        new ProcessBuilder("cmd", "/c", "taskkill", "/PID", String.valueOf(pid), "/T", "/F")
                                .redirectErrorStream(true)
                                .start();
                    } catch (Exception ignore) {}
                }
            }
        } catch (Exception ignore) {}

        try {
            Thread t2 = loopProcThread;
            loopProcThread = null;
            if (t2 != null) {
                try { t2.join(300); } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}

        Thread t = worker;
        worker = null;

        try {
            TargetDataLine l = line;
            line = null;
            if (l != null) {
                try { l.stop(); } catch (Exception ignore) {}
                try { l.close(); } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}

        if (t != null) {
            try { t.join(500); } catch (Exception ignore) {}
        }

        SwingUtilities.invokeLater(() -> {
            if (meter != null) meter.setValue(0, -60.0, false, 1f, 1f, false);

            pcmAcc.reset();
            transcribing = false;
            partialHistory.clear();
            recentTranslatedDisplays.clear();
            chunkMergeTimer.stop();
            clearMergeBuffer();
            resetAutoDetectState("stop");
            hearingNoiseFloorRms = 120.0;
            hearingVoiceHoldChunks = 0;
            hearingPrefilterState.reset();

            // ★ここが肝：startMonitor() 経由の stop では toggle を触らない
            if (updateToggleUi && toggle != null) {
                ignoreToggleEvent = true;
                try {
                    toggle.setSelected(false);
                    toggle.setText("Hearing: OFF");
                } finally {
                    ignoreToggleEvent = false;
                }
            }
        });
    }

    private static final class HearingHistoryItem {
        final String sourceText;
        final long sourceAtMs;
        String translatedText;
        long translatedAtMs;

        HearingHistoryItem(String sourceText, long sourceAtMs, String translatedText, long translatedAtMs) {
            this.sourceText = sourceText;
            this.sourceAtMs = sourceAtMs;
            this.translatedText = translatedText;
            this.translatedAtMs = translatedAtMs;
        }

        boolean isTranslated() {
            return translatedText != null && !translatedText.isBlank() && translatedAtMs > 0L;
        }
    }

    private static final class RecentTranslatedDisplay {
        final long sourceAtMs;
        final String translatedText;
        final long translatedAtMs;

        RecentTranslatedDisplay(long sourceAtMs, String translatedText, long translatedAtMs) {
            this.sourceAtMs = sourceAtMs;
            this.translatedText = translatedText;
            this.translatedAtMs = translatedAtMs;
        }
    }

    private static final class PendingMergeChunk {
        final long sourceAtMs;
        final String sourceText;

        PendingMergeChunk(long sourceAtMs, String sourceText) {
            this.sourceAtMs = sourceAtMs;
            this.sourceText = sourceText;
        }
    }

    private static final class AutoDetectVote {
        final String lang;
        final int letters;
        final long atMs;

        AutoDetectVote(String lang, int letters, long atMs) {
            this.lang = (lang == null) ? "" : lang.trim().toLowerCase(java.util.Locale.ROOT);
            this.letters = Math.max(0, letters);
            this.atMs = atMs;
        }
    }

    private static final class PendingAutoDetectTranslation {
        final long sourceAtMs;
        final String sourceText;
        final long queuedAtMs;

        PendingAutoDetectTranslation(long sourceAtMs, String sourceText, long queuedAtMs) {
            this.sourceAtMs = sourceAtMs;
            this.sourceText = sourceText;
            this.queuedAtMs = queuedAtMs;
        }
    }

    // ★Hearing: PCM chunk を Whisper に投げて、Overlay/Lbl を更新する共通処理
    // 直近partialを最大N件保持（古い→新しい）※Nはユーザー設定
    private final java.util.ArrayDeque<HearingHistoryItem> partialHistory = new java.util.ArrayDeque<>(10);
    private final java.util.ArrayDeque<RecentTranslatedDisplay> recentTranslatedDisplays = new java.util.ArrayDeque<>(4);
    private long waitingTranslateSinceMs = 0L;
    private long lastWaitingTranslateLogMs = 0L;

    private static String normalizeShortCaptionKey(String text) {
        if (text == null) return "";
        String s = text.trim().toLowerCase(java.util.Locale.ROOT);
        s = s.replaceAll("[\\s\\p{Punct}、。！？「」『』（）()\\[\\]{}【】…・~〜ー]+", "");
        return s;
    }

    private static boolean isInlineRepeatSpamCaption(String text) {
        String normalized = normalizeShortCaptionKey(text);
        if (normalized.isEmpty()) return false;
        int cp = normalized.codePointCount(0, normalized.length());
        if (cp < 12) return false;
        try {
            return java.util.regex.Pattern.compile("(.{1,8})\\1{4,}").matcher(normalized).find();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean shouldSuppressShortRepeatedCaption(String text) {
        String normalized = normalizeShortCaptionKey(text);
        if (normalized.isEmpty()) return false;
        if (isInlineRepeatSpamCaption(text)) {
            Config.logDebug("[Hearing][Filter] suppress inline repeat spam: " + text);
            return true;
        }
        int cp = normalized.codePointCount(0, normalized.length());
        if (cp <= 0 || cp > SHORT_REPEAT_MAX_CP) return false;

        long now = System.currentTimeMillis();
        synchronized (recentShortCaptionMs) {
            recentShortCaptionMs.entrySet().removeIf(e -> now - e.getValue() > SHORT_REPEAT_SUPPRESS_MS);
            Long prev = recentShortCaptionMs.get(normalized);
            recentShortCaptionMs.put(normalized, now);
            if (prev != null && now - prev <= SHORT_REPEAT_SUPPRESS_MS) {
                Config.logDebug("[Hearing][Filter] suppress short repeated caption: " + text);
                return true;
            }
        }
        return false;
    }

    private HearingHistoryItem pushPartialHistory(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        pruneExpiredTranslatedCaptions();
        for (HearingHistoryItem item : partialHistory) {
            if (s.equals(item.sourceText)) return null;
        }
        int maxSize = prefs.getInt("hearing.overlay.history_size", 6);
        if (partialHistory.size() >= maxSize) partialHistory.pollFirst();
        HearingHistoryItem added = new HearingHistoryItem(s, System.currentTimeMillis(), null, 0L);
        partialHistory.addLast(added);
        return added;
    }
    private void replaceLatestHistory(long sourceAtMs, String sourceText, String translatedText) {
        if (translatedText == null) return;
        sourceText = (sourceText == null) ? "" : sourceText.trim();
        translatedText = translatedText.trim();
        if (translatedText.isEmpty()) return;
        java.util.ArrayList<HearingHistoryItem> items = new java.util.ArrayList<>(partialHistory);
        for (int i = items.size() - 1; i >= 0; i--) {
            HearingHistoryItem item = items.get(i);
            if (item.sourceAtMs == sourceAtMs
                    || (!sourceText.isEmpty() && sourceText.equals(item.sourceText))) {
                item.translatedText = translatedText;
                item.translatedAtMs = System.currentTimeMillis();
                noteRecentTranslatedDisplay(item.sourceAtMs, translatedText, item.translatedAtMs);
                partialHistory.clear();
                for (HearingHistoryItem updated : items) partialHistory.addLast(updated);
                return;
            }
        }
        pruneExpiredTranslatedCaptions();
        HearingHistoryItem appended = new HearingHistoryItem(sourceText, System.currentTimeMillis(), translatedText, System.currentTimeMillis());
        partialHistory.addLast(appended);
        noteRecentTranslatedDisplay(appended.sourceAtMs, translatedText, appended.translatedAtMs);
        trimPartialHistory();
    }

    private void noteRecentTranslatedDisplay(long sourceAtMs, String translatedText, long translatedAtMs) {
        if (translatedText == null || translatedText.isBlank() || translatedAtMs <= 0L) return;
        synchronized (recentTranslatedDisplays) {
            recentTranslatedDisplays.removeIf(entry -> entry == null || entry.sourceAtMs == sourceAtMs);
            recentTranslatedDisplays.addLast(new RecentTranslatedDisplay(sourceAtMs, translatedText.trim(), translatedAtMs));
            while (recentTranslatedDisplays.size() > RECENT_TRANSLATED_DISPLAY_BUFFER) {
                recentTranslatedDisplays.pollFirst();
            }
        }
    }

    private int getOverlayHistorySize() {
        return Math.max(1, prefs.getInt("hearing.overlay.history_size", 6));
    }

    private void queuePendingAutoDetectTranslation(HearingHistoryItem item) {
        if (item == null || item.sourceText == null || item.sourceText.isBlank()) return;
        long now = System.currentTimeMillis();
        synchronized (pendingAutoDetectTranslations) {
            pendingAutoDetectTranslations.removeIf(entry -> entry == null
                    || entry.sourceAtMs == item.sourceAtMs
                    || (now - entry.queuedAtMs) > AUTO_DETECT_BACKFILL_MAX_AGE_MS);
            pendingAutoDetectTranslations.addLast(new PendingAutoDetectTranslation(item.sourceAtMs, item.sourceText, now));
            while (pendingAutoDetectTranslations.size() > AUTO_DETECT_BACKFILL_LIMIT) {
                pendingAutoDetectTranslations.pollFirst();
            }
        }
    }

    private long getAutoDetectPendingWaitMs() {
        synchronized (pendingAutoDetectTranslations) {
            PendingAutoDetectTranslation oldest = pendingAutoDetectTranslations.peekFirst();
            if (oldest == null) return 0L;
            return Math.max(0L, System.currentTimeMillis() - oldest.queuedAtMs);
        }
    }

    private boolean isHistoryEntryStillPending(PendingAutoDetectTranslation pending) {
        if (pending == null) return false;
        long now = System.currentTimeMillis();
        if ((now - pending.queuedAtMs) > AUTO_DETECT_BACKFILL_MAX_AGE_MS) return false;
        for (HearingHistoryItem item : partialHistory) {
            if (item == null) continue;
            if (item.sourceAtMs == pending.sourceAtMs) {
                return !item.isTranslated() && item.sourceText != null && !item.sourceText.isBlank();
            }
        }
        return false;
    }

    private void drainPendingAutoDetectTranslations(String sourceLangHint) {
        if (sourceLangHint == null || sourceLangHint.isBlank()) return;
        PendingAutoDetectTranslation next = null;
        long versionSnapshot;
        synchronized (pendingAutoDetectTranslations) {
            if (autoDetectBackfillRunning) return;
            while (!pendingAutoDetectTranslations.isEmpty()) {
                PendingAutoDetectTranslation candidate = pendingAutoDetectTranslations.pollFirst();
                if (isHistoryEntryStillPending(candidate)) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) return;
            autoDetectBackfillRunning = true;
            versionSnapshot = autoDetectStateVersion;
        }
        Config.log("[Hearing][Lang] backfill translate: " + next.sourceText);
        requestAsyncHearingTranslationInternal(next.sourceAtMs, next.sourceText, sourceLangHint, () -> {
            synchronized (pendingAutoDetectTranslations) {
                autoDetectBackfillRunning = false;
            }
            SwingUtilities.invokeLater(() -> {
                synchronized (pendingAutoDetectTranslations) {
                    if (autoDetectStateVersion != versionSnapshot) return;
                }
                drainPendingAutoDetectTranslations(sourceLangHint);
            });
        });
    }

    private void requestAsyncHearingTranslationInternal(long sourceAtMs,
                                                        String sourceText,
                                                        String sourceLangHint,
                                                        Runnable onDone) {
        if (sourceText == null || sourceText.isBlank()) {
            if (onDone != null) onDone.run();
            return;
        }
        if (!host.isHearingTranslateEnabled()) {
            if (onDone != null) onDone.run();
            return;
        }
        host.translateHearingTextAsync(sourceText, sourceLangHint, translatedText -> SwingUtilities.invokeLater(() -> {
            try {
                if (!running) return;
                if (partialHistory.isEmpty()) return;
                if (!host.isHearingTranslateEnabled()) return;
                String translated = (translatedText == null) ? "" : translatedText.trim();
                if (translated.isEmpty()) return;
                if (shouldSuppressShortRepeatedCaption(translated)) return;
                replaceLatestHistory(sourceAtMs, sourceText, translated);
                refreshOverlayCaption();
            } finally {
                if (onDone != null) onDone.run();
            }
        }));
    }

    private void requestAsyncHearingTranslation(HearingHistoryItem item) {
        if (item == null || item.sourceText == null || item.sourceText.isBlank()) return;
        if (!host.isHearingTranslateEnabled()) return;
        String sourceLangHint = resolveStableAutoDetectSourceLang(item.sourceText);
        if (isHearingAutoDetectMode() && (sourceLangHint == null || sourceLangHint.isBlank())) {
            queuePendingAutoDetectTranslation(item);
            Config.log("[Hearing][Lang] waiting auto-detect stabilization: letters="
                    + getAutoDetectProbeLetterCount()
                    + " pendingWaitMs=" + getAutoDetectPendingWaitMs()
                    + " resetGapMs=" + getAutoDetectResetGapMs()
                    + " text=" + item.sourceText);
            return;
        }
        requestAsyncHearingTranslationInternal(item.sourceAtMs, item.sourceText, sourceLangHint, () -> {
            if (isHearingAutoDetectMode()) {
                drainPendingAutoDetectTranslations(sourceLangHint);
            }
        });
    }

    private boolean isHearingAutoDetectMode() {
        String configured = "auto";
        try {
            configured = prefs.get("hearing.lang", "auto");
        } catch (Exception ignore) {}
        return configured == null || configured.isBlank() || "auto".equalsIgnoreCase(configured.trim());
    }

    private void resetAutoDetectState(String reason) {
        synchronized (autoDetectProbeBuffer) {
            autoDetectProbeBuffer.setLength(0);
            autoDetectVotes.clear();
            pendingAutoDetectTranslations.clear();
            stableAutoDetectLang = "";
            stableAutoDetectLockedAtMs = 0L;
            autoDetectLastSwitchAtMs = 0L;
            lastAutoDetectInputMs = 0L;
            autoDetectBackfillRunning = false;
            autoDetectStateVersion++;
        }
        if (reason != null && !reason.isBlank()) {
            Config.logDebug("[Hearing][Lang] auto-detect state reset: " + reason);
        }
    }

    private int countLetterCodePoints(CharSequence text) {
        if (text == null || text.length() == 0) return 0;
        int count = 0;
        for (int i = 0; i < text.length();) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) count++;
        }
        return count;
    }

    private int getAutoDetectProbeLetterCount() {
        synchronized (autoDetectProbeBuffer) {
            return countLetterCodePoints(autoDetectProbeBuffer);
        }
    }

    private int autoDetectStableThreshold(String scriptCandidate) {
        String normalized = (scriptCandidate == null) ? "" : scriptCandidate.trim().toLowerCase(java.util.Locale.ROOT);
        if ("ja".equals(normalized) || "zh".equals(normalized) || "ko".equals(normalized)) {
            return 6;
        }
        return AUTO_DETECT_STABLE_LETTER_CP;
    }

    private int getAutoDetectProbeThreshold(String scriptCandidate) {
        int threshold = autoDetectStableThreshold(scriptCandidate);
        if (isLongMergeWaitForAutoDetect()) {
            threshold = Math.max(4, threshold - AUTO_DETECT_LONG_MERGE_PROBE_THRESHOLD_REDUCTION);
        }
        return threshold;
    }

    private long getAutoDetectResetGapMs() {
        int mergeMs = getChunkMergeMs();
        long scaled = (mergeMs <= 0)
                ? AUTO_DETECT_RESET_MIN_GAP_MS
                : Math.max(AUTO_DETECT_RESET_MIN_GAP_MS, (mergeMs * 2L) + 1000L);
        return Math.min(AUTO_DETECT_RESET_MAX_GAP_MS, scaled);
    }

    private boolean isLongMergeWaitForAutoDetect() {
        return getChunkMergeMs() >= 3000;
    }

    private int getAutoDetectInitialVoteThreshold() {
        return isLongMergeWaitForAutoDetect()
                ? AUTO_DETECT_LONG_MERGE_INITIAL_VOTE_THRESHOLD
                : AUTO_DETECT_INITIAL_VOTE_THRESHOLD;
    }

    private int getAutoDetectSwitchVoteThreshold() {
        return isLongMergeWaitForAutoDetect()
                ? AUTO_DETECT_LONG_MERGE_SWITCH_VOTE_THRESHOLD
                : AUTO_DETECT_SWITCH_VOTE_THRESHOLD;
    }

    private int autoDetectMinLettersForVote(String candidate) {
        String normalized = (candidate == null) ? "" : candidate.trim().toLowerCase(java.util.Locale.ROOT);
        if ("ja".equals(normalized) || "zh".equals(normalized) || "ko".equals(normalized)) {
            return 2;
        }
        return 4;
    }

    private void appendAutoDetectProbe(String text) {
        if (text == null || text.isBlank()) return;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            if (countLetterCodePoints(autoDetectProbeBuffer) >= AUTO_DETECT_PROBE_MAX_CP) break;
            autoDetectProbeBuffer.appendCodePoint(cp);
        }
        while (countLetterCodePoints(autoDetectProbeBuffer) > AUTO_DETECT_PROBE_MAX_CP && autoDetectProbeBuffer.length() > 0) {
            int first = autoDetectProbeBuffer.codePointAt(0);
            autoDetectProbeBuffer.delete(0, Character.charCount(first));
        }
    }

    private void appendAutoDetectVote(String candidate, int letters, long now) {
        if (candidate == null || candidate.isBlank()) return;
        autoDetectVotes.addLast(new AutoDetectVote(candidate, letters, now));
        while (autoDetectVotes.size() > AUTO_DETECT_VOTE_WINDOW) {
            autoDetectVotes.pollFirst();
        }
    }

    private String findBestAutoDetectVoteLang() {
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (AutoDetectVote vote : autoDetectVotes) {
            if (vote == null || vote.lang.isBlank()) continue;
            counts.merge(vote.lang, 1, Integer::sum);
        }
        String bestLang = "";
        int bestCount = 0;
        for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestLang = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return bestLang;
    }

    private int countAutoDetectVotes(String lang) {
        if (lang == null || lang.isBlank()) return 0;
        int count = 0;
        for (AutoDetectVote vote : autoDetectVotes) {
            if (vote != null && lang.equals(vote.lang)) count++;
        }
        return count;
    }

    private String buildAutoDetectVoteSummary() {
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (AutoDetectVote vote : autoDetectVotes) {
            if (vote == null || vote.lang.isBlank()) continue;
            counts.merge(vote.lang, 1, Integer::sum);
        }
        if (counts.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }

    private String resolveStableAutoDetectSourceLang(String text) {
        if (!isHearingAutoDetectMode()) {
            resetAutoDetectState("configured-lang");
            return "";
        }
        String resolvedLang = "";
        String moonshineSwitchLang = "";
        synchronized (autoDetectProbeBuffer) {
            long now = System.currentTimeMillis();
            long resetGapMs = getAutoDetectResetGapMs();
            if (lastAutoDetectInputMs > 0L && (now - lastAutoDetectInputMs) >= resetGapMs) {
                autoDetectProbeBuffer.setLength(0);
                autoDetectVotes.clear();
                stableAutoDetectLang = "";
                stableAutoDetectLockedAtMs = 0L;
                autoDetectLastSwitchAtMs = 0L;
                Config.logDebug("[Hearing][Lang] auto-detect gap reset: gapMs=" + (now - lastAutoDetectInputMs)
                        + " thresholdMs=" + resetGapMs);
            }
            lastAutoDetectInputMs = now;
            appendAutoDetectProbe(text);
            int probeLetters = countLetterCodePoints(autoDetectProbeBuffer);
            String candidate = host.detectHearingSourceLangByScript(text);
            int textLetters = countLetterCodePoints(text);
            if (candidate != null && !candidate.isBlank() && textLetters >= autoDetectMinLettersForVote(candidate)) {
                appendAutoDetectVote(candidate, textLetters, now);
            } else if (candidate != null && !candidate.isBlank()) {
                Config.logDebug("[Hearing][Lang] weak auto-detect evidence ignored: candidate=" + candidate
                        + " letters=" + textLetters + " text=" + text);
            }

            String bestLang = findBestAutoDetectVoteLang();
            int bestVotes = countAutoDetectVotes(bestLang);
            boolean hasStable = stableAutoDetectLang != null && !stableAutoDetectLang.isBlank();
            if (!hasStable) {
                String probeCandidate = host.detectHearingSourceLangByScript(autoDetectProbeBuffer.toString());
                int probeThreshold = getAutoDetectProbeThreshold(probeCandidate);
                long pendingWaitMs = getAutoDetectPendingWaitMs();
                if (!bestLang.isBlank()
                        && bestVotes >= getAutoDetectInitialVoteThreshold()
                        && probeLetters >= probeThreshold) {
                    stableAutoDetectLang = bestLang;
                    stableAutoDetectLockedAtMs = now;
                    autoDetectLastSwitchAtMs = now;
                    autoDetectStateVersion++;
                    moonshineSwitchLang = bestLang;
                    Config.log("[Hearing][Lang] auto-detect stabilized: " + bestLang
                            + " votes=" + bestVotes
                            + " probeLetters=" + probeLetters
                            + " threshold=" + probeThreshold
                            + " voteSummary=" + buildAutoDetectVoteSummary()
                            + " probe=" + autoDetectProbeBuffer);
                    resolvedLang = stableAutoDetectLang;
                } else if (!bestLang.isBlank()
                        && bestVotes >= 1
                        && probeLetters >= probeThreshold
                        && pendingWaitMs >= AUTO_DETECT_MAX_STABILIZATION_WAIT_MS) {
                    stableAutoDetectLang = bestLang;
                    stableAutoDetectLockedAtMs = now;
                    autoDetectLastSwitchAtMs = now;
                    autoDetectStateVersion++;
                    moonshineSwitchLang = bestLang;
                    Config.log("[Hearing][Lang] auto-detect timeout-stabilized: " + bestLang
                            + " votes=" + bestVotes
                            + " probeLetters=" + probeLetters
                            + " pendingWaitMs=" + pendingWaitMs
                            + " threshold=" + probeThreshold
                            + " voteSummary=" + buildAutoDetectVoteSummary()
                            + " probe=" + autoDetectProbeBuffer);
                    resolvedLang = stableAutoDetectLang;
                } else if (isLongMergeWaitForAutoDetect()
                        && !probeCandidate.isBlank()
                        && probeCandidate.equals(bestLang)
                        && bestVotes >= 1
                        && probeLetters >= (probeThreshold + AUTO_DETECT_LONG_MERGE_PROBE_FORCE_EXTRA_CP)) {
                    stableAutoDetectLang = probeCandidate;
                    stableAutoDetectLockedAtMs = now;
                    autoDetectLastSwitchAtMs = now;
                    autoDetectStateVersion++;
                    Config.log("[Hearing][Lang] auto-detect probe-forced: " + probeCandidate
                            + " votes=" + bestVotes
                            + " probeLetters=" + probeLetters
                            + " threshold=" + (probeThreshold + AUTO_DETECT_LONG_MERGE_PROBE_FORCE_EXTRA_CP)
                            + " voteSummary=" + buildAutoDetectVoteSummary()
                            + " probe=" + autoDetectProbeBuffer);
                    resolvedLang = stableAutoDetectLang;
                } else {
                    resolvedLang = "";
                }
            } else if (now - stableAutoDetectLockedAtMs < AUTO_DETECT_LOCK_HOLD_MS) {
                resolvedLang = stableAutoDetectLang;
            } else if (now - autoDetectLastSwitchAtMs < AUTO_DETECT_SWITCH_COOLDOWN_MS) {
                resolvedLang = stableAutoDetectLang;
            } else {
                if (!bestLang.isBlank()
                        && !bestLang.equals(stableAutoDetectLang)
                        && bestVotes >= getAutoDetectSwitchVoteThreshold()) {
                    String prev = stableAutoDetectLang;
                    stableAutoDetectLang = bestLang;
                    stableAutoDetectLockedAtMs = now;
                    autoDetectLastSwitchAtMs = now;
                    autoDetectStateVersion++;
                    autoDetectProbeBuffer.setLength(0);
                    autoDetectVotes.clear();
                    appendAutoDetectProbe(text);
                    appendAutoDetectVote(bestLang, Math.max(textLetters, autoDetectMinLettersForVote(bestLang)), now);
                    moonshineSwitchLang = bestLang;
                    Config.log("[Hearing][Lang] auto-detect switched: " + prev + " -> " + bestLang
                            + " votes=" + bestVotes
                            + " probeLetters=" + probeLetters);
                }
                resolvedLang = stableAutoDetectLang;
            }
        }
        if (!moonshineSwitchLang.isBlank()) {
            host.requestHearingAutoMoonshineSessionLang(moonshineSwitchLang);
        }
        return resolvedLang;
    }

    private int getChunkMergeMs() {
        int value = prefs.getInt("hearing.overlay.chunk_merge_ms", DEFAULT_CHUNK_MERGE_MS);
        if (value <= 0) return 0;
        return Math.max(1000, value);
    }
    private int getRecognitionChunkMs() {
        return 2000;
    }

    private void enqueueForMergedTranslation(HearingHistoryItem item) {
        if (item == null) return;
        if (!host.isHearingTranslateEnabled()) return;
        if (getChunkMergeMs() <= 0) {
            requestAsyncHearingTranslation(item);
            return;
        }
        long now = System.currentTimeMillis();
        boolean shouldFlushNow = false;
        synchronized (pendingMergeChunks) {
            if (!pendingMergeChunks.isEmpty()
                    && pendingMergeStartedAtMs > 0L
                    && (now - pendingMergeStartedAtMs) >= getChunkMergeMs()) {
                shouldFlushNow = true;
            }
        }
        if (shouldFlushNow) {
            Config.logDebug("[Hearing][Merge] force flush by age");
            flushMergeBuffer();
        }
        synchronized (pendingMergeChunks) {
            if (pendingMergeChunks.isEmpty()) {
                pendingMergeStartedAtMs = now;
            }
            pendingMergeChunks.add(new PendingMergeChunk(item.sourceAtMs, item.sourceText));
        }
        if (shouldForceFlushMergeByLength()) {
            chunkMergeTimer.stop();
            Config.log("[Hearing][Merge] force flush by length"
                    + " (cp=" + getPendingMergeCodePointCount()
                    + " chunks=" + getPendingMergeChunkCount() + ")");
            consecutiveBoundaryChunks = 0;
            flushMergeBuffer();
            return;
        }
        boolean isBoundary = looksLikeMergeBoundary(item.sourceText);
        if (isBoundary) {
            boolean boundaryFlush = false;
            int chunkCount = 0;
            int previousBoundaryStreak = consecutiveBoundaryChunks;
            synchronized (pendingMergeChunks) {
                chunkCount = pendingMergeChunks.size();
                boundaryFlush = chunkCount >= 5 || previousBoundaryStreak >= 3;
            }
            if (boundaryFlush) {
                chunkMergeTimer.stop();
                Config.log("[Hearing][Merge] force flush by boundary (chunks=" + chunkCount
                        + " boundaryStreak=" + (previousBoundaryStreak + 1) + ")");
                consecutiveBoundaryChunks = 0;
                flushMergeBuffer();
                return;
            }
        }
        consecutiveBoundaryChunks = isBoundary ? Math.min(8, consecutiveBoundaryChunks + 1) : 0;
        chunkMergeTimer.stop();
        chunkMergeTimer.setInitialDelay(getChunkMergeMs());
        chunkMergeTimer.restart();
    }

    private int getPendingMergeCodePointCount() {
        synchronized (pendingMergeChunks) {
            return countCodePoints(mergeChunkTexts(pendingMergeChunks));
        }
    }

    private boolean shouldForceFlushMergeByLength() {
        synchronized (pendingMergeChunks) {
            int chunkCount = pendingMergeChunks.size();
            if (chunkCount < MERGE_FORCE_FLUSH_MIN_CHUNKS) return false;
            String merged = mergeChunkTexts(pendingMergeChunks);
            int mergedCp = countCodePoints(merged);
            if (mergedCp < MERGE_FORCE_FLUSH_CODEPOINTS) return false;
            if (mergedCp >= MERGE_FORCE_FLUSH_HARD_CODEPOINTS) return true;
            return hasTailFlushBoundary(merged, MERGE_FORCE_FLUSH_LOOKBACK_CODEPOINTS);
        }
    }

    private int countCodePoints(String text) {
        return (text == null || text.isEmpty()) ? 0 : text.codePointCount(0, text.length());
    }

    private boolean hasTailFlushBoundary(String text, int lookbackCodePoints) {
        if (text == null || text.isBlank()) return false;
        int totalCp = text.codePointCount(0, text.length());
        int startCp = Math.max(0, totalCp - Math.max(8, lookbackCodePoints));
        int startIndex = text.offsetByCodePoints(0, startCp);
        for (int i = startIndex; i < text.length();) {
            int cp = Character.codePointAt(text, i);
            if (isFlushBoundaryCodePoint(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private boolean isFlushBoundaryCodePoint(int cp) {
        return Character.isWhitespace(cp)
                || cp == '。'
                || cp == '！'
                || cp == '？'
                || cp == '.'
                || cp == '!'
                || cp == '?'
                || cp == '…'
                || cp == ','
                || cp == '，'
                || cp == '、'
                || cp == ';'
                || cp == '；'
                || cp == ':'
                || cp == '：';
    }

    private void clearMergeBuffer() {
        synchronized (pendingMergeChunks) {
            pendingMergeChunks.clear();
            pendingMergeStartedAtMs = 0L;
        }
        consecutiveBoundaryChunks = 0;
    }

    private void flushMergeBuffer() {
        java.util.ArrayList<PendingMergeChunk> chunks;
        synchronized (pendingMergeChunks) {
            if (pendingMergeChunks.isEmpty()) return;
            chunks = new java.util.ArrayList<>(pendingMergeChunks);
            pendingMergeChunks.clear();
            pendingMergeStartedAtMs = 0L;
        }
        consecutiveBoundaryChunks = 0;
        if (!host.isHearingTranslateEnabled()) return;
        String merged = mergeChunkTexts(chunks);
        if (merged.isBlank()) return;
        HearingHistoryItem mergedItem = mergePendingHistoryItems(chunks, merged);
        refreshOverlayCaption();
        Config.log("[Hearing][Merge] flush: " + merged);
        requestAsyncHearingTranslation(mergedItem);
    }

    private String mergeChunkTexts(java.util.List<PendingMergeChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        String prev = "";
        for (PendingMergeChunk chunk : chunks) {
            if (chunk == null || chunk.sourceText == null) continue;
            String text = chunk.sourceText.trim();
            if (text.isEmpty()) continue;
            if (sb.length() > 0 && needsSpaceBetween(prev, text)) {
                sb.append(' ');
            }
            sb.append(text);
            prev = text;
        }
        return sb.toString().trim();
    }

    private boolean needsSpaceBetween(String prev, String next) {
        if (prev == null || prev.isEmpty() || next == null || next.isEmpty()) return false;
        char last = prev.charAt(prev.length() - 1);
        char first = next.charAt(0);
        return Character.isLetterOrDigit(last) && Character.isLetterOrDigit(first)
                && last < 128 && first < 128;
    }

    private boolean looksLikeMergeBoundary(String text) {
        if (text == null) return false;
        String s = text.trim();
        if (s.isEmpty()) return false;
        return s.endsWith("。")
                || s.endsWith("！")
                || s.endsWith("？")
                || s.endsWith(".")
                || s.endsWith("!")
                || s.endsWith("?")
                || s.endsWith("…");
    }

    private HearingHistoryItem mergePendingHistoryItems(java.util.List<PendingMergeChunk> chunks, String mergedSource) {
        if (chunks == null || chunks.isEmpty() || mergedSource == null || mergedSource.isBlank()) return null;
        java.util.LinkedHashSet<Long> mergeIds = new java.util.LinkedHashSet<>();
        for (PendingMergeChunk chunk : chunks) {
            if (chunk != null) mergeIds.add(chunk.sourceAtMs);
        }
        if (mergeIds.isEmpty()) return null;

        java.util.ArrayList<HearingHistoryItem> items = new java.util.ArrayList<>(partialHistory);
        java.util.ArrayList<HearingHistoryItem> rebuilt = new java.util.ArrayList<>(items.size());
        HearingHistoryItem mergedItem = null;
        for (HearingHistoryItem item : items) {
            if (item != null && mergeIds.contains(item.sourceAtMs)) {
                if (mergedItem == null) {
                    mergedItem = new HearingHistoryItem(mergedSource, item.sourceAtMs, null, 0L);
                    rebuilt.add(mergedItem);
                }
                continue;
            }
            rebuilt.add(item);
        }
        if (mergedItem == null) {
            mergedItem = new HearingHistoryItem(mergedSource, System.currentTimeMillis(), null, 0L);
            partialHistory.addLast(mergedItem);
        } else {
            partialHistory.clear();
            for (HearingHistoryItem item : rebuilt) partialHistory.addLast(item);
        }
        synchronized (pendingAutoDetectTranslations) {
            java.util.ArrayDeque<PendingAutoDetectTranslation> rewritten = new java.util.ArrayDeque<>();
            boolean mergedQueued = false;
            while (!pendingAutoDetectTranslations.isEmpty()) {
                PendingAutoDetectTranslation pending = pendingAutoDetectTranslations.pollFirst();
                if (pending == null) continue;
                if (mergeIds.contains(pending.sourceAtMs)) {
                    if (!mergedQueued && mergedItem != null) {
                        rewritten.addLast(new PendingAutoDetectTranslation(
                                mergedItem.sourceAtMs,
                                mergedItem.sourceText,
                                pending.queuedAtMs
                        ));
                        mergedQueued = true;
                    }
                    continue;
                }
                rewritten.addLast(pending);
            }
            pendingAutoDetectTranslations.addAll(rewritten);
        }
        trimPartialHistory();
        return mergedItem;
    }

    private void trimPartialHistory() {
        pruneExpiredTranslatedCaptions();
        int maxSize = prefs.getInt("hearing.overlay.history_size", 6);
        while (partialHistory.size() > maxSize) {
            partialHistory.pollFirst();
        }
    }

    private int getTranslatedKeepMs() {
        return Math.max(1000, prefs.getInt("hearing.overlay.translated_keep_ms", DEFAULT_TRANSLATED_KEEP_MS));
    }

    private boolean pruneExpiredTranslatedCaptions() {
        if (partialHistory.isEmpty()) return false;
        long now = System.currentTimeMillis();
        int keepMs = getTranslatedKeepMs();
        boolean removed = false;
        for (HearingHistoryItem item : partialHistory) {
            if (item != null && item.isTranslated() && now - item.translatedAtMs >= keepMs) {
                item.translatedText = null;
                item.translatedAtMs = 0L;
                removed = true;
            }
        }
        return removed;
    }

    private void cleanupExpiredTranslatedCaptions() {
        if (!pruneExpiredTranslatedCaptions()) return;
        refreshOverlayCaption();
    }

    private void noteHearingMainBusySkip() {
        long now = System.currentTimeMillis();
        if (hearingMainBusySinceMs <= 0L) {
            hearingMainBusySinceMs = now;
            hearingMainBusySkipCount = 0;
        }
        hearingMainBusySkipCount++;
        if (hearingMainBusySkipCount == 1 || hearingMainBusySkipCount % 3 == 0) {
            Config.log("[Hearing][REC] main busy -> skip hearing chunk LOOPBACK"
                    + " (skipCount=" + hearingMainBusySkipCount
                    + " busyMs=" + Math.max(0L, now - hearingMainBusySinceMs) + ")");
        }
    }

    private void clearHearingMainBusySkipIfNeeded() {
        if (hearingMainBusySinceMs <= 0L) return;
        long now = System.currentTimeMillis();
        Config.log("[Hearing][REC] main busy cleared"
                + " (skipCount=" + hearingMainBusySkipCount
                + " busyMs=" + Math.max(0L, now - hearingMainBusySinceMs) + ")");
        hearingMainBusySinceMs = 0L;
        hearingMainBusySkipCount = 0;
        hearingMainBusyBypassLogMs = 0L;
    }

    private boolean shouldBypassHearingMainBusy() {
        if (hearingMainBusySinceMs <= 0L) return false;
        long now = System.currentTimeMillis();
        long busyMs = Math.max(0L, now - hearingMainBusySinceMs);
        if (busyMs < HEARING_MAIN_BUSY_BYPASS_MS) return false;
        if (hearingMainBusyBypassLogMs == 0L || (now - hearingMainBusyBypassLogMs) >= 5000L) {
            hearingMainBusyBypassLogMs = now;
            Config.log("[Hearing][REC] main busy bypass -> continue hearing chunk LOOPBACK"
                    + " (skipCount=" + hearingMainBusySkipCount
                    + " busyMs=" + busyMs + ")");
        }
        return true;
    }

    private java.util.List<HearingHistoryItem> snapshotHistoryItems() {
        pruneExpiredTranslatedCaptions();
        return new java.util.ArrayList<>(partialHistory);
    }

    private java.util.List<RecentTranslatedDisplay> snapshotRecentTranslatedDisplays() {
        long now = System.currentTimeMillis();
        int keepMs = getTranslatedKeepMs();
        synchronized (recentTranslatedDisplays) {
            recentTranslatedDisplays.removeIf(entry -> entry == null
                    || entry.translatedText == null
                    || entry.translatedText.isBlank()
                    || entry.translatedAtMs <= 0L
                    || (now - entry.translatedAtMs) >= keepMs);
            return new java.util.ArrayList<>(recentTranslatedDisplays);
        }
    }

    private void refreshOverlayCaption() {
        if (overlay == null) return;
        java.util.List<HearingHistoryItem> snapshot = snapshotHistoryItems();
        java.util.List<RecentTranslatedDisplay> translatedSnapshot = snapshotRecentTranslatedDisplays();
        overlay.setEntries(snapshot, translatedSnapshot, running ? "Listening..." : "");

        int translatedCount = 0;
        String lastSource = "";
        for (HearingHistoryItem item : snapshot) {
            if (item == null) continue;
            if (item.isTranslated()) translatedCount++;
            if (item.sourceText != null && !item.sourceText.isBlank()) {
                lastSource = item.sourceText;
            }
        }

        boolean waitingTranslation = running && !snapshot.isEmpty() && translatedCount == 0;
        if (waitingTranslation) {
            long now = System.currentTimeMillis();
            if (waitingTranslateSinceMs <= 0L) {
                waitingTranslateSinceMs = now;
                lastWaitingTranslateLogMs = 0L;
                Config.log("[Hearing][State] waiting translation started: entries=" + snapshot.size()
                        + " mergePending=" + getPendingMergeChunkCount()
                        + " busy=" + host.isHearingTranslateBusy()
                        + " mode=" + host.getHearingTranslateQueueMode()
                        + " last=" + lastSource);
            } else if ((now - lastWaitingTranslateLogMs) >= 5000L) {
                lastWaitingTranslateLogMs = now;
                Config.log("[Hearing][State] still waiting translation: elapsed=" + (now - waitingTranslateSinceMs)
                        + "ms entries=" + snapshot.size()
                        + " mergePending=" + getPendingMergeChunkCount()
                        + " busy=" + host.isHearingTranslateBusy()
                        + " active=" + host.getHearingTranslateActiveText()
                        + " last=" + lastSource);
            }
        } else if (waitingTranslateSinceMs > 0L) {
            long elapsed = System.currentTimeMillis() - waitingTranslateSinceMs;
            Config.log("[Hearing][State] waiting translation cleared: elapsed=" + elapsed
                    + "ms translatedCount=" + translatedCount
                    + " entries=" + snapshot.size());
            waitingTranslateSinceMs = 0L;
            lastWaitingTranslateLogMs = 0L;
        }
    }

    private String getOverlayAutoLangStatusText() {
        if (!isHearingAutoDetectMode()) return "";
        synchronized (autoDetectProbeBuffer) {
            String stable = (stableAutoDetectLang == null) ? "" : stableAutoDetectLang.trim().toLowerCase(java.util.Locale.ROOT);
            String best = findBestAutoDetectVoteLang();
            int bestVotes = countAutoDetectVotes(best);
            if (stable.isBlank() && best.isBlank()) return "AUTO";
            String voteSuffix = compactVoteSuffix(bestVotes);
            if (stable.isBlank()) return compactAutoLangLabel(best) + "?" + voteSuffix;
            if (!best.isBlank() && !best.equals(stable)) {
                return compactAutoLangLabel(stable) + ">" + compactAutoLangLabel(best) + voteSuffix;
            }
            return compactAutoLangLabel(stable) + voteSuffix;
        }
    }

    private String getOverlayAutoLangStatusTooltip() {
        if (!isHearingAutoDetectMode()) return "";
        synchronized (autoDetectProbeBuffer) {
            String stable = (stableAutoDetectLang == null || stableAutoDetectLang.isBlank()) ? "-" : stableAutoDetectLang;
            String best = findBestAutoDetectVoteLang();
            if (best == null || best.isBlank()) best = "-";
            return "Auto lang: stable=" + stable + " vote=" + best + " " + buildAutoDetectVoteSummary();
        }
    }

    private String getOverlayActivityStatusText() {
        if (!running) return "";
        if (host.isHearingTranslateBusy() || autoDetectBackfillRunning || waitingTranslateSinceMs > 0L) {
            return "TR";
        }
        return "REC";
    }

    private String getOverlayActivityStatusTooltip() {
        if (!running) return "";
        if (host.isHearingTranslateBusy() || autoDetectBackfillRunning || waitingTranslateSinceMs > 0L) {
            return "Translating";
        }
        return "Listening";
    }

    private String compactAutoLangLabel(String lang) {
        if (lang == null || lang.isBlank()) return "AUTO";
        String normalized = lang.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "en" -> "EN";
            case "ja" -> "JA";
            case "zh" -> "ZH";
            case "ko" -> "KO";
            default -> normalized.toUpperCase(java.util.Locale.ROOT);
        };
    }

    private String compactVoteSuffix(int votes) {
        if (votes <= 0) return "";
        return String.valueOf(Math.min(9, votes));
    }

    private int getPendingMergeChunkCount() {
        synchronized (pendingMergeChunks) {
            return pendingMergeChunks.size();
        }
    }
    private void submitHearingChunk(byte[] pcm16k16mono) {

        if (host.isHearingBlockedByTts()) {
            clearHearingMainBusySkipIfNeeded();
            Config.logDebug("[Hearing][REC] skip chunk (" + (host.isTtsSpeaking() ? "TTS active" : "TTS cooldown") + ")");
            return;
        }

        // ★メインがtranscribe中は、Hearing側でWhisperは叩かない（競合回避）
        // 字幕汚染を避けるため、Talk 側 global partial は Hearing へ流用しない
        if (host.isTranscribing()) {
            noteHearingMainBusySkip();
            if (!shouldBypassHearingMainBusy()) {
                return;
            }
        }
        clearHearingMainBusySkipIfNeeded();

        byte[] filteredChunk = AudioPrefilter.processForAsr(
                pcm16k16mono,
                pcm16k16mono.length,
                16000,
                AudioPrefilter.Mode.HEARING,
                MobMateWhisp.getHearingAudioPrefilterMode(),
                hearingPrefilterState
        );

        AudioPrefilter.VoiceMetrics voiceMetrics = AudioPrefilter.analyzeVoiceLike(pcm16k16mono, pcm16k16mono.length, 16000);
        boolean voiceLike;
        double floorSnapshot;
        synchronized (hearingVoiceGateLock) {
            boolean recentVoiceHold = hearingVoiceHoldChunks > 0;
            voiceLike = AudioPrefilter.isVoiceLike(voiceMetrics, hearingNoiseFloorRms, recentVoiceHold);
            if (voiceLike) {
                if (hearingGateRejectStreak >= 3) {
                    Config.log(String.format(
                            java.util.Locale.ROOT,
                            "[Hearing][Gate] recovered after %d rejects rms=%.1f zcr=%.3f vbr=%.3f floor=%.1f peak=%d",
                            hearingGateRejectStreak,
                            voiceMetrics.rms,
                            voiceMetrics.zcr,
                            voiceMetrics.voiceBandRatio,
                            hearingNoiseFloorRms,
                            voiceMetrics.peak
                    ));
                }
                hearingGateRejectStreak = 0;
                hearingVoiceHoldChunks = HEARING_VOICE_HOLD_MAX_CHUNKS;
                gentlyRecoverHearingNoiseFloor(voiceMetrics.rms);
            } else {
                hearingGateRejectStreak++;
                if (hearingVoiceHoldChunks > 0) hearingVoiceHoldChunks--;
                updateHearingNoiseFloor(voiceMetrics);
                if (hearingGateRejectStreak == 1 || (hearingGateRejectStreak % 5) == 0) {
                    Config.log(String.format(
                            java.util.Locale.ROOT,
                            "[Hearing][Gate] reject streak=%d rms=%.1f zcr=%.3f vbr=%.3f floor=%.1f peak=%d hold=%d",
                            hearingGateRejectStreak,
                            voiceMetrics.rms,
                            voiceMetrics.zcr,
                            voiceMetrics.voiceBandRatio,
                            hearingNoiseFloorRms,
                            voiceMetrics.peak,
                            hearingVoiceHoldChunks
                    ));
                }
            }
            floorSnapshot = hearingNoiseFloorRms;
        }
        if (!voiceLike) {
            Config.logDebug(String.format(
                    java.util.Locale.ROOT,
                    "[Hearing][REC] skip chunk (gate) rms=%.1f zcr=%.3f vbr=%.3f floor=%.1f peak=%d",
                    voiceMetrics.rms, voiceMetrics.zcr, voiceMetrics.voiceBandRatio, floorSnapshot, voiceMetrics.peak
            ));
            return;
        }
        filteredChunk = AudioPrefilter.normalizeFinalChunkForAsr(filteredChunk, filteredChunk.length);

        String lastPartial;
        try {
            lastPartial = host.transcribeHearingRaw(filteredChunk);
        } catch (Exception ex) {
            Config.log("[Hearing][REC] transcribeHearingRaw failed (" + "LOOPBACK" + "): " + ex);
            return;
        }

        if (!running) return;

        if (lastPartial != null && !lastPartial.isEmpty()) {
            if (host.looksLikeTtsEcho(lastPartial)) {
                Config.logDebug("[Hearing][REC] suppress TTS echo: " + lastPartial);
                return;
            }
            if (shouldSuppressShortRepeatedCaption(lastPartial)) {
                return;
            }
            HearingHistoryItem pushed = pushPartialHistory(lastPartial);
            refreshOverlayCaption();
            enqueueForMergedTranslation(pushed);
        }

        int len = (lastPartial == null) ? 0 : lastPartial.length();
        Config.logDebug("[Hearing][REC] chunk ok (" + "LOOPBACK" + ") partialLen=" + len);
    }

    private void gentlyRecoverHearingNoiseFloor(double speechRms) {
        if (!Double.isFinite(speechRms) || speechRms <= 0.0) return;
        double target = Math.max(80.0, Math.min(HEARING_NOISE_FLOOR_MAX_RMS, speechRms * 0.42));
        if (target < hearingNoiseFloorRms) {
            hearingNoiseFloorRms = (hearingNoiseFloorRms * 0.82) + (target * 0.18);
        }
    }

    private void updateHearingNoiseFloor(AudioPrefilter.VoiceMetrics metrics) {
        if (metrics == null) return;
        double rms = metrics.rms;
        if (!Double.isFinite(rms) || rms <= 0.0) return;
        // speech-like な chunk で floor を押し上げると、しばらくして全部 gate reject しやすいっす。
        boolean likelyNoiseChunk =
                metrics.voiceBandRatio < 0.24
                        || metrics.zcr < 0.012
                        || metrics.zcr > 0.34;
        if (!likelyNoiseChunk) return;
        double clamped = Math.max(40.0, Math.min(HEARING_NOISE_FLOOR_MAX_RMS, rms));
        if (clamped < hearingNoiseFloorRms) {
            hearingNoiseFloorRms = (hearingNoiseFloorRms * 0.7) + (clamped * 0.3);
        } else if (clamped <= hearingNoiseFloorRms * HEARING_NOISE_FLOOR_UPTRACK_RATIO) {
            hearingNoiseFloorRms = (hearingNoiseFloorRms * 0.985) + (clamped * 0.015);
        }
    }




    private void restoreBounds() {
        int x = prefs.getInt("ui.hearing.x", 30);
        int y = prefs.getInt("ui.hearing.y", 30);
        int h = prefs.getInt("ui.hearing.h", getHeight());
        int minW = Math.max(computedWindowWidth(), getPreferredSize().width);
        if (h <= 0) h = getHeight();

        int w = minW;

        setBounds(x, y, w, h);
        Config.log("[Hearing] restoreBounds x=" + x + " y=" + y + " w=" + w + " h=" + h);
    }
    private void saveBounds() {
        Rectangle r = getBounds();
        prefs.putInt("ui.hearing.x", r.x);
        prefs.putInt("ui.hearing.y", r.y);
        prefs.remove("ui.hearing.w");
        prefs.putInt("ui.hearing.h", r.height);
        Config.log("[Hearing] saveBounds x=" + r.x + " y=" + r.y + " w=" + r.width + " h=" + r.height);
    }

    private void ensureOverlayVisible() {
        if (overlay == null) overlay = new HearingOverlayWindow();
        overlay.showWindow();
    }

    private void hideOverlay() {
        if (overlay != null) overlay.hideWindow();
    }

    private void setOverlayText(String s) {
        if (overlay == null) return;
        overlay.setPlaceholder(s);
    }

    private void saveOverlayCustomLocation(Point location) {
        if (location == null) return;
        prefs.put("hearing.overlay.position", OVERLAY_POS_CUSTOM);
        prefs.putInt("hearing.overlay.custom_x", location.x);
        prefs.putInt("hearing.overlay.custom_y", location.y);
        syncPrefsQuietly();
    }

    private class HearingOverlayWindow extends JWindow {
        private final CaptionCardsView cardsView = new CaptionCardsView();
        private final JPanel contentPanel;
        private final JToggleButton lockButton;
        private final JLabel autoLangBadgeLabel;
        private final JLabel activityBadgeLabel;
        private final Timer keepTopTimer;
        private final Timer animationTimer;
        private Point dragStartScreen;
        private Point dragStartWindow;

        HearingOverlayWindow() {
            super();
            setAlwaysOnTop(true);
            setFocusableWindowState(false);
            setBackground(new Color(0, 0, 0, 0));

            contentPanel = new JPanel(new BorderLayout());
            contentPanel.setOpaque(false);
            updateSettings(); // ★初期設定を適用
            contentPanel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

            lockButton = new JToggleButton();
            lockButton.setFocusable(false);
            lockButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            lockButton.setMargin(new Insets(0, 0, 0, 0));
            lockButton.setPreferredSize(new Dimension(20, 18));
            lockButton.setMinimumSize(new Dimension(20, 18));
            lockButton.setMaximumSize(new Dimension(20, 18));
            lockButton.setOpaque(true);
            lockButton.setContentAreaFilled(true);
            lockButton.setBackground(new Color(18, 22, 30, 210));
            lockButton.setForeground(new Color(235, 235, 235));
            lockButton.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 70), 1, true));
            lockButton.addActionListener(e -> {
                setOverlayLocked(lockButton.isSelected());
                updateLockButtonState();
            });

            autoLangBadgeLabel = new JLabel();
            autoLangBadgeLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            autoLangBadgeLabel.setOpaque(true);
            autoLangBadgeLabel.setBackground(new Color(18, 22, 30, 205));
            autoLangBadgeLabel.setForeground(new Color(230, 230, 230));
            autoLangBadgeLabel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(255, 255, 255, 48), 1, true),
                    BorderFactory.createEmptyBorder(0, 5, 0, 5)
            ));
            autoLangBadgeLabel.setVisible(false);

            activityBadgeLabel = new JLabel();
            activityBadgeLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            activityBadgeLabel.setOpaque(true);
            activityBadgeLabel.setBackground(new Color(18, 22, 30, 205));
            activityBadgeLabel.setForeground(new Color(230, 230, 230));
            activityBadgeLabel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(255, 255, 255, 48), 1, true),
                    BorderFactory.createEmptyBorder(0, 5, 0, 5)
            ));
            activityBadgeLabel.setVisible(false);

            cardsView.setPlaceholder("");
            contentPanel.add(cardsView, BorderLayout.CENTER);

            keepTopTimer = new Timer(1500, e -> ensureTopMostNow());
            keepTopTimer.setRepeats(true);
            keepTopTimer.setCoalesce(true);
            animationTimer = new Timer(33, e -> cardsView.repaint());
            animationTimer.setRepeats(true);
            animationTimer.setCoalesce(true);
            setContentPane(contentPanel);
            getLayeredPane().add(lockButton, JLayeredPane.POPUP_LAYER);
            getLayeredPane().add(autoLangBadgeLabel, JLayeredPane.POPUP_LAYER);
            getLayeredPane().add(activityBadgeLabel, JLayeredPane.POPUP_LAYER);
            installDragMove(contentPanel);
            installDragMove(cardsView);
            updateLockButtonState();
            pack();
            layoutLockButton();
        }

        void updateSettings() {
            // 背景色
            String bgColor = prefs.get("hearing.overlay.bg_color", "green");
            Color bg = switch (bgColor) {
                case "blue" -> new Color(30, 50, 90);
                case "gray" -> new Color(40, 40, 40);
                case "red" -> new Color(70, 30, 30);
                default -> new Color(30, 90, 50); // green
            };
            cardsView.setTheme(bg, prefs.getInt("hearing.overlay.font_size", 18));

            // 透明度
            int opacity = prefs.getInt("hearing.overlay.opacity", 72);
            try {
                setOpacity(opacity / 100.0f);
            } catch (Exception ignore) {}

            // 再描画
            if (isVisible()) {
                repackToFit();
            }
        }

        private boolean isOverlayLocked() {
            return prefs.getBoolean("hearing.overlay.locked", false);
        }

        private void setOverlayLocked(boolean locked) {
            prefs.putBoolean("hearing.overlay.locked", locked);
            syncPrefsQuietly();
        }

        private void updateLockButtonState() {
            boolean locked = isOverlayLocked();
            lockButton.setSelected(locked);
            lockButton.setText(locked ? "L" : "M");
            lockButton.setToolTipText(locked ? "Unlock subtitle position" : "Lock subtitle position");
            Color accent = cardsView.translatedTextColor;
            Color base = cardsView.cardFillColor;
            Color border = cardsView.cardBorderColor;
            if (locked) {
                lockButton.setBackground(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 220));
                lockButton.setForeground(new Color(28, 24, 20));
                lockButton.setBorder(BorderFactory.createLineBorder(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 245), 1, true));
            } else {
                lockButton.setBackground(new Color(base.getRed(), base.getGreen(), base.getBlue(), 210));
                lockButton.setForeground(new Color(235, 235, 235));
                lockButton.setBorder(BorderFactory.createLineBorder(border, 1, true));
            }
            updateAutoLangBadge();
            updateActivityBadge();
            layoutLockButton();
            lockButton.repaint();
        }

        private boolean lockButtonOnLeadingSide() {
            String position = prefs.get("hearing.overlay.position", OVERLAY_POS_BOTTOM_LEFT);
            return !OVERLAY_POS_BOTTOM_LEFT.equals(position);
        }

        private void layoutLockButton() {
            if (lockButton == null) return;
            Dimension size = lockButton.getPreferredSize();
            int x = lockButtonOnLeadingSide()
                    ? 4
                    : Math.max(4, getWidth() - size.width - 4);
            lockButton.setBounds(x, 3, size.width, size.height);
            layoutBadges();
        }

        private void updateAutoLangBadge() {
            if (autoLangBadgeLabel == null) return;
            String text = HearingFrame.this.getOverlayAutoLangStatusText();
            boolean show = !text.isBlank();
            autoLangBadgeLabel.setVisible(show);
            if (!show) return;
            autoLangBadgeLabel.setText(text);
            autoLangBadgeLabel.setToolTipText(HearingFrame.this.getOverlayAutoLangStatusTooltip());
            Dimension pref = autoLangBadgeLabel.getPreferredSize();
            int w = Math.max(28, pref.width);
            int h = Math.max(16, pref.height);
            autoLangBadgeLabel.setSize(w, h);
            layoutBadges();
        }

        private void updateActivityBadge() {
            if (activityBadgeLabel == null) return;
            String text = HearingFrame.this.getOverlayActivityStatusText();
            boolean show = !text.isBlank();
            activityBadgeLabel.setVisible(show);
            if (!show) return;
            activityBadgeLabel.setText(text);
            activityBadgeLabel.setToolTipText(HearingFrame.this.getOverlayActivityStatusTooltip());
            if ("TR".equals(text)) {
                Color accent = cardsView.translatedTextColor;
                activityBadgeLabel.setBackground(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 220));
                activityBadgeLabel.setForeground(new Color(30, 24, 20));
            } else {
                activityBadgeLabel.setBackground(new Color(18, 22, 30, 205));
                activityBadgeLabel.setForeground(new Color(230, 230, 230));
            }
            Dimension pref = activityBadgeLabel.getPreferredSize();
            int w = Math.max(28, pref.width);
            int h = Math.max(16, pref.height);
            activityBadgeLabel.setSize(w, h);
            layoutBadges();
        }

        private void layoutBadges() {
            int gap = 4;
            if (lockButtonOnLeadingSide()) {
                int x = 4 + lockButton.getWidth() + gap;
                if (autoLangBadgeLabel != null && autoLangBadgeLabel.isVisible()) {
                    autoLangBadgeLabel.setLocation(x, 4);
                    x += autoLangBadgeLabel.getWidth() + gap;
                }
                if (activityBadgeLabel != null && activityBadgeLabel.isVisible()) {
                    if (x + activityBadgeLabel.getWidth() <= Math.max(40, getWidth() - 4)) {
                        activityBadgeLabel.setLocation(x, 4);
                    } else {
                        activityBadgeLabel.setVisible(false);
                    }
                }
                return;
            }
            int cursor = Math.max(4, getWidth() - lockButton.getWidth() - 4);
            if (activityBadgeLabel != null && activityBadgeLabel.isVisible()) {
                cursor -= gap + activityBadgeLabel.getWidth();
                if (cursor >= 4) {
                    activityBadgeLabel.setLocation(cursor, 4);
                } else {
                    activityBadgeLabel.setVisible(false);
                    cursor = Math.max(4, getWidth() - lockButton.getWidth() - 4);
                }
            }
            if (autoLangBadgeLabel != null && autoLangBadgeLabel.isVisible()) {
                cursor -= gap + autoLangBadgeLabel.getWidth();
                autoLangBadgeLabel.setLocation(Math.max(4, cursor), 4);
            }
        }

        private GraphicsConfiguration getTargetGraphicsConfiguration() {
            int displayIndex = prefs.getInt("hearing.overlay.display", 0);
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice[] screens = ge.getScreenDevices();
            if (displayIndex >= 0 && displayIndex < screens.length) {
                return screens[displayIndex].getDefaultConfiguration();
            }
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration();
        }

        private Rectangle getTargetScreenVisualBounds() {
            GraphicsConfiguration gc = getTargetGraphicsConfiguration();
            Rectangle bounds = gc.getBounds();
            Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            return new Rectangle(
                    bounds.x + screenInsets.left,
                    bounds.y + screenInsets.top,
                    Math.max(120, bounds.width - screenInsets.left - screenInsets.right),
                    Math.max(120, bounds.height - screenInsets.top - screenInsets.bottom)
            );
        }

        private Point clampWindowLocationToScreen(int x, int y, Rectangle screenBounds) {
            if (screenBounds == null) return new Point(x, y);
            int maxX = screenBounds.x + Math.max(0, screenBounds.width - getWidth());
            int maxY = screenBounds.y + Math.max(0, screenBounds.height - getHeight());
            int clampedX = Math.max(screenBounds.x, Math.min(maxX, x));
            int clampedY = Math.max(screenBounds.y, Math.min(maxY, y));
            return new Point(clampedX, clampedY);
        }

        void updatePosition() {
            String position = prefs.get("hearing.overlay.position", "bottom_left");
            Rectangle screenBounds = getTargetScreenVisualBounds();
            int margin = 14;

            if (OVERLAY_POS_CUSTOM.equals(position)) {
                int savedX = prefs.getInt("hearing.overlay.custom_x", Integer.MIN_VALUE);
                int savedY = prefs.getInt("hearing.overlay.custom_y", Integer.MIN_VALUE);
                if (savedX != Integer.MIN_VALUE && savedY != Integer.MIN_VALUE) {
                    Point clamped = clampWindowLocationToScreen(savedX, savedY, screenBounds);
                    setLocation(clamped);
                    return;
                }
                position = OVERLAY_POS_BOTTOM_LEFT;
            }
            int x, y;

            y = switch (position) {
                case OVERLAY_POS_BOTTOM_RIGHT -> {
                    x = screenBounds.x + screenBounds.width - getWidth() - margin;
                    yield screenBounds.y + screenBounds.height - getHeight() - margin - 60;
                }
                case OVERLAY_POS_TOP_CENTER -> {
                    x = screenBounds.x + (screenBounds.width - getWidth()) / 2;
                    yield screenBounds.y + margin;
                }
                default -> {
                    x = screenBounds.x + margin;
                    yield screenBounds.y + screenBounds.height - getHeight() - margin - 60;
                }
            };
            Point clamped = clampWindowLocationToScreen(x, Math.max(screenBounds.y + margin, y), screenBounds);
            setLocation(clamped);
        }

        void setPlaceholder(String s) {
            if (!SwingUtilities.isEventDispatchThread()) {
                final String t = s;
                SwingUtilities.invokeLater(() -> setPlaceholder(t));
                return;
            }
            updateLockButtonState();
            cardsView.setPlaceholder(s);
            repackToFit();
        }

        void setEntries(java.util.List<HearingHistoryItem> items,
                        java.util.List<RecentTranslatedDisplay> translatedItems,
                        String emptyStateText) {
            if (!SwingUtilities.isEventDispatchThread()) {
                java.util.ArrayList<HearingHistoryItem> snapshot = new java.util.ArrayList<>(items);
                java.util.ArrayList<RecentTranslatedDisplay> translatedSnapshot =
                        (translatedItems == null) ? new java.util.ArrayList<>() : new java.util.ArrayList<>(translatedItems);
                SwingUtilities.invokeLater(() -> setEntries(snapshot, translatedSnapshot, emptyStateText));
                return;
            }
            updateLockButtonState();
            cardsView.setEntries(items, translatedItems, emptyStateText, getTranslatedKeepMs());
            repackToFit();
        }

        private void repackToFit() {
            Rectangle screenBounds = getTargetScreenVisualBounds();
            String flow = prefs.get("hearing.overlay.flow", OVERLAY_FLOW_VERTICAL_UP);
            boolean horizontal = OVERLAY_FLOW_HORIZONTAL_LEFT.equals(flow) || OVERLAY_FLOW_HORIZONTAL_RIGHT.equals(flow);
            int maxWindowW = Math.max(340, (int) (screenBounds.width * (horizontal ? 1.0 : 0.42)));
            int maxCardW = Math.max(280, maxWindowW - 20);
            cardsView.setMaxCardWidth(maxCardW);
            cardsView.revalidate();
            contentPanel.revalidate();
            pack();
            Dimension cardsPref = cardsView.getPreferredSize();
            Insets insets = contentPanel.getInsets();
            int insetW = (insets == null) ? 0 : (insets.left + insets.right);
            int desiredW = Math.max(getWidth(), cardsPref.width + insetW + (cardsView.isShowingPlaceholderCard() ? 18 : 8));
            int desiredH = Math.max(getHeight(), cardsPref.height + ((insets == null) ? 0 : (insets.top + insets.bottom)));
            setSize(Math.min(desiredW, maxWindowW),
                    Math.min(desiredH, Math.max(100, (int) (screenBounds.height * 0.72))));
            layoutLockButton();
            updatePosition();
            ensureTopMostNow();
        }

        private String fitLinesToWidth(String s, FontMetrics fm, int maxPx) {
            if (s == null || s.isBlank()) return "";
            String[] lines = s.split("\\n", -1);
            StringBuilder sb = new StringBuilder(s.length() + 8);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) sb.append('\n');
                sb.append(ellipsizeLine(lines[i], fm, maxPx));
            }
            return sb.toString();
        }

        private String ellipsizeLine(String s, FontMetrics fm, int maxPx) {
            if (s == null) return "";
            if (fm.stringWidth(s) <= maxPx) return s;

            final String ell = "…";
            int lo = 0, hi = s.length();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                String t = s.substring(0, mid) + ell;
                if (fm.stringWidth(t) <= maxPx) lo = mid + 1;
                else hi = mid;
            }
            int cut = Math.max(0, lo - 1);
            return s.substring(0, cut) + ell;
        }

        void showWindow() {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(this::showWindow);
                return;
            }
            repackToFit();
            setVisible(true);
            ensureTopMostNow();
            if (!keepTopTimer.isRunning()) keepTopTimer.start();
            if (!animationTimer.isRunning()) animationTimer.start();
        }

        void hideWindow() {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(this::hideWindow);
                return;
            }
            keepTopTimer.stop();
            animationTimer.stop();
            if (OVERLAY_POS_CUSTOM.equals(prefs.get("hearing.overlay.position", OVERLAY_POS_BOTTOM_LEFT))) {
                saveOverlayCustomLocation(getLocation());
            }
            setVisible(false);
        }

        private void ensureTopMostNow() {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(this::ensureTopMostNow);
                return;
            }
            if (!isVisible()) return;
            try {
                if (isAlwaysOnTop()) {
                    setAlwaysOnTop(false);
                }
                setAlwaysOnTop(true);
                toFront();
                repaint();
            } catch (Exception ignore) {}
        }

        private void installDragMove(Component c) {
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (isOverlayLocked()) return;
                    dragStartScreen = e.getLocationOnScreen();
                    dragStartWindow = getLocation();
                }

                @Override public void mouseDragged(MouseEvent e) {
                    if (isOverlayLocked()) return;
                    if (dragStartScreen == null || dragStartWindow == null) return;
                    Point now = e.getLocationOnScreen();
                    int dx = now.x - dragStartScreen.x;
                    int dy = now.y - dragStartScreen.y;
                    Point next = new Point(dragStartWindow.x + dx, dragStartWindow.y + dy);
                    setLocation(next);
                }

                @Override public void mouseReleased(MouseEvent e) {
                    if (isOverlayLocked()) {
                        dragStartScreen = null;
                        dragStartWindow = null;
                        return;
                    }
                    saveOverlayCustomLocation(getLocation());
                    dragStartScreen = null;
                    dragStartWindow = null;
                }
            };
            c.addMouseListener(ma);
            c.addMouseMotionListener(ma);
        }

    }

    private static final class CaptionCardSnapshot {
        final String sourceText;
        final long sourceAtMs;
        final String translatedText;
        final long translatedAtMs;
        final boolean carriedTranslation;

        CaptionCardSnapshot(String sourceText, long sourceAtMs, String translatedText, long translatedAtMs) {
            this(sourceText, sourceAtMs, translatedText, translatedAtMs, false);
        }

        CaptionCardSnapshot(String sourceText, long sourceAtMs, String translatedText, long translatedAtMs, boolean carriedTranslation) {
            this.sourceText = sourceText;
            this.sourceAtMs = sourceAtMs;
            this.translatedText = translatedText;
            this.translatedAtMs = translatedAtMs;
            this.carriedTranslation = carriedTranslation;
        }

        boolean isTranslated() {
            return translatedText != null && !translatedText.isBlank() && translatedAtMs > 0L;
        }
    }

    private static final class CardLayoutInfo {
        java.util.List<String> sourceLines = java.util.Collections.emptyList();
        java.util.List<String> translatedLines = java.util.Collections.emptyList();
        Font sourceRenderFont;
        Font translatedRenderFont;
        float alpha = 1f;
        float translatedAlpha = 1f;
        float sourceBaseAlpha = 1f;
        float translatedBaseAlpha = 1f;
        long sourceAtMs;
        long translatedAtMs;
        boolean translated;
        int width;
        int height;
    }

    private static final class FixedLongCardSnapshot {
        final java.util.List<String> lines;
        final long latestAtMs;

        FixedLongCardSnapshot(java.util.List<String> lines, long latestAtMs) {
            this.lines = (lines == null) ? java.util.Collections.emptyList() : java.util.List.copyOf(lines);
            this.latestAtMs = latestAtMs;
        }
    }

    private static final class FixedLongCardLineRender {
        final String text;
        final Font font;
        final Color color;
        final int gapBefore;

        FixedLongCardLineRender(String text, Font font, Color color, int gapBefore) {
            this.text = text;
            this.font = font;
            this.color = color;
            this.gapBefore = gapBefore;
        }
    }

    private final class CaptionCardsView extends JComponent {
        private final java.util.List<CaptionCardSnapshot> entries = new java.util.ArrayList<>();
        private String placeholder = "";
        private int translatedKeepMs = DEFAULT_TRANSLATED_KEEP_MS;
        private int maxCardWidth = 520;
        private Color overlayBaseColor = new Color(30, 90, 50);
        private Color sourceTextColor = new Color(240, 240, 240);
        private Color translatedTextColor = new Color(245, 200, 140);
        private Color mutedTextColor = new Color(230, 230, 230, 210);
        private Color cardFillColor = new Color(14, 19, 28, 194);
        private Color cardBorderColor = new Color(255, 255, 255, 64);
        private Color sourceStripeColor = new Color(255, 255, 255, 46);
        private Color translatedStripeColor = new Color(245, 200, 140, 150);
        private Color cardShadowColor = new Color(0, 0, 0, 92);
        private Font sourceFont = new Font("Dialog", Font.PLAIN, 14);
        private Font translatedFont = new Font("Dialog", Font.BOLD, 18);
        private Font placeholderFont = new Font("Dialog", Font.BOLD, 16);
        private final java.util.HashMap<Long, Long> horizontalFadeStartAtMs = new java.util.HashMap<>();
        private long stickyCarrySourceAtMs = -1L;
        private long stickyCarryPrevSourceAtMs = -1L;
        private long stickyCarryUntilMs = 0L;
        private long stickyCarryTranslatedAtMs = 0L;
        private String stickyCarryTranslationText = null;
        private FixedLongCardSnapshot fixedLongCard = null;

        void setTheme(Color bg, int fontSize) {
            overlayBaseColor = (bg == null) ? new Color(30, 90, 50) : bg;
            int translatedSize = Math.max(16, fontSize);
            sourceFont = withZeroTracking(new Font("Dialog", Font.PLAIN, Math.max(12, translatedSize - 4)));
            translatedFont = withZeroTracking(new Font("Dialog", Font.BOLD, translatedSize));
            placeholderFont = withZeroTracking(new Font("Dialog", Font.BOLD, Math.max(14, translatedSize - 1)));

            sourceTextColor = blendColors(overlayBaseColor, Color.WHITE, 0.78f, 238);
            mutedTextColor = blendColors(overlayBaseColor, Color.WHITE, 0.66f, 205);
            translatedTextColor = buildAccentColor(overlayBaseColor);
            cardFillColor = blendColors(overlayBaseColor, Color.BLACK, 0.50f, 192);
            cardBorderColor = blendColors(translatedTextColor, Color.WHITE, 0.16f, 116);
            sourceStripeColor = blendColors(overlayBaseColor, Color.WHITE, 0.32f, 42);
            translatedStripeColor = blendColors(translatedTextColor, Color.WHITE, 0.10f, 150);
            repaint();
        }

        void setMaxCardWidth(int width) {
            maxCardWidth = Math.max(280, width);
        }

        void setPlaceholder(String s) {
            placeholder = (s == null) ? "" : s.replace("\r", "").trim();
            entries.clear();
            fixedLongCard = null;
            clearStickyCarry();
            revalidate();
            repaint();
        }

        private Font withZeroTracking(Font font) {
            if (font == null) return null;
            java.util.Map<java.text.AttributedCharacterIterator.Attribute, Object> attrs = new java.util.HashMap<>(font.getAttributes());
            attrs.put(TextAttribute.TRACKING, 0f);
            return font.deriveFont(attrs);
        }

        private boolean prefersCompactOverlayFont(String text) {
            if (text == null || text.isBlank()) return false;
            int latinLike = 0;
            int cjkLike = 0;
            int cpCount = 0;
            for (int i = 0; i < text.length();) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if (Character.isWhitespace(cp)) continue;
                cpCount++;
                Character.UnicodeScript script = Character.UnicodeScript.of(cp);
                if (script == Character.UnicodeScript.LATIN || script == Character.UnicodeScript.HANGUL) {
                    latinLike++;
                } else if (script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA
                        || script == Character.UnicodeScript.HAN) {
                    cjkLike++;
                }
            }
            if (cpCount < 18) return false;
            return latinLike > cjkLike;
        }

        private Font fitFontToWidth(Font baseFont, java.util.List<String> lines, int maxWidthPx, double minScale, boolean preferCompact) {
            if (baseFont == null || lines == null || lines.isEmpty()) return baseFont;
            float baseSize = baseFont.getSize2D();
            float startSize = preferCompact ? Math.max(baseSize * 0.94f, baseSize - 1f) : baseSize;
            Font candidate = withZeroTracking(baseFont.deriveFont(startSize));
            float minSize = (float) Math.max(11.0, baseSize * minScale);
            while (candidate.getSize2D() > minSize) {
                FontMetrics fm = getFontMetrics(candidate);
                if (maxMeasuredWidth(lines, fm) <= maxWidthPx) {
                    return candidate;
                }
                candidate = withZeroTracking(baseFont.deriveFont(candidate.getSize2D() - 1f));
            }
            return candidate;
        }

        void setEntries(java.util.List<HearingHistoryItem> items,
                        java.util.List<RecentTranslatedDisplay> translatedItems,
                        String emptyStateText,
                        int keepMs) {
            translatedKeepMs = Math.max(1000, keepMs);
            entries.clear();
            fixedLongCard = buildFixedLongCardSnapshot(translatedItems);
            if (items != null) {
                entries.addAll(buildDisplaySnapshots(items));
            }
            if (entries.isEmpty()) clearStickyCarry();
            placeholder = entries.isEmpty() ? ((emptyStateText == null) ? "" : emptyStateText.trim()) : "";
            revalidate();
            repaint();
        }

        private java.util.List<CaptionCardSnapshot> buildDisplaySnapshots(java.util.List<HearingHistoryItem> items) {
            java.util.List<CaptionCardSnapshot> snapshots = new java.util.ArrayList<>(items.size());
            CaptionCardSnapshot pending = null;
            long pendingLastSourceAtMs = 0L;

            for (HearingHistoryItem item : items) {
                if (item == null || item.sourceText == null || item.sourceText.isBlank()) continue;
                CaptionCardSnapshot current = new CaptionCardSnapshot(item.sourceText, item.sourceAtMs, item.translatedText, item.translatedAtMs);
                if (pending != null && canCollapseDisplaySnapshot(pending, pendingLastSourceAtMs, current)) {
                    pending = new CaptionCardSnapshot(
                            mergeDisplaySourceTexts(pending.sourceText, current.sourceText),
                            pending.sourceAtMs,
                            null,
                            0L
                    );
                    pendingLastSourceAtMs = current.sourceAtMs;
                    continue;
                }
                if (pending != null) snapshots.add(pending);
                pending = current;
                pendingLastSourceAtMs = current.sourceAtMs;
            }
            if (pending != null) snapshots.add(pending);
            applyActiveTranslationCarry(snapshots, System.currentTimeMillis());
            applyStickyTranslationCarry(snapshots, System.currentTimeMillis());
            snapshots = applyAutoTwoLineFocus(snapshots);
            return snapshots;
        }

        private void clearStickyCarry() {
            stickyCarrySourceAtMs = -1L;
            stickyCarryPrevSourceAtMs = -1L;
            stickyCarryUntilMs = 0L;
            stickyCarryTranslatedAtMs = 0L;
            stickyCarryTranslationText = null;
        }

        private void applyStickyTranslationCarry(java.util.List<CaptionCardSnapshot> snapshots, long nowMs) {
            if (snapshots == null || snapshots.size() < 2) {
                clearStickyCarry();
                return;
            }
            if (!HearingFrame.this.isHearingAutoDetectMode()) {
                clearStickyCarry();
                return;
            }
            for (CaptionCardSnapshot entry : snapshots) {
                if (entry != null && !entry.isTranslated()) {
                    clearStickyCarry();
                    return;
                }
            }

            int latestIndex = snapshots.size() - 1;
            CaptionCardSnapshot latest = snapshots.get(latestIndex);
            if (latest == null || !latest.isTranslated() || latest.carriedTranslation) {
                clearStickyCarry();
                return;
            }

            int previousTranslatedIndex = -1;
            for (int i = latestIndex - 1; i >= 0; i--) {
                CaptionCardSnapshot entry = snapshots.get(i);
                if (entry != null && entry.isTranslated()) {
                    previousTranslatedIndex = i;
                    break;
                }
            }
            if (previousTranslatedIndex < 0) {
                clearStickyCarry();
                return;
            }

            CaptionCardSnapshot previous = snapshots.get(previousTranslatedIndex);
            if (previous == null || !previous.isTranslated()) {
                clearStickyCarry();
                return;
            }
            if ((latest.sourceAtMs - previous.sourceAtMs) > DISPLAY_ACTIVE_TRANSLATION_SOURCE_GAP_MS) {
                clearStickyCarry();
                return;
            }

            boolean stickyChanged = stickyCarrySourceAtMs != latest.sourceAtMs
                    || stickyCarryPrevSourceAtMs != previous.sourceAtMs;
            if (stickyChanged) {
                stickyCarrySourceAtMs = latest.sourceAtMs;
                stickyCarryPrevSourceAtMs = previous.sourceAtMs;
                stickyCarryUntilMs = nowMs + DISPLAY_STICKY_CARRY_MS;
                stickyCarryTranslatedAtMs = previous.translatedAtMs;
                stickyCarryTranslationText = previous.translatedText;
                Config.logDebug("[Hearing][Display] sticky carry start: latest=" + latest.sourceText
                        + " prev=" + previous.sourceText);
            }

            if (stickyCarryTranslationText == null || stickyCarryTranslationText.isBlank()) {
                clearStickyCarry();
                return;
            }
            if (nowMs > stickyCarryUntilMs) {
                clearStickyCarry();
                return;
            }

            snapshots.set(latestIndex, new CaptionCardSnapshot(
                    latest.sourceText,
                    latest.sourceAtMs,
                    stickyCarryTranslationText,
                    stickyCarryTranslatedAtMs,
                    true
            ));
        }

        private java.util.List<CaptionCardSnapshot> applyAutoTwoLineFocus(java.util.List<CaptionCardSnapshot> snapshots) {
            if (snapshots == null || snapshots.isEmpty()) return snapshots;
            if (!HearingFrame.this.isHearingAutoDetectMode()) return snapshots;
            int limit = getAutoSourceDisplayCardLimit();
            CaptionCardSnapshot latest = snapshots.get(snapshots.size() - 1);
            if (latest != null && latest.carriedTranslation) {
                return tailSnapshots(snapshots, limit);
            }
            int activeIndex = -1;
            for (int i = snapshots.size() - 1; i >= 0; i--) {
                CaptionCardSnapshot entry = snapshots.get(i);
                if (entry != null && !entry.isTranslated()) {
                    activeIndex = i;
                    break;
                }
            }
            if (activeIndex >= 0) {
                return tailSnapshots(snapshots, limit);
            }
            return tailSnapshots(snapshots, limit);
        }

        private int getAutoSourceDisplayCardLimit() {
            return Math.max(1, HearingFrame.this.getOverlayHistorySize());
        }

        private java.util.List<CaptionCardSnapshot> tailSnapshots(java.util.List<CaptionCardSnapshot> snapshots, int limit) {
            if (snapshots == null || snapshots.size() <= limit) return snapshots;
            int from = Math.max(0, snapshots.size() - Math.max(1, limit));
            return new java.util.ArrayList<>(snapshots.subList(from, snapshots.size()));
        }

        private void applyActiveTranslationCarry(java.util.List<CaptionCardSnapshot> snapshots, long nowMs) {
            if (snapshots == null || snapshots.size() < 2) return;
            int activeIndex = -1;
            for (int i = snapshots.size() - 1; i >= 0; i--) {
                CaptionCardSnapshot entry = snapshots.get(i);
                if (entry != null && !entry.isTranslated()) {
                    activeIndex = i;
                    break;
                }
            }
            if (activeIndex <= 0) return;

            int translatedIndex = -1;
            for (int i = activeIndex - 1; i >= 0; i--) {
                CaptionCardSnapshot entry = snapshots.get(i);
                if (entry != null && entry.isTranslated()) {
                    translatedIndex = i;
                    break;
                }
            }
            if (translatedIndex < 0) return;

            CaptionCardSnapshot active = snapshots.get(activeIndex);
            CaptionCardSnapshot lastTranslated = snapshots.get(translatedIndex);
            if (active == null || lastTranslated == null || !lastTranslated.isTranslated()) return;
            if (active.sourceAtMs < lastTranslated.sourceAtMs) return;

            long translatedAge = Math.max(0L, nowMs - lastTranslated.translatedAtMs);
            if (translatedAge > Math.max(translatedKeepMs, (int) DISPLAY_ACTIVE_TRANSLATION_HOLD_MS)) return;
            if ((active.sourceAtMs - lastTranslated.sourceAtMs) > DISPLAY_ACTIVE_TRANSLATION_SOURCE_GAP_MS) return;

            snapshots.set(activeIndex, new CaptionCardSnapshot(
                    active.sourceText,
                    active.sourceAtMs,
                    lastTranslated.translatedText,
                    lastTranslated.translatedAtMs,
                    true
            ));

            if (translatedIndex == activeIndex - 1) {
                snapshots.remove(translatedIndex);
            }
        }

        private boolean canCollapseDisplaySnapshot(CaptionCardSnapshot pending, long pendingLastSourceAtMs, CaptionCardSnapshot current) {
            if (pending == null || current == null) return false;
            if (pending.isTranslated() || current.isTranslated()) return false;
            long gap = Math.max(0L, current.sourceAtMs - pendingLastSourceAtMs);
            if (gap > DISPLAY_COLLAPSE_GAP_MS) return false;
            int mergedCp = codePointCount(mergeDisplaySourceTexts(pending.sourceText, current.sourceText));
            return mergedCp <= DISPLAY_COLLAPSE_MAX_CP;
        }

        private String mergeDisplaySourceTexts(String left, String right) {
            String a = (left == null) ? "" : left.trim();
            String b = (right == null) ? "" : right.trim();
            if (a.isEmpty()) return b;
            if (b.isEmpty()) return a;
            return HearingFrame.this.needsSpaceBetween(a, b) ? (a + " " + b) : (a + b);
        }

        private int codePointCount(String text) {
            return (text == null) ? 0 : text.codePointCount(0, text.length());
        }

        boolean isShowingPlaceholderCard() {
            return entries.isEmpty() && !placeholder.isBlank();
        }

        @Override public Dimension getPreferredSize() {
            if (entries.isEmpty()) {
                FontMetrics fm = getFontMetrics(placeholderFont);
                String text = placeholder.isBlank() ? "Listening..." : placeholder;
                int maxWidth = Math.max(240, maxCardWidth);
                java.util.List<String> lines = wrapText(text, fm, Math.max(180, maxWidth - 40));
                int preferredWidth = Math.min(maxWidth, Math.max(196, maxMeasuredWidth(lines, fm) + 48));
                int preferredHeight = 26 + (Math.max(1, lines.size()) * fm.getHeight()) + 18;
                return new Dimension(preferredWidth, preferredHeight);
            }

            java.util.List<CardLayoutInfo> layouts = buildLayouts(System.currentTimeMillis(), false, true);
            String flow = getOverlayFlow();
            FixedLongCardSnapshot fixedCard = fixedLongCard;
            if (isHorizontalFlow(flow)) {
                int totalWidth = 0;
                int maxHeight = 0;
                for (CardLayoutInfo info : layouts) {
                    totalWidth += info.width;
                    maxHeight = Math.max(maxHeight, info.height);
                }
                totalWidth += Math.max(0, layouts.size() - 1) * 10;
                int preferredWidth = totalWidth + 8;
                int preferredHeight = maxHeight + 8;
                if (fixedCard != null) {
                    Dimension fixedBarSize = measureFixedLongCard(fixedCard, Math.max(220, maxCardWidth));
                    preferredWidth = Math.max(preferredWidth, fixedBarSize.width + 8);
                    preferredHeight += 8 + fixedBarSize.height;
                }
                return new Dimension(preferredWidth, preferredHeight);
            }

            int width = 8;
            int height = 8;
            for (CardLayoutInfo info : layouts) {
                width = Math.max(width, info.width + 8);
                height += info.height + 10;
            }
            if (fixedCard != null) {
                Dimension fixedBarSize = measureFixedLongCard(fixedCard, Math.max(220, maxCardWidth));
                width = Math.max(width, fixedBarSize.width + 8);
                height += 8 + fixedBarSize.height;
            }
            return new Dimension(width, height + 6);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                if (entries.isEmpty()) {
                    if (!placeholder.isBlank()) {
                        paintPlaceholderCard(g2, 4, 4, Math.max(180, Math.min(getPreferredSize().width - 8, getWidth() - 8)), placeholder);
                    }
                    return;
                }

                String flow = getOverlayFlow();
                long nowMs = System.currentTimeMillis();
                java.util.List<CardLayoutInfo> layouts = orderLayoutsForFlow(buildLayouts(nowMs, !isHorizontalFlow(flow), true), flow);
                FixedLongCardSnapshot fixedCard = fixedLongCard;
                Dimension fixedBarSize = (fixedCard == null) ? null : measureFixedLongCard(fixedCard, Math.max(220, getWidth() - 8));

                if (isHorizontalFlow(flow)) {
                    applyHorizontalExitCountdown(layouts, nowMs);
                    int totalWidth = 0;
                    int maxHeight = 0;
                    for (CardLayoutInfo info : layouts) {
                        totalWidth += info.width;
                        maxHeight = Math.max(maxHeight, info.height);
                    }
                    totalWidth += Math.max(0, layouts.size() - 1) * 10;
                    int x = OVERLAY_FLOW_HORIZONTAL_LEFT.equals(flow)
                            ? Math.max(4, getWidth() - totalWidth - 4)
                            : 4;
                    int y = 4;
                    for (CardLayoutInfo layout : layouts) {
                        paintCaptionCard(g2, x, y, layout.width, layout);
                        x += layout.width + 10;
                    }
                    if (fixedCard != null && fixedBarSize != null) {
                        paintFixedLongCard(g2, 4, y + maxHeight + 8, fixedBarSize.width, fixedCard, nowMs);
                    }
                    return;
                }

                int totalHeight = 0;
                for (CardLayoutInfo layout : layouts) totalHeight += layout.height;
                totalHeight += Math.max(0, layouts.size() - 1) * 10;
                int reservedBottom = (fixedBarSize == null) ? 0 : (fixedBarSize.height + 8);
                int y = OVERLAY_FLOW_VERTICAL_UP.equals(flow)
                        ? Math.max(4, getHeight() - totalHeight - reservedBottom - 4)
                        : 4;
                for (CardLayoutInfo layout : layouts) {
                    paintCaptionCard(g2, 4, y, layout.width, layout);
                    y += layout.height + 10;
                }
                if (fixedCard != null && fixedBarSize != null) {
                    paintFixedLongCard(g2, 4, Math.max(4, getHeight() - fixedBarSize.height - 4), fixedBarSize.width, fixedCard, nowMs);
                }
            } finally {
                g2.dispose();
            }
        }

        private java.util.List<CardLayoutInfo> buildLayouts(long nowMs, boolean applyTranslatedAgeFade, boolean sourceOnly) {
            java.util.ArrayList<CardLayoutInfo> layouts = new java.util.ArrayList<>(entries.size());
            int contentWidth = Math.max(180, maxCardWidth - 44);

            for (CaptionCardSnapshot entry : entries) {
                CardLayoutInfo info = new CardLayoutInfo();
                info.sourceAtMs = entry.sourceAtMs;
                info.translatedAtMs = entry.translatedAtMs;
                info.translated = entry.isTranslated();
                String sourceText = "「" + entry.sourceText + "」";
                info.sourceRenderFont = fitFontToWidth(
                        sourceFont,
                        java.util.List.of(sourceText),
                        contentWidth,
                        prefersCompactOverlayFont(entry.sourceText) ? 0.78 : 0.86,
                        prefersCompactOverlayFont(entry.sourceText)
                );
                FontMetrics sourceFm = getFontMetrics(info.sourceRenderFont);
                info.sourceLines = wrapText(sourceText, sourceFm, contentWidth);
                float sourceFadeIn = Math.min(1f, Math.max(0.16f, (nowMs - entry.sourceAtMs) / (float) CARD_FADE_IN_MS));
                info.alpha = sourceFadeIn;
                info.translatedAlpha = sourceFadeIn;
                info.sourceBaseAlpha = sourceFadeIn;
                info.translatedBaseAlpha = sourceFadeIn;
                info.translatedRenderFont = translatedFont;

                if (entry.isTranslated()) {
                    boolean compactTranslated = prefersCompactOverlayFont(entry.translatedText);
                    info.translatedRenderFont = fitFontToWidth(
                            translatedFont,
                            java.util.List.of(entry.translatedText),
                            contentWidth,
                            compactTranslated ? 0.74 : 0.84,
                            compactTranslated
                    );
                    FontMetrics translatedFm = getFontMetrics(info.translatedRenderFont);
                    info.translatedLines = wrapText(entry.translatedText, translatedFm, contentWidth);
                    float translatedFadeIn = Math.min(1f, Math.max(0.12f, (nowMs - entry.translatedAtMs) / (float) CARD_FADE_IN_MS));
                    info.translatedAlpha = Math.min(1f, sourceFadeIn * translatedFadeIn);
                    info.translatedBaseAlpha = info.translatedAlpha;

                    if (applyTranslatedAgeFade) {
                        long age = Math.max(0L, nowMs - entry.translatedAtMs);
                        if (age >= translatedKeepMs) {
                            info.alpha = 0f;
                            info.translatedAlpha = 0f;
                        } else if (age >= Math.max(0, translatedKeepMs - TRANSLATED_FADE_MS)) {
                            float fadeRatio = (translatedKeepMs - age) / (float) Math.max(1, TRANSLATED_FADE_MS);
                            float smooth = Math.max(0.10f, Math.min(1f, fadeRatio));
                            info.alpha *= smooth;
                            info.translatedAlpha *= smooth;
                        }
                    }
                }
                if (sourceOnly) {
                    info.translatedLines = java.util.Collections.emptyList();
                }

                FontMetrics translatedFm = getFontMetrics(info.translatedRenderFont);
                int maxLineWidth = Math.max(maxMeasuredWidth(info.sourceLines, sourceFm), maxMeasuredWidth(info.translatedLines, translatedFm));
                info.width = Math.max(180, Math.min(maxCardWidth, maxLineWidth + 40));

                int sourceLineStep = lineStep(sourceFm);
                int translatedLineStep = lineStep(translatedFm);
                int sourceHeight = sourceLineStep * Math.max(1, info.sourceLines.size());
                int translatedHeight = info.translatedLines.isEmpty() ? 0 : 6 + (translatedLineStep * info.translatedLines.size());
                info.height = 14 + sourceHeight + translatedHeight + 14;
                layouts.add(info);
            }
            return layouts;
        }

        private FixedLongCardSnapshot buildFixedLongCardSnapshot(java.util.List<RecentTranslatedDisplay> items) {
            if (items == null || items.isEmpty()) return null;
            int limit = 2;
            java.util.ArrayList<String> lines = new java.util.ArrayList<>(Math.min(limit, items.size()));
            long latestAtMs = 0L;
            for (int i = items.size() - 1; i >= 0 && lines.size() < limit; i--) {
                RecentTranslatedDisplay entry = items.get(i);
                if (entry == null || entry.translatedText == null || entry.translatedText.isBlank()) continue;
                lines.add(0, entry.translatedText);
                latestAtMs = Math.max(latestAtMs, entry.translatedAtMs);
            }
            if (lines.isEmpty()) return null;
            return new FixedLongCardSnapshot(lines, latestAtMs);
        }

        private java.util.List<FixedLongCardLineRender> buildFixedLongCardLineRenders(FixedLongCardSnapshot fixedCard, int widthHint) {
            java.util.ArrayList<FixedLongCardLineRender> renders = new java.util.ArrayList<>();
            if (fixedCard == null || fixedCard.lines.isEmpty()) return renders;
            int maxWidth = Math.max(180, Math.min(maxCardWidth, widthHint));
            int contentWidth = Math.max(140, maxWidth - 40);
            java.util.List<String> lines = fixedCard.lines;
            int total = lines.size();
            for (int i = 0; i < total; i++) {
                String text = lines.get(i);
                boolean olderLine = total >= 2 && i < total - 1;
                Font baseFont = olderLine
                        ? withZeroTracking(translatedFont.deriveFont(Math.max(14f, translatedFont.getSize2D() - 2f)))
                        : translatedFont;
                boolean compact = prefersCompactOverlayFont(text);
                Font fittedFont = fitFontToWidth(
                        baseFont,
                        java.util.List.of(text),
                        contentWidth,
                        olderLine ? (compact ? 0.72 : 0.80) : (compact ? 0.74 : 0.84),
                        compact
                );
                Color lineColor = olderLine
                        ? blendColors(translatedTextColor, Color.WHITE, 0.42f, 196)
                        : translatedTextColor;
                renders.add(new FixedLongCardLineRender(text, fittedFont, lineColor, olderLine ? 0 : 10));
            }
            return renders;
        }

        private Dimension measureFixedLongCard(FixedLongCardSnapshot fixedCard, int widthHint) {
            if (fixedCard == null) return new Dimension(Math.max(180, widthHint), 0);
            int maxWidth = Math.max(180, Math.min(maxCardWidth, widthHint));
            java.util.List<FixedLongCardLineRender> renders = buildFixedLongCardLineRenders(fixedCard, widthHint);
            if (renders.isEmpty()) return new Dimension(maxWidth, 0);
            int preferredWidth = 180;
            int preferredHeight = 14;
            boolean first = true;
            for (FixedLongCardLineRender render : renders) {
                FontMetrics fm = getFontMetrics(render.font);
                preferredWidth = Math.max(preferredWidth, fm.stringWidth(render.text) + 40);
                if (!first) preferredHeight += render.gapBefore;
                preferredHeight += lineStep(fm);
                first = false;
            }
            preferredHeight += 14;
            return new Dimension(Math.min(preferredWidth, maxWidth), preferredHeight);
        }

        private void paintFixedLongCard(Graphics2D g2, int x, int y, int w, FixedLongCardSnapshot fixedCard, long nowMs) {
            if (fixedCard == null) return;
            Graphics2D cg = (Graphics2D) g2.create();
            try {
                java.util.List<FixedLongCardLineRender> renders = buildFixedLongCardLineRenders(fixedCard, w);
                if (renders.isEmpty()) return;
                int h = 14;
                boolean first = true;
                for (FixedLongCardLineRender render : renders) {
                    FontMetrics fm = getFontMetrics(render.font);
                    if (!first) h += render.gapBefore;
                    h += lineStep(fm);
                    first = false;
                }
                h += 14;
                float textAlpha = 1f;
                long latestAtMs = fixedCard.latestAtMs;
                if (latestAtMs > 0L) {
                    textAlpha = Math.min(1f, Math.max(0.16f, (nowMs - latestAtMs) / (float) CARD_FADE_IN_MS));
                }

                int arc = 18;
                cg.setColor(cardShadowColor);
                cg.fillRoundRect(x + 3, y + 5, w, h, arc, arc);

                GradientPaint gradient = new GradientPaint(
                        x, y,
                        brighten(cardFillColor, 1.02f),
                        x, y + h,
                        darken(cardFillColor, 0.95f)
                );
                cg.setPaint(gradient);
                cg.fillRoundRect(x, y, w, h, arc, arc);
                cg.setColor(cardBorderColor);
                cg.drawRoundRect(x, y, w, h, arc, arc);
                cg.setColor(translatedStripeColor);
                cg.fillRoundRect(x + 8, y + 10, 6, Math.max(18, h - 20), 6, 6);

                Graphics2D tg = (Graphics2D) cg.create();
                try {
                    tg.setComposite(AlphaComposite.SrcOver.derive(textAlpha));
                    int drawY = y + 14;
                    boolean drawFirst = true;
                    for (FixedLongCardLineRender render : renders) {
                        if (!drawFirst) drawY += render.gapBefore;
                        tg.setFont(render.font);
                        FontMetrics fm = getFontMetrics(render.font);
                        drawLines(tg, java.util.List.of(render.text), fm, x + 24, drawY, render.color, new Color(0, 0, 0, 132));
                        drawY += lineStep(fm);
                        drawFirst = false;
                    }
                } finally {
                    tg.dispose();
                }
            } finally {
                cg.dispose();
            }
        }

        private void applyHorizontalExitCountdown(java.util.List<CardLayoutInfo> layouts, long nowMs) {
            if (layouts == null || layouts.isEmpty()) {
                horizontalFadeStartAtMs.clear();
                return;
            }

            java.util.HashSet<Long> visibleTranslated = new java.util.HashSet<>();
            CardLayoutInfo exitCard = null;
            for (CardLayoutInfo info : layouts) {
                if (info == null || !info.translated) continue;
                visibleTranslated.add(info.sourceAtMs);
                if (exitCard == null) exitCard = info;
                info.alpha = info.sourceBaseAlpha;
                info.translatedAlpha = info.translatedBaseAlpha;
            }
            horizontalFadeStartAtMs.entrySet().removeIf(e -> !visibleTranslated.contains(e.getKey()));
            if (exitCard == null) return;

            long fadeStartAt = horizontalFadeStartAtMs.computeIfAbsent(exitCard.sourceAtMs, k -> nowMs);
            long age = Math.max(0L, nowMs - fadeStartAt);
            if (age >= translatedKeepMs) {
                exitCard.alpha = 0f;
                exitCard.translatedAlpha = 0f;
                return;
            }
            if (age >= Math.max(0, translatedKeepMs - TRANSLATED_FADE_MS)) {
                float fadeRatio = (translatedKeepMs - age) / (float) Math.max(1, TRANSLATED_FADE_MS);
                float smooth = Math.max(0.10f, Math.min(1f, fadeRatio));
                exitCard.alpha = exitCard.sourceBaseAlpha * smooth;
                exitCard.translatedAlpha = exitCard.translatedBaseAlpha * smooth;
            }
        }

        private void paintCaptionCard(Graphics2D g2, int x, int y, int w, CardLayoutInfo layout) {
            if (layout.alpha <= 0f) return;
            Graphics2D cg = (Graphics2D) g2.create();
            try {
                cg.setComposite(AlphaComposite.SrcOver.derive(layout.alpha));
                int arc = 18;

                cg.setColor(cardShadowColor);
                cg.fillRoundRect(x + 3, y + 5, w, layout.height, arc, arc);

                GradientPaint gradient = new GradientPaint(
                        x, y,
                        brighten(cardFillColor, 1.08f),
                        x, y + layout.height,
                        darken(cardFillColor, 0.90f)
                );
                cg.setPaint(gradient);
                cg.fillRoundRect(x, y, w, layout.height, arc, arc);

                cg.setColor(cardBorderColor);
                cg.drawRoundRect(x, y, w, layout.height, arc, arc);

                Color stripe = layout.translatedLines.isEmpty() ? sourceStripeColor : translatedStripeColor;
                cg.setColor(stripe);
                cg.fillRoundRect(x + 8, y + 10, 6, Math.max(18, layout.height - 20), 6, 6);

                int textX = x + 24;
                int drawY = y + 14;

                Font sourceRenderFont = (layout.sourceRenderFont == null) ? sourceFont : layout.sourceRenderFont;
                cg.setFont(sourceRenderFont);
                FontMetrics sourceFm = getFontMetrics(sourceRenderFont);
                drawLines(cg, layout.sourceLines, sourceFm, textX, drawY, sourceTextColor, new Color(0, 0, 0, 120));
                drawY += lineStep(sourceFm) * Math.max(1, layout.sourceLines.size());

                if (!layout.translatedLines.isEmpty()) {
                    drawY += 6;
                    Font translatedRenderFont = (layout.translatedRenderFont == null) ? translatedFont : layout.translatedRenderFont;
                    cg.setFont(translatedRenderFont);
                    Graphics2D tg = (Graphics2D) cg.create();
                    try {
                        tg.setComposite(AlphaComposite.SrcOver.derive(layout.translatedAlpha));
                        drawLines(tg, layout.translatedLines, getFontMetrics(translatedRenderFont), textX, drawY, translatedTextColor, new Color(0, 0, 0, 132));
                    } finally {
                        tg.dispose();
                    }
                }
            } finally {
                cg.dispose();
            }
        }

        private void paintPlaceholderCard(Graphics2D g2, int x, int y, int w, String text) {
            FontMetrics fm = getFontMetrics(placeholderFont);
            java.util.List<String> lines = wrapText(text, fm, w - 36);
            int h = 18 + (fm.getHeight() * Math.max(1, lines.size())) + 18;
            int arc = 18;

            g2.setColor(new Color(0, 0, 0, 72));
            g2.fillRoundRect(x + 3, y + 5, w, h, arc, arc);
            g2.setColor(blendColors(overlayBaseColor, Color.BLACK, 0.52f, 148));
            g2.fillRoundRect(x, y, w, h, arc, arc);
            g2.setColor(new Color(255, 255, 255, 44));
            g2.drawRoundRect(x, y, w, h, arc, arc);

            g2.setFont(placeholderFont);
            drawLines(g2, lines, fm, x + 18, y + 14, mutedTextColor, new Color(0, 0, 0, 112));
        }

        private void drawLines(Graphics2D g2, java.util.List<String> lines, FontMetrics fm, int x, int y, Color fill, Color shadow) {
            int baseline = y + fm.getAscent();
            for (String line : lines) {
                g2.setColor(shadow);
                g2.drawString(line, x, baseline + 1);
                g2.setColor(fill);
                g2.drawString(line, x, baseline);
                baseline += lineStep(fm);
            }
        }

        private int lineStep(FontMetrics fm) {
            return Math.max(fm.getAscent() + fm.getDescent(), Math.round(fm.getHeight() * OVERLAY_TEXT_LINE_HEIGHT));
        }

        private java.util.List<String> wrapText(String text, FontMetrics fm, int maxWidthPx) {
            java.util.ArrayList<String> lines = new java.util.ArrayList<>();
            if (text == null || text.isBlank()) {
                lines.add("");
                return lines;
            }
            for (String paragraph : text.replace("\r", "").split("\\n", -1)) {
                String remain = paragraph.trim();
                if (remain.isEmpty()) {
                    lines.add("");
                    continue;
                }
                StringBuilder current = new StringBuilder();
                int index = 0;
                while (index < remain.length()) {
                    int cp = remain.codePointAt(index);
                    String ch = new String(Character.toChars(cp));
                    String candidate = current + ch;
                    if (current.length() > 0 && fm.stringWidth(candidate) > maxWidthPx) {
                        lines.add(current.toString());
                        current.setLength(0);
                    } else {
                        current.append(ch);
                        index += Character.charCount(cp);
                    }
                }
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                }
            }
            return lines;
        }

        private int maxMeasuredWidth(java.util.List<String> lines, FontMetrics fm) {
            int max = 0;
            for (String line : lines) {
                max = Math.max(max, fm.stringWidth(line));
            }
            return max;
        }

        private String getOverlayFlow() {
            return prefs.get("hearing.overlay.flow", OVERLAY_FLOW_VERTICAL_UP);
        }

        private boolean isHorizontalFlow(String flow) {
            return OVERLAY_FLOW_HORIZONTAL_LEFT.equals(flow) || OVERLAY_FLOW_HORIZONTAL_RIGHT.equals(flow);
        }

        private java.util.List<CardLayoutInfo> orderLayoutsForFlow(java.util.List<CardLayoutInfo> layouts, String flow) {
            if (OVERLAY_FLOW_VERTICAL_DOWN.equals(flow) || OVERLAY_FLOW_HORIZONTAL_LEFT.equals(flow)) {
                java.util.ArrayList<CardLayoutInfo> reversed = new java.util.ArrayList<>(layouts);
                java.util.Collections.reverse(reversed);
                return reversed;
            }
            return layouts;
        }

        private Color buildAccentColor(Color base) {
            float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
            float accentHue = (hsb[0] + 0.44f) % 1.0f;
            float accentSat = Math.max(0.28f, Math.min(0.52f, hsb[1] + 0.16f));
            float accentBri = Math.max(0.84f, Math.min(0.96f, hsb[2] + 0.34f));
            Color accent = Color.getHSBColor(accentHue, accentSat, accentBri);
            return new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 248);
        }

        private Color blendColors(Color a, Color b, float bWeight, int alpha) {
            float aWeight = 1.0f - bWeight;
            int r = Math.round((a.getRed() * aWeight) + (b.getRed() * bWeight));
            int g = Math.round((a.getGreen() * aWeight) + (b.getGreen() * bWeight));
            int bl = Math.round((a.getBlue() * aWeight) + (b.getBlue() * bWeight));
            return new Color(Math.max(0, Math.min(255, r)),
                    Math.max(0, Math.min(255, g)),
                    Math.max(0, Math.min(255, bl)),
                    Math.max(0, Math.min(255, alpha)));
        }

        private Color brighten(Color color, float factor) {
            return new Color(
                    Math.min(255, Math.round(color.getRed() * factor)),
                    Math.min(255, Math.round(color.getGreen() * factor)),
                    Math.min(255, Math.round(color.getBlue() * factor)),
                    color.getAlpha()
            );
        }

        private Color darken(Color color, float factor) {
            return new Color(
                    Math.max(0, Math.round(color.getRed() * factor)),
                    Math.max(0, Math.round(color.getGreen() * factor)),
                    Math.max(0, Math.round(color.getBlue() * factor)),
                    color.getAlpha()
            );
        }
    }

    private void startWasapiLoopbackProc() {
        stopMonitor(false);

        // ★ helper exe 優先（同階層に置いた想定）
        File helperExe = new File("./MobMateLoopbackPcm.exe");
        File ps1 = new File("./wasapi_loopback.ps1");

        boolean useExe = helperExe.exists();
        if (!useExe && !ps1.exists()) {
            Config.log("[Hearing] WASAPI helper not found: " + helperExe.getAbsolutePath() + " / " + ps1.getAbsolutePath());
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    this,
                    "WASAPIループバック用の helper が見つかりません。\n"
                            + helperExe.getAbsolutePath() + "\n"
                            + ps1.getAbsolutePath(),
                    "Hearing (WASAPI)",
                    JOptionPane.WARNING_MESSAGE
            ));
            SwingUtilities.invokeLater(() -> {
                if (toggle != null) {
                    ignoreToggleEvent = true;
                    try { toggle.setSelected(false); toggle.setText("Hearing: OFF"); }
                    finally { ignoreToggleEvent = false; }
                }
            });
            return;
        }

        intentionalStop = false;  // ★新規開始時にリセット
        running = true;

        // UIで選ばれてる表示名（LoopbackトークンでもOK）
        Object sel = (outputCombo != null) ? outputCombo.getSelectedItem() : null;
        String devName = (sel != null) ? sel.toString() : "";

        try {
            ProcessBuilder pb;

            if (useExe) {
                // exe側は「無指定ならautopick」前提。もしexeが -Device を受けるなら渡してもOK。
                // Loopbackトークンはexe側でautopickさせるので渡さない。
                if (devName != null && !devName.isEmpty() && !LOOPBACK_TOKEN.equals(devName)) {
                    pb = new ProcessBuilder(
                            helperExe.getAbsolutePath(),
                            "-Device", devName
                    );
                } else {
                    pb = new ProcessBuilder(helperExe.getAbsolutePath());
                }
                Config.logDebug("[Hearing][WASAPI] start helper exe: " + helperExe.getAbsolutePath());
            } else {
                String pwsh = "powershell";
                pb = new ProcessBuilder(
                        pwsh,
                        "-NoProfile",
                        "-ExecutionPolicy", "Bypass",
                        "-File", ps1.getAbsolutePath(),
                        "-Device", devName
                );
                Config.logDebug("[Hearing][WASAPI] start ps1: " + ps1.getAbsolutePath());
            }

            pb.redirectErrorStream(false); // ★stdout=データ(PEAK/PCM), stderr=ログ
            stopWasapiProc();
            loopProc = pb.start();

            // stderr吸い上げ
            loopErrThread = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(loopProc.getErrorStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = br.readLine()) != null) {
                        Config.log("[Hearing][WASAPI] " + l);
                    }
                } catch (Exception ignore) {}
            }, "hearing-wasapi-stderr");
            loopErrThread.setDaemon(true);
            loopErrThread.start();

        } catch (Exception ex) {
            Config.logError("[Hearing] WASAPI helper start failed", ex);
            running = false;
            return;
        }

        final Process p = loopProc;

        loopProcThread = new Thread(() -> {
            long lastPcmArrivedMs = 0;
            int lastPeak = 0;

            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                long lastDbgMs = 0;

                while (running && (line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    // stdoutは「PEAK <int>」 と 「PCM <base64>」 を想定
                    if (line.startsWith("PEAK ")) {
                        int peak;
                        try { peak = Integer.parseInt(line.substring(5).trim()); }
                        catch (Exception ignore) { continue; }
                        lastPeak = peak;

                        final int peakFinal = peak;
                        SwingUtilities.invokeLater(() -> {
                            double db;
                            if (peakFinal <= 0) db = -60.0;
                            else {
                                db = 20.0 * Math.log10(peakFinal / 32768.0);
                                if (db < -60.0) db = -60.0;
                                if (db > 0.0) db = 0.0;
                            }
                            double linearLevel = Math.min(100.0, (peakFinal / 32768.0) * 100.0);
                            double dbLevel = Math.max(0.0, Math.min(100.0, ((db + 60.0) / 60.0) * 100.0));
                            int level = (int) Math.round(Math.max(linearLevel, dbLevel));
                            boolean recentSignal = peakFinal > 120;
                            if (meter != null) meter.setValue(level, db, false, 1f, 1f, recentSignal);
                        });
                        continue;
                    }

                    if (line.startsWith("PCM ")) {
                        String b64 = line.substring(4).trim();
                        if (b64.isEmpty()) continue;

                        byte[] pcm;
                        try {
                            pcm = Base64.getDecoder().decode(b64);
                        } catch (Exception ignore) {
                            continue;
                        }

                        lastPcmArrivedMs = System.currentTimeMillis();

                        if (meter != null) {
                            meter.pushWaveformPcm16le(pcm, pcm.length, 1.0f, false);
                        }

                        // まずは素直に貯める（helper側が 16k/mono/16bit を吐く前提）
                        pcmAcc.write(pcm, 0, pcm.length);

                        // 結合待ち設定に寄せつつ、認識遅延が暴れすぎない範囲で Hearing chunk を決める
                        int triggerBytes = (int) ((16000L * 2L * getRecognitionChunkMs()) / 1000L);

                        if (!transcribing && pcmAcc.size() >= triggerBytes) {
                            transcribing = true;

                            byte[] chunk = pcmAcc.toByteArray();
                            pcmAcc.reset();

                            Config.logDebug("[Hearing][ASR] trigger: bytes=" + chunk.length
                                    + " chunkMs=" + getRecognitionChunkMs()
                                    + " peak=" + lastPeak);

                            new Thread(() -> {
                                long t0 = System.currentTimeMillis();
                                try {
//                                    host.transcribeHearingRaw(chunk);
                                    submitHearingChunk(chunk);

                                } catch (Exception ex) {
                                    Config.logError("[Hearing][ASR] failed", ex);
                                } finally {
                                    transcribing = false;
                                }
                            }, "hearing-whisper").start();
                        }

                        continue;
                    }

                    // それ以外のstdoutは一応デバッグに流す（うるさければコメントアウトでOK）
                    // Config.logDebug("[Hearing][WASAPI][STDOUT] " + line);

                    // 5秒に1回だけ「PCM来てる？」監視ログ
                    long now = System.currentTimeMillis();
                    if (now - lastDbgMs > 10000) {
                        lastDbgMs = now;
                        long age = (lastPcmArrivedMs == 0) ? -1 : (now - lastPcmArrivedMs);
                        Config.logDebug("[Hearing][WASAPI] pcmAgeMs=" + age + " accBytes=" + pcmAcc.size());
                    }
                }
            } catch (Exception ex) {
                if (intentionalStop) {
                    Config.log("[Hearing][WASAPI] loopback process stopped (intentional).");
                } else {
                    // ★crashカウンター（既存の5回リトライコードがあればそのまま活かす）
                    long now = System.currentTimeMillis();
                    if (now - wasapiFirstCrashMs > WASAPI_CRASH_WINDOW_MS) {
                        wasapiCrashCount = 0;
                        wasapiFirstCrashMs = now;
                    }
                    wasapiCrashCount++;

                    if (wasapiCrashCount > WASAPI_MAX_RETRIES) {
                        Config.logError("[Hearing] WASAPI helper crashed " + wasapiCrashCount
                                + " times in " + (WASAPI_CRASH_WINDOW_MS/1000) + "s — giving up",ex);
                    } else {
                        Config.logError("[Hearing] WASAPI helper crashed ("
                                + wasapiCrashCount + "/" + WASAPI_MAX_RETRIES + ")",ex);
                        Config.log("[Hearing][WASAPI] restarting helper in 500ms...");
                        new Thread(() -> {
                            try { Thread.sleep(500); } catch (Exception ignore) {}
                            try {
                                if (running && !intentionalStop) startWasapiLoopbackProc();
                            } catch (Exception ex2) {
                                Config.logError("[Hearing][WASAPI] restart failed", ex2);
                            }
                        }, "hearing-wasapi-restart").start();
                    }
                }
            } finally {
                if (!intentionalStop) running = false;
                try { if (p != null) p.destroyForcibly(); } catch (Exception ignore) {}
                SwingUtilities.invokeLater(() -> {
                    if (meter != null) meter.setValue(0, -60.0, false, 1f, 1f, false);
                });
                try { pcmAcc.reset(); } catch (Exception ignore) {}
                transcribing = false;
            }
        }, "hearing-wasapi-stdout");

        loopProcThread.setDaemon(true);
        loopProcThread.start();
    }

    // ===== Loopback PCM buffer (16kHz mono s16le) =====
    private final Object pcmLock = new Object();
    private final java.util.ArrayDeque<byte[]> pcmChunks = new java.util.ArrayDeque<>();
    private int pcmBytes = 0;
    private static final int PCM_RATE = 16000;
    private static final int PCM_MAX_SEC = 20; // 20秒ぶん保持
    private static final int PCM_MAX_BYTES = PCM_RATE * 2 * PCM_MAX_SEC;

    private void pushLoopbackPcm(byte[] chunk) {
        if (chunk == null || chunk.length == 0) return;
        synchronized (pcmLock) {
            pcmChunks.addLast(chunk);
            pcmBytes += chunk.length;

            // 古いのを捨てて上限維持
            while (pcmBytes > PCM_MAX_BYTES && !pcmChunks.isEmpty()) {
                byte[] old = pcmChunks.removeFirst();
                pcmBytes -= old.length;
            }
        }
    }

    /** Whisper側が呼ぶ想定：最大 ms ぶんをまとめて取り出す（取り出した分は消費） */
    public byte[] drainLoopbackPcmMs(int ms) {
        int needBytes = (int)Math.max(0, (long)ms * PCM_RATE * 2 / 1000L);
        if (needBytes <= 0) needBytes = PCM_RATE * 2 / 10; // 100ms最低
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(needBytes);

        synchronized (pcmLock) {
            while (needBytes > 0 && !pcmChunks.isEmpty()) {
                byte[] c = pcmChunks.peekFirst();
                if (c == null || c.length == 0) { pcmChunks.removeFirst(); continue; }

                if (c.length <= needBytes) {
                    baos.write(c, 0, c.length);
                    pcmChunks.removeFirst();
                    pcmBytes -= c.length;
                    needBytes -= c.length;
                } else {
                    // 部分消費
                    baos.write(c, 0, needBytes);
                    byte[] rest = java.util.Arrays.copyOfRange(c, needBytes, c.length);
                    pcmChunks.removeFirst();
                    pcmChunks.addFirst(rest);
                    pcmBytes -= needBytes;
                    needBytes = 0;
                }
            }
        }
        return baos.toByteArray();
    }
}


