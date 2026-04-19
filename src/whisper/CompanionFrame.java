package whisper;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

public class CompanionFrame extends JFrame {

    private static final String MODE_VOICE = "voice";
    private static final String MODE_SUBTITLE = "subtitle";

    private static final class SituationVisual {
        final String labelKey;
        final Color chipBg;
        final Color chipFg;
        final Color auraColor;
        final boolean avatarAccent;

        SituationVisual(String labelKey, Color chipBg, Color chipFg, Color auraColor, boolean avatarAccent) {
            this.labelKey = labelKey;
            this.chipBg = chipBg;
            this.chipFg = chipFg;
            this.auraColor = auraColor;
            this.avatarAccent = avatarAccent;
        }
    }

    private final Preferences prefs;
    private final MobMateWhisp host;
    private final CompanionBalloonWindow balloonWindow;

    private JToggleButton toggle;
    private JComboBox<ChoiceItem> modeCombo;
    private JComboBox<ChoiceItem> engineCombo;
    private JComboBox<ChoiceItem> toneCombo;
    private JComboBox<ChoiceItem> voiceCombo;
    private JComboBox<String> outputCombo;
    private JComboBox<String> volumeCombo;
    private JComboBox<ChoiceItem> autonomousFrequencyCombo;
    private JComboBox<ChoiceItem> micStyleCombo;
    private JComboBox<ChoiceItem> mmAudioFilterCombo;
    private JCheckBox debugModeCheck;
    private JCheckBox debugCaptureImagesCheck;
    private JLabel voiceLabel;
    private JLabel stateChipLabel;
    private JLabel runtimeChipLabel;
    private JLabel modeChipLabel;
    private JLabel vibeChipLabel;
    private JLabel detailLabel;
    private JLabel statusLabel;
    private JLabel avatarHintLabel;
    private JButton openDebugReportButton;
    private AvatarPanel avatarPanel;
    private String lastDebugReportPath = "";

    private String lastHeard = "";
    private String lastReply = "";
    private String situationWindowTitle = "";
    private String situationMoodDominant = "neutral";
    private String situationEnergyTrend = "stable";
    private String situationUtteranceTrend = "unknown";
    private String situationFatigueLevel = "low";
    private boolean situationExcitementBurst = false;
    private boolean situationLongSilence = false;
    private boolean situationStuckDetected = false;
    private int situationSighCount = 0;
    private long situationObservedAtMs = 0L;

    public CompanionFrame(Preferences prefs, Image icon, MobMateWhisp host) {
        this.prefs = prefs;
        this.host = host;
        this.balloonWindow = new CompanionBalloonWindow(this, prefs);
        if (icon != null) setIconImage(icon);
        setTitle(UiText.t("ui.companion.title"));
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        setResizable(true);
        buildUi();
        restoreBounds();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                setVisible(false);
                host.setCompanionEnabled(false);
                persistVisibleState(false);
                balloonWindow.hideBalloon();
            }
        });
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        avatarPanel = new AvatarPanel();
        avatarPanel.setPreferredSize(new Dimension(248, 248));
        avatarPanel.setMinimumSize(new Dimension(248, 248));
        avatarPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        installDragMove(avatarPanel);

        JPanel controls = new JPanel(new GridBagLayout());
        controls.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 2, 2, 2);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;

        toggle = new JToggleButton();
        setToggleSelected(prefs.getBoolean("companion.enabled", false));
        toggle.addActionListener(e -> {
            setToggleSelected(toggle.isSelected());
            host.setCompanionEnabled(toggle.isSelected());
        });

        modeCombo = new JComboBox<>(new ChoiceItem[] {
                new ChoiceItem(MODE_VOICE, UiText.t("ui.companion.mode.voice")),
                new ChoiceItem(MODE_SUBTITLE, UiText.t("ui.companion.mode.subtitle"))
        });
        modeCombo.addActionListener(e -> {
            ChoiceItem item = (ChoiceItem) modeCombo.getSelectedItem();
            String value = (item == null) ? MODE_VOICE : item.id;
            prefs.put("companion.output.mode", value);
            prefs.putBoolean("companion.tts.enabled", MODE_VOICE.equals(value));
            syncPrefsQuietly();
            refreshControlsEnabledState();
            host.refreshCompanionStatusAsync();
        });

        engineCombo = new JComboBox<>(new ChoiceItem[] {
                new ChoiceItem("windows", UiText.t("ui.companion.engine.windows")),
                new ChoiceItem("voicevox", UiText.t("ui.companion.engine.voicevox")),
                new ChoiceItem("piper_plus", UiText.t("ui.companion.engine.piper_plus")),
                new ChoiceItem("voiceger", UiText.t("ui.companion.engine.voiceger"))
        });
        engineCombo.addActionListener(e -> {
            saveChoice("companion.tts.engine", engineCombo);
            refreshVoiceChoices();
        });

        toneCombo = new JComboBox<>(new ChoiceItem[] {
                new ChoiceItem("desu", UiText.t("ui.companion.tone.desu")),
                new ChoiceItem("yansu", UiText.t("ui.companion.tone.yansu")),
                new ChoiceItem("ssu", UiText.t("ui.companion.tone.ssu")),
                new ChoiceItem("noda", UiText.t("ui.companion.tone.noda")),
                new ChoiceItem("dayomon", UiText.t("ui.companion.tone.dayomon")),
                new ChoiceItem("nanodesu", UiText.t("ui.companion.tone.nanodesu"))
        });
        toneCombo.addActionListener(e -> saveChoice("companion.tone", toneCombo));

        voiceCombo = new JComboBox<>();
        voiceCombo.addActionListener(e -> saveVoiceSelection());

        outputCombo = new JComboBox<>();
        outputCombo.addActionListener(e -> saveString("companion.output.device", outputCombo.getSelectedItem()));

        volumeCombo = new JComboBox<>(buildVolumeChoices());
        volumeCombo.addActionListener(e -> saveString("companion.volume.percent", normalizeVolumeSelection(volumeCombo.getSelectedItem())));

        autonomousFrequencyCombo = new JComboBox<>(new ChoiceItem[] {
                new ChoiceItem("very_low", UiText.t("ui.companion.autonomous.frequency.very_low")),
                new ChoiceItem("low", UiText.t("ui.companion.autonomous.frequency.low")),
                new ChoiceItem("normal", UiText.t("ui.companion.autonomous.frequency.normal")),
                new ChoiceItem("high", UiText.t("ui.companion.autonomous.frequency.high")),
                new ChoiceItem("very_high", UiText.t("ui.companion.autonomous.frequency.very_high"))
        });
        autonomousFrequencyCombo.addActionListener(e -> saveChoice("companion.autonomous.frequency", autonomousFrequencyCombo));

        micStyleCombo = new JComboBox<>(new ChoiceItem[] {
                new ChoiceItem("partner", UiText.t("ui.companion.mic.style.partner")),
                new ChoiceItem("tsukkomi", UiText.t("ui.companion.mic.style.tsukkomi"))
        });
        micStyleCombo.addActionListener(e -> saveChoice("companion.mic.style", micStyleCombo));

        mmAudioFilterCombo = new JComboBox<>(new ChoiceItem[] {
                new ChoiceItem("off", UiText.t("ui.companion.mm.audio.filter.off")),
                new ChoiceItem("normal", UiText.t("ui.companion.mm.audio.filter.normal")),
                new ChoiceItem("strong", UiText.t("ui.companion.mm.audio.filter.strong"))
        });
        mmAudioFilterCombo.addActionListener(e -> saveChoice("companion.mm.audio.prefilter", mmAudioFilterCombo));

        if (host.isInternalCompanionDebugUiEnabled()) {
            debugModeCheck = new JCheckBox(UiText.t("ui.companion.debug.mode"));
            debugModeCheck.setOpaque(false);
            debugModeCheck.addActionListener(e -> {
                boolean enabled = debugModeCheck.isSelected();
                saveBoolean("companion.debug.mode", enabled);
                if (!enabled && debugCaptureImagesCheck != null && debugCaptureImagesCheck.isSelected()) {
                    debugCaptureImagesCheck.setSelected(false);
                    prefs.putBoolean("companion.debug.capture_images", false);
                    syncPrefsQuietly();
                }
            });

            debugCaptureImagesCheck = new JCheckBox(UiText.t("ui.companion.debug.capture_images"));
            debugCaptureImagesCheck.setOpaque(false);
            debugCaptureImagesCheck.addActionListener(e -> {
                boolean enabled = debugModeCheck != null && debugModeCheck.isSelected() && debugCaptureImagesCheck.isSelected();
                saveBoolean("companion.debug.capture_images", enabled);
            });
        }

        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 2;
        controls.add(toggle, gc);

        gc.gridwidth = 1;
        gc.gridy++;
        controls.add(labeled(UiText.t("ui.companion.mode"), modeCombo), gc);
        gc.gridx = 1;
        controls.add(labeled(UiText.t("ui.companion.engine"), engineCombo), gc);

        gc.gridx = 0;
        gc.gridy++;
        controls.add(labeled(UiText.t("ui.companion.tone"), toneCombo), gc);
        gc.gridx = 1;
        voiceLabel = new JLabel(UiText.t("ui.companion.voice"));
        controls.add(labeled(voiceLabel, voiceCombo), gc);

        gc.gridx = 0;
        gc.gridy++;
        controls.add(labeled(UiText.t("ui.companion.output"), outputCombo), gc);
        gc.gridx = 1;
        gc.gridwidth = 1;
        controls.add(labeled(UiText.t("ui.companion.volume"), volumeCombo), gc);

        gc.gridx = 0;
        gc.gridy++;
        controls.add(labeled(UiText.t("ui.companion.autonomous.frequency"), autonomousFrequencyCombo), gc);
        gc.gridx = 1;
        gc.gridwidth = 1;
        controls.add(labeled(UiText.t("ui.companion.mic.style"), micStyleCombo), gc);

        gc.gridx = 0;
        gc.gridy++;
        gc.gridwidth = 2;
        controls.add(labeled(UiText.t("ui.companion.mm.audio.filter"), mmAudioFilterCombo), gc);

        gc.gridx = 0;
        gc.gridy++;
        gc.gridwidth = 2;
        if (host.isInternalCompanionDebugUiEnabled()) {
            JPanel debugPanel = new JPanel(new GridLayout(2, 1, 0, 2));
            debugPanel.setOpaque(false);
            debugPanel.add(debugModeCheck);
            debugPanel.add(debugCaptureImagesCheck);
            controls.add(debugPanel, gc);
        }

        gc.gridx = 0;
        gc.gridy++;
        gc.gridwidth = 2;
        controls.add(buildStatusBar(), gc);

        gc.gridy++;
        avatarHintLabel = new JLabel();
        avatarHintLabel.setFont(avatarHintLabel.getFont().deriveFont(Font.PLAIN, 11f));
        avatarHintLabel.setForeground(new Color(92, 92, 92));
        controls.add(avatarHintLabel, gc);

        root.add(avatarPanel, BorderLayout.CENTER);
        root.add(controls, BorderLayout.SOUTH);
        setContentPane(root);
        setMinimumSize(new Dimension(292, 430));
        pack();
        setSize(Math.max(292, getWidth()), Math.max(430, getHeight()));
    }

    private void installDragMove(JComponent component) {
        MouseAdapter dragger = new MouseAdapter() {
            private Point startScreen;
            private Point startWindow;

            @Override
            public void mousePressed(MouseEvent e) {
                startScreen = e.getLocationOnScreen();
                startWindow = getLocation();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (startScreen == null || startWindow == null) return;
                Point now = e.getLocationOnScreen();
                int dx = now.x - startScreen.x;
                int dy = now.y - startScreen.y;
                setLocation(startWindow.x + dx, startWindow.y + dy);
                balloonWindow.reposition();
            }
        };
        component.addMouseListener(dragger);
        component.addMouseMotionListener(dragger);
    }

    private static JPanel labeled(String label, JComponent comp) {
        return labeled(new JLabel(label), comp);
    }

    private static JPanel labeled(JLabel label, JComponent comp) {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setOpaque(false);
        panel.add(label, BorderLayout.WEST);
        panel.add(comp, BorderLayout.CENTER);
        return panel;
    }

    private void saveString(String key, Object value) {
        String stringValue = value == null ? "" : value.toString();
        prefs.put(key, stringValue);
        syncPrefsQuietly();
        refreshControlsEnabledState();
        host.refreshCompanionStatusAsync();
    }

    private void saveChoice(String key, JComboBox<ChoiceItem> combo) {
        ChoiceItem item = (ChoiceItem) combo.getSelectedItem();
        prefs.put(key, item == null ? "" : item.id);
        syncPrefsQuietly();
        refreshControlsEnabledState();
        host.refreshCompanionStatusAsync();
    }

    private void saveBoolean(String key, boolean value) {
        prefs.putBoolean(key, value);
        syncPrefsQuietly();
        refreshControlsEnabledState();
        host.refreshCompanionStatusAsync();
    }

    private void saveVoiceSelection() {
        ChoiceItem item = (ChoiceItem) voiceCombo.getSelectedItem();
        String value = (item == null || item.id == null || item.id.isBlank()) ? "auto" : item.id;
        String engine = currentEngine();
        switch (engine) {
            case "voicevox" -> prefs.put("companion.voicevox.speaker", value);
            case "piper_plus" -> prefs.put("companion.piper.plus.model_id", value);
            case "voiceger" -> prefs.put("companion.voiceger.lang", value);
            default -> prefs.put("companion.windows.voice", value);
        }
        syncPrefsQuietly();
        refreshControlsEnabledState();
        host.refreshCompanionStatusAsync();
    }

    private void syncPrefsQuietly() {
        try { prefs.sync(); } catch (Exception ignore) {}
    }

    public void refreshChoices() {
        refreshOutputChoices();
        setToggleSelected(prefs.getBoolean("companion.enabled", false));
        selectChoice(modeCombo, prefs.get("companion.output.mode", MODE_VOICE));
        selectChoice(engineCombo, prefs.get("companion.tts.engine", "windows"));
        selectChoice(toneCombo, prefs.get("companion.tone", "desu"));
        volumeCombo.setSelectedItem(normalizeVolumeSelection(prefs.get("companion.volume.percent", "100")));
        selectChoice(autonomousFrequencyCombo, prefs.get("companion.autonomous.frequency", "normal"));
        selectChoice(micStyleCombo, prefs.get("companion.mic.style", "partner"));
        selectChoice(mmAudioFilterCombo, prefs.get("companion.mm.audio.prefilter", "normal"));
        if (debugModeCheck != null) {
            debugModeCheck.setSelected(prefs.getBoolean("companion.debug.mode", false));
        }
        if (debugCaptureImagesCheck != null) {
            debugCaptureImagesCheck.setSelected(prefs.getBoolean("companion.debug.capture_images", false));
        }
        refreshVoiceChoices();
        refreshControlsEnabledState();
        avatarPanel.reloadAvatar();
        avatarHintLabel.setText(UiText.t("ui.companion.avatar.tip") + " " + avatarPanel.getAvatarHint());
        avatarHintLabel.setToolTipText(avatarPanel.getAvatarHint());
        refreshStatusSummary(toggle.isSelected(), null, prefs.get("companion.output.device", "").trim());
    }

    private void refreshControlsEnabledState() {
        String mode = prefs.get("companion.output.mode", MODE_VOICE).trim().toLowerCase(Locale.ROOT);
        boolean voiceEnabled = MODE_VOICE.equals(mode);
        refreshToggleLabel();
        outputCombo.setEnabled(voiceEnabled);
        engineCombo.setEnabled(voiceEnabled);
        voiceCombo.setEnabled(voiceEnabled);
        volumeCombo.setEnabled(voiceEnabled);
        boolean debugEnabled = debugModeCheck != null && debugModeCheck.isSelected();
        if (debugCaptureImagesCheck != null) {
            debugCaptureImagesCheck.setEnabled(debugEnabled);
            if (!debugEnabled && debugCaptureImagesCheck.isSelected()) {
                debugCaptureImagesCheck.setSelected(false);
            }
        }
        if (openDebugReportButton != null) {
            boolean hasReport = lastDebugReportPath != null && !lastDebugReportPath.isBlank();
            openDebugReportButton.setEnabled(debugEnabled && hasReport);
        }
    }

    private void refreshToggleLabel() {
        if (toggle == null) {
            return;
        }
        toggle.setText(toggle.isSelected()
                ? UiText.t("ui.companion.toggle.on")
                : UiText.t("ui.companion.toggle.off"));
    }

    private void setToggleSelected(boolean selected) {
        if (toggle == null) {
            return;
        }
        toggle.setSelected(selected);
        refreshToggleLabel();
    }

    private void refreshVoiceChoices() {
        String engine = currentEngine();
        List<ChoiceItem> items = new ArrayList<>();
        String selectedId = "auto";

        switch (engine) {
            case "voicevox" -> {
                updateVoiceLabel(engine);
                selectedId = normalizedAuto(prefs.get("companion.voicevox.speaker", "auto"));
                items.add(new ChoiceItem("auto", "auto"));
                List<MobMateWhisp.VoiceVoxSpeaker> speakers = host.getVoiceVoxSpeakersForSettings();
                if (speakers == null || speakers.isEmpty()) {
                    if (!"auto".equalsIgnoreCase(selectedId)) {
                        items.add(new ChoiceItem(selectedId, selectedId));
                    }
                } else {
                    for (MobMateWhisp.VoiceVoxSpeaker sp : speakers) {
                        items.add(new ChoiceItem(String.valueOf(sp.id()), sp.id() + ":" + sp.name()));
                    }
                }
            }
            case "piper_plus" -> {
                updateVoiceLabel(engine);
                selectedId = normalizedAuto(prefs.get("companion.piper.plus.model_id", "auto"));
                items.add(new ChoiceItem("auto", "auto"));
                for (PiperPlusCatalog.Entry entry : PiperPlusCatalog.pickerEntries()) {
                    items.add(new ChoiceItem(entry.id(), entry.comboLabel()));
                }
            }
            case "voiceger" -> {
                updateVoiceLabel(engine);
                selectedId = normalizedAuto(prefs.get("companion.voiceger.lang", "auto"));
                items.add(new ChoiceItem("auto", "auto"));
                items.add(new ChoiceItem("all_ja", "Japanese"));
                items.add(new ChoiceItem("en", "English"));
                items.add(new ChoiceItem("all_zh", "Chinese"));
                items.add(new ChoiceItem("all_ko", "Korean"));
                items.add(new ChoiceItem("all_yue", "Cantonese"));
            }
            default -> {
                updateVoiceLabel("windows");
                selectedId = normalizedAuto(prefs.get("companion.windows.voice", "auto"));
                for (String voice : host.getCompanionVoiceChoices()) {
                    items.add(new ChoiceItem(voice, voice));
                }
            }
        }

        applyChoiceItems(voiceCombo, items, selectedId);
    }

    private void updateVoiceLabel(String engine) {
        if (voiceLabel == null) return;
        String text = switch (engine) {
            case "piper_plus" -> UiText.t("ui.companion.voice.model");
            case "voiceger" -> UiText.t("ui.companion.voice.lang");
            default -> UiText.t("ui.companion.voice");
        };
        voiceLabel.setText(text);
    }

    private static void applyChoiceItems(JComboBox<ChoiceItem> combo, List<ChoiceItem> items, String selectedId) {
        DefaultComboBoxModel<ChoiceItem> model = new DefaultComboBoxModel<>();
        for (ChoiceItem item : items) {
            model.addElement(item);
        }
        combo.setModel(model);
        selectChoice(combo, selectedId);
    }

    private static String normalizedAuto(String value) {
        String trimmed = (value == null) ? "" : value.trim();
        return trimmed.isBlank() ? "auto" : trimmed;
    }

    private String currentEngine() {
        return prefs.get("companion.tts.engine", "windows").trim().toLowerCase(Locale.ROOT);
    }

    private static void selectChoice(JComboBox<ChoiceItem> combo, String id) {
        if (combo == null) return;
        String normalized = (id == null || id.isBlank()) ? "auto" : id.trim();
        ComboBoxModel<ChoiceItem> model = combo.getModel();
        for (int i = 0; i < model.getSize(); i++) {
            ChoiceItem item = model.getElementAt(i);
            if (item != null && item.id.equalsIgnoreCase(normalized)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        if (model.getSize() > 0) {
            combo.setSelectedIndex(0);
        }
    }

    public void refreshOutputChoices() {
        String current = prefs.get("companion.output.device", "").trim();
        List<String> outputs = host.getCompanionOutputDeviceChoices();
        outputCombo.setModel(new DefaultComboBoxModel<>(outputs.toArray(String[]::new)));
        if (!current.isBlank() && outputs.contains(current)) {
            outputCombo.setSelectedItem(current);
        } else if (!outputs.isEmpty()) {
            String preferred = host.getPreferredCompanionOutputDevice();
            outputCombo.setSelectedItem(outputs.contains(preferred) ? preferred : outputs.get(0));
        }
    }

    public void showWindow() {
        refreshChoices();
        persistVisibleState(true);
        setVisible(true);
        setToggleSelected(true);
        host.setCompanionEnabled(true);
        toFront();
        requestFocus();
        balloonWindow.reposition();
    }

    public void ensureVisibleAboveMainWindow() {
        if (!isDisplayable()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (!isVisible()) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (!isDisplayable() || !isVisible()) {
                    return;
                }
                toFront();
                balloonWindow.reposition();
            });
        });
    }

    public void hideWindow() {
        setVisible(false);
        setToggleSelected(false);
        host.setCompanionEnabled(false);
        persistVisibleState(false);
        balloonWindow.hideBalloon();
    }

    public void updateStatus(String text) {
        String normalized = (text == null || text.isBlank()) ? UiText.t("ui.companion.status.idle") : text.trim();
        statusLabel.setText(normalized);
        statusLabel.setToolTipText(normalized);
    }

    public void refreshStatusSummary(boolean enabled, CompanionSidecarClient.Status status, String outputDevice) {
        setToggleSelected(enabled);
        String mode = prefs.get("companion.output.mode", MODE_VOICE).trim().toLowerCase(Locale.ROOT);
        String ttsEngine = prefs.get("companion.tts.engine", "windows").trim().toLowerCase(Locale.ROOT);
        lastDebugReportPath = normalizedDebugReportPath(status == null ? "" : status.debugReportPath());

        boolean installed = status != null && status.installed();
        boolean running = status != null && status.running();
        String activeEngine = (status == null) ? "" : status.activeEngine();

        String stateText;
        Color stateBg;
        Color stateFg = Color.WHITE;
        if (!enabled) {
            stateText = "OFF";
            stateBg = new Color(110, 110, 110);
        } else if (!installed) {
            stateText = "DLC";
            stateBg = new Color(176, 68, 68);
        } else if (running) {
            stateText = "READY";
            stateBg = new Color(72, 143, 98);
        } else {
            stateText = "IDLE";
            stateBg = new Color(164, 126, 61);
        }
        setChip(stateChipLabel, stateText, stateBg, stateFg, buildStateTooltip(enabled, installed, running));

        String runtimeText = shortenRuntimeChip(activeEngine);
        Color runtimeBg = running ? new Color(65, 110, 156) : new Color(106, 106, 106);
        setChip(runtimeChipLabel, runtimeText, runtimeBg, Color.WHITE,
                activeEngine == null || activeEngine.isBlank() ? "Model status unknown" : "Model: " + activeEngine);

        String modeText = MODE_SUBTITLE.equalsIgnoreCase(mode) ? "BALLOON" : "VOICE";
        setChip(modeChipLabel, modeText, new Color(201, 150, 91), new Color(74, 49, 26),
                MODE_SUBTITLE.equalsIgnoreCase(mode)
                        ? UiText.t("ui.companion.mode.subtitle")
                        : UiText.t("ui.companion.mode.voice"));

        String resolvedOutput = (outputDevice == null || outputDevice.isBlank())
                ? host.getPreferredCompanionOutputDevice()
                : outputDevice.trim();
        String shortOutput = shortenOutputName(resolvedOutput);
        String volumeText = normalizeVolumeSelection(prefs.get("companion.volume.percent", "100"));
        String detail = "TTS " + formatTtsEngineLabel(ttsEngine)
                + " " + volumeText
                + " / Out " + (shortOutput.isBlank() ? "-" : shortOutput);
        detailLabel.setText(detail);
        detailLabel.setToolTipText("TTS: " + formatTtsEngineLabel(ttsEngine) + " " + volumeText
                + (resolvedOutput == null || resolvedOutput.isBlank() ? "" : " / Output: " + resolvedOutput));

        if (status == null) {
            updateStatus(enabled ? "Companion standby..." : "Companion off");
        } else if (!installed) {
            updateStatus("MobEcho DLC missing");
        } else if (running) {
            updateStatus("Sidecar ready");
        } else {
            updateStatus("Sidecar stopped");
        }
        if (openDebugReportButton != null) {
            boolean debugEnabled = debugModeCheck != null && debugModeCheck.isSelected();
            boolean hasReport = !lastDebugReportPath.isBlank();
            openDebugReportButton.setEnabled(debugEnabled && hasReport);
            openDebugReportButton.setToolTipText(hasReport ? lastDebugReportPath : UiText.t("ui.companion.debug.open_report"));
        }
        refreshSituationChip();
    }

    private String normalizedDebugReportPath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim();
        if (normalized.isBlank()) {
            return "";
        }
        return normalized;
    }

    public void refreshSituationSummary(String windowTitle,
                                        String moodDominant,
                                        String energyTrend,
                                        String utteranceTrend,
                                        String fatigueLevel,
                                        boolean excitementBurst,
                                        boolean longSilence,
                                        boolean stuckDetected,
                                        int sighCount,
                                        long observedAtMs) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> refreshSituationSummary(
                    windowTitle,
                    moodDominant,
                    energyTrend,
                    utteranceTrend,
                    fatigueLevel,
                    excitementBurst,
                    longSilence,
                    stuckDetected,
                    sighCount,
                    observedAtMs
            ));
            return;
        }
        situationWindowTitle = windowTitle == null ? "" : windowTitle.trim();
        situationMoodDominant = normalizeSituationToken(moodDominant, "neutral");
        situationEnergyTrend = normalizeSituationToken(energyTrend, "stable");
        situationUtteranceTrend = normalizeSituationToken(utteranceTrend, "unknown");
        situationFatigueLevel = normalizeSituationToken(fatigueLevel, "low");
        situationExcitementBurst = excitementBurst;
        situationLongSilence = longSilence;
        situationStuckDetected = stuckDetected;
        situationSighCount = Math.max(0, sighCount);
        situationObservedAtMs = Math.max(0L, observedAtMs);
        refreshSituationChip();
    }

    public void updateExchange(String heard, String reply) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> updateExchange(heard, reply));
            return;
        }
        String nextHeard = (heard == null) ? "" : heard;
        String nextReply = (reply == null) ? "" : reply;
        boolean heardChanged = !nextHeard.isBlank() && !nextHeard.equals(lastHeard);

        lastHeard = nextHeard;
        lastReply = nextReply;
        avatarPanel.setConversation(lastHeard, lastReply, heardChanged);
        if (!lastReply.isBlank()) {
            balloonWindow.showMessage(lastReply);
        } else {
            balloonWindow.hideBalloon();
        }
    }

    public void startReplyAnimation(String replyText, boolean voiceMode) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> startReplyAnimation(replyText, voiceMode));
            return;
        }
        avatarPanel.startReplyAnimation(replyText, voiceMode);
    }

    public void shutdownForExit() {
        host.setCompanionEnabled(false);
        balloonWindow.shutdown();
        setVisible(false);
        dispose();
    }

    private void persistVisibleState(boolean visible) {
        prefs.putBoolean("ui.companion.visible", visible);
        try { prefs.flush(); } catch (Exception ignore) {}
    }

    private void restoreBounds() {
        int x = prefs.getInt("ui.companion.x", Integer.MIN_VALUE);
        int y = prefs.getInt("ui.companion.y", Integer.MIN_VALUE);
        int w = prefs.getInt("ui.companion.w", Integer.MIN_VALUE);
        int h = prefs.getInt("ui.companion.h", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || w == Integer.MIN_VALUE || h == Integer.MIN_VALUE) {
            Rectangle fallback = buildSmartDefaultBounds();
            setBounds(fallback);
        } else {
            setBounds(x, y, Math.max(292, w), Math.max(430, h));
        }
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentMoved(java.awt.event.ComponentEvent e) {
                saveBounds();
                balloonWindow.reposition();
            }

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                saveBounds();
                balloonWindow.reposition();
            }
        });
    }

    private Rectangle buildSmartDefaultBounds() {
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        String hearingPos = prefs.get("hearing.overlay.position", "bottom_left");
        int width = 292;
        int height = 430;
        int margin = 28;
        int x;
        int y = usable.y + Math.max(18, usable.height - height - 96);
        if ("bottom_right".equalsIgnoreCase(hearingPos)) {
            x = usable.x + margin;
        } else {
            x = usable.x + Math.max(margin, usable.width - width - margin);
        }
        return new Rectangle(x, y, width, height);
    }

    private void saveBounds() {
        Rectangle r = getBounds();
        prefs.putInt("ui.companion.x", r.x);
        prefs.putInt("ui.companion.y", r.y);
        prefs.putInt("ui.companion.w", r.width);
        prefs.putInt("ui.companion.h", r.height);
        try { prefs.flush(); } catch (Exception ignore) {}
    }

    private JPanel buildStatusBar() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(4, 2, 2, 2)
        ));

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(0, 0, 0, 0);
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;

        stateChipLabel = createChipLabel();
        runtimeChipLabel = createChipLabel();
        modeChipLabel = createChipLabel();
        vibeChipLabel = createChipLabel();
        JLabel sep1 = createStatusSeparator();
        JLabel sep2 = createStatusSeparator();
        JLabel sep3 = createStatusSeparator();
        JLabel sep4 = createStatusSeparator();
        detailLabel = new JLabel("-");
        detailLabel.setForeground(UIManager.getColor("Label.foreground"));
        detailLabel.setFont(detailLabel.getFont().deriveFont(Font.PLAIN, 11f));
        statusLabel = new JLabel(UiText.t("ui.companion.status.idle"));
        statusLabel.setForeground(Color.GRAY);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        if (host.isInternalCompanionDebugUiEnabled()) {
            openDebugReportButton = new JButton(UiText.t("ui.companion.debug.open_report"));
            openDebugReportButton.setFont(openDebugReportButton.getFont().deriveFont(Font.PLAIN, 11f));
            openDebugReportButton.setEnabled(false);
            openDebugReportButton.addActionListener(e -> host.openCompanionDebugReport(lastDebugReportPath));
        }

        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        strip.setOpaque(false);
        strip.add(stateChipLabel);
        strip.add(sep1);
        strip.add(runtimeChipLabel);
        strip.add(sep2);
        strip.add(modeChipLabel);
        strip.add(sep3);
        strip.add(vibeChipLabel);
        strip.add(sep4);
        strip.add(detailLabel);

        panel.add(strip, gc);

        gc.gridx = 0;
        gc.gridy = 1;
        gc.gridwidth = 1;
        gc.insets = new Insets(2, 2, 0, 0);
        gc.weightx = 1.0;
        panel.add(statusLabel, gc);

        gc.gridx = 1;
        gc.gridy = 1;
        gc.gridwidth = 1;
        gc.insets = new Insets(2, 8, 0, 0);
        gc.weightx = 0.0;
        gc.anchor = GridBagConstraints.EAST;
        gc.fill = GridBagConstraints.NONE;
        if (openDebugReportButton != null) {
            panel.add(openDebugReportButton, gc);
        }
        return panel;
    }

    private JLabel createChipLabel() {
        JLabel label = new JLabel("...");
        label.setOpaque(true);
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0, 0, 0, 22)),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)
        ));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        return label;
    }

    private JLabel createStatusSeparator() {
        JLabel label = new JLabel("|");
        label.setForeground(UIManager.getColor("Separator.foreground"));
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        return label;
    }

    private void setChip(JLabel label, String text, Color bg, Color fg, String tooltip) {
        if (label == null) return;
        label.setText(text);
        label.setBackground(bg);
        label.setForeground(fg);
        label.setToolTipText(tooltip);
    }

    private void refreshSituationChip() {
        if (vibeChipLabel == null) {
            return;
        }
        long ageMs = situationObservedAtMs <= 0L ? Long.MAX_VALUE : Math.max(0L, System.currentTimeMillis() - situationObservedAtMs);
        SituationVisual visual = buildSituationVisual(ageMs);
        setChip(
                vibeChipLabel,
                UiText.t(visual.labelKey),
                visual.chipBg,
                visual.chipFg,
                buildSituationTooltip(ageMs)
        );
    }

    private String buildSituationTooltip(long ageMs) {
        String ageText = ageMs == Long.MAX_VALUE ? "none" : formatAgeMs(ageMs);
        String windowText = situationWindowTitle == null || situationWindowTitle.isBlank() ? "-" : situationWindowTitle;
        return "<html><b>" + escapeHtmlText(UiText.t("ui.companion.vibe.tooltip.title")) + "</b>"
                + "<br>" + escapeHtmlText(UiText.t("ui.companion.vibe.tooltip.mood")) + ": " + escapeHtmlText(localizeMoodToken(situationMoodDominant))
                + "<br>" + escapeHtmlText(UiText.t("ui.companion.vibe.tooltip.energy")) + ": " + escapeHtmlText(localizeEnergyToken(situationEnergyTrend))
                + "<br>" + escapeHtmlText(UiText.t("ui.companion.vibe.tooltip.utterance")) + ": " + escapeHtmlText(localizeUtteranceToken(situationUtteranceTrend))
                + "<br>" + escapeHtmlText(UiText.t("ui.companion.vibe.tooltip.fatigue")) + ": " + escapeHtmlText(localizeFatigueToken(situationFatigueLevel))
                + "<br>" + escapeHtmlText(UiText.t("ui.companion.vibe.tooltip.burst")) + ": " + escapeHtmlText(localizeBool(situationExcitementBurst))
                + "<br>" + escapeHtmlText(UiText.t("ui.companion.vibe.tooltip.long_silence")) + ": " + escapeHtmlText(localizeBool(situationLongSilence))
                + "<br>" + escapeHtmlText(UiText.t("ui.companion.vibe.tooltip.stuck")) + ": " + escapeHtmlText(localizeBool(situationStuckDetected))
                + "<br>" + escapeHtmlText(UiText.t("ui.companion.vibe.tooltip.sighs")) + ": " + situationSighCount
                + "<br>" + escapeHtmlText(UiText.t("ui.companion.vibe.tooltip.window")) + ": " + escapeHtmlText(windowText)
                + "<br>" + escapeHtmlText(UiText.t("ui.companion.vibe.tooltip.observed")) + ": " + escapeHtmlText(localizeObserved(ageText))
                + "</html>";
    }

    private SituationVisual buildSituationVisual(long ageMs) {
        if (ageMs > 120_000L) {
            return new SituationVisual(
                    "ui.companion.vibe.label.unknown",
                    new Color(115, 115, 115),
                    Color.WHITE,
                    new Color(115, 115, 115),
                    false
            );
        }
        if (situationStuckDetected) {
            return new SituationVisual(
                    "ui.companion.vibe.label.stuck",
                    new Color(164, 92, 72),
                    Color.WHITE,
                    new Color(206, 124, 102),
                    true
            );
        }
        if (situationLongSilence) {
            return new SituationVisual(
                    "ui.companion.vibe.label.quiet",
                    new Color(105, 124, 158),
                    Color.WHITE,
                    new Color(140, 166, 206),
                    true
            );
        }
        if ("high".equals(situationFatigueLevel)) {
            return new SituationVisual(
                    "ui.companion.vibe.label.tired",
                    new Color(135, 103, 78),
                    Color.WHITE,
                    new Color(185, 150, 112),
                    true
            );
        }
        if (situationExcitementBurst || "excited".equals(situationMoodDominant)) {
            return new SituationVisual(
                    "ui.companion.vibe.label.hype",
                    new Color(177, 109, 57),
                    Color.WHITE,
                    new Color(255, 193, 99),
                    true
            );
        }
        if ("sing_song_lite".equals(situationMoodDominant)) {
            return new SituationVisual(
                    "ui.companion.vibe.label.light",
                    new Color(145, 111, 169),
                    Color.WHITE,
                    new Color(196, 157, 224),
                    true
            );
        }
        if ("calm".equals(situationMoodDominant)) {
            return new SituationVisual(
                    "ui.companion.vibe.label.calm",
                    new Color(88, 126, 145),
                    Color.WHITE,
                    new Color(140, 189, 213),
                    true
            );
        }
        if ("medium".equals(situationFatigueLevel) || "falling".equals(situationEnergyTrend)) {
            return new SituationVisual(
                    "ui.companion.vibe.label.low",
                    new Color(127, 121, 89),
                    Color.WHITE,
                    new Color(191, 184, 130),
                    true
            );
        }
        return new SituationVisual(
                "ui.companion.vibe.label.steady",
                new Color(88, 135, 112),
                Color.WHITE,
                new Color(119, 176, 146),
                false
        );
    }

    private String localizeMoodToken(String token) {
        return switch (normalizeSituationToken(token, "neutral")) {
            case "excited" -> UiText.t("ui.companion.vibe.value.mood.excited");
            case "calm" -> UiText.t("ui.companion.vibe.value.mood.calm");
            case "sing_song_lite" -> UiText.t("ui.companion.vibe.value.mood.light");
            default -> UiText.t("ui.companion.vibe.value.mood.neutral");
        };
    }

    private String localizeEnergyToken(String token) {
        return switch (normalizeSituationToken(token, "stable")) {
            case "rising" -> UiText.t("ui.companion.vibe.value.energy.rising");
            case "falling" -> UiText.t("ui.companion.vibe.value.energy.falling");
            default -> UiText.t("ui.companion.vibe.value.energy.stable");
        };
    }

    private String localizeUtteranceToken(String token) {
        return switch (normalizeSituationToken(token, "unknown")) {
            case "rising" -> UiText.t("ui.companion.vibe.value.utterance.rising");
            case "falling" -> UiText.t("ui.companion.vibe.value.utterance.falling");
            case "stable" -> UiText.t("ui.companion.vibe.value.utterance.stable");
            default -> UiText.t("ui.companion.vibe.value.utterance.unknown");
        };
    }

    private String localizeFatigueToken(String token) {
        return switch (normalizeSituationToken(token, "low")) {
            case "high" -> UiText.t("ui.companion.vibe.value.fatigue.high");
            case "medium" -> UiText.t("ui.companion.vibe.value.fatigue.medium");
            default -> UiText.t("ui.companion.vibe.value.fatigue.low");
        };
    }

    private String localizeBool(boolean value) {
        return UiText.t(value ? "ui.companion.vibe.value.bool.yes" : "ui.companion.vibe.value.bool.no");
    }

    private String localizeObserved(String ageText) {
        if ("none".equals(ageText)) {
            return UiText.t("ui.companion.vibe.value.observed.none");
        }
        return ageText + " " + UiText.t("ui.companion.vibe.value.observed.ago");
    }

    private static String normalizeSituationToken(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String formatAgeMs(long ageMs) {
        if (ageMs < 1000L) {
            return "just now";
        }
        long sec = ageMs / 1000L;
        if (sec < 60L) {
            return sec + "s";
        }
        long min = sec / 60L;
        if (min < 60L) {
            return min + "m";
        }
        long hour = min / 60L;
        return hour + "h";
    }

    private static String escapeHtmlText(String value) {
        if (value == null || value.isBlank()) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
    }

    private String buildStateTooltip(boolean enabled, boolean installed, boolean running) {
        if (!enabled) return "Companion disabled";
        if (!installed) return "MobEcho DLC missing";
        return running ? "Companion enabled and sidecar ready" : "Companion enabled but sidecar stopped";
    }

    private String shortenRuntimeChip(String activeEngine) {
        String normalized = (activeEngine == null) ? "" : activeEngine.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return "CHECK";
        if (normalized.contains("qwen")) return "QWEN";
        if (normalized.contains("fallback")) return "FALLBACK";
        if (normalized.contains("stop")) return "STOPPED";
        if (normalized.contains("missing")) return "MISSING";
        if (normalized.length() > 10) return normalized.substring(0, 10).toUpperCase(Locale.ROOT);
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String formatTtsEngineLabel(String engine) {
        return switch (engine) {
            case "voicevox" -> "VOICEVOX";
            case "piper_plus" -> "Piper+";
            case "voiceger" -> "Voiceger";
            default -> "Windows";
        };
    }

    private String shortenOutputName(String output) {
        if (output == null) return "";
        String normalized = output.trim();
        if (normalized.isBlank()) return "";
        int paren = normalized.indexOf(" (");
        if (paren > 0) {
            normalized = normalized.substring(0, paren).trim();
        }
        if (normalized.length() <= 24) {
            return normalized;
        }
        return normalized.substring(0, 21) + "...";
    }

    private static String[] buildVolumeChoices() {
        String[] values = new String[10];
        for (int i = 0; i < values.length; i++) {
            values[i] = ((i + 1) * 10) + "%";
        }
        return values;
    }

    private static String normalizeVolumeSelection(Object value) {
        String text = value == null ? "" : value.toString().trim();
        if (text.endsWith("%")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        int percent = 100;
        try {
            percent = Integer.parseInt(text);
        } catch (NumberFormatException ignore) {}
        percent = Math.max(10, Math.min(100, (percent / 10) * 10));
        return percent + "%";
    }

    private final class AvatarPanel extends JPanel {
        private static final int ANIMATION_TICK_MS = 110;
        private static final double FACE_X_RATIO = 0.35;
        private static final double FACE_Y_RATIO = 0.21;
        private static final double FACE_W_RATIO = 0.30;
        private static final double FACE_H_RATIO = 0.22;
        private BufferedImage avatarImage;
        private String avatarHint = "";
        private String heard = "";
        private String reply = "";
        private final Timer animationTimer;
        private long eyesOpenUntilMs = 0L;
        private long talkingUntilMs = 0L;
        private int mouthFrame = 0;

        AvatarPanel() {
            setOpaque(false);
            setToolTipText("");
            animationTimer = new Timer(ANIMATION_TICK_MS, e -> onAnimationTick());
            animationTimer.setRepeats(true);
        }

        @Override
        public void removeNotify() {
            animationTimer.stop();
            super.removeNotify();
        }

        String getAvatarHint() {
            return avatarHint;
        }

        void setConversation(String heard, String reply, boolean heardChanged) {
            this.heard = (heard == null) ? "" : heard;
            this.reply = (reply == null) ? "" : reply;
            String tooltip = "<html><b>Heard</b><br>" + escapeHtml(this.heard)
                    + "<br><br><b>Reply</b><br>" + escapeHtml(this.reply) + "</html>";
            setToolTipText(tooltip);
            if (heardChanged) {
                triggerHeardAnimation();
            }
            repaint();
        }

        void startReplyAnimation(String replyText, boolean voiceMode) {
            triggerReplyAnimation(replyText, voiceMode);
        }

        void reloadAvatar() {
            avatarImage = null;
            File file = resolveAvatarFile();
            avatarHint = file.getAbsolutePath();
            if (file.isFile()) {
                try {
                    avatarImage = ImageIO.read(file);
                } catch (Exception ignore) {
                    avatarImage = null;
                }
            }
            setToolTipText(avatarHint);
            repaint();
        }

        private File resolveAvatarFile() {
            String custom = prefs.get("companion.avatar.path", "").trim();
            if (!custom.isBlank()) {
                return new File(custom);
            }
            File dir = host.getExeDir().toPath().resolve("mobecho").resolve("avatar").toFile();
            String[] names = { "avatar.png", "avatar.jpg", "avatar.jpeg", "face.png", "face.jpg", "face.jpeg" };
            for (String name : names) {
                File candidate = new File(dir, name);
                if (candidate.isFile()) {
                    return candidate;
                }
            }
            return new File(dir, "avatar.png");
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();

                Shape clip = new RoundRectangle2D.Float(0, 0, w - 1, h - 1, 28, 28);
                g2.setColor(new Color(254, 246, 231));
                g2.fill(clip);
                g2.setColor(new Color(220, 185, 137));
                g2.draw(clip);

                GradientPaint gp = new GradientPaint(0, 0, new Color(255, 247, 227), 0, h, new Color(243, 221, 186));
                g2.setPaint(gp);
                g2.fillRoundRect(6, 6, w - 12, h - 12, 24, 24);

                if (avatarImage != null) {
                    drawAvatarImage(g2, w, h);
                } else {
                    drawFallbackFace(g2, w, h);
                }

                drawMoodOverlay(g2, w, h);
                drawExpressionOverlay(g2, w, h);
                drawBadge(g2, w, h);
            } finally {
                g2.dispose();
            }
        }

        private void drawAvatarImage(Graphics2D g2, int w, int h) {
            int pad = 18;
            int drawW = w - (pad * 2);
            int drawH = h - (pad * 2) - 20;
            double scale = Math.min(drawW / (double) avatarImage.getWidth(), drawH / (double) avatarImage.getHeight());
            int iw = Math.max(1, (int) Math.round(avatarImage.getWidth() * scale));
            int ih = Math.max(1, (int) Math.round(avatarImage.getHeight() * scale));
            int x = (w - iw) / 2;
            int y = Math.max(10, (h - ih) / 2 - 8);
            g2.drawImage(avatarImage, x, y, iw, ih, null);
        }

        private void drawFallbackFace(Graphics2D g2, int w, int h) {
            long now = System.currentTimeMillis();
            boolean animatedEyes = now < eyesOpenUntilMs;
            boolean animatedMouth = now < talkingUntilMs;
            int cx = w / 2;
            int cy = (h / 2) - 8;

            g2.setColor(new Color(117, 165, 104));
            g2.fillRoundRect(cx - 86, cy - 70, 172, 146, 40, 40);

            g2.setColor(new Color(246, 235, 212));
            g2.fillRoundRect(cx - 58, cy - 44, 116, 86, 26, 26);

            g2.setColor(new Color(56, 94, 84));
            g2.fillRoundRect(cx - 52, cy - 38, 104, 74, 24, 24);

            if (!animatedEyes || !animatedMouth) {
                g2.setColor(new Color(255, 221, 102));
                g2.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if (!animatedEyes) {
                    g2.drawArc(cx - 38, cy - 6, 24, 20, 200, 140);
                    g2.drawArc(cx + 14, cy - 6, 24, 20, 200, 140);
                }
                if (!animatedMouth) {
                    g2.drawArc(cx - 24, cy + 8, 48, 30, 200, 140);
                }
            }

            g2.setColor(new Color(169, 207, 122));
            g2.fillOval(cx - 114, cy - 28, 30, 76);
            g2.fillOval(cx + 84, cy - 28, 30, 76);

            g2.setColor(new Color(255, 244, 228));
            g2.fillOval(cx - 108, cy - 22, 18, 64);
            g2.fillOval(cx + 90, cy - 22, 18, 64);

            g2.setColor(new Color(120, 180, 108));
            g2.fillRoundRect(cx - 16, cy + 84, 32, 16, 8, 8);
            g2.fillOval(cx - 36, cy + 108, 72, 72);
            g2.setColor(new Color(255, 245, 226));
            g2.fillOval(cx - 18, cy + 126, 36, 36);
        }

        private void triggerHeardAnimation() {
            long now = System.currentTimeMillis();
            eyesOpenUntilMs = Math.max(eyesOpenUntilMs, now + 820L);
            ensureAnimationTimer();
            repaint();
        }

        private void triggerReplyAnimation(String replyText, boolean voiceMode) {
            long now = System.currentTimeMillis();
            int chars = (replyText == null || replyText.isBlank()) ? 0 : replyText.codePointCount(0, replyText.length());
            long duration = (voiceMode ? 900L : 620L) + (long) chars * (voiceMode ? 52L : 30L);
            duration = Math.max(voiceMode ? 1400L : 900L, Math.min(voiceMode ? 4300L : 2200L, duration));
            talkingUntilMs = Math.max(talkingUntilMs, now + duration);
            eyesOpenUntilMs = Math.max(eyesOpenUntilMs, now + Math.min(900L, duration / 2L));
            ensureAnimationTimer();
            repaint();
        }

        private void ensureAnimationTimer() {
            if (!animationTimer.isRunning()) {
                animationTimer.start();
            }
        }

        private void onAnimationTick() {
            long now = System.currentTimeMillis();
            if (now < talkingUntilMs) {
                mouthFrame = (mouthFrame + 1) % 3;
                repaint();
                return;
            }
            if (mouthFrame != 0) {
                mouthFrame = 0;
                repaint();
            }
            if (now < eyesOpenUntilMs) {
                repaint();
                return;
            }
            boolean hadAnimation = (talkingUntilMs != 0L) || (eyesOpenUntilMs != 0L) || (mouthFrame != 0);
            talkingUntilMs = 0L;
            eyesOpenUntilMs = 0L;
            mouthFrame = 0;
            animationTimer.stop();
            if (hadAnimation) {
                repaint();
            }
        }

        private void drawExpressionOverlay(Graphics2D g2, int w, int h) {
            long now = System.currentTimeMillis();
            boolean eyesOpen = now < eyesOpenUntilMs;
            boolean talking = now < talkingUntilMs;
            if (!eyesOpen && !talking) {
                return;
            }

            int faceW = Math.max(58, (int) Math.round(w * FACE_W_RATIO));
            int faceH = Math.max(44, (int) Math.round(h * FACE_H_RATIO));
            int faceX = Math.max(18, (int) Math.round(w * FACE_X_RATIO));
            int faceY = Math.max(18, (int) Math.round(h * FACE_Y_RATIO));

            g2.setStroke(new BasicStroke(Math.max(3.2f, w * 0.012f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(255, 213, 99, 238));

            if (eyesOpen) {
                int eyeW = Math.max(10, faceW / 8);
                int eyeH = Math.max(12, faceH / 5);
                int eyeY = faceY + Math.max(10, faceH / 5);
                int leftEyeX = faceX + Math.max(10, faceW / 5) - (eyeW / 2);
                int rightEyeX = faceX + faceW - Math.max(10, faceW / 5) - (eyeW / 2);
                g2.fillOval(leftEyeX, eyeY, eyeW, eyeH);
                g2.fillOval(rightEyeX, eyeY, eyeW, eyeH);
            }

            if (talking) {
                int mouthW = Math.max(26, faceW / 3);
                int mouthX = faceX + (faceW - mouthW) / 2;
                int mouthY = faceY + Math.max(24, (int) Math.round(faceH * 0.56));
                int mouthH = switch (mouthFrame) {
                    case 1 -> Math.max(10, faceH / 4);
                    case 2 -> Math.max(16, faceH / 3);
                    default -> Math.max(6, faceH / 7);
                };
                g2.fillRoundRect(mouthX, mouthY, mouthW, mouthH, mouthH, mouthH);
            }
        }

        private void drawMoodOverlay(Graphics2D g2, int w, int h) {
            long ageMs = situationObservedAtMs <= 0L ? Long.MAX_VALUE : Math.max(0L, System.currentTimeMillis() - situationObservedAtMs);
            SituationVisual visual = buildSituationVisual(ageMs);
            if (visual == null || !visual.avatarAccent || ageMs > 120_000L) {
                return;
            }

            int faceW = Math.max(58, (int) Math.round(w * FACE_W_RATIO));
            int faceH = Math.max(44, (int) Math.round(h * FACE_H_RATIO));
            int faceX = Math.max(18, (int) Math.round(w * FACE_X_RATIO));
            int faceY = Math.max(18, (int) Math.round(h * FACE_Y_RATIO));

            Composite oldComposite = g2.getComposite();
            Stroke oldStroke = g2.getStroke();

            g2.setComposite(AlphaComposite.SrcOver.derive(0.12f));
            g2.setColor(visual.auraColor);
            g2.fillRoundRect(faceX - 16, faceY - 12, faceW + 32, faceH + 26, 28, 28);

            g2.setComposite(AlphaComposite.SrcOver.derive(0.28f));
            g2.setColor(visual.auraColor);
            g2.setStroke(new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawRoundRect(faceX - 8, faceY - 6, faceW + 16, faceH + 14, 22, 22);

            String badge = UiText.t(visual.labelKey);
            Font font = getFont().deriveFont(Font.BOLD, 11f);
            FontMetrics fm = g2.getFontMetrics(font);
            int bw = fm.stringWidth(badge) + 16;
            int bh = 20;
            int bx = Math.min(w - bw - 14, faceX + faceW - 4);
            int by = Math.max(14, faceY - 20);

            g2.setComposite(AlphaComposite.SrcOver.derive(0.92f));
            g2.setFont(font);
            g2.setColor(new Color(255, 250, 240, 232));
            g2.fillRoundRect(bx, by, bw, bh, 12, 12);
            g2.setColor(visual.auraColor.darker());
            g2.drawRoundRect(bx, by, bw, bh, 12, 12);
            g2.setColor(new Color(74, 58, 42));
            g2.drawString(badge, bx + 8, by + 14);

            g2.setComposite(oldComposite);
            g2.setStroke(oldStroke);
        }

        private void drawBadge(Graphics2D g2, int w, int h) {
            String mode = prefs.get("companion.output.mode", MODE_VOICE);
            String badge = MODE_SUBTITLE.equalsIgnoreCase(mode) ? UiText.t("ui.companion.mode.subtitle") : UiText.t("ui.companion.mode.voice");
            Font font = getFont().deriveFont(Font.BOLD, 12f);
            FontMetrics fm = g2.getFontMetrics(font);
            int bw = fm.stringWidth(badge) + 18;
            int bh = 22;
            int x = 14;
            int y = h - bh - 12;
            g2.setFont(font);
            g2.setColor(new Color(255, 250, 240, 225));
            g2.fillRoundRect(x, y, bw, bh, 14, 14);
            g2.setColor(new Color(201, 150, 91));
            g2.drawRoundRect(x, y, bw, bh, 14, 14);
            g2.setColor(new Color(100, 74, 53));
            g2.drawString(badge, x + 9, y + 15);
        }

        private String escapeHtml(String value) {
            if (value == null || value.isBlank()) return "";
            return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\n", "<br>");
        }
    }

    private static final class ChoiceItem {
        final String id;
        final String label;

        ChoiceItem(String id, String label) {
            this.id = (id == null) ? "" : id;
            this.label = (label == null || label.isBlank()) ? this.id : label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
