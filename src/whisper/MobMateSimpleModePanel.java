package whisper;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class MobMateSimpleModePanel extends JPanel {

    private static final String DISCORD_INVITE_URL = "https://discord.gg/CkhYzNw7YF";
    private static final int WINDOW_EDGE_RESIZE_MARGIN = 8;
    private static final long GUIDANCE_ROTATE_INTERVAL_MS = 60_000L;

    private final MobMateWhisp app;
    private final Timer refreshTimer;
    private final SimpleGuidanceEvaluator guidanceEvaluator = new SimpleGuidanceEvaluator();
    private boolean updatingControls = false;
    private Point dragAnchorOnScreen;
    private Point dragWindowOrigin;
    private long guidanceLastRotatedAt = 0L;
    private int guidancePassiveIndex = 0;
    private String guidancePassiveSignature = "";

    private FadingHistoryTextLabel liveHelperLabel;
    private JLabel statusChip;
    private JLabel hearingChip;
    private JLabel companionChip;
    private JLabel speakerChip;
    private JLabel aiAssistChip;
    private GainMeter liveMeter;
    private HistoryTextLabel historyBlock1;
    private JPanel historyBlockPanel1;
    private final List<HistoryTextLabel> historyTailBlocks = new ArrayList<>();
    private JScrollPane historyTailScrollPane;
    private JButton instantButton;
    private JButton pendingButton;
    private JComboBox<Integer> pendingSecCombo;
    private JPanel pendingSectionBody;
    private JButton pendingSectionToggleButton;
    private ChevronIcon pendingSectionToggleIcon;
    private JLabel pendingSectionSummaryLabel;
    private JPanel pendingActionRow;
    private JButton pendingApproveButton;
    private JButton pendingCancelButton;
    private JLabel pendingCountdownLabel;
    private HistoryTextLabel pendingPreviewLabel;
    private JButton historyButton;
    private JComboBox<String> liveLangCombo;
    private JComboBox<String> liveTranslateTargetCombo;

    private JButton recordButton;
    private JButton hearingButton;
    private JButton companionButton;

    private JComboBox<String> inputCombo;
    private JComboBox<String> outputCombo;
    private JComboBox<String> monitorVolumeCombo;
    private JCheckBox aiAssistToggle;

    private JComboBox<String> languageCombo;
    private JComboBox<String> recognitionEngineCombo;
    private JComboBox<String> ttsEngineCombo;
    private JComboBox<String> ttsVoiceCombo;

    private JTextField radioShortcutField;
    private JComboBox<String> overlayPositionCombo;
    private JComboBox<PresetOption> overlayBgPresetCombo;
    private JComboBox<PresetOption> overlayFgPresetCombo;
    private JPanel headerPanel;
    private FadingStatusLabel headerPrimaryStatusLabel;
    private JLabel headerHearingStatusLabel;
    private JLabel headerCompanionStatusLabel;
    private JLabel headerSpeakerStatusLabel;
    private JLabel headerAiAssistStatusLabel;
    private JLabel headerBackendStatusLabel;
    private JPanel scrollContentPanel;
    private JScrollPane contentScrollPane;

    private Color currentOverlayBg = new Color(0x1D, 0x6F, 0x5A);
    private Color currentOverlayFg = Color.WHITE;

    private static final PresetOption[] OVERLAY_BG_PRESETS = new PresetOption[] {
            new PresetOption("preset.bg.deep_green", "#1D6F5A"),
            new PresetOption("preset.bg.navy", "#304A6E"),
            new PresetOption("preset.bg.charcoal", "#2F3A4B"),
            new PresetOption("preset.bg.wine", "#7A3E4B")
    };

    private static final PresetOption[] OVERLAY_FG_PRESETS = new PresetOption[] {
            new PresetOption("preset.fg.white", "#FFFFFF"),
            new PresetOption("preset.fg.ivory", "#F8F3E7"),
            new PresetOption("preset.fg.navy", "#26374C"),
            new PresetOption("preset.fg.yellow", "#F4E38A")
    };

    MobMateSimpleModePanel(MobMateWhisp app) {
        super(new BorderLayout());
        this.app = app;
        setOpaque(false);
        buildUi();
        applyThemeColors();
        installWindowDragSupport(this);
        loadCurrentPrefs();
        refreshFromSnapshot();
        refreshTimer = new Timer(500, e -> refreshFromSnapshot());
        refreshTimer.start();
    }

    void shutdown() {
        refreshTimer.stop();
        app.attachSimpleGainMeter(null);
    }

    void markCalibrating() {
        if (recordButton != null) {
            recordButton.setEnabled(false);
            recordButton.setText(tr("record.calibrating"));
        }
        if (liveHelperLabel != null) {
            liveHelperLabel.setAnimatedText(tr("live.calibrating"), mutedForeground());
        }
    }

    private void buildUi() {
        JPanel fixedTop = new JPanel(new BorderLayout());
        fixedTop.setOpaque(true);
        fixedTop.setBackground(panelBackground());
        JPanel fixedTopHeader = new JPanel(new BorderLayout());
        fixedTopHeader.setOpaque(false);
        fixedTopHeader.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        fixedTopHeader.add(buildTopBar(), BorderLayout.CENTER);
        fixedTop.add(fixedTopHeader, BorderLayout.NORTH);
        fixedTop.add(buildLiveCard(), BorderLayout.CENTER);

        scrollContentPanel = new ViewportWidthPanel();
        scrollContentPanel.setOpaque(true);
        scrollContentPanel.setBackground(panelBackground());
        scrollContentPanel.setLayout(new BoxLayout(scrollContentPanel, BoxLayout.Y_AXIS));
        scrollContentPanel.add(buildQuickActionsCard());
        scrollContentPanel.add(Box.createVerticalStrut(8));
        scrollContentPanel.add(buildTranslationCard());
        scrollContentPanel.add(Box.createVerticalStrut(8));
        scrollContentPanel.add(buildAudioCard());
        scrollContentPanel.add(Box.createVerticalStrut(8));
        scrollContentPanel.add(buildVoiceCard());
        scrollContentPanel.add(Box.createVerticalStrut(8));
        scrollContentPanel.add(buildRadioCard());

        contentScrollPane = new JScrollPane(scrollContentPanel);
        contentScrollPane.setBorder(BorderFactory.createEmptyBorder());
        contentScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        contentScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        contentScrollPane.getViewport().setOpaque(false);
        contentScrollPane.setOpaque(false);
        add(fixedTop, BorderLayout.NORTH);
        add(contentScrollPane, BorderLayout.CENTER);
    }

    private JPanel buildTopBar() {
        headerPanel = new JPanel(new BorderLayout(8, 0));
        headerPanel.setOpaque(false);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));

        JLabel titleLabel = new JLabel("Mobmate", new LiveHeaderIcon(), SwingConstants.LEFT);
        titleLabel.setIconTextGap(8);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, Math.max(13f, titleLabel.getFont().getSize2D())));

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        statusRow.setOpaque(false);
        headerPrimaryStatusLabel = createPrimaryHeaderStatusToken(tr("status.ready"));
        headerHearingStatusLabel = createHeaderStatusToken(tr("status.hearing.off"));
        headerCompanionStatusLabel = createHeaderStatusToken(tr("status.echo.off"));
        headerSpeakerStatusLabel = createHeaderStatusToken(tr("status.spk.short"));
        headerAiAssistStatusLabel = createHeaderStatusToken(tr("status.aia.short") + "0");
        headerBackendStatusLabel = createHeaderStatusToken(tr("backend.cpu"));
        statusRow.add(headerPrimaryStatusLabel);
        statusRow.add(createHeaderStatusSeparator());
        statusRow.add(headerSpeakerStatusLabel);
        statusRow.add(createHeaderStatusSeparator());
        statusRow.add(headerAiAssistStatusLabel);
        statusRow.add(createHeaderStatusSeparator());
        statusRow.add(headerBackendStatusLabel);
        statusRow.add(createHeaderStatusSeparator());
        statusRow.add(headerHearingStatusLabel);
        statusRow.add(createHeaderStatusSeparator());
        statusRow.add(headerCompanionStatusLabel);
        applyPrimaryStatusTokenWidth();
        applySpeakerStatusTokenWidth();

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleLabel.getPreferredSize().height));
        statusRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, statusRow.getPreferredSize().height));
        left.add(titleLabel);
        left.add(Box.createVerticalStrut(1));
        left.add(statusRow);

        JButton settingsButton = createHeaderButton(false);
        settingsButton.setToolTipText(tr("top.settings"));
        settingsButton.addActionListener(e -> app.openSettingsCenter());

        JButton discordButton = createHeaderTextButton("Discord");
        discordButton.setToolTipText(tr("top.discord_support"));
        discordButton.addActionListener(this::openDiscordSupport);

        JButton closeButton = createHeaderButton(true);
        closeButton.setToolTipText(tr("top.close"));
        closeButton.addActionListener(e -> {
            Window window = SwingUtilities.getWindowAncestor(this);
            if (window != null) {
                window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
            }
        });

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(discordButton);
        right.add(settingsButton);
        right.add(closeButton);

        headerPanel.add(left, BorderLayout.CENTER);
        headerPanel.add(right, BorderLayout.EAST);
        installWindowDragSupport(headerPanel);
        return headerPanel;
    }

    private JPanel buildLiveCard() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setAlignmentX(Component.LEFT_ALIGNMENT);

        liveHelperLabel = createLiveGuidanceLabel(tr("live.default_guidance"));
        liveHelperLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(liveHelperLabel);
        body.add(Box.createVerticalStrut(8));

        JPanel meterRow = new JPanel(new BorderLayout(8, 0));
        meterRow.setOpaque(false);
        meterRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        meterRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        liveMeter = new GainMeter();
        liveMeter.setCompactLabels(true);
        app.attachSimpleGainMeter(liveMeter);
        liveMeter.setPreferredSize(new Dimension(360, 22));
        liveMeter.setMinimumSize(new Dimension(220, 20));
        liveMeter.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        liveMeter.setAlignmentX(Component.LEFT_ALIGNMENT);

        recordButton = createRecordActionButton(tr("record.start"));
        recordButton.addActionListener(e -> {
            markGuidanceInteraction();
            app.toggleRecordingFromSimpleUi();
        });
        recordButton.setMaximumSize(recordButton.getPreferredSize());

        meterRow.add(liveMeter, BorderLayout.CENTER);
        meterRow.add(recordButton, BorderLayout.EAST);
        body.add(meterRow);
        body.add(Box.createVerticalStrut(6));

        JPanel historyRow = new JPanel(new BorderLayout(6, 0));
        historyRow.setOpaque(false);
        historyRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        historyRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        historyBlock1 = createHistoryTextLabel(true);
        historyBlockPanel1 = createHistoryBlock(historyBlock1, true);
        historyBlockPanel1.setPreferredSize(new Dimension(190, 42));
        historyBlockPanel1.setMinimumSize(new Dimension(150, 42));
        historyBlockPanel1.setMaximumSize(new Dimension(220, 42));
        historyRow.add(historyBlockPanel1, BorderLayout.WEST);

        JPanel historyTailStrip = new JPanel();
        historyTailStrip.setOpaque(false);
        historyTailStrip.setLayout(new BoxLayout(historyTailStrip, BoxLayout.X_AXIS));
        historyTailBlocks.clear();
        for (int i = 0; i < 4; i++) {
            HistoryTextLabel tailLabel = createHistoryTextLabel(false);
            JPanel tailPanel = createHistoryBlock(tailLabel, false);
            tailPanel.setPreferredSize(new Dimension(176, 42));
            tailPanel.setMinimumSize(new Dimension(140, 42));
            tailPanel.setMaximumSize(new Dimension(220, 42));
            historyTailBlocks.add(tailLabel);
            historyTailStrip.add(tailPanel);
            if (i < 3) {
                historyTailStrip.add(Box.createHorizontalStrut(6));
            }
        }

        historyTailScrollPane = new JScrollPane(
                historyTailStrip,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        historyTailScrollPane.setBorder(BorderFactory.createEmptyBorder());
        historyTailScrollPane.setOpaque(false);
        historyTailScrollPane.getViewport().setOpaque(false);
        historyTailScrollPane.setPreferredSize(new Dimension(340, 46));
        historyTailScrollPane.setMinimumSize(new Dimension(180, 46));
        historyTailScrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        historyTailScrollPane.getHorizontalScrollBar().setUnitIncrement(24);
        historyRow.add(historyTailScrollPane, BorderLayout.CENTER);
        body.add(historyRow);
        body.add(Box.createVerticalStrut(8));

        JPanel pendingSection = new JPanel(new BorderLayout(0, 4));
        pendingSection.setOpaque(false);
        pendingSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        pendingSection.setMaximumSize(new Dimension(Integer.MAX_VALUE, 190));
        pendingSection.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor()),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));

        JPanel pendingHeader = new JPanel(new BorderLayout(6, 0));
        pendingHeader.setOpaque(false);
        JLabel pendingTitle = new JLabel(tr("pending.title"), sectionIconForKey("pending"), SwingConstants.LEFT);
        pendingTitle.setIconTextGap(8);
        pendingTitle.setForeground(labelForeground());
        pendingTitle.setFont(pendingTitle.getFont().deriveFont(Font.BOLD, Math.max(11f, pendingTitle.getFont().getSize2D() - 0.2f)));
        pendingSectionSummaryLabel = new JLabel(" ");
        pendingSectionSummaryLabel.setForeground(mutedForeground());
        pendingSectionSummaryLabel.setFont(pendingSectionSummaryLabel.getFont().deriveFont(Font.BOLD, Math.max(9f, pendingSectionSummaryLabel.getFont().getSize2D() - 2.0f)));
        JPanel pendingTitleWrap = new JPanel(new BorderLayout(8, 0));
        pendingTitleWrap.setOpaque(false);
        pendingTitleWrap.add(pendingTitle, BorderLayout.WEST);
        pendingTitleWrap.add(pendingSectionSummaryLabel, BorderLayout.CENTER);
        pendingSectionToggleIcon = new ChevronIcon(true);
        pendingSectionToggleButton = new JButton(pendingSectionToggleIcon);
        pendingSectionToggleButton.setFocusable(false);
        pendingSectionToggleButton.setContentAreaFilled(false);
        pendingSectionToggleButton.setBorderPainted(false);
        pendingSectionToggleButton.setForeground(new Color(0x74, 0xBC, 0xB6));
        pendingSectionToggleButton.addActionListener(e ->
                setPendingSectionExpanded(!pendingSectionBody.isVisible()));
        pendingHeader.add(pendingTitleWrap, BorderLayout.CENTER);
        pendingHeader.add(pendingSectionToggleButton, BorderLayout.EAST);
        pendingSection.add(pendingHeader, BorderLayout.NORTH);

        pendingSectionBody = new JPanel();
        pendingSectionBody.setOpaque(false);
        pendingSectionBody.setLayout(new BoxLayout(pendingSectionBody, BoxLayout.Y_AXIS));

        JTextArea pendingHelper = helperLabel(tr("pending.helper"));
        pendingHelper.setAlignmentX(Component.LEFT_ALIGNMENT);
        pendingSectionBody.add(pendingHelper);
        pendingSectionBody.add(Box.createVerticalStrut(6));

        pendingActionRow = new JPanel(new BorderLayout(8, 0));
        pendingActionRow.setOpaque(false);
        pendingActionRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        pendingActionRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        pendingCancelButton = createPendingDecisionButton(tr("pending.cancel"), false);
        pendingCancelButton.addActionListener(e -> {
            markGuidanceInteraction();
            app.cancelPendingConfirm();
            refreshFromSnapshot();
        });
        pendingApproveButton = createPendingDecisionButton(tr("pending.approve"), true);
        pendingApproveButton.addActionListener(e -> {
            markGuidanceInteraction();
            app.approvePendingConfirm();
            refreshFromSnapshot();
        });
        pendingCountdownLabel = new JLabel("T-0s");
        pendingCountdownLabel.setForeground(mutedForeground());
        pendingCountdownLabel.setFont(pendingCountdownLabel.getFont().deriveFont(Font.BOLD, Math.max(10f, pendingCountdownLabel.getFont().getSize2D() - 1.0f)));
        pendingPreviewLabel = createHistoryTextLabel(false);
        pendingPreviewLabel.setForeground(normalForeground());
        pendingPreviewLabel.setFont(pendingPreviewLabel.getFont().deriveFont(Font.PLAIN, 11.4f));

        JPanel pendingActionButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        pendingActionButtons.setOpaque(false);
        pendingActionButtons.add(pendingCancelButton);
        pendingActionButtons.add(pendingCountdownLabel);
        pendingActionButtons.add(pendingApproveButton);
        pendingActionRow.add(pendingActionButtons, BorderLayout.WEST);
        pendingActionRow.add(createHistoryBlock(pendingPreviewLabel, false), BorderLayout.CENTER);
        pendingSectionBody.add(pendingActionRow);
        pendingSectionBody.add(Box.createVerticalStrut(6));

        JPanel pendingRow = new JPanel();
        pendingRow.setOpaque(false);
        pendingRow.setLayout(new BoxLayout(pendingRow, BoxLayout.X_AXIS));
        pendingRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        instantButton = new JButton(tr("pending.mode.instant"));
        pendingButton = new JButton(tr("pending.mode.wait"));
        styleModeButton(instantButton, false);
        styleModeButton(pendingButton, false);
        instantButton.addActionListener(e -> {
            markGuidanceInteraction();
            app.setConfirmModeFromUi(false);
        });
        pendingButton.addActionListener(e -> {
            markGuidanceInteraction();
            app.setConfirmModeFromUi(true);
        });
        pendingSecCombo = new JComboBox<>(new Integer[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        pendingSecCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setText(value == null ? "" : (value + " sec"));
                return this;
            }
        });
        pendingSecCombo.addActionListener(e -> {
            if (updatingControls) return;
            Integer value = (Integer) pendingSecCombo.getSelectedItem();
            if (value != null) {
                markGuidanceInteraction();
                app.setConfirmSecondsFromUi(value);
            }
        });
        pendingRow.add(instantButton);
        pendingRow.add(Box.createHorizontalStrut(6));
        pendingRow.add(pendingButton);
        pendingRow.add(Box.createHorizontalStrut(6));
        pendingRow.add(new JLabel(tr("pending.wait_seconds")));
        pendingRow.add(Box.createHorizontalStrut(6));
        pendingRow.add(pendingSecCombo);
        pendingRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, pendingRow.getPreferredSize().height));
        pendingSectionBody.add(pendingRow);
        pendingSection.add(pendingSectionBody, BorderLayout.CENTER);
        setPendingSectionExpanded(app.getSimpleCardExpanded("live_pending", false));
        body.add(pendingSection);

        JPanel card = createBareCard(body);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor()),
                BorderFactory.createEmptyBorder(10, 0, 10, 12)
        ));
        return card;
    }

    private JPanel buildQuickActionsCard() {
        JPanel body = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        body.setOpaque(false);

        historyButton = createActionButton(tr("history.button"), new HistoryIcon());
        historyButton.addActionListener(e -> {
            markGuidanceInteraction();
            app.showHistory();
        });
        hearingButton = createActionButton(tr("quick.hearing"), new HearingFeatureIcon());
        hearingButton.addActionListener(e -> {
            markGuidanceInteraction();
            app.openHearingWindowFromSimpleUi();
        });
        companionButton = createActionButton(tr("quick.companion"), new MobEchoIcon());
        companionButton.addActionListener(e -> {
            markGuidanceInteraction();
            app.toggleCompanionWindowFromSimpleUi();
        });
        companionButton.setVisible(app.isCompanionUiAvailableForSimpleUi());

        body.add(historyButton);
        body.add(hearingButton);
        body.add(companionButton);
        return createBareCard(body);
    }

    private JPanel buildTranslationCard() {
        JPanel body = formBody();

        liveLangCombo = new JComboBox<>(app.getTalkLanguageOptions());
        liveLangCombo.setRenderer(LanguageOptions.whisperRenderer());
        liveLangCombo.addActionListener(e -> {
            if (updatingControls) return;
            String selected = Objects.toString(liveLangCombo.getSelectedItem(), "");
            if (!app.requestTalkLanguageChange(this, selected)) {
                refreshTalkControlSelections();
                return;
            }
            markGuidanceInteraction();
            refreshTalkControlSelections();
        });

        liveTranslateTargetCombo = new JComboBox<>(LanguageOptions.translationTargets());
        liveTranslateTargetCombo.setRenderer(LanguageOptions.translationRenderer());
        liveTranslateTargetCombo.addActionListener(e -> {
            if (updatingControls) return;
            String selected = Objects.toString(liveTranslateTargetCombo.getSelectedItem(), "OFF");
            markGuidanceInteraction();
            app.setTalkTranslateTarget(selected);
            app.prewarmPiperPlusForTalkTargetSelection(this, selected);
            app.maybeRecommendTalkTtsRoute(this, "talk-translate-target");
            refreshTalkControlSelections();
        });

        addExpandedFormRow(body, tr("translation.source"), liveLangCombo);
        addExpandedFormRow(body, tr("translation.target"), liveTranslateTargetCombo);
        addHelperTextRow(body, tr("translation.helper"));

        return createCard("translation", tr("translation.title"), tr("translation.desc"), new Color(0x74, 0xBC, 0xB6), body, false);
    }

    private JPanel buildAudioCard() {
        JPanel body = formBody();
        inputCombo = new JComboBox<>();
        outputCombo = new JComboBox<>();
        monitorVolumeCombo = new JComboBox<>(new String[]{"0%", "10%", "20%", "30%", "40%", "50%", "60%", "70%", "80%", "90%", "100%"});
        aiAssistToggle = new JCheckBox(tr("audio.ai_assist"));
        aiAssistToggle.setOpaque(false);

        for (String item : app.getInputsMixerNames()) inputCombo.addItem(item);
        for (String item : app.getOutputMixerNames()) outputCombo.addItem(item);

        inputCombo.addActionListener(e -> {
            if (updatingControls) return;
            Object selected = inputCombo.getSelectedItem();
            if (selected != null && !selected.toString().isBlank()) {
                app.requestInputDeviceChange(this, selected.toString());
            }
        });
        outputCombo.addActionListener(e -> {
            if (updatingControls) return;
            Object selected = outputCombo.getSelectedItem();
            if (selected != null && !selected.toString().isBlank()) {
                app.requestOutputDeviceChange(this, selected.toString());
            }
        });
        monitorVolumeCombo.addActionListener(e -> {
            if (updatingControls) return;
            Object selected = monitorVolumeCombo.getSelectedItem();
            if (selected == null) return;
            String text = selected.toString().replace("%", "").trim();
            try {
                app.setTtsMonitorVolumePercentFromUi(Integer.parseInt(text));
            } catch (NumberFormatException ignore) {
            }
        });
        aiAssistToggle.addActionListener(e -> {
            if (updatingControls) return;
            app.setAiAssistEnabledFromUi(aiAssistToggle.isSelected());
        });

        addExpandedFormRow(body, tr("audio.mic"), inputCombo);
        addExpandedFormRow(body, tr("audio.speaker"), outputCombo);
        addFormRow(body, tr("audio.monitor_volume"), monitorVolumeCombo);
        addFormRow(body, tr("audio.mic_boost"), aiAssistToggle);
        // Keep the AI assist helper directly above the Discord routing guide.
        // This avoids the guide visually swallowing the context text.
        addHelperTextRow(body, tr("audio.ai_assist.helper"));
        addAudioRoutingGuideRow(body);

        return createCard("audio", tr("audio.title"), tr("audio.desc"), new Color(0x63, 0xA9, 0xD1), body, false);
    }

    private JPanel buildVoiceCard() {
        JPanel body = formBody();
        languageCombo = new JComboBox<>(app.getTalkLanguageOptions());
        languageCombo.setRenderer(LanguageOptions.whisperRenderer());
        recognitionEngineCombo = new JComboBox<>(new String[]{"moonshine", "whisper"});
        recognitionEngineCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String raw = String.valueOf(value);
                setText("moonshine".equalsIgnoreCase(raw) ? tr("recog.engine.moonshine") : tr("recog.engine.whisper"));
                return this;
            }
        });
        ttsEngineCombo = new JComboBox<>(app.getSimpleTtsEngineChoices().toArray(new String[0]));
        ttsEngineCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setText(displayTtsEngine(value == null ? "auto" : value.toString()));
                return this;
            }
        });
        ttsVoiceCombo = new JComboBox<>();

        languageCombo.addActionListener(e -> {
            if (updatingControls) return;
            Object selected = languageCombo.getSelectedItem();
            if (selected != null) {
                if (!app.requestTalkLanguageChange(this, selected.toString())) {
                    refreshTalkControlSelections();
                    return;
                }
                refreshTalkControlSelections();
            }
        });
        recognitionEngineCombo.addActionListener(e -> {
            if (updatingControls) return;
            Object selected = recognitionEngineCombo.getSelectedItem();
            if (selected != null) {
                app.requestRecognitionEngineChange(this, selected.toString());
            }
        });
        ttsEngineCombo.addActionListener(e -> {
            if (updatingControls) return;
            Object selected = ttsEngineCombo.getSelectedItem();
            if (selected == null) return;
            app.setSimpleTtsEngineFromUi(selected.toString());
            reloadTtsVoiceChoices();
        });
        ttsVoiceCombo.addActionListener(e -> {
            if (updatingControls) return;
            Object engine = ttsEngineCombo.getSelectedItem();
            Object voice = ttsVoiceCombo.getSelectedItem();
            if (engine != null && voice != null) {
                app.setSimpleTtsVoiceFromUi(engine.toString(), voice.toString());
            }
        });

        addExpandedFormRow(body, tr("voice.language"), languageCombo);
        addExpandedFormRow(body, tr("voice.recognition_engine"), recognitionEngineCombo);
        addDualComboFormRow(body, tr("voice.tts_voice"), ttsEngineCombo, ttsVoiceCombo, 110);
        addHelperTextRow(body, tr("voice.helper"));

        return createCard("voice", tr("voice.title"), tr("voice.desc"), new Color(0x6C, 0xA5, 0xD7), body, false);
    }

    private JPanel buildRadioCard() {
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        JPanel controls = formBody();

        radioShortcutField = new JTextField();
        radioShortcutField.setEditable(false);
        JButton captureButton = new JButton(tr("radio.capture"));
        captureButton.addActionListener(e -> {
            app.captureRadioHotkeyFromUi(this);
            refreshFromSnapshot();
        });
        JButton overlayTestButton = new JButton(tr("radio.preview"));
        overlayTestButton.addActionListener(e -> app.previewRadioOverlayFromUi());
        JPanel shortcutRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        shortcutRow.setOpaque(false);
        radioShortcutField.setPreferredSize(new Dimension(170, 26));
        radioShortcutField.setMinimumSize(new Dimension(170, 26));
        radioShortcutField.setMaximumSize(new Dimension(220, 26));
        shortcutRow.add(radioShortcutField);
        shortcutRow.add(captureButton);
        shortcutRow.add(overlayTestButton);
        addExpandedFormRow(controls, tr("radio.shortcut"), shortcutRow);
        controls.add(helperLabel(tr("radio.shortcut.recommend")));
        controls.add(Box.createVerticalStrut(6));

        overlayPositionCombo = new JComboBox<>(new String[]{"TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"});
        overlayPositionCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setText(displayOverlayPosition(value == null ? "TOP_LEFT" : value.toString()));
                return this;
            }
        });
        overlayPositionCombo.addActionListener(e -> {
            if (updatingControls) return;
            Object selected = overlayPositionCombo.getSelectedItem();
            if (selected != null) app.setRadioOverlayPosition(selected.toString());
        });
        addExpandedFormRow(controls, tr("radio.overlay_position"), overlayPositionCombo);

        overlayBgPresetCombo = createPresetCombo(OVERLAY_BG_PRESETS);
        overlayBgPresetCombo.addActionListener(e -> {
            if (updatingControls) return;
            applyOverlayPresetSelections();
        });
        addExpandedFormRow(controls, tr("radio.bg_color"), overlayBgPresetCombo);

        overlayFgPresetCombo = createPresetCombo(OVERLAY_FG_PRESETS);
        overlayFgPresetCombo.addActionListener(e -> {
            if (updatingControls) return;
            applyOverlayPresetSelections();
        });
        addExpandedFormRow(controls, tr("radio.fg_color"), overlayFgPresetCombo);

        body.add(controls, BorderLayout.CENTER);

        JButton openConfigButton = new JButton(tr("radio.open_config"));
        openConfigButton.addActionListener(e -> app.openRadioChatConfigFileFromUi());
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        JPanel rightInfo = new JPanel();
        rightInfo.setOpaque(false);
        rightInfo.setLayout(new BoxLayout(rightInfo, BoxLayout.Y_AXIS));
        openConfigButton.setAlignmentX(Component.RIGHT_ALIGNMENT);
        rightInfo.add(openConfigButton);
        rightInfo.add(Box.createVerticalStrut(6));
        JTextArea infoText = helperLabel(tr("radio.helper"));
        infoText.setColumns(24);
        infoText.setAlignmentX(Component.RIGHT_ALIGNMENT);
        rightInfo.add(infoText);
        footer.add(rightInfo, BorderLayout.CENTER);
        body.add(footer, BorderLayout.SOUTH);

        return createCard("radio", tr("radio.title"), tr("radio.desc"), new Color(0x6B, 0xA5, 0xE2), body, false);
    }

    private void loadCurrentPrefs() {
        updatingControls = true;
        try {
            inputCombo.setSelectedItem(app.getSelectedInputDeviceForUi());
            outputCombo.setSelectedItem(app.getSelectedOutputDeviceForUi());
            monitorVolumeCombo.setSelectedItem(app.getTtsMonitorVolumePercentForUi() + "%");
            aiAssistToggle.setSelected(app.isAiAssistEnabledForUi());

            refreshTalkControlSelections();
            recognitionEngineCombo.setSelectedItem(app.getSelectedRecognitionEngineForUi());
            ttsEngineCombo.setSelectedItem(app.getSelectedTtsEngineForUi());
            reloadTtsVoiceChoices();

            pendingSecCombo.setSelectedItem(app.getConfirmSecondsForUi());
            radioShortcutField.setText(app.getRadioHotkeyDisplayForUi());
            overlayPositionCombo.setSelectedItem(app.getOverlayPositionForUi());
            currentOverlayBg = app.getOverlayBgForUi();
            currentOverlayFg = app.getOverlayFgForUi();
            selectPreset(overlayBgPresetCombo, OVERLAY_BG_PRESETS, currentOverlayBg);
            selectPreset(overlayFgPresetCombo, OVERLAY_FG_PRESETS, currentOverlayFg);
        } finally {
            updatingControls = false;
        }
    }

    private void reloadTtsVoiceChoices() {
        updatingControls = true;
        try {
            String engine = valueOrDefault(ttsEngineCombo.getSelectedItem(), "auto");
            ttsVoiceCombo.removeAllItems();
            List<String> voices = app.getSimpleTtsVoiceChoices(engine);
            for (String voice : voices) ttsVoiceCombo.addItem(voice);
            ttsVoiceCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    setText(displayVoiceLabel(engine, value == null ? "auto" : value.toString()));
                    return this;
                }
            });
            ttsVoiceCombo.setSelectedItem(app.getSelectedSimpleTtsVoice(engine));
        } finally {
            updatingControls = false;
        }
    }

    private void refreshFromSnapshot() {
        MobMateWhisp.SimpleMainSnapshot snapshot = app.getSimpleMainSnapshot();
        refreshHeaderStatus(snapshot);
        if (liveMeter != null) {
            liveMeter.setValue(snapshot.inputLevel(), snapshot.inputDb(), false, 1.0f, 1.0f, true);
            liveMeter.setSpeakerValue(snapshot.speakerLevel(), snapshot.speakerDb());
        }

        List<String> recent = snapshot.recentLines();
        String partialPreview = MobMateWhisp.getLastPartial();
        boolean showPartial = snapshot.transcribing() && partialPreview != null && !partialPreview.isBlank();
        historyBlock1.setFullText(showPartial
                ? partialPreview.trim()
                : toDisplayLine(snapshot.latestText(), tr("history.empty_latest")));
        applyPrimaryHistoryBlockStyle(showPartial);
        for (int i = 0; i < historyTailBlocks.size(); i++) {
            historyTailBlocks.get(i).setFullText(safeRecentLine(recent, i));
        }

        styleModeButton(instantButton, !snapshot.pendingMode());
        styleModeButton(pendingButton, snapshot.pendingMode());
        if (!updatingControls) {
            updatingControls = true;
            try {
                pendingSecCombo.setSelectedItem(snapshot.pendingSec());
                radioShortcutField.setText(app.getRadioHotkeyDisplayForUi());
            } finally {
                updatingControls = false;
            }
        }
        historyButton.setToolTipText(snapshot.pendingQueueCount() > 0
                ? (tr("history.tooltip_with_pending") + " +" + snapshot.pendingQueueCount())
                : tr("history.tooltip"));

        boolean hasPendingItem = app.hasPendingConfirm();
        String pendingPreview = app.getPendingConfirmPreviewText();
        int remainingSec = Math.max(0, app.getPendingConfirmRemainingSeconds());
        if (pendingActionRow != null) {
            pendingActionRow.setVisible(hasPendingItem);
        }
        if (pendingCountdownLabel != null) {
            pendingCountdownLabel.setText("T-" + remainingSec + "s");
        }
        if (pendingPreviewLabel != null) {
            pendingPreviewLabel.setFullText(toDisplayLine(pendingPreview, tr("pending.preview_empty")));
        }
        if (pendingSectionSummaryLabel != null) {
            if (hasPendingItem) {
                String queueText = snapshot.pendingQueueCount() > 1 ? (" +" + snapshot.pendingQueueCount()) : "";
                pendingSectionSummaryLabel.setText(tr("pending.waiting_short") + "  T-" + remainingSec + "s" + queueText);
                pendingSectionSummaryLabel.setForeground(mix(new Color(0x74, 0xBC, 0xB6), labelForeground(), 0.25f));
            } else if (snapshot.pendingMode()) {
                pendingSectionSummaryLabel.setText(tr("pending.wait_mode_short"));
                pendingSectionSummaryLabel.setForeground(mutedForeground());
            } else {
                pendingSectionSummaryLabel.setText("");
            }
        }

        refreshGuidance(snapshot);

        recordButton.setEnabled(snapshot.calibrationComplete());
        if (!snapshot.calibrationComplete() && !snapshot.recording()) {
            recordButton.setText(tr("record.calibrating"));
            applyRecordButtonState(false, false);
        } else if (snapshot.recording()) {
            recordButton.setText(tr("record.stop"));
            applyRecordButtonState(true, true);
        } else {
            recordButton.setText(tr("record.start"));
            applyRecordButtonState(true, false);
        }
        refreshTalkControlSelections();
    }

    private void refreshGuidance(MobMateWhisp.SimpleMainSnapshot snapshot) {
        if (liveHelperLabel == null || snapshot == null) {
            return;
        }
        SimpleGuidanceEvaluator.Context context = new SimpleGuidanceEvaluator.Context(
                snapshot.uiLanguage(),
                snapshot.calibrationComplete(),
                snapshot.recording(),
                snapshot.transcribing(),
                snapshot.speakerEnrolling(),
                snapshot.speakerEnrollCount(),
                snapshot.speakerEnrollRequired(),
                snapshot.inputLevel(),
                snapshot.pendingMode(),
                snapshot.pendingRemainingSec(),
                snapshot.pendingQueueCount(),
                snapshot.inputConfigured(),
                snapshot.outputConfigured(),
                snapshot.translateEnabled(),
                snapshot.translateTarget(),
                snapshot.radioConfigured(),
                snapshot.featureAnnouncement()
        );
        SimpleGuidanceEvaluator.Result result = guidanceEvaluator.evaluate(context);
        SimpleGuidanceEvaluator.GuidanceMessage selected = pickGuidanceMessage(result);
        if (selected == null) {
            liveHelperLabel.setAnimatedText("", mutedForeground());
            return;
        }
        liveHelperLabel.setAnimatedText(formatGuidanceMessage(selected), colorForGuidance(selected.severity()));
    }

    private SimpleGuidanceEvaluator.GuidanceMessage pickGuidanceMessage(SimpleGuidanceEvaluator.Result result) {
        if (result == null) {
            return null;
        }
        if (result.immediate() != null) {
            guidancePassiveSignature = "";
            guidancePassiveIndex = 0;
            guidanceLastRotatedAt = 0L;
            return result.immediate();
        }
        List<SimpleGuidanceEvaluator.GuidanceMessage> passive = result.passive();
        if (passive == null || passive.isEmpty()) {
            guidancePassiveSignature = "";
            guidancePassiveIndex = 0;
            guidanceLastRotatedAt = 0L;
            return null;
        }
        String signature = buildGuidanceSignature(passive);
        long now = System.currentTimeMillis();
        if (!signature.equals(guidancePassiveSignature)) {
            guidancePassiveSignature = signature;
            guidancePassiveIndex = 0;
            guidanceLastRotatedAt = now;
        } else if (guidanceLastRotatedAt <= 0L) {
            guidanceLastRotatedAt = now;
        } else if (now - guidanceLastRotatedAt >= GUIDANCE_ROTATE_INTERVAL_MS) {
            guidancePassiveIndex = (guidancePassiveIndex + 1) % passive.size();
            guidanceLastRotatedAt = now;
        }
        return passive.get(Math.min(guidancePassiveIndex, passive.size() - 1));
    }

    private String buildGuidanceSignature(List<SimpleGuidanceEvaluator.GuidanceMessage> passive) {
        StringBuilder sb = new StringBuilder();
        for (SimpleGuidanceEvaluator.GuidanceMessage msg : passive) {
            if (msg == null) continue;
            if (sb.length() > 0) sb.append('|');
            sb.append(msg.key()).append(':').append(msg.text());
        }
        return sb.toString();
    }

    private String formatGuidanceMessage(SimpleGuidanceEvaluator.GuidanceMessage msg) {
        return (guidancePrefix(msg.severity()) + " " + msg.text()).trim();
    }

    private String guidancePrefix(SimpleGuidanceEvaluator.Severity severity) {
        String lang = app.getUiLanguageForSimpleUi();
        String normalized = lang == null ? "en" : lang.toLowerCase(Locale.ROOT);
        boolean ja = normalized.startsWith("ja");
        boolean ko = normalized.startsWith("ko");
        boolean zhTw = normalized.startsWith("zh_tw") || normalized.startsWith("zh-tw");
        boolean zhCn = normalized.startsWith("zh_cn") || normalized.startsWith("zh-cn") || normalized.equals("zh");
        return switch (severity) {
            case CAUTION -> {
                if (ja) yield "注意:";
                if (ko) yield "주의:";
                if (zhTw) yield "注意:";
                if (zhCn) yield "注意:";
                yield "Note:";
            }
            case GUIDE -> {
                if (ja) yield "案内:";
                if (ko) yield "안내:";
                if (zhTw) yield "指引:";
                if (zhCn) yield "指引:";
                yield "Guide:";
            }
            case TIP -> {
                if (ja) yield "ヒント:";
                if (ko) yield "힌트:";
                if (zhTw) yield "提示:";
                if (zhCn) yield "提示:";
                yield "Tip:";
            }
        };
    }

    private Color colorForGuidance(SimpleGuidanceEvaluator.Severity severity) {
        return switch (severity) {
            case CAUTION -> mix(new Color(0xF2, 0xA6, 0x3A), labelForeground(), 0.35f);
            case GUIDE -> labelForeground();
            case TIP -> mix(accentBlue(), labelForeground(), 0.30f);
        };
    }

    private void markGuidanceInteraction() {
        guidanceLastRotatedAt = 0L;
        guidancePassiveSignature = "";
    }

    private void refreshTalkControlSelections() {
        boolean wasUpdating = updatingControls;
        updatingControls = true;
        try {
            reloadTalkLanguageChoices(languageCombo, app.getTalkLanguage());
            reloadTalkLanguageChoices(liveLangCombo, app.getTalkLanguage());
            if (languageCombo != null) {
                languageCombo.setSelectedItem(app.getTalkLanguage());
            }
            if (liveLangCombo != null) {
                liveLangCombo.setSelectedItem(app.getTalkLanguage());
            }
            if (liveTranslateTargetCombo != null) {
                liveTranslateTargetCombo.setSelectedItem(app.getTalkTranslateTarget());
            }
        } finally {
            updatingControls = wasUpdating;
        }
    }

    private void reloadTalkLanguageChoices(JComboBox<String> combo, String selectedValue) {
        if (combo == null) return;
        combo.setModel(new DefaultComboBoxModel<>(app.getTalkLanguageOptions()));
        combo.setRenderer(LanguageOptions.whisperRenderer());
        if (selectedValue != null && !selectedValue.isBlank()) {
            combo.setSelectedItem(selectedValue);
        }
    }

    private void setPendingSectionExpanded(boolean expanded) {
        if (pendingSectionBody == null) return;
        pendingSectionBody.setVisible(expanded);
        if (pendingSectionToggleIcon != null) {
            pendingSectionToggleIcon.setExpanded(expanded);
        }
        if (pendingSectionToggleButton != null) {
            pendingSectionToggleButton.repaint();
        }
        app.setSimpleCardExpanded("live_pending", expanded);
        revalidate();
        repaint();
        SwingUtilities.invokeLater(this::trimWindowHeightIfNeeded);
    }

    private JPanel createCard(String key, String title, String desc, Color accent, JComponent body, boolean expanded) {
        return new CollapsibleCard(key, title, desc, accent, body, expanded);
    }

    private JPanel createBareCard(JComponent body) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(true);
        panel.setBackground(cardBackground());
        panel.setBorder(cardBorder());
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private JPanel formBody() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        return body;
    }

    private void trimWindowHeightIfNeeded() {
        Window window = SwingUtilities.getWindowAncestor(this);
        if (!(window instanceof RootPaneContainer rootPaneContainer)) return;
        if (contentScrollPane != null && scrollContentPanel != null) {
            Dimension scrollPref = contentScrollPane.getPreferredSize();
            Dimension contentPref = scrollContentPanel.getPreferredSize();
            contentScrollPane.setPreferredSize(new Dimension(scrollPref.width, contentPref.height));
        }
        Dimension pref = rootPaneContainer.getContentPane().getPreferredSize();
        int targetHeight = pref.height + window.getInsets().top + window.getInsets().bottom;
        int minHeight = Math.max(360, window.getMinimumSize().height);
        if (targetHeight + 4 < window.getHeight()) {
            window.setSize(window.getWidth(), Math.max(minHeight, targetHeight));
        }
        window.revalidate();
    }

    private void addFormRow(JPanel body, String label, JComponent component) {
        JLabel l = new JLabel(label);
        l.setForeground(labelForeground());
        l.setPreferredSize(new Dimension(92, 24));
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(26, component.getPreferredSize().height)));
        row.add(l, BorderLayout.WEST);
        row.add(wrapCompactField(component), BorderLayout.CENTER);
        body.add(row);
        body.add(Box.createVerticalStrut(6));
    }

    private void addExpandedFormRow(JPanel body, String label, JComponent component) {
        JLabel l = new JLabel(label);
        l.setForeground(labelForeground());
        l.setPreferredSize(new Dimension(92, 24));
        JComponent field = prepareExpandedField(component, 0);
        int rowHeight = Math.max(26, Math.max(l.getPreferredSize().height, field.getPreferredSize().height));
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowHeight));
        row.add(l, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        body.add(row);
        body.add(Box.createVerticalStrut(6));
    }

    private void addDualComboFormRow(JPanel body, String label, JComboBox<?> left, JComboBox<?> right, int minEachWidth) {
        JLabel l = new JLabel(label);
        l.setForeground(labelForeground());
        l.setPreferredSize(new Dimension(92, 24));

        JComboBox<?> leftField = prepareComboField(left, minEachWidth);
        JComboBox<?> rightField = prepareComboField(right, minEachWidth);

        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        fields.setAlignmentX(Component.LEFT_ALIGNMENT);
        fields.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(leftField.getPreferredSize().height, rightField.getPreferredSize().height)));

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = 0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weighty = 1.0;

        gc.gridx = 0;
        gc.weightx = 0.48;
        gc.insets = new Insets(0, 0, 0, 8);
        fields.add(leftField, gc);

        gc.gridx = 1;
        gc.weightx = 0.52;
        gc.insets = new Insets(0, 0, 0, 0);
        fields.add(rightField, gc);

        int rowHeight = Math.max(26, Math.max(l.getPreferredSize().height, fields.getPreferredSize().height));
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowHeight));
        row.add(l, BorderLayout.WEST);
        row.add(fields, BorderLayout.CENTER);
        body.add(row);
        body.add(Box.createVerticalStrut(6));
    }

    private void addHelperTextRow(JPanel body, String text) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(Box.createHorizontalStrut(102), BorderLayout.WEST);
        JTextArea helper = helperLabel(text);
        helper.setColumns(26);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, helper.getPreferredSize().height));
        row.add(helper, BorderLayout.CENTER);
        body.add(row);
        body.add(Box.createVerticalStrut(6));
    }

    private void addAudioRoutingGuideRow(JPanel body) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel guide = new JPanel();
        guide.setOpaque(true);
        guide.setBackground(mix(new Color(0x4A, 0x5C, 0x74), cardBackground(), 0.72f));
        guide.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(mix(new Color(0x63, 0xA9, 0xD1), borderColor(), 0.55f)),
                BorderFactory.createEmptyBorder(8, 10, 4, 10)
        ));
        guide.setLayout(new BoxLayout(guide, BoxLayout.Y_AXIS));
        guide.setAlignmentX(Component.LEFT_ALIGNMENT);
        guide.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        guide.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                guide.revalidate();
                guide.repaint();
            }
        });

        JLabel title = new JLabel(tr("audio.discord.title"));
        title.setForeground(new Color(0x8F, 0xC8, 0xFF));
        title.setFont(title.getFont().deriveFont(Font.BOLD, Math.max(10.0f, title.getFont().getSize2D() - 0.2f)));
        guide.add(title);
        guide.add(Box.createVerticalStrut(6));
        guide.add(createAudioRoutingGuideLine("MobMate", new Color(0x63, 0xA9, 0xD1), tr("audio.discord.input")));
        guide.add(Box.createVerticalStrut(4));
        guide.add(createAudioRoutingGuideLine(tr("audio.discord.recommend.badge"), new Color(0xE0, 0x8F, 0x2F), tr("audio.discord.output_recommend")));
        guide.add(Box.createVerticalStrut(4));
        guide.add(createAudioRoutingGuideLine("Discord", new Color(0x74, 0xBC, 0xB6), tr("audio.discord.output")));
        guide.add(Box.createVerticalStrut(4));
        guide.add(createAudioRoutingGuideLine("Discord", new Color(0xF3, 0xAB, 0x38), tr("audio.discord.speaker")));

        row.add(guide, BorderLayout.CENTER);
        body.add(row);
        body.add(Box.createVerticalStrut(0));
    }

    private JPanel createAudioRoutingGuideLine(String badgeText, Color badgeColor, String lineText) {
        JPanel line = new JPanel(new GridBagLayout());
        line.setOpaque(false);
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel badge = new JLabel(badgeText);
        badge.setOpaque(true);
        badge.setForeground(Color.WHITE);
        badge.setBackground(badgeColor);
        badge.setBorder(BorderFactory.createEmptyBorder(2, 7, 2, 7));
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, Math.max(9.0f, badge.getFont().getSize2D() - 1.1f)));

        JTextArea text = guideLabel(lineText);
        text.setForeground(mix(labelForeground(), Color.WHITE, 0.1f));
        text.setFont(text.getFont().deriveFont(Math.max(9f, text.getFont().getSize2D() - 2.0f)));
        text.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = 0;
        gc.insets = new Insets(0, 0, 0, 8);
        gc.anchor = GridBagConstraints.NORTHWEST;

        gc.gridx = 0;
        gc.weightx = 0.0;
        gc.fill = GridBagConstraints.NONE;
        line.add(badge, gc);

        gc.gridx = 1;
        gc.weightx = 1.0;
        gc.insets = new Insets(0, 0, 0, 0);
        gc.fill = GridBagConstraints.HORIZONTAL;
        line.add(text, gc);
        return line;
    }

    private JTextArea guideLabel(String text) {
        JTextArea label = new WrappingTextArea(text, 180);
        label.setForeground(mutedForeground());
        label.setFont(label.getFont().deriveFont(Math.max(10f, label.getFont().getSize2D() - 2f)));
        label.setEditable(false);
        label.setFocusable(false);
        label.setLineWrap(true);
        label.setWrapStyleWord(true);
        label.setOpaque(false);
        label.setBorder(BorderFactory.createEmptyBorder());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private FadingHistoryTextLabel createLiveGuidanceLabel(String text) {
        FadingHistoryTextLabel label = new FadingHistoryTextLabel();
        label.setForeground(mutedForeground());
        label.setFont(label.getFont().deriveFont(Font.PLAIN, Math.max(9.2f, label.getFont().getSize2D() - 3.0f)));
        label.setAnimatedText(text, mutedForeground());
        label.setIcon(null);
        label.setBorder(BorderFactory.createEmptyBorder());
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(20, label.getPreferredSize().height)));
        return label;
    }

    private JButton createPendingDecisionButton(String text, boolean approve) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setFont(button.getFont().deriveFont(Font.BOLD, Math.max(10.5f, button.getFont().getSize2D() - 1.0f)));
        button.setMargin(new Insets(4, 10, 4, 10));
        button.setOpaque(true);
        button.setForeground(approve ? Color.WHITE : normalForeground());
        button.setBackground(approve ? accentSuccess() : subtleSurfaceBackground());
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(approve ? accentSuccess().darker() : borderColor()),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)
        ));
        return button;
    }

    private JComponent prepareExpandedField(JComponent component, int minWidth) {
        Dimension pref = component.getPreferredSize();
        int height = Math.max(26, pref.height);
        component.setMinimumSize(new Dimension(minWidth, height));
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        component.setPreferredSize(new Dimension(Math.max(pref.width, minWidth), height));
        return component;
    }

    private <T extends JComboBox<?>> T prepareComboField(T combo, int minWidth) {
        prepareExpandedField(combo, minWidth);
        return combo;
    }

    private JTextArea helperLabel(String text) {
        JTextArea label = new JTextArea(text);
        label.setForeground(mutedForeground());
        label.setFont(label.getFont().deriveFont(Math.max(11f, label.getFont().getSize2D() - 1f)));
        label.setEditable(false);
        label.setFocusable(false);
        label.setLineWrap(true);
        label.setWrapStyleWord(true);
        label.setOpaque(false);
        label.setBorder(BorderFactory.createEmptyBorder());
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static final class WrappingTextArea extends JTextArea {
        private final int minimumWrapWidth;

        private WrappingTextArea(String text, int minimumWrapWidth) {
            super(text);
            this.minimumWrapWidth = minimumWrapWidth;
        }

        @Override
        public Dimension getPreferredSize() {
            int wrapWidth = resolveWrapWidth();
            if (wrapWidth > 0) {
                super.setSize(wrapWidth, Short.MAX_VALUE);
            }
            Dimension pref = super.getPreferredSize();
            return new Dimension(pref.width, pref.height);
        }

        @Override
        public Dimension getMinimumSize() {
            Dimension pref = getPreferredSize();
            return new Dimension(Math.min(minimumWrapWidth, pref.width), pref.height);
        }

        private int resolveWrapWidth() {
            if (getWidth() > 0) {
                return Math.max(minimumWrapWidth, getWidth());
            }
            Container parent = getParent();
            if (parent == null) {
                return minimumWrapWidth;
            }
            int width = parent.getWidth();
            if (width <= 0) {
                return minimumWrapWidth;
            }
            Insets insets = parent.getInsets();
            width -= insets.left + insets.right;
            for (Component sibling : parent.getComponents()) {
                if (sibling == this || !sibling.isVisible()) continue;
                width -= sibling.getPreferredSize().width;
            }
            if (parent.getLayout() instanceof GridBagLayout) {
                width -= 8;
            }
            return Math.max(minimumWrapWidth, width);
        }
    }

    private final class HistoryBubblePanel extends JPanel {
        private final boolean primary;

        private HistoryBubblePanel(boolean primary) {
            this.primary = primary;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                int arc = 12;
                int tailH = primary ? 6 : 0;
                int bodyH = h - tailH;

                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, w - 1, Math.max(0, bodyH - 1), arc, arc);
                if (primary) {
                    Polygon tail = new Polygon();
                    tail.addPoint(14, bodyH - 2);
                    tail.addPoint(22, bodyH - 2);
                    tail.addPoint(12, h - 1);
                    g2.fillPolygon(tail);
                }
            } finally {
                g2.dispose();
            }
        }

        @Override
        protected void paintBorder(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(borderColor());
                int w = getWidth();
                int h = getHeight();
                int arc = 12;
                int tailH = primary ? 6 : 0;
                int bodyH = h - tailH;
                g2.drawRoundRect(0, 0, w - 1, Math.max(0, bodyH - 1), arc, arc);
                if (primary) {
                    g2.drawLine(14, bodyH - 1, 12, h - 2);
                    g2.drawLine(22, bodyH - 1, 12, h - 2);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    private HistoryTextLabel createHistoryTextLabel(boolean primary) {
        HistoryTextLabel label = new HistoryTextLabel();
        label.setForeground(primary ? normalForeground() : secondaryForeground());
        float size = primary ? 12.5f : 11.8f;
        label.setFont(label.getFont().deriveFont(primary ? Font.BOLD : Font.PLAIN, size));
        label.setIconTextGap(6);
        return label;
    }

    private JPanel createHistoryBlock(HistoryTextLabel label, boolean primary) {
        JPanel panel = new HistoryBubblePanel(primary);
        panel.setLayout(new BorderLayout());
        panel.setOpaque(false);
        panel.setBackground(primary ? subtleSurfaceBackground().brighter() : subtleSurfaceBackground());
        panel.setBorder(BorderFactory.createEmptyBorder(6, 9, 6, 9));
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    private Icon sectionIconForKey(String key) {
        return switch (key) {
            case "pending" -> new SectionSymbolIcon(SectionSymbolIcon.Kind.PENDING);
            case "translation" -> new SectionSymbolIcon(SectionSymbolIcon.Kind.TRANSLATION);
            case "audio" -> new SectionSymbolIcon(SectionSymbolIcon.Kind.AUDIO);
            case "voice" -> new SectionSymbolIcon(SectionSymbolIcon.Kind.VOICE);
            case "radio" -> new SectionSymbolIcon(SectionSymbolIcon.Kind.RADIO);
            default -> new BubbleIcon("•");
        };
    }

    private void applyPrimaryHistoryBlockStyle(boolean partialMode) {
        if (historyBlock1 == null || historyBlockPanel1 == null) return;
        if (partialMode) {
            Color bg = subtleSurfaceBackground();
            Color partialBg = new Color(
                    Math.max(0, bg.getRed() - 3),
                    Math.max(0, bg.getGreen() - 3),
                    Math.min(255, bg.getBlue() + 10)
            );
            historyBlockPanel1.setBackground(partialBg);
            historyBlock1.setForeground(new Color(110, 150, 210));
            historyBlock1.setIcon(new PartialPreviewIcon());
        } else {
            historyBlockPanel1.setBackground(subtleSurfaceBackground().brighter());
            historyBlock1.setForeground(normalForeground());
            historyBlock1.setIcon(null);
        }
    }

    private String compactStatusLabel(String raw) {
        if (raw == null || raw.isBlank()) return tr("status.ready");
        String text = raw.trim()
                .replace("● ", "")
                .replace("▪ ", "")
                .replace("◉ ", "")
                .replace("[", "")
                .replace("]", "");
        return text.isBlank() ? tr("status.ready") : text;
    }

    private String compactSpeakerStatusLabel(MobMateWhisp.SimpleMainSnapshot snapshot) {
        String base = tr("status.spk.short");
        if (snapshot == null || !snapshot.speakerEnabled()) {
            return "○" + base;
        }
        if (snapshot.speakerEnrolling()) {
            return "◎" + base + "+";
        }
        if (snapshot.speakerStaleSession()) {
            return "◐" + base;
        }
        if (snapshot.speakerReady()) {
            return "●" + base;
        }
        String raw = compactStatusLabel(snapshot.speakerText());
        if (raw == null || raw.isBlank()) {
            return base;
        }
        return raw
                .replace("○SPK", "○" + base)
                .replace("◐SPK", "◐" + base)
                .replace("●SPK", "●" + base)
                .replace("◎SPK", "◎" + base);
    }

    private void refreshHeaderStatus(MobMateWhisp.SimpleMainSnapshot snapshot) {
        if (headerPrimaryStatusLabel == null) return;
        applyPrimaryStatusTokenWidth();
        applySpeakerStatusTokenWidth();
        headerPrimaryStatusLabel.setAnimatedText(
                compactStatusLabel(snapshot.statusText()),
                snapshot.statusColor() != null ? snapshot.statusColor() : normalForeground()
        );

        headerHearingStatusLabel.setText(snapshot.hearingVisible() ? tr("status.hearing.on") : tr("status.hearing.off"));
        headerHearingStatusLabel.setForeground(snapshot.hearingVisible() ? new Color(106, 169, 245) : mutedForeground());

        headerCompanionStatusLabel.setText(snapshot.companionVisible() ? tr("status.echo.on") : tr("status.echo.off"));
        headerCompanionStatusLabel.setForeground(snapshot.companionVisible() ? new Color(243, 171, 56) : mutedForeground());

        headerSpeakerStatusLabel.setText(compactSpeakerStatusLabel(snapshot));
        headerSpeakerStatusLabel.setForeground(snapshot.speakerColor() != null ? snapshot.speakerColor() : mutedForeground());
        headerSpeakerStatusLabel.setToolTipText(snapshot.speakerText());

        headerAiAssistStatusLabel.setText(compactAiAssistStatusLabel(snapshot.aiAssistText()));
        headerAiAssistStatusLabel.setForeground(snapshot.aiAssistColor() != null ? snapshot.aiAssistColor() : mutedForeground());

        String backend = compactBackendMode(app.getCpuGpuMode());
        headerBackendStatusLabel.setText(backend);
        headerBackendStatusLabel.setForeground(colorForBackendMode(backend));
        headerBackendStatusLabel.setToolTipText(tr("backend.tooltip") + ": " + app.getCpuGpuMode());
    }

    private JLabel createChip(String text, Color fg) {
        JLabel label = new JLabel(text);
        label.setForeground(fg);
        label.setFont(label.getFont().deriveFont(Font.BOLD, Math.max(10f, label.getFont().getSize2D() - 1.4f)));
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor()),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
        ));
        return label;
    }

    private JLabel createHeaderStatusToken(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(mutedForeground());
        label.setFont(label.getFont().deriveFont(Font.BOLD, Math.max(8f, label.getFont().getSize2D() - 2.2f)));
        return label;
    }

    private FadingStatusLabel createPrimaryHeaderStatusToken(String text) {
        FadingStatusLabel label = new FadingStatusLabel();
        label.setForeground(mutedForeground());
        label.setFont(label.getFont().deriveFont(Font.BOLD, Math.max(8f, label.getFont().getSize2D() - 2.2f)));
        label.setAnimatedText(text, mutedForeground());
        return label;
    }

    private void applyPrimaryStatusTokenWidth() {
        if (headerPrimaryStatusLabel == null) return;
        FontMetrics fm = headerPrimaryStatusLabel.getFontMetrics(headerPrimaryStatusLabel.getFont());
        String[] candidates = new String[] {
                compactStatusLabel(UiText.t("ui.simple.status.primary.ready")),
                compactStatusLabel(UiText.t("ui.simple.status.primary.trans")),
                compactStatusLabel(UiText.t("ui.simple.status.primary.rec"))
        };
        int width = 0;
        for (String candidate : candidates) {
            width = Math.max(width, fm.stringWidth(candidate == null ? "" : candidate));
        }
        width += 8;
        Dimension pref = headerPrimaryStatusLabel.getPreferredSize();
        Dimension fixed = new Dimension(width, pref.height);
        headerPrimaryStatusLabel.setPreferredSize(fixed);
        headerPrimaryStatusLabel.setMinimumSize(fixed);
        headerPrimaryStatusLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
    }

    private void applySpeakerStatusTokenWidth() {
        if (headerSpeakerStatusLabel == null) return;
        FontMetrics fm = headerSpeakerStatusLabel.getFontMetrics(headerSpeakerStatusLabel.getFont());
        String base = tr("status.spk.short");
        String[] candidates = new String[] {
                "○" + base,
                "◐" + base,
                "●" + base,
                "◎" + base + "+"
        };
        int width = 0;
        for (String candidate : candidates) {
            width = Math.max(width, fm.stringWidth(candidate == null ? "" : candidate));
        }
        width += 6;
        Dimension pref = headerSpeakerStatusLabel.getPreferredSize();
        Dimension fixed = new Dimension(width, pref.height);
        headerSpeakerStatusLabel.setPreferredSize(fixed);
        headerSpeakerStatusLabel.setMinimumSize(fixed);
        headerSpeakerStatusLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
    }

    private String compactAiAssistStatusLabel(String raw) {
        String shortLabel = tr("status.aia.short");
        if (raw == null || raw.isBlank()) return shortLabel;
        String text = raw.trim();
        if (text.startsWith("○")) {
            text = "○" + text.substring(1).replaceFirst("^AIA", shortLabel);
        } else if (text.startsWith("AIA")) {
            text = shortLabel + text.substring(3);
        }
        return text;
    }

    private JLabel createHeaderStatusSeparator() {
        JLabel label = new JLabel("/");
        label.setForeground(mutedForeground());
        label.setFont(label.getFont().deriveFont(Math.max(8f, label.getFont().getSize2D() - 2.5f)));
        return label;
    }

    private JButton createActionButton(String text, String glyph) {
        return createActionButton(text, new BubbleIcon(glyph));
    }

    private JButton createActionButton(String text, Icon icon) {
        JButton button = new JButton(text, icon);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setIconTextGap(6);
        button.setFocusPainted(false);
        button.setBackground(cardBackground());
        button.setForeground(normalForeground());
        button.setFont(button.getFont().deriveFont(Math.max(11f, button.getFont().getSize2D() - 0.8f)));
        button.setMargin(new Insets(6, 8, 6, 8));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor()),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        Dimension size = new Dimension(132, 38);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        return button;
    }

    private JButton createRecordActionButton(String text) {
        JButton button = new JButton(text, new RecordStateIcon(false));
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setIconTextGap(8);
        button.setFocusPainted(false);
        button.setForeground(Color.WHITE);
        button.setFont(button.getFont().deriveFont(Font.BOLD, Math.max(12f, button.getFont().getSize2D() + 0.2f)));
        button.setMargin(new Insets(6, 10, 6, 10));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(47, 141, 123)),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)
        ));
        button.setBackground(new Color(54, 161, 140));
        Dimension size = new Dimension(152, 40);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        return button;
    }

    private void styleModeButton(AbstractButton button, boolean selected) {
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setForeground(selected ? Color.WHITE : normalForeground());
        button.setBackground(selected ? accentSuccess() : subtleSurfaceBackground());
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(selected ? accentSuccess().darker() : borderColor()),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)
        ));
    }

    private void applyRecordButtonState(boolean enabled, boolean recording) {
        if (recordButton == null) return;
        recordButton.setEnabled(enabled);
        recordButton.setIcon(new RecordStateIcon(recording));
        if (!enabled) {
            recordButton.setBackground(new Color(97, 108, 118));
            recordButton.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(86, 95, 103)),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)
            ));
            return;
        }
        Color bg = recording ? new Color(183, 68, 74) : new Color(54, 161, 140);
        Color border = recording ? new Color(151, 53, 59) : new Color(47, 141, 123);
        recordButton.setBackground(bg);
        recordButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)
        ));
    }

    private JButton createHeaderTextButton(String text) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.setMargin(new Insets(4, 8, 4, 8));
        button.setFont(button.getFont().deriveFont(Math.max(10f, button.getFont().getSize2D() - 1.5f)));
        button.setBackground(subtleSurfaceBackground());
        button.setForeground(normalForeground());
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor()),
                BorderFactory.createEmptyBorder(0, 2, 0, 2)
        ));
        return button;
    }

    private JButton createHeaderButton(boolean danger) {
        JButton button = new JButton(new HeaderIcon(danger ? HeaderIcon.Kind.CLOSE : HeaderIcon.Kind.MENU, danger));
        button.setFocusable(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setPreferredSize(new Dimension(28, 28));
        button.setMinimumSize(new Dimension(28, 28));
        button.setMaximumSize(new Dimension(28, 28));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(danger ? new Color(150, 60, 70) : borderColor()),
                BorderFactory.createEmptyBorder(0, 0, 0, 0)
        ));
        if (danger) {
            button.setBackground(new Color(201, 67, 79));
            button.setForeground(Color.WHITE);
        } else {
            button.setBackground(subtleSurfaceBackground());
            button.setForeground(normalForeground());
        }
        return button;
    }

    private JComponent wrapCompactField(JComponent component) {
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        wrap.setOpaque(false);
        Dimension pref = component.getPreferredSize();
        int height = Math.max(26, pref.height);
        int width = computeCompactWidth(component);
        component.setPreferredSize(new Dimension(width, height));
        component.setMaximumSize(new Dimension(width, height));
        wrap.add(component);
        return wrap;
    }

    private int computeCompactWidth(JComponent component) {
        if (component == inputCombo || component == outputCombo) return 300;
        if (component == monitorVolumeCombo) return 92;
        if (component == languageCombo) return 150;
        if (component == recognitionEngineCombo) return 240;
        if (component == ttsEngineCombo || component == ttsVoiceCombo) return 190;
        if (component == pendingSecCombo) return 72;
        if (component == overlayPositionCombo) return 130;
        if (component == overlayBgPresetCombo || component == overlayFgPresetCombo) return 190;
        if (component == radioShortcutField) return 240;
        return Math.min(Math.max(component.getPreferredSize().width, 96), 260);
    }

    private void openDiscordSupport(ActionEvent e) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(DISCORD_INVITE_URL));
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    tr("discord.open_failed"),
                    "MobMate",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private String safeRecentLine(List<String> lines, int index) {
        if (lines == null || index >= lines.size()) return tr("history.waiting");
        String value = lines.get(index);
        return (value == null || value.isBlank()) ? tr("history.waiting") : value;
    }

    private static String toDisplayLine(String text, String fallback) {
        return (text == null || text.isBlank()) ? fallback : text;
    }

    private static String valueOrDefault(Object value, String fallback) {
        if (value == null) return fallback;
        String text = value.toString();
        return text == null || text.isBlank() ? fallback : text;
    }

    private String displayTtsEngine(String engine) {
        return switch (engine.toLowerCase(Locale.ROOT)) {
            case "voicevox" -> "VOICEVOX";
            case "windows" -> "Windows";
            case "piper_plus" -> "Piper+";
            case "voiceger_tts" -> "Voiceger";
            default -> tr("common.auto");
        };
    }

    private JComboBox<PresetOption> createPresetCombo(PresetOption[] presets) {
        JComboBox<PresetOption> combo = new JComboBox<>(presets);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof PresetOption option) {
                    setText(displayPresetOption(option));
                }
                return this;
            }
        });
        return combo;
    }

    private void applyOverlayPresetSelections() {
        PresetOption bg = (PresetOption) overlayBgPresetCombo.getSelectedItem();
        PresetOption fg = (PresetOption) overlayFgPresetCombo.getSelectedItem();
        if (bg == null || fg == null) return;
        currentOverlayBg = Color.decode(bg.hex());
        currentOverlayFg = Color.decode(fg.hex());
        app.setRadioOverlayColors(bg.hex(), fg.hex());
    }

    private void selectPreset(JComboBox<PresetOption> combo, PresetOption[] presets, Color color) {
        if (combo == null || color == null) return;
        String hex = toHex(color);
        PresetOption match = Arrays.stream(presets)
                .filter(p -> p.hex().equalsIgnoreCase(hex))
                .findFirst()
                .orElse(presets[0]);
        combo.setSelectedItem(match);
    }

    private String displayVoiceLabel(String engine, String value) {
        if ("voicevox".equalsIgnoreCase(engine)) {
            return "auto".equalsIgnoreCase(value) ? tr("common.auto") : value;
        }
        if ("windows".equalsIgnoreCase(engine)) {
            return "auto".equalsIgnoreCase(value) ? tr("common.auto") : value;
        }
        if ("piper_plus".equalsIgnoreCase(engine)) {
            if ("auto".equalsIgnoreCase(value)) return tr("common.auto");
            PiperPlusCatalog.Entry entry = PiperPlusCatalog.findById(value);
            return entry != null ? entry.comboLabel() : value;
        }
        if ("voiceger_tts".equalsIgnoreCase(engine) || "voiceger".equalsIgnoreCase(engine)) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "all_ja" -> tr("lang.japanese");
                case "en" -> tr("lang.english");
                case "all_zh" -> tr("lang.chinese");
                case "all_ko" -> tr("lang.korean");
                case "all_yue" -> tr("lang.cantonese");
                default -> "auto".equalsIgnoreCase(value) ? tr("common.auto") : value;
            };
        }
        return tr("common.auto");
    }

    private String displayOverlayPosition(String pos) {
        return switch (pos.toUpperCase(Locale.ROOT)) {
            case "TOP_RIGHT" -> tr("overlay.top_right");
            case "BOTTOM_LEFT" -> tr("overlay.bottom_left");
            case "BOTTOM_RIGHT" -> tr("overlay.bottom_right");
            default -> tr("overlay.top_left");
        };
    }

    private String displayPresetOption(PresetOption option) {
        if (option == null) return "";
        return tr(option.labelKey()) + "  " + option.hex();
    }

    private String tr(String key) {
        return UiText.t(key);
    }

    private String compactBackendMode(String raw) {
        if (raw == null || raw.isBlank()) return tr("backend.cpu");
        String text = raw.trim().toUpperCase(Locale.ROOT);
        if (text.contains("VULKAN")) return tr("backend.vulkan");
        if (text.contains("CUDA")) return tr("backend.cuda");
        if (text.contains("CPU")) return tr("backend.cpu");
        return text.replace(" MODE", "");
    }

    private Color colorForBackendMode(String mode) {
        if (mode == null || mode.isBlank()) return mutedForeground();
        return switch (mode.toUpperCase(Locale.ROOT)) {
            case "VULKAN" -> new Color(88, 181, 228);
            case "CUDA" -> new Color(103, 201, 116);
            case "CPU" -> mutedForeground();
            default -> normalForeground();
        };
    }

    private static Color contrastFor(Color color) {
        double luminance = (0.299 * color.getRed()) + (0.587 * color.getGreen()) + (0.114 * color.getBlue());
        return luminance >= 150 ? new Color(45, 55, 72) : Color.WHITE;
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor()),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)
        );
    }

    private final class CollapsibleCard extends JPanel {
        private final String prefsKey;
        private final JComponent body;
        private final JButton toggleButton;
        private final ChevronIcon toggleIcon;

        private CollapsibleCard(String key, String title, String desc, Color accent, JComponent body, boolean expanded) {
            super(new BorderLayout(0, 10));
            this.prefsKey = key;
            this.body = body;
            setOpaque(true);
            setBackground(cardBackground());
            setBorder(cardBorder());
            setAlignmentX(Component.LEFT_ALIGNMENT);

            JPanel header = new JPanel(new BorderLayout(10, 0));
            header.setOpaque(false);

            JPanel left = new JPanel(new BorderLayout(10, 0));
            left.setOpaque(false);

            JLabel titleLabel = new JLabel(title, sectionIconForKey(key), SwingConstants.LEFT);
            titleLabel.setIconTextGap(8);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
            JTextArea descLabel = helperLabel(desc);
            left.add(titleLabel, BorderLayout.WEST);
            left.add(descLabel, BorderLayout.CENTER);

            toggleIcon = new ChevronIcon(expanded);
            toggleButton = new JButton(toggleIcon);
            toggleButton.setFocusable(false);
            toggleButton.setContentAreaFilled(false);
            toggleButton.setBorderPainted(false);
            toggleButton.setForeground(accent);
            toggleButton.addActionListener(e -> setExpanded(!this.body.isVisible()));

            header.add(left, BorderLayout.CENTER);
            header.add(toggleButton, BorderLayout.EAST);
            add(header, BorderLayout.NORTH);
            add(body, BorderLayout.CENTER);
            setExpanded(app.getSimpleCardExpanded(key, expanded));
        }

        private void setExpanded(boolean expanded) {
            body.setVisible(expanded);
            toggleIcon.setExpanded(expanded);
            toggleButton.repaint();
            app.setSimpleCardExpanded(prefsKey, expanded);
            revalidate();
            repaint();
            SwingUtilities.invokeLater(MobMateSimpleModePanel.this::trimWindowHeightIfNeeded);
        }

        @Override
        public Dimension getMaximumSize() {
            Dimension pref = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, pref.height);
        }
    }

    private static final class ViewportWidthPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static final class BubbleIcon implements Icon {
        private final String glyph;

        private BubbleIcon(String glyph) {
            this.glyph = glyph == null ? "•" : glyph;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = UIManager.getColor("Component.accentColor");
            if (base == null) base = UIManager.getColor("Actions.Blue");
            if (base == null) base = new Color(102, 145, 219);
            g2.setColor(base);
            g2.fillOval(x, y, getIconWidth(), getIconHeight());
            g2.setColor(Color.WHITE);
            Font font = c.getFont().deriveFont(Font.BOLD, 11f);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics(font);
            int tx = x + (getIconWidth() - fm.stringWidth(glyph)) / 2;
            int ty = y + ((getIconHeight() - fm.getHeight()) / 2) + fm.getAscent();
            g2.drawString(glyph, tx, ty);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 20;
        }

        @Override
        public int getIconHeight() {
            return 20;
        }
    }

    private final class HeaderIcon implements Icon {
        enum Kind { MENU, CLOSE }

        private final Kind kind;
        private final boolean danger;

        private HeaderIcon(Kind kind, boolean danger) {
            this.kind = kind;
            this.danger = danger;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(danger ? Color.WHITE : normalForeground());
            if (kind == Kind.MENU) {
                int left = x + 2;
                int right = x + getIconWidth() - 2;
                g2.drawLine(left, y + 4, right, y + 4);
                g2.drawLine(left, y + 9, right, y + 9);
                g2.drawLine(left, y + 14, right, y + 14);
            } else {
                g2.drawLine(x + 3, y + 3, x + getIconWidth() - 3, y + getIconHeight() - 3);
                g2.drawLine(x + getIconWidth() - 3, y + 3, x + 3, y + getIconHeight() - 3);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }
    }

    private static final class ChevronIcon implements Icon {
        private boolean expanded;

        private ChevronIcon(boolean expanded) {
            this.expanded = expanded;
        }

        void setExpanded(boolean expanded) {
            this.expanded = expanded;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(c.getForeground());
            if (expanded) {
                g2.drawLine(x + 3, y + 5, x + 8, y + 10);
                g2.drawLine(x + 8, y + 10, x + 13, y + 5);
            } else {
                g2.drawLine(x + 5, y + 3, x + 10, y + 8);
                g2.drawLine(x + 10, y + 8, x + 5, y + 13);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }

    private static final class RecordStateIcon implements Icon {
        private final boolean recording;

        private RecordStateIcon(boolean recording) {
            this.recording = recording;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (recording) {
                g2.setColor(Color.WHITE);
                g2.fillRoundRect(x + 3, y + 2, 4, 12, 2, 2);
                g2.fillRoundRect(x + 9, y + 2, 4, 12, 2, 2);
            } else {
                g2.setColor(Color.WHITE);
                Polygon tri = new Polygon();
                tri.addPoint(x + 3, y + 2);
                tri.addPoint(x + 3, y + 14);
                tri.addPoint(x + 14, y + 8);
                g2.fillPolygon(tri);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }

    private static final class PendingModeIcon implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Color base = new Color(0x74, 0xBC, 0xB6);
            g2.setColor(base);
            g2.drawRoundRect(x + 2, y + 2, 12, 12, 4, 4);
            g2.drawLine(x + 5, y + 1, x + 5, y + 4);
            g2.drawLine(x + 11, y + 1, x + 11, y + 4);
            g2.drawLine(x + 5, y + 10, x + 8, y + 7);
            g2.drawLine(x + 8, y + 7, x + 10, y + 9);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }

    private static final class SectionSymbolIcon implements Icon {
        enum Kind { PENDING, TRANSLATION, AUDIO, VOICE, RADIO }

        private final Kind kind;

        private SectionSymbolIcon(Kind kind) {
            this.kind = kind;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = switch (kind) {
                case PENDING -> new Color(0x74, 0xBC, 0xB6);
                case TRANSLATION -> new Color(0x6B, 0xA5, 0xE2);
                case AUDIO -> new Color(0x63, 0xA9, 0xD1);
                case VOICE -> new Color(0x6C, 0xA5, 0xD7);
                case RADIO -> new Color(0x6B, 0xA5, 0xE2);
            };
            g2.setColor(base);
            g2.fillOval(x, y, getIconWidth(), getIconHeight());
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (kind) {
                case PENDING -> drawPending(g2, x, y);
                case TRANSLATION -> drawTranslation(g2, x, y);
                case AUDIO -> drawAudio(g2, x, y);
                case VOICE -> drawVoice(g2, x, y);
                case RADIO -> drawRadio(g2, x, y);
            }
            g2.dispose();
        }

        private void drawPending(Graphics2D g2, int x, int y) {
            g2.drawOval(x + 4, y + 4, 8, 8);
            g2.drawLine(x + 8, y + 8, x + 8, y + 5);
            g2.drawLine(x + 8, y + 8, x + 10, y + 9);
        }

        private void drawTranslation(Graphics2D g2, int x, int y) {
            g2.drawLine(x + 4, y + 6, x + 11, y + 6);
            g2.drawLine(x + 9, y + 4, x + 11, y + 6);
            g2.drawLine(x + 9, y + 8, x + 11, y + 6);
            g2.drawLine(x + 12, y + 10, x + 5, y + 10);
            g2.drawLine(x + 7, y + 8, x + 5, y + 10);
            g2.drawLine(x + 7, y + 12, x + 5, y + 10);
        }

        private void drawAudio(Graphics2D g2, int x, int y) {
            Polygon speaker = new Polygon(
                    new int[]{x + 4, x + 7, x + 9, x + 9, x + 7, x + 4},
                    new int[]{y + 9, y + 9, y + 7, y + 11, y + 13, y + 13},
                    6
            );
            g2.fillPolygon(speaker);
            g2.drawArc(x + 8, y + 6, 4, 6, -40, 80);
            g2.drawArc(x + 9, y + 4, 6, 10, -40, 80);
        }

        private void drawVoice(Graphics2D g2, int x, int y) {
            g2.drawLine(x + 5, y + 5, x + 5, y + 11);
            g2.drawLine(x + 8, y + 4, x + 8, y + 12);
            g2.drawLine(x + 11, y + 6, x + 11, y + 10);
            g2.drawArc(x + 3, y + 3, 10, 10, 210, 120);
        }

        private void drawRadio(Graphics2D g2, int x, int y) {
            g2.drawRoundRect(x + 4, y + 5, 8, 7, 2, 2);
            g2.drawLine(x + 7, y + 4, x + 10, y + 1);
            g2.drawLine(x + 6, y + 13, x + 6, y + 15);
            g2.drawLine(x + 10, y + 13, x + 10, y + 15);
            g2.fillOval(x + 6, y + 7, 1, 1);
            g2.drawLine(x + 8, y + 8, x + 10, y + 8);
            g2.drawArc(x + 11, y + 4, 4, 4, -60, 80);
        }

        @Override
        public int getIconWidth() {
            return 20;
        }

        @Override
        public int getIconHeight() {
            return 20;
        }
    }

    private static final class LiveHeaderIcon implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Image image = resolveWindowIcon(c);
            if (image != null) {
                g.drawImage(image, x, y, getIconWidth(), getIconHeight(), c);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0x5A, 0x90, 0xE8));
            g2.fillRoundRect(x + 1, y + 1, 16, 16, 6, 6);

            g2.setColor(new Color(0x8D, 0xB8, 0xF6));
            g2.fillRoundRect(x + 3, y + 3, 12, 6, 4, 4);

            g2.setColor(new Color(0x3E, 0x6F, 0xBC));
            g2.fillRoundRect(x + 4, y + 4, 10, 10, 4, 4);

            g2.setColor(Color.WHITE);
            g2.fillOval(x + 6, y + 7, 2, 2);
            g2.fillOval(x + 11, y + 7, 2, 2);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawArc(x + 6, y + 8, 6, 4, 200, 140);

            Polygon tail = new Polygon();
            tail.addPoint(x + 7, y + 13);
            tail.addPoint(x + 6, y + 16);
            tail.addPoint(x + 10, y + 13);
            g2.fillPolygon(tail);
            g2.dispose();
        }

        private Image resolveWindowIcon(Component c) {
            Window window = SwingUtilities.getWindowAncestor(c);
            if (window instanceof Frame frame) {
                List<Image> icons = frame.getIconImages();
                if (icons != null) {
                    for (Image icon : icons) {
                        if (icon != null) {
                            return icon;
                        }
                    }
                }
                Image icon = frame.getIconImage();
                if (icon != null) {
                    return icon;
                }
            }
            return null;
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }
    }

    private static final class HistoryIcon implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(106, 150, 220));
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawRoundRect(x + 2, y + 2, 12, 12, 3, 3);
            g2.drawLine(x + 5, y + 5, x + 11, y + 5);
            g2.drawLine(x + 5, y + 8, x + 11, y + 8);
            g2.drawLine(x + 5, y + 11, x + 9, y + 11);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }

    private static final class PartialPreviewIcon implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(110, 150, 210));
            g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawRoundRect(x + 3, y + 1, 8, 2, 2, 2);
            g2.drawRoundRect(x + 3, y + 13, 8, 2, 2, 2);
            g2.drawLine(x + 4, y + 3, x + 10, y + 13);
            g2.drawLine(x + 10, y + 3, x + 4, y + 13);
            g2.fillOval(x + 5, y + 7, 4, 4);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 14;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }

    private static final class HearingFeatureIcon implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(94, 152, 220));
            g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawArc(x + 2, y + 1, 11, 13, 300, 220);
            g2.drawArc(x + 6, y + 4, 6, 7, 300, 200);
            g2.drawLine(x + 11, y + 12, x + 14, y + 15);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }
    }

    private static class HistoryTextLabel extends JLabel {
        private String fullText = "";

        private HistoryTextLabel() {
            super(" ");
            setOpaque(false);
            setToolTipText(null);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    refreshDisplayText();
                }
            });
        }

        void setFullText(String text) {
            fullText = text == null ? "" : text.trim();
            setToolTipText(fullText.isBlank() ? null : fullText);
            refreshDisplayText();
        }

        private void refreshDisplayText() {
            if (fullText == null || fullText.isBlank()) {
                super.setText(" ");
                return;
            }
            int available = getWidth() - 4;
            if (available <= 0) {
                super.setText(fullText);
                return;
            }
            FontMetrics fm = getFontMetrics(getFont());
            super.setText(ellipsize(fullText, fm, available));
        }

        private static String ellipsize(String text, FontMetrics fm, int availableWidth) {
            if (fm.stringWidth(text) <= availableWidth) return text;
            String suffix = "...";
            int suffixWidth = fm.stringWidth(suffix);
            if (suffixWidth >= availableWidth) return suffix;
            int end = text.length();
            while (end > 1 && fm.stringWidth(text.substring(0, end)) + suffixWidth > availableWidth) {
                end--;
            }
            return text.substring(0, Math.max(1, end)).trim() + suffix;
        }
    }

    private static final class FadingHistoryTextLabel extends HistoryTextLabel {
        private final Timer fadeTimer;
        private float alpha = 1.0f;
        private boolean fadingOut = true;
        private String pendingText = "";
        private Color pendingColor = Color.WHITE;
        private String currentText = "";

        private FadingHistoryTextLabel() {
            fadeTimer = new Timer(35, e -> onFadeStep());
        }

        void setAnimatedText(String text, Color color) {
            String next = text == null ? "" : text.trim();
            Color nextColor = color == null ? getForeground() : color;
            if (Objects.equals(currentText, next) && Objects.equals(getForeground(), nextColor) && !fadeTimer.isRunning()) {
                return;
            }
            if (currentText.isBlank()) {
                currentText = next;
                super.setFullText(next);
                setForeground(nextColor);
                alpha = 1.0f;
                repaint();
                return;
            }
            pendingText = next;
            pendingColor = nextColor;
            fadingOut = true;
            if (!fadeTimer.isRunning()) {
                fadeTimer.start();
            }
        }

        private void onFadeStep() {
            if (fadingOut) {
                alpha -= 0.18f;
                if (alpha <= 0.10f) {
                    alpha = 0.10f;
                    currentText = pendingText;
                    super.setFullText(pendingText);
                    setForeground(pendingColor);
                    fadingOut = false;
                }
            } else {
                alpha += 0.18f;
                if (alpha >= 1.0f) {
                    alpha = 1.0f;
                    fadeTimer.stop();
                }
            }
            repaint();
        }

        @Override
        public void paint(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0.0f, Math.min(1.0f, alpha))));
            super.paint(g2);
            g2.dispose();
        }
    }

    private static final class FadingStatusLabel extends JLabel {
        private final Timer fadeTimer;
        private float alpha = 1.0f;
        private boolean fadingOut = true;
        private String pendingText = "";
        private Color pendingColor = Color.WHITE;
        private String currentText = "";
        private Color currentColor = Color.WHITE;

        private FadingStatusLabel() {
            fadeTimer = new Timer(35, e -> onFadeStep());
        }

        void setAnimatedText(String text, Color color) {
            String next = text == null ? "" : text.trim();
            Color nextColor = color == null ? getForeground() : color;
            if (Objects.equals(currentText, next) && Objects.equals(currentColor, nextColor) && !fadeTimer.isRunning()) {
                return;
            }
            if (currentText.isBlank()) {
                currentText = next;
                currentColor = nextColor;
                super.setText(next);
                setForeground(nextColor);
                alpha = 1.0f;
                repaint();
                return;
            }
            pendingText = next;
            pendingColor = nextColor;
            fadingOut = true;
            if (!fadeTimer.isRunning()) {
                fadeTimer.start();
            }
        }

        private void onFadeStep() {
            if (fadingOut) {
                alpha -= 0.20f;
                if (alpha <= 0.12f) {
                    alpha = 0.12f;
                    currentText = pendingText;
                    currentColor = pendingColor;
                    super.setText(pendingText);
                    setForeground(pendingColor);
                    fadingOut = false;
                }
            } else {
                alpha += 0.20f;
                if (alpha >= 1.0f) {
                    alpha = 1.0f;
                    fadeTimer.stop();
                }
            }
            repaint();
        }

        @Override
        public void paint(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0.0f, Math.min(1.0f, alpha))));
            super.paint(g2);
            g2.dispose();
        }
    }

    private static final class MobEchoIcon implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(104, 155, 230));
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawRoundRect(x + 3, y + 3, 10, 8, 4, 4);
            g2.drawLine(x + 6, y + 12, x + 5, y + 15);
            g2.drawLine(x + 10, y + 12, x + 11, y + 15);
            g2.drawLine(x + 1, y + 6, x + 3, y + 6);
            g2.drawLine(x + 13, y + 6, x + 15, y + 6);
            g2.fillOval(x + 6, y + 6, 1, 1);
            g2.fillOval(x + 9, y + 6, 1, 1);
            g2.drawArc(x + 6, y + 7, 4, 2, 180, 180);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }
    }

    private final class ResponsiveFormRow extends JPanel {
        private static final int LABEL_WIDTH = 92;
        private static final int GAP = 10;
        private final JLabel label;
        private final JComponent field;

        private ResponsiveFormRow(String text, JComponent field) {
            this.label = new JLabel(text);
            this.field = field;
            setLayout(null);
            setOpaque(false);
            label.setForeground(labelForeground());
            add(label);
            add(field);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension fieldPref = field.getPreferredSize();
            int h = Math.max(26, Math.max(label.getPreferredSize().height, fieldPref.height));
            return new Dimension(LABEL_WIDTH + GAP + fieldPref.width, h);
        }

        @Override
        public Dimension getMaximumSize() {
            Dimension pref = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, pref.height);
        }

        @Override
        public void doLayout() {
            int h = getHeight();
            int fieldX = LABEL_WIDTH + GAP;
            int fieldW = Math.max(0, getWidth() - fieldX);
            label.setBounds(0, Math.max(0, (h - 24) / 2), LABEL_WIDTH, 24);
            field.setBounds(fieldX, 0, fieldW, h);
        }
    }

    private final class ResponsiveDualComboRow extends JPanel {
        private static final int GAP = 10;
        private final JComboBox<?> left;
        private final JComboBox<?> right;
        private final int minEachWidth;

        private ResponsiveDualComboRow(JComboBox<?> left, JComboBox<?> right, int minEachWidth) {
            this.left = left;
            this.right = right;
            this.minEachWidth = minEachWidth;
            setLayout(null);
            setOpaque(false);
            add(left);
            add(right);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension lp = left.getPreferredSize();
            Dimension rp = right.getPreferredSize();
            int h = Math.max(26, Math.max(lp.height, rp.height));
            return new Dimension(lp.width + GAP + rp.width, h);
        }

        @Override
        public Dimension getMaximumSize() {
            Dimension pref = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, pref.height);
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            int available = Math.max(0, w - GAP);
            int each = available / 2;
            int min = Math.min(minEachWidth, Math.max(60, available / 2));
            each = Math.max(min, each);
            if ((each * 2) + GAP > w) {
                each = Math.max(60, (w - GAP) / 2);
            }
            int rightW = Math.max(60, w - GAP - each);
            left.setBounds(0, 0, each, h);
            right.setBounds(each + GAP, 0, rightW, h);
        }
    }

    private static final class PresetOption {
        private final String labelKey;
        private final String hex;

        private PresetOption(String labelKey, String hex) {
            this.labelKey = labelKey;
            this.hex = hex;
        }

        String labelKey() {
            return labelKey;
        }

        String hex() {
            return hex;
        }

        @Override
        public String toString() {
            return labelKey + "  " + hex;
        }
    }

    private void applyThemeColors() {
        setBackground(panelBackground());
        setOpaque(true);
    }

    private void installWindowDragSupport(Component component) {
        if (component == null || isInteractive(component)) return;
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Window window = SwingUtilities.getWindowAncestor(MobMateSimpleModePanel.this);
                if (window == null) return;
                if (isNearWindowResizeEdge(window, e)) {
                    dragAnchorOnScreen = null;
                    dragWindowOrigin = null;
                    return;
                }
                try {
                    dragAnchorOnScreen = e.getLocationOnScreen();
                    dragWindowOrigin = window.getLocation();
                } catch (IllegalComponentStateException ignore) {
                    dragAnchorOnScreen = null;
                    dragWindowOrigin = null;
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Window window = SwingUtilities.getWindowAncestor(MobMateSimpleModePanel.this);
                if (window == null || dragAnchorOnScreen == null || dragWindowOrigin == null) return;
                if (isNearWindowResizeEdge(window, e)) return;
                try {
                    Point now = e.getLocationOnScreen();
                    int dx = now.x - dragAnchorOnScreen.x;
                    int dy = now.y - dragAnchorOnScreen.y;
                    window.setLocation(dragWindowOrigin.x + dx, dragWindowOrigin.y + dy);
                } catch (IllegalComponentStateException ignore) {
                }
            }
        };
        component.addMouseListener(adapter);
        component.addMouseMotionListener(adapter);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                installWindowDragSupport(child);
            }
        }
    }

    private boolean isNearWindowResizeEdge(Window window, MouseEvent e) {
        if (window == null) return false;
        Point point = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), window);
        if (point == null) return false;
        return point.x <= WINDOW_EDGE_RESIZE_MARGIN
                || point.x >= Math.max(WINDOW_EDGE_RESIZE_MARGIN, window.getWidth() - WINDOW_EDGE_RESIZE_MARGIN);
    }

    private boolean isInteractive(Component component) {
        return component instanceof AbstractButton
                || component instanceof JComboBox<?>
                || component instanceof JTextComponent
                || component instanceof JScrollBar
                || component instanceof JList<?>
                || component instanceof JTable
                || component instanceof JTree
                || component instanceof JSlider
                || component instanceof JSpinner;
    }

    private Color panelBackground() {
        Color color = UIManager.getColor("Panel.background");
        return color != null ? color : new Color(0xF2, 0xF5, 0xFA);
    }

    private Color cardBackground() {
        Color color = UIManager.getColor("TextField.background");
        if (color == null) color = UIManager.getColor("Panel.background");
        return color != null ? color : Color.WHITE;
    }

    private Color subtleSurfaceBackground() {
        Color color = UIManager.getColor("Button.background");
        return color != null ? color : cardBackground();
    }

    private Color borderColor() {
        Color color = UIManager.getColor("Separator.foreground");
        if (color == null) color = UIManager.getColor("Component.borderColor");
        return color != null ? color : new Color(0xD9, 0xE4, 0xF1);
    }

    private Color normalForeground() {
        Color color = UIManager.getColor("Label.foreground");
        return color != null ? color : new Color(45, 55, 72);
    }

    private Color labelForeground() {
        return normalForeground();
    }

    private Color mutedForeground() {
        Color color = UIManager.getColor("Label.disabledForeground");
        return color != null ? color : new Color(120, 130, 145);
    }

    private Color secondaryForeground() {
        Color base = normalForeground();
        return mix(base, panelBackground(), 0.35f);
    }

    private Color accentBlue() {
        Color color = UIManager.getColor("Actions.Blue");
        if (color == null) color = UIManager.getColor("Component.accentColor");
        return color != null ? color : new Color(0x5C, 0x9F, 0xDE);
    }

    private Color accentWarning() {
        return new Color(0xE0, 0xA8, 0x46);
    }

    private Color accentSuccess() {
        return new Color(0x66, 0xAA, 0x9A);
    }

    private static Color mix(Color a, Color b, float ratioB) {
        float ratioA = 1.0f - ratioB;
        int r = Math.round((a.getRed() * ratioA) + (b.getRed() * ratioB));
        int g = Math.round((a.getGreen() * ratioA) + (b.getGreen() * ratioB));
        int bl = Math.round((a.getBlue() * ratioA) + (b.getBlue() * ratioB));
        return new Color(clampColor(r), clampColor(g), clampColor(bl));
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
