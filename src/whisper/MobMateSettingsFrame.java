package whisper;

import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

public class MobMateSettingsFrame extends JDialog {

    private static final String DISCORD_INVITE_URL = "https://discord.gg/CkhYzNw7YF";
    private static final String PIPER_PLUS_MODEL_GUIDE_URL = "https://github.com/zufall-upon/MobMate#piper-model-guide";
    private static final String OUTTTS_MARKER = "↑Settings↓Logs below";

    private final MobMateWhisp app;

    private final DefaultListModel<String> navModel = new DefaultListModel<>();
    private final JList<String> navList = new JList<>(navModel);
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardPanel = new JPanel(cardLayout);
    private final Map<String, String> pageIds = new LinkedHashMap<>();

    private final Map<String, File> moonModelMap = new LinkedHashMap<>();
    private final AtomicBoolean ttsListsLoaded = new AtomicBoolean(false);
    private final AtomicBoolean ttsListsLoading = new AtomicBoolean(false);
    private boolean syncingTtsControls = false;

    // ===== General =====
    private JComboBox<Choice<String>> actionCombo;
    private JComboBox<Choice<String>> triggerModeCombo;
    private JComboBox<String> hotkeyCombo;
    private JCheckBox shiftCheck;
    private JCheckBox ctrlCheck;
    private JCheckBox silenceDetectionCheck;

    // ===== Audio =====
    private JComboBox<String> inputCombo;
    private JComboBox<String> outputCombo;
    private JComboBox<String> monitorVolumeCombo;
    private JCheckBox autoGainCheck;
    private JCheckBox aiAssistCheck;
    private JComboBox<Choice<String>> audioPrefilterModeCombo;
    private JComboBox<Choice<Float>> inputGainCombo;

    // ===== Recognition =====
    private JComboBox<Choice<String>> recogEngineCombo;
    private JComboBox<String> whisperModelCombo;
    private JComboBox<String> moonshineModelCombo;
    private JComboBox<Choice<Integer>> gpuSelectCombo;
    private boolean gpuSelectable = false;     
    private JLabel gpuNoteLabel;               
    private JLabel vadLaughNoteLabel;          
    private JCheckBox lowGpuCheck;
    private JCheckBox altLaughCheck;

    private JCheckBox speakerEnabledCheck;
    private JComboBox<Choice<Integer>> speakerEnrollCombo;
    private JComboBox<Choice<Float>> speakerThresholdCombo;

    // ===== TTS =====
    private JComboBox<Choice<String>> ttsEngineCombo;
    private JComboBox<String> ttsPrimaryVoiceCombo;
    private JComboBox<Choice<Integer>> ttsConfirmSecCombo;
    private JToggleButton ttsConfirmInstantButton;
    private JToggleButton ttsConfirmPendingButton;
    private boolean syncingPendingControls = false;
    private JComboBox<String> windowsVoiceCombo;
    private JComboBox<String> voicevoxSpeakerCombo;
    private JComboBox<String> piperPlusModelCombo;
    private JButton piperPlusDownloadBtn;
    private JCheckBox voicevoxAutoEmotionCheck;
    private JCheckBox ttsReflectEmotionCheck;
    private JComboBox<Choice<String>> ttsContourStrengthCombo;
    private JComboBox<Choice<String>> ttsToneEmphasisCombo;
    private JComboBox<Choice<String>> voicegerLangCombo;
    private JLabel piperPlusLicenseLabel;
    private JLabel piperPlusStatusLabel;
    private JLabel piperPlusProsodyLabel;
    private JPanel ttsForm;
    private FormRow ttsWindowsVoiceRow;
    private FormRow ttsPiperPlusModelRow;
    private FormRow ttsPiperPlusLicenseRow;
    private FormRow ttsPiperPlusStatusRow;
    private FormRow ttsPiperPlusProsodyRow;
    private FormRow ttsVoicevoxSpeakerRow;
    private FormRow ttsVoicevoxAutoEmotionRow;
    private FormRow ttsReflectEmotionRow;
    private FormRow ttsContourStrengthRow;
    private FormRow ttsToneEmphasisRow;
    private FormRow ttsVoicegerLangRow;
    private JLabel ttsSetupSectionLabel;
    private FormRow ttsSetupButtonsRow;
    private FormRow ttsSetupNoteRow;
    private JButton ttsSetupVoicevoxBtn;
    private JButton ttsSetupXttsBtn;
    private JButton ttsSetupVoicegerBtn;

    // ===== Text / _outtts =====
    private JComboBox<String> whisperLanguageCombo;
    private JComboBox<String> talkTranslateTargetCombo;
    private JTextArea initialPromptArea;
    private JTextArea hearingInitialPromptArea;
    private JTextField voicevoxExeField;
    private JTextField voicevoxApiField;
    private JButton voicevoxDetectButton;
    private JTextField xttsApiField;
    private JTextField xttsApiChkField;
    private JTextField xttsLanguageField;
    private JComboBox<Choice<String>> ignoreModeCombo;
    private JCheckBox laughsEnableCheck;          
    private JTextField laughsDetectField;
    private JTextField laughsDetectAutoField;
    private JTextField laughsReplaceField;
    private JSpinner vadSensitivitySpinner;       
    private JSpinner vadToleranceSpinner;         

    // ===== Radio / Overlay =====
    private JComboBox<Choice<Integer>> radioModCombo;
    private JComboBox<Choice<Integer>> radioKeyCombo;
    private JTextField radioShortcutDisplayField;
    private JComboBox<Choice<String>> radioBgPresetCombo;
    private JComboBox<Choice<String>> radioFgPresetCombo;
    private JCheckBox overlayEnableCheck;
    private JComboBox<Choice<String>> overlayPosCombo;
    private JComboBox<Choice<Integer>> overlayDisplayCombo;    // ★CHANGE
    private JSpinner overlayFontSizeSpinner;
    private JSlider overlayOpacitySlider;
    private JComboBox<Choice<String>> overlayThemePresetCombo;
    private boolean overlayThemePresetApplying = false;
    private boolean syncingRadioControls = false;
    private JSpinner overlayMarginSpinner;
    private JSpinner overlayMaxLinesSpinner;
    private JComboBox<String> overlayPageChangeCombo;          // ★CHANGE
    private JTextField overlayBgField;
    private JTextField overlayFgField;

    // ===== Appearance =====
    private JComboBox<Choice<String>> uiLangCombo;
    private JComboBox<Choice<Integer>> uiFontSizeCombo;
    private JComboBox<Choice<String>> uiMainModeCombo;
    private JCheckBox darkThemeCheck;

    public MobMateSettingsFrame(Frame owner, MobMateWhisp app) {
        super(owner, false);
        this.app = app;

        setTitle(tt("settings.title", "Settings Center"));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(980, 720));
        setSize(1080, 760);
        buildUi();
        loadCurrentValues();
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel(tt("settings.title", "Settings Center"));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));

        JLabel sub = new JLabel(tt(
                "settings.desc",
                "Beginner-friendly settings screen. Utility actions remain available from the Δ button and tray menu."
        ));
        sub.setForeground(Color.GRAY);

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(sub);

        root.add(header, BorderLayout.NORTH);

        navList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        navList.setFixedCellHeight(34);
        navList.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        addPage("general",     tt("settings.nav.general", "Basic"),          buildGeneralPage());
        addPage("audio",       tt("settings.nav.audio", "Audio"),            buildAudioPage());
        addPage("recognition", tt("settings.nav.recognition", "Recognition"), buildRecognitionPage());
        addPage("tts",         tt("settings.nav.tts", "TTS / Voice"),        buildTtsPage());
        addPage("text",        tt("settings.nav.text", "Text / _outtts"),    buildTextPage());
        addPage("radio",       tt("settings.nav.radio", "Radio / Overlay"),  buildRadioPage());
        addPage("appearance",  tt("settings.nav.appearance", "Appearance"),  buildAppearancePage());

        navList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            String key = navList.getSelectedValue();
            if (key == null) return;

            String pageId = pageIds.get(key);
            if (pageId != null) {
                cardLayout.show(cardPanel, pageId);

                if ("tts".equals(pageId)) {
                    ensureTtsListsLoadedAsync(false);
                }
            }
        });

        JScrollPane leftScroll = new JScrollPane(navList);
        leftScroll.setPreferredSize(new Dimension(200, 400));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, cardPanel);
        split.setDividerLocation(220);
        split.setResizeWeight(0.0);
        root.add(split, BorderLayout.CENTER);

        root.add(buildBottomBar(), BorderLayout.SOUTH);

        setContentPane(root);

        if (!navModel.isEmpty()) {
            navList.setSelectedIndex(0);
        }
    }

    private JPanel buildBottomBar() {
        JPanel p = new JPanel(new BorderLayout());

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

        JButton wizardBtn = new JButton(tt("settings.util.wizard", "Wizard"));
        wizardBtn.addActionListener(e -> openWizard());

        JButton openMoonBtn = new JButton(tt("settings.util.moonDir", "moonshine_model"));
        openMoonBtn.addActionListener(e -> openLocalFile(app.getMoonBaseDir()));

        JButton softRestartBtn = new JButton(tt("settings.util.restart", "Soft Restart"));
        softRestartBtn.addActionListener(e -> {
            saveAll(false);
            app.requestSoftRestartForSettings();
            dispose();
        });

        left.add(wizardBtn);
        left.add(openMoonBtn);
        left.add(softRestartBtn);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));

        JButton discordBtn = new JButton(tt("settings.util.discord", "Discord"));
        discordBtn.addActionListener(e -> openDiscordInvite());

        JButton applyBtn = new JButton(tt("ui.button.apply", "Apply"));
        JButton okBtn = new JButton(tt("ui.button.ok", "OK"));
        JButton closeBtn = new JButton(tt("ui.button.close", "Close"));

        applyBtn.addActionListener(e -> saveAll(false));
        okBtn.addActionListener(e -> saveAll(true));
        closeBtn.addActionListener(e -> dispose());

        right.add(discordBtn);
        right.add(applyBtn);
        right.add(okBtn);
        right.add(closeBtn);

        p.add(left, BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private void addPage(String id, String title, JComponent page) {
        navModel.addElement(title);
        pageIds.put(title, id);
        cardPanel.add(new JScrollPane(page), id);
    }

    private JPanel buildGeneralPage() {
        JPanel form = formPanel();
        int row = 0;

        actionCombo = new JComboBox<>(new Choice[]{
                new Choice<>(tt("settings.action.none", "Do nothing"), "nothing"),
                new Choice<>(tt("settings.action.paste", "Paste"), "paste"),
                new Choice<>(tt("settings.action.type", "Type"), "type")
        });

        triggerModeCombo = new JComboBox<>(new Choice[]{
                new Choice<>(tt("settings.trigger.ptt", "Push To Talk"), "push_to_talk"),
                new Choice<>(tt("settings.trigger.double", "Push To Talk (double tap)"), "push_to_talk_double_tap"),
                new Choice<>(tt("settings.trigger.startstop", "Start / Stop"), "start_stop")
        });

        hotkeyCombo = new JComboBox<>();
        for (int i = 1; i <= 18; i++) {
            hotkeyCombo.addItem("F" + i);
        }

        shiftCheck = new JCheckBox("SHIFT");
        ctrlCheck = new JCheckBox("CTRL");
        silenceDetectionCheck = new JCheckBox(tt("menu.silenceDetection", "Silence detection"));

        addRow(form, row++, tt("settings.label.action", "Action"), actionCombo);
        addRow(form, row++, tt("settings.label.trigger", "Trigger mode"), triggerModeCombo);
        addRow(form, row++, tt("settings.label.hotkey", "Recording hotkey"), hotkeyCombo);

        JPanel modPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        modPanel.add(shiftCheck);
        modPanel.add(ctrlCheck);
        addRow(form, row++, tt("settings.label.modifier", "Modifiers"), modPanel);

        addRow(form, row++, "", silenceDetectionCheck);

        return wrapPage(
                tt("settings.page.general.title", "Basic"),
                tt("settings.page.general.desc", "Frequently used recording behavior."),
                form
        );
    }

    private JPanel buildAudioPage() {
        JPanel form = formPanel();
        int row = 0;

        inputCombo = new JComboBox<>();
        outputCombo = new JComboBox<>();
        monitorVolumeCombo = new JComboBox<>(new String[]{
                "0%", "10%", "20%", "30%", "40%", "50%", "60%", "70%", "80%", "90%", "100%"
        });
        autoGainCheck = new JCheckBox(tt("menu.autoGainTuner", "Auto gain"));
        aiAssistCheck = new JCheckBox(tt("settings.audio.aiAssist", "AI setting assist"));
        audioPrefilterModeCombo = new JComboBox<>(new Choice[]{
                new Choice<>(tt("settings.audioPrefilter.off", "Off"), "off"),
                new Choice<>(tt("settings.audioPrefilter.normal", "Normal filter"), "normal"),
                new Choice<>(tt("settings.audioPrefilter.strong", "Strong filter"), "strong")
        });
        inputGainCombo = new JComboBox<>(new Choice[]{
                new Choice<>("x 1.0", 1.0f),
                new Choice<>("x 1.8", 1.8f),
                new Choice<>("x 2.6", 2.6f),
                new Choice<>("x 3.4", 3.4f),
                new Choice<>("x 4.2", 4.2f),
                new Choice<>("x 5.0", 5.0f),
                new Choice<>("x 5.8", 5.8f),
                new Choice<>("x 6.6", 6.6f),
                new Choice<>("x 7.4", 7.4f),
                new Choice<>("x 8.2", 8.2f)
        });

        addRow(form, row++, tt("settings.label.inputDevice", "Input device"), inputCombo);
        addRow(form, row++, tt("settings.label.outputDevice", "Output device"), outputCombo);
        JTextArea audioGuideNote = helperNote(tt("settings.page.audio.discordGuide",
                "Discord example: “Microphone input” is your usual microphone. Set a virtual device for “TTS output target” so Discord can use it as its microphone, and keep Discord's speaker on your usual headset or speakers."));
        addRow(form, row++, "", audioGuideNote);
        addRow(form, row++, tt("settings.label.monitorVolume", "Monitor volume"), monitorVolumeCombo);
        addRow(form, row++, "", autoGainCheck);
        addRow(form, row++, "", aiAssistCheck);
        addRow(form, row++, tt("settings.label.audioPrefilter", "Voice audio filter"), audioPrefilterModeCombo);
        addRow(form, row++, tt("settings.label.inputGain", "Input gain"), inputGainCombo);

        return wrapPage(
                tt("settings.page.audio.title", "Audio"),
                tt("settings.page.audio.desc", "Microphone, output target, and gain settings."),
                form
        );
    }

    private JPanel buildRecognitionPage() {
        JPanel form = formPanel();
        int row = 0;

        recogEngineCombo = new JComboBox<>(new Choice[]{
                new Choice<>("Whisper", "whisper"),
                new Choice<>("Moonshine", "moonshine")
        });
        whisperModelCombo = new JComboBox<>();
        moonshineModelCombo = new JComboBox<>();

        gpuSelectCombo = new JComboBox<>();
        reloadGpuChoices();

        lowGpuCheck = new JCheckBox(tt("menu.lowGpuMode", "Low GPU mode"));
        altLaughCheck = new JCheckBox(tt("menu.vadLaugh", "VAD laugh detection"));

        gpuNoteLabel = new JLabel();
        vadLaughNoteLabel = new JLabel();

        speakerEnabledCheck = new JCheckBox(tt("settings.speaker.enable", "Enable speaker filter"));
        speakerEnrollCombo = new JComboBox<>(new Choice[]{
                new Choice<>("3", 3),
                new Choice<>("5", 5),
                new Choice<>("7", 7),
                new Choice<>("10", 10)
        });
        speakerThresholdCombo = new JComboBox<>(new Choice[]{
                new Choice<>("10%", 0.10f),
                new Choice<>("25%", 0.25f),
                new Choice<>("45%", 0.45f),
                new Choice<>("55%", 0.55f),
                new Choice<>("60%", 0.60f),
                new Choice<>("70%", 0.70f),
                new Choice<>("80%", 0.80f)
        });

        recogEngineCombo.addActionListener(e -> updateRecognitionUiState()); 

        addRow(form, row++, tt("settings.label.recogEngine", "Recognition engine"), recogEngineCombo);
        addRow(form, row++, tt("settings.label.whisperModel", "Whisper model"), whisperModelCombo);
        addRow(form, row++, tt("settings.label.moonModel", "Moonshine model"), moonshineModelCombo);
        addRow(form, row++, tt("settings.label.gpuSelect", "GPU selection"), gpuSelectCombo);
        addRow(form, row++, "", gpuNoteLabel);
        addRow(form, row++, "", lowGpuCheck);
        addRow(form, row++, "", altLaughCheck);
        addRow(form, row++, "", vadLaughNoteLabel);

        form.add(sectionLabel(tt("settings.section.speaker", "Speaker Filter")), sectionGbc(row++));
        addRow(form, row++, "", speakerEnabledCheck);
        addRow(form, row++, tt("settings.label.speakerSamples", "Enroll samples"), speakerEnrollCombo);
        addRow(form, row++, tt("settings.label.speakerThreshold", "Sensitivity"), speakerThresholdCombo);

        updateRecognitionUiState(); 

        return wrapPage(
                tt("settings.page.recognition.title", "Recognition"),
                tt("settings.page.recognition.desc", "Engine, model, and speaker filter settings."),
                form
        );
    }

    private JPanel buildTtsPage() {
        JPanel form = formPanel();
        ttsForm = form;
        int row = 0;

        ttsEngineCombo = new JComboBox<>(new Choice[]{
                new Choice<>("Auto", "auto"),
                new Choice<>("piper-plus", "piper_plus"),
                new Choice<>("VOICEVOX", "voicevox"),
                new Choice<>("XTTS", "xtts"),
                new Choice<>("Windows", "windows"),
                new Choice<>("Voiceger (VC)", "voiceger_vc"),
                new Choice<>("Voiceger (TTS)", "voiceger_tts")
        });
        ttsEngineCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setText(displaySettingsTtsEngine(value));
                return this;
            }
        });
        ttsPrimaryVoiceCombo = new JComboBox<>();
        ttsConfirmSecCombo = new JComboBox<>(new Choice[]{
                new Choice<>("1 sec", 1),
                new Choice<>("2 sec", 2),
                new Choice<>("3 sec", 3),
                new Choice<>("4 sec", 4),
                new Choice<>("5 sec", 5),
                new Choice<>("6 sec", 6),
                new Choice<>("7 sec", 7),
                new Choice<>("8 sec", 8),
                new Choice<>("9 sec", 9),
                new Choice<>("10 sec", 10),
                new Choice<>("11 sec", 11),
                new Choice<>("12 sec", 12),
                new Choice<>("13 sec", 13),
                new Choice<>("14 sec", 14),
                new Choice<>("15 sec", 15)
        });
        ttsConfirmInstantButton = new JToggleButton(tt("pending.mode.instant", "Instant"));
        ttsConfirmPendingButton = new JToggleButton(tt("pending.mode.wait", "Wait"));
        ButtonGroup pendingModeGroup = new ButtonGroup();
        pendingModeGroup.add(ttsConfirmInstantButton);
        pendingModeGroup.add(ttsConfirmPendingButton);
        styleSettingsPendingModeButtons();
        ttsConfirmInstantButton.addActionListener(e -> {
            if (syncingPendingControls) return;
            app.setConfirmModeFromUi(false);
            refreshPendingSelections();
        });
        ttsConfirmPendingButton.addActionListener(e -> {
            if (syncingPendingControls) return;
            app.setConfirmModeFromUi(true);
            refreshPendingSelections();
        });
        ttsConfirmSecCombo.addActionListener(e -> {
            if (syncingPendingControls) return;
            Integer value = selectedValue(ttsConfirmSecCombo);
            if (value != null) {
                app.setConfirmSecondsFromUi(value);
                refreshPendingSelections();
            }
        });

        windowsVoiceCombo = new JComboBox<>();
        voicevoxSpeakerCombo = new JComboBox<>();
        piperPlusModelCombo = new JComboBox<>();
        piperPlusDownloadBtn = new JButton(tt("settings.piperPlus.download", "Download / Update"));
        piperPlusDownloadBtn.addActionListener(e -> handleSelectedPiperPlusPrimaryAction());
        JButton piperPlusRemoveBtn = new JButton(tt("settings.piperPlus.remove", "Remove"));
        piperPlusRemoveBtn.addActionListener(e -> removeSelectedPiperPlusModel());
        JButton piperPlusOpenBtn = new JButton(tt("settings.piperPlus.open", "Open folder"));
        piperPlusOpenBtn.addActionListener(e -> openSelectedPiperPlusModelFolder());

        voicevoxAutoEmotionCheck = new JCheckBox(tt("menu.voicevox.autoEmotion", "VOICEVOX auto emotion"));
        ttsReflectEmotionCheck = new JCheckBox(tt("settings.tts.reflectEmotion", "Reflect emotion into TTS"));
        ttsContourStrengthCombo = new JComboBox<>(new Choice[]{
                new Choice<>(tt("settings.choice.mild", "Mild"), "mild"),
                new Choice<>(tt("settings.choice.normal", "Normal"), "normal"),
                new Choice<>(tt("settings.choice.strong", "Strong"), "strong")
        });
        ttsToneEmphasisCombo = new JComboBox<>(new Choice[]{
                new Choice<>(tt("settings.choice.mild", "Mild"), "mild"),
                new Choice<>(tt("settings.choice.normal", "Normal"), "normal"),
                new Choice<>(tt("settings.choice.strong", "Strong"), "strong")
        });
        voicegerLangCombo = new JComboBox<>(new Choice[]{
                new Choice<>("Japanese", "all_ja"),
                new Choice<>("English", "en"),
                new Choice<>("Chinese", "all_zh"),
                new Choice<>("Korean", "all_ko"),
                new Choice<>("Cantonese", "all_yue")
        });

        form.add(sectionLabel(tt("settings.section.ttsConfirm", "Speech pending mode")), sectionGbc(row++));
        JTextArea pendingNote = helperNote(tt("settings.section.ttsConfirm.desc",
                "Choose whether speech is confirmed immediately or after a short wait. Radio Chat shortcuts can quickly select speak or discard too."));
        addRow(form, row++, "", pendingNote);
        JPanel pendingModePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        pendingModePanel.setOpaque(false);
        pendingModePanel.add(ttsConfirmInstantButton);
        pendingModePanel.add(ttsConfirmPendingButton);
        pendingModePanel.add(new JLabel(tt("pending.wait_seconds", "Wait time")));
        pendingModePanel.add(ttsConfirmSecCombo);
        addRow(form, row++, tt("settings.label.ttsPendingCompact", "Speech pending mode"), pendingModePanel);

        form.add(sectionLabel(tt("settings.section.ttsVoice", "Voice Output")), sectionGbc(row++));
        JPanel primaryVoicePanel = createResponsiveDualFieldPanel(ttsEngineCombo, ttsPrimaryVoiceCombo);
        addRow(form, row++, tt("settings.label.ttsVoiceCompact", "TTS / Voice"), primaryVoicePanel);
        ttsWindowsVoiceRow = addTrackedRow(form, row++, tt("settings.label.windowsVoice", "Windows voice"), windowsVoiceCombo);

        JPanel piperPanel = new JPanel(new BorderLayout(8, 0));
        piperPanel.add(piperPlusModelCombo, BorderLayout.CENTER);
        JPanel piperButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        piperButtons.add(piperPlusDownloadBtn);
        piperButtons.add(piperPlusRemoveBtn);
        piperButtons.add(piperPlusOpenBtn);
        piperPanel.add(piperButtons, BorderLayout.EAST);
        ttsPiperPlusModelRow = addTrackedRow(form, row++, tt("settings.label.piperPlusModel", "piper-plus model"), piperPanel);
        piperPlusLicenseLabel = new JLabel("License: -");
        piperPlusStatusLabel = new JLabel("Status: -");
        piperPlusProsodyLabel = new JLabel("Prosody: -");
        ttsPiperPlusLicenseRow = addTrackedRow(form, row++, "", piperPlusLicenseLabel);
        ttsPiperPlusStatusRow = addTrackedRow(form, row++, "", piperPlusStatusLabel);
        ttsPiperPlusProsodyRow = addTrackedRow(form, row++, "", piperPlusProsodyLabel);

        // Keep the detailed VOICEVOX combo off-screen so legacy save/load paths and compact-row sync
        // can continue to share the same backing selection without exposing a duplicate row in Settings.
        ttsVoicevoxSpeakerRow = null;

        ttsVoicevoxAutoEmotionRow = addTrackedRow(form, row++, "", voicevoxAutoEmotionCheck);
        ttsReflectEmotionRow = addTrackedRow(form, row++, "", ttsReflectEmotionCheck);
        ttsContourStrengthRow = addTrackedRow(form, row++, tt("settings.label.ttsContourStrength", "Cadence reflection"), ttsContourStrengthCombo);
        ttsToneEmphasisRow = addTrackedRow(form, row++, tt("settings.label.ttsToneEmphasis", "Bright / dark tone emphasis"), ttsToneEmphasisCombo);
        ttsVoicegerLangRow = addTrackedRow(form, row++, tt("settings.label.voicegerLang", "Voiceger TTS language"), voicegerLangCombo);

        ttsSetupSectionLabel = sectionLabel(tt("settings.section.ttsSetup", "Setup / Advanced"));
        form.add(ttsSetupSectionLabel, sectionGbc(row++));

        JPanel setupButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        ttsSetupVoicevoxBtn = new JButton(tt("settings.util.voicevox", "VOICEVOX"));
        ttsSetupVoicevoxBtn.addActionListener(e -> openVoiceVoxSetup());

        ttsSetupXttsBtn = new JButton(tt("settings.util.xtts", "XTTS"));
        ttsSetupXttsBtn.addActionListener(e -> openXttsSetup());

        ttsSetupVoicegerBtn = new JButton(tt("settings.util.voiceger", "Voiceger"));
        ttsSetupVoicegerBtn.addActionListener(e -> openVoicegerSetup());

        setupButtons.add(ttsSetupVoicevoxBtn);
        setupButtons.add(ttsSetupXttsBtn);
        setupButtons.add(ttsSetupVoicegerBtn);

        ttsSetupButtonsRow = addTrackedRow(form, row++, tt("settings.label.ttsSetup", "Open setup"), setupButtons);

        JTextArea note = helperNote(tt("settings.ttsSetup.note",
                "Open VOICEVOX / XTTS / Voiceger setup pages when you need advanced setup."));
        ttsSetupNoteRow = addTrackedRow(form, row++, "", note);

        ttsEngineCombo.addActionListener(e -> {
            if (syncingTtsControls) return;
            updateTtsPrimaryVoiceChoices();
            updateTtsUiState();
        });
        ttsPrimaryVoiceCombo.addActionListener(e -> {
            if (syncingTtsControls) return;
            syncDetailedTtsSelectionFromPrimary();
        });
        windowsVoiceCombo.addActionListener(e -> {
            if (syncingTtsControls) return;
            refreshPrimaryVoiceSelectionFromDetailed();
        });
        voicevoxSpeakerCombo.addActionListener(e -> {
            if (syncingTtsControls) return;
            refreshPrimaryVoiceSelectionFromDetailed();
        });
        piperPlusModelCombo.addActionListener(e -> {
            if (syncingTtsControls) return;
            refreshPrimaryVoiceSelectionFromDetailed();
        });
        voicegerLangCombo.addActionListener(e -> {
            if (syncingTtsControls) return;
            refreshPrimaryVoiceSelectionFromDetailed();
        });
        updateTtsPrimaryVoiceChoices();
        updateTtsUiState();

        return wrapPage(
                tt("settings.page.tts.title", "TTS / Voice"),
                tt("settings.page.tts.desc", "Speech engine and voice selection."),
                form
        );
    }

    private JPanel buildTextPage() {
        JPanel form = formPanel();
        int row = 0;

        whisperLanguageCombo = new JComboBox<>(LanguageOptions.whisperLangs());
        whisperLanguageCombo.setRenderer(LanguageOptions.whisperRenderer());
        talkTranslateTargetCombo = new JComboBox<>(LanguageOptions.translationTargets());
        talkTranslateTargetCombo.setRenderer(LanguageOptions.translationRenderer());
        initialPromptArea = new JTextArea(4, 40);
        initialPromptArea.setLineWrap(true);
        initialPromptArea.setWrapStyleWord(true);
        hearingInitialPromptArea = new JTextArea(3, 40);
        hearingInitialPromptArea.setLineWrap(true);
        hearingInitialPromptArea.setWrapStyleWord(true);

        voicevoxExeField = new JTextField();
        voicevoxApiField = new JTextField();
        voicevoxDetectButton = new JButton(tt("settings.voicevox.detect", "Auto detect"));
        voicevoxDetectButton.addActionListener(e -> detectVoiceVoxSettings());
        xttsApiField = new JTextField();
        xttsApiChkField = new JTextField();
        xttsLanguageField = new JTextField();
        ignoreModeCombo = new JComboBox<>(new Choice[]{
                new Choice<>(tt("settings.ignore.simple", "Simple"), "simple"),
                new Choice<>(tt("settings.ignore.regex", "Regex"), "regex")
        });

        laughsEnableCheck = new JCheckBox(tt("settings.laughs.enable", "Enable laugh normalization")); 
        laughsDetectField = new JTextField();
        laughsDetectAutoField = new JTextField();
        laughsReplaceField = new JTextField();

        vadSensitivitySpinner = new JSpinner(new SpinnerNumberModel(50, 0, 100, 1)); 
        vadToleranceSpinner   = new JSpinner(new SpinnerNumberModel(5, 1, 10, 1));   

        addRow(form, row++, tt("settings.label.whisperLang", "Talk language (Whisper)"), whisperLanguageCombo);
        addRow(form, row++, tt("settings.label.translateTarget", "Translation target"), talkTranslateTargetCombo);
        addRow(form, row++, tt("settings.label.initialPrompt", "Initial prompt"),
                new JScrollPane(initialPromptArea), true);
        addRow(form, row++, tt("settings.label.hearingInitialPrompt", "Hearing prompt (Whisper)"),
                new JScrollPane(hearingInitialPromptArea), true);

        form.add(sectionLabel(tt("settings.section.voicevox", "VOICEVOX connection")), sectionGbc(row++));
        JPanel voicevoxExePanel = new JPanel(new BorderLayout(8, 0));
        voicevoxExePanel.setOpaque(false);
        voicevoxExePanel.add(voicevoxExeField, BorderLayout.CENTER);
        voicevoxExePanel.add(voicevoxDetectButton, BorderLayout.EAST);
        addRow(form, row++, tt("settings.label.voicevoxExe", "VOICEVOX executable"), voicevoxExePanel);
        addRow(form, row++, tt("settings.label.voicevoxApi", "VOICEVOX API URL"), voicevoxApiField);

        form.add(sectionLabel(tt("settings.section.xtts", "XTTS connection")), sectionGbc(row++));
        addRow(form, row++, tt("settings.label.xttsApi", "XTTS API URL"), xttsApiField);
        addRow(form, row++, tt("settings.label.xttsHealth", "XTTS health check URL"), xttsApiChkField);
        addRow(form, row++, tt("settings.label.xttsLanguage", "XTTS language code"), xttsLanguageField);

        form.add(sectionLabel(tt("settings.section.filter", "Filter / Laugh settings")), sectionGbc(row++));
        addRow(form, row++, tt("settings.label.ignoreMode", "Ignore mode"), ignoreModeCombo);
        addRow(form, row++, "", laughsEnableCheck); 
        addRow(form, row++, tt("settings.label.laughsDetect", "Manual laugh detection tokens"), laughsDetectField);
        addRow(form, row++, tt("settings.label.laughsDetectAuto", "Auto laugh detection tokens"), laughsDetectAutoField);
        addRow(form, row++, tt("settings.label.laughsReplace", "Laugh replacement words"), laughsReplaceField);

        form.add(sectionLabel(tt("settings.section.vad", "Voice activity detection")), sectionGbc(row++)); 
        addRow(form, row++, tt("settings.label.vadSensitivity", "Speech sensitivity"), vadSensitivitySpinner); 
        addRow(form, row++, tt("settings.label.vadTolerance", "Silence tolerance"), vadToleranceSpinner); 

        return wrapPage(
                tt("settings.page.text.title", "Text settings"),
                tt("settings.page.text.desc", "Prompt, TTS connection, filtering, and VAD related settings."),
                form
        );
    }

    private JPanel buildRadioPage() {
        JPanel form = formPanel();
        int row = 0;

        radioModCombo = new JComboBox<>(new Choice[]{
                new Choice<>("NONE", 0),
                new Choice<>("SHIFT", 1),
                new Choice<>("CTRL", 2),
                new Choice<>("SHIFT+CTRL", 3)
        });

        radioKeyCombo = new JComboBox<>(new Choice[]{
                new Choice<>("F1", NativeKeyEvent.VC_F1),
                new Choice<>("F2", NativeKeyEvent.VC_F2),
                new Choice<>("F3", NativeKeyEvent.VC_F3),
                new Choice<>("F4", NativeKeyEvent.VC_F4),
                new Choice<>("F5", NativeKeyEvent.VC_F5),
                new Choice<>("F6", NativeKeyEvent.VC_F6),
                new Choice<>("F7", NativeKeyEvent.VC_F7),
                new Choice<>("F8", NativeKeyEvent.VC_F8),
                new Choice<>("F9", NativeKeyEvent.VC_F9),
                new Choice<>("F10", NativeKeyEvent.VC_F10),
                new Choice<>("F11", NativeKeyEvent.VC_F11),
                new Choice<>("F12", NativeKeyEvent.VC_F12),
                new Choice<>("F13", NativeKeyEvent.VC_F13),
                new Choice<>("F14", NativeKeyEvent.VC_F14),
                new Choice<>("F15", NativeKeyEvent.VC_F15),
                new Choice<>("F16", NativeKeyEvent.VC_F16),
                new Choice<>("F17", NativeKeyEvent.VC_F17),
                new Choice<>("F18", NativeKeyEvent.VC_F18)
        });

        overlayEnableCheck = new JCheckBox(tt("settings.overlay.enable", "Show subtitle overlay"));
        overlayPosCombo = new JComboBox<>(new Choice[]{
                new Choice<>(tt("settings.overlay.position.top_left", "Top left"), "TOP_LEFT"),
                new Choice<>(tt("settings.overlay.position.top_right", "Top right"), "TOP_RIGHT"),
                new Choice<>(tt("settings.overlay.position.bottom_left", "Bottom left"), "BOTTOM_LEFT"),
                new Choice<>(tt("settings.overlay.position.bottom_right", "Bottom right"), "BOTTOM_RIGHT")
        });

        overlayDisplayCombo = new JComboBox<>(new Choice[]{
                new Choice<>(tt("settings.overlay.display.primary", "Primary"), 0),
                new Choice<>(tt("settings.overlay.display.second", "2nd display"), 1),
                new Choice<>(tt("settings.overlay.display.third", "3rd display"), 2),
                new Choice<>(tt("settings.overlay.display.fourth", "4th display"), 3)
        });

        overlayFontSizeSpinner = new JSpinner(new SpinnerNumberModel(16, 10, 64, 1));

        overlayOpacitySlider = new JSlider(30, 100, 78);
        overlayOpacitySlider.setPaintTicks(true);
        overlayOpacitySlider.setMajorTickSpacing(10);
        overlayOpacitySlider.setPaintLabels(true);

        overlayThemePresetCombo = new JComboBox<>(new Choice[]{
                new Choice<>(tt("settings.overlay.theme.custom", "Custom"), "custom"),
                new Choice<>(tt("settings.overlay.theme.green", "Green"), "green"),
                new Choice<>(tt("settings.overlay.theme.blue", "Blue"), "blue"),
                new Choice<>(tt("settings.overlay.theme.gray", "Gray"), "gray"),
                new Choice<>(tt("settings.overlay.theme.red", "Dark red"), "red")
        });

        overlayMarginSpinner = new JSpinner(new SpinnerNumberModel(12, 0, 64, 1));
        overlayMaxLinesSpinner = new JSpinner(new SpinnerNumberModel(12, 4, 30, 1));

        overlayPageChangeCombo = new JComboBox<>(new String[]{
                "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
                "q", "e", "r", "tab",
                "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10", "f11", "f12"
        });

        overlayBgField = new JTextField();
        overlayFgField = new JTextField();

        radioShortcutDisplayField = new JTextField();
        radioShortcutDisplayField.setEditable(false);
        radioShortcutDisplayField.setColumns(16);

        radioBgPresetCombo = new JComboBox<>();
        radioFgPresetCombo = new JComboBox<>();
        repopulateRadioPresetCombo(radioBgPresetCombo, true, "#1D6F5A");
        repopulateRadioPresetCombo(radioFgPresetCombo, false, "#FFFFFF");

        overlayThemePresetCombo.addActionListener(e -> {
            if (overlayThemePresetApplying) return;
            applyOverlayThemePreset(selectedValue(overlayThemePresetCombo));
        });
        installOverlayThemeTracking(overlayBgField);
        installOverlayThemeTracking(overlayFgField);
        radioBgPresetCombo.addActionListener(e -> {
            if (syncingRadioControls) return;
            String bgHex = selectedValue(radioBgPresetCombo);
            String fgHex = selectedValue(radioFgPresetCombo);
            if (bgHex != null) overlayBgField.setText(bgHex);
            if (fgHex != null) overlayFgField.setText(fgHex);
            app.setRadioOverlayColors(
                    Objects.toString(bgHex, overlayBgField.getText()).trim(),
                    Objects.toString(fgHex, overlayFgField.getText()).trim()
            );
        });
        radioFgPresetCombo.addActionListener(e -> {
            if (syncingRadioControls) return;
            String bgHex = selectedValue(radioBgPresetCombo);
            String fgHex = selectedValue(radioFgPresetCombo);
            if (bgHex != null) overlayBgField.setText(bgHex);
            if (fgHex != null) overlayFgField.setText(fgHex);
            app.setRadioOverlayColors(
                    Objects.toString(bgHex, overlayBgField.getText()).trim(),
                    Objects.toString(fgHex, overlayFgField.getText()).trim()
            );
        });

        overlayEnableCheck.addActionListener(e -> updateOverlayDetailEnabled()); 

        form.add(sectionLabel(tt("settings.section.radioChat", "Radio Chat Mode")), sectionGbc(row++));
        JTextArea radioNote = helperNote(tt("settings.section.radioChat.desc",
                "Use the same simple controls as the main window so shortcut, position, and colors stay in sync."));
        addRow(form, row++, "", radioNote);

        JButton captureRadioShortcutButton = new JButton(tt("settings.radio.capture", "Capture shortcut"));
        captureRadioShortcutButton.addActionListener(e -> {
            app.captureRadioHotkeyFromUi(this);
            refreshRadioSelections();
        });
        JButton previewOverlayButton = new JButton(tt("settings.radio.preview", "Preview overlay"));
        previewOverlayButton.addActionListener(e -> app.previewRadioOverlayFromUi());
        JPanel shortcutPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        shortcutPanel.setOpaque(false);
        shortcutPanel.add(radioShortcutDisplayField);
        shortcutPanel.add(captureRadioShortcutButton);
        shortcutPanel.add(previewOverlayButton);
        addTrackedRow(form, row++, tt("settings.label.radioShortcut", "Shortcut"), shortcutPanel);

        JTextArea radioHint = helperNote(tt("settings.radio.shortcut.recommend",
                "Recommended: Scroll Lock / Pause / Ctrl+Alt+Shift+M"));
        addTrackedRow(form, row++, "", radioHint);
        addTrackedRow(form, row++, tt("settings.label.overlayPosition", "Screen position"), overlayPosCombo);
        addTrackedRow(form, row++, tt("settings.label.overlayBgPreset", "Background color"), radioBgPresetCombo);
        addTrackedRow(form, row++, tt("settings.label.overlayFgPreset", "Text color"), radioFgPresetCombo);

        JButton openRadioConfigButton = new JButton(tt("settings.radio.openConfig", "Open radio chat config file"));
        openRadioConfigButton.addActionListener(e -> app.openRadioChatConfigFileFromUi());
        addTrackedRow(form, row++, "", openRadioConfigButton);

        JTextArea overlayNote = helperNote(tt("settings.section.overlay.desc",
                "Advanced overlay tuning is still available below when you need display, opacity, or page change details."));
        addTrackedRow(form, row++, "", overlayNote);

        form.add(sectionLabel(tt("settings.section.overlayAdvanced", "Overlay Advanced")), sectionGbc(row++));
        addRow(form, row++, "", overlayEnableCheck);
        addRow(form, row++, tt("settings.label.overlayDisplay", "Display"), overlayDisplayCombo);
        addRow(form, row++, tt("settings.label.overlayFontSize", "Text size"), overlayFontSizeSpinner);
        addRow(form, row++, tt("settings.label.overlayOpacity", "Background opacity"), overlayOpacitySlider);
        addRow(form, row++, tt("settings.label.overlayMargin", "Screen edge margin"), overlayMarginSpinner);
        addRow(form, row++, tt("settings.label.overlayMaxLines", "Visible lines"), overlayMaxLinesSpinner);
        addRow(form, row++, tt("settings.label.overlayPageChange", "Page change key"), overlayPageChangeCombo);

        JTextArea note = helperNote(tt("settings.overlay.theme.note",
                "Color presets match the Simple mode card. Advanced overlay rows below stay available for fine tuning."));
        addRow(form, row++, "", note);

        updateOverlayDetailEnabled();

        return wrapPage(
                tt("settings.page.radio.title", "Radio Chat / Overlay"),
                tt("settings.page.radio.desc", "Set up Radio Chat controls and how subtitles appear in-game."),
                form
        );
    }

    private JPanel buildAppearancePage() {
        JPanel form = formPanel();
        int row = 0;

        uiLangCombo = new JComboBox<>(new Choice[]{
                new Choice<>("English", "en"),
                new Choice<>("日本語", "ja"),
                new Choice<>("简体中文", "zh_cn"),
                new Choice<>("繁體中文", "zh_tw"),
                new Choice<>("한국어", "ko")
        });

        uiFontSizeCombo = new JComboBox<>(new Choice[]{
                new Choice<>("12", 12),
                new Choice<>("14", 14),
                new Choice<>("16", 16),
                new Choice<>("18", 18),
                new Choice<>("20", 20),
                new Choice<>("22", 22),
                new Choice<>("24", 24)
        });
        uiMainModeCombo = new JComboBox<>(new Choice[]{
                new Choice<>("Simple", MobMateWhisp.UI_MAIN_MODE_SIMPLE),
                new Choice<>("Classic", MobMateWhisp.UI_MAIN_MODE_CLASSIC)
        });

        darkThemeCheck = new JCheckBox(tt("settings.label.darkTheme", "Dark theme"));

        addRow(form, row++, tt("settings.label.uiLanguage", "UI language"), uiLangCombo);
        addRow(form, row++, tt("settings.label.uiFont", "UI font size"), uiFontSizeCombo);
        addRow(form, row++, tt("settings.label.mainWindowMode", "Main window mode"), uiMainModeCombo);
        addRow(form, row++, "", darkThemeCheck);

        return wrapPage(
                tt("settings.page.appearance.title", "Appearance"),
                tt("settings.page.appearance.desc", "Language, font size, and theme."),
                form
        );
    }

    public void refreshLinkedSelections() {
        SwingUtilities.invokeLater(() -> {
            refreshAudioSelections();
            refreshRecognitionSelections();
            refreshVoiceSelections();
            refreshPendingSelections();
            refreshRadioSelections();
            selectChoice(voicegerLangCombo, MobMateWhisp.prefs.get("voiceger.tts.lang", "all_ja"));
            selectStringComboValue(whisperLanguageCombo, app.getTalkLanguage(), app.getTalkLanguage());
            selectStringComboValue(talkTranslateTargetCombo, app.getTalkTranslateTarget(), app.getTalkTranslateTarget());
        });
    }

    private void refreshAudioSelections() {
        if (inputCombo != null) {
            Object selectedInput = inputCombo.getSelectedItem();
            String desiredInput = MobMateWhisp.prefs.get("audio.device", "");
            inputCombo.removeAllItems();
            for (String s : app.getInputsMixerNames()) inputCombo.addItem(s);
            selectStringComboValue(inputCombo, desiredInput, Objects.toString(selectedInput, ""));
        }
        if (outputCombo != null) {
            Object selectedOutput = outputCombo.getSelectedItem();
            String desiredOutput = MobMateWhisp.prefs.get("audio.output.device", "");
            outputCombo.removeAllItems();
            for (String s : app.getOutputMixerNames()) outputCombo.addItem(s);
            selectStringComboValue(outputCombo, desiredOutput, Objects.toString(selectedOutput, ""));
        }
        if (monitorVolumeCombo != null) {
            selectStringComboValue(
                    monitorVolumeCombo,
                    app.getTtsMonitorVolumePercentForUi() + "%",
                    "0%"
            );
        }
        if (aiAssistCheck != null) {
            aiAssistCheck.setSelected(app.isAiAssistEnabledForUi());
        }
    }

    private void refreshVoiceSelections() {
        if (recogEngineCombo != null) {
            selectChoice(recogEngineCombo, MobMateWhisp.prefs.get("recog.engine", "whisper"));
            updateRecognitionUiState();
        }
        if (ttsEngineCombo != null) {
            selectChoice(ttsEngineCombo, MobMateWhisp.prefs.get("tts.engine", "auto"));
        }
        if (windowsVoiceCombo != null) {
            if (windowsVoiceCombo.getItemCount() == 0) {
                windowsVoiceCombo.addItem("auto");
            }
            selectStringComboValue(windowsVoiceCombo, MobMateWhisp.prefs.get("tts.windows.voice", "auto"), "auto");
        }
        if (piperPlusModelCombo != null) {
            reloadPiperPlusModelChoices();
        }
        if (voicegerLangCombo != null) {
            selectChoice(voicegerLangCombo, MobMateWhisp.prefs.get("voiceger.tts.lang", "all_ja"));
        }
        if (voicevoxSpeakerCombo != null && ttsListsLoaded.get() && !ttsListsLoading.get()) {
            applyVoiceVoxSpeakerList(app.getVoiceVoxSpeakersForSettings());
        }
        updateTtsPrimaryVoiceChoices();
        updatePiperPlusSelectionDetails();
        updateTtsUiState();
    }

    private void refreshRecognitionSelections() {
        if (speakerEnabledCheck != null) {
            speakerEnabledCheck.setSelected(MobMateWhisp.prefs.getBoolean("speaker.enabled", false));
        }
        if (speakerEnrollCombo != null) {
            selectChoice(speakerEnrollCombo, MobMateWhisp.prefs.getInt("speaker.enroll_samples", MobMateWhisp.DEFAULT_SPEAKER_ENROLL_SAMPLES));
        }
        if (speakerThresholdCombo != null) {
            selectChoice(speakerThresholdCombo, MobMateWhisp.prefs.getFloat("speaker.threshold_initial", MobMateWhisp.DEFAULT_SPEAKER_THRESHOLD));
        }
    }

    private void refreshPendingSelections() {
        syncingPendingControls = true;
        try {
            boolean pendingMode = app.isTtsConfirmModeEnabled();
            if (ttsConfirmInstantButton != null) {
                ttsConfirmInstantButton.setSelected(!pendingMode);
            }
            if (ttsConfirmPendingButton != null) {
                ttsConfirmPendingButton.setSelected(pendingMode);
            }
            if (ttsConfirmSecCombo != null) {
                selectChoice(ttsConfirmSecCombo, app.getConfirmSecondsForUi());
            }
            styleSettingsPendingModeButtons();
        } finally {
            syncingPendingControls = false;
        }
    }

    private void refreshRadioSelections() {
        syncingRadioControls = true;
        try {
            if (radioModCombo != null) {
                selectChoice(radioModCombo, MobMateWhisp.prefs.getInt("radio.modMask", 0));
            }
            if (radioKeyCombo != null) {
                selectChoice(radioKeyCombo, MobMateWhisp.prefs.getInt("radio.keyCode", NativeKeyEvent.VC_F18));
            }
            if (radioShortcutDisplayField != null) {
                radioShortcutDisplayField.setText(app.getRadioHotkeyDisplayForUi());
            }
            if (overlayEnableCheck != null) {
                overlayEnableCheck.setSelected(Config.getBool("overlay.enable", true));
            }
            if (overlayPosCombo != null) {
                selectChoice(overlayPosCombo, app.getOverlayPositionForUi().trim().toUpperCase(Locale.ROOT));
            }
            int overlayDisplay = Math.max(0, Config.getInt("overlay.display", 0));
            ensureOverlayDisplayChoice(overlayDisplay);
            if (overlayDisplayCombo != null) {
                selectChoice(overlayDisplayCombo, overlayDisplay);
            }
            if (overlayFontSizeSpinner != null) {
                overlayFontSizeSpinner.setValue(Math.max(10, Config.getInt("overlay.font_size", 16)));
            }
            if (overlayOpacitySlider != null) {
                overlayOpacitySlider.setValue((int) (Config.getFloat("overlay.opacity", 0.78f) * 100f));
            }
            if (overlayMarginSpinner != null) {
                overlayMarginSpinner.setValue(Math.max(0, Config.getInt("overlay.margin", 12)));
            }
            if (overlayMaxLinesSpinner != null) {
                overlayMaxLinesSpinner.setValue(Math.max(4, Config.getInt("overlay.max_lines", 12)));
            }
            if (overlayPageChangeCombo != null) {
                selectStringComboValue(
                        overlayPageChangeCombo,
                        Config.getString("overlay.page_change", "0").trim(),
                        "0"
                );
            }
            String bgHex = normalizeHex(toHex(app.getOverlayBgForUi()));
            String fgHex = normalizeHex(toHex(app.getOverlayFgForUi()));
            if (overlayBgField != null) overlayBgField.setText(bgHex);
            if (overlayFgField != null) overlayFgField.setText(fgHex);
            if (radioBgPresetCombo != null) {
                repopulateRadioPresetCombo(radioBgPresetCombo, true, bgHex);
            }
            if (radioFgPresetCombo != null) {
                repopulateRadioPresetCombo(radioFgPresetCombo, false, fgHex);
            }
            if (overlayThemePresetCombo != null) {
                selectChoice(overlayThemePresetCombo, detectOverlayThemePreset(bgHex, fgHex));
            }
            updateOverlayDetailEnabled();
        } finally {
            syncingRadioControls = false;
        }
    }
    private void loadCurrentValues() {
        // ===== General =====
        selectChoice(actionCombo, MobMateWhisp.prefs.get("action", "nothing"));
        selectChoice(triggerModeCombo, MobMateWhisp.prefs.get("trigger-mode", "start_stop"));
        hotkeyCombo.setSelectedItem(MobMateWhisp.prefs.get("hotkey", "F9"));
        shiftCheck.setSelected(MobMateWhisp.prefs.getBoolean("shift-hotkey", false));
        ctrlCheck.setSelected(MobMateWhisp.prefs.getBoolean("ctrl-hotkey", false));
        silenceDetectionCheck.setSelected(MobMateWhisp.prefs.getBoolean("silence-detection", true));

        // ===== Audio =====
        refreshAudioSelections();
        autoGainCheck.setSelected(MobMateWhisp.prefs.getBoolean("audio.autoGain", true));
        selectChoice(audioPrefilterModeCombo, MobMateWhisp.getAudioPrefilterMode());
        selectChoice(inputGainCombo, MobMateWhisp.prefs.getFloat("audio.inputGainMultiplier", 1.0f));

        // ===== Recognition =====
        selectChoice(recogEngineCombo, MobMateWhisp.prefs.get("recog.engine", "whisper"));

        whisperModelCombo.removeAllItems();
        File modelsDir = new File("models");
        File[] bins = modelsDir.isDirectory() ? modelsDir.listFiles((d, n) -> n.endsWith(".bin")) : null;
        if (bins != null) {
            Arrays.sort(bins, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File f : bins) whisperModelCombo.addItem(f.getName());
        }
        whisperModelCombo.setSelectedItem(MobMateWhisp.prefs.get("model", ""));

        moonModelMap.clear();
        moonModelMap.putAll(app.scanMoonshineModelMap());
        moonshineModelCombo.removeAllItems();
        for (String key : moonModelMap.keySet()) moonshineModelCombo.addItem(key);
        String moonKey = app.findMoonshineModelKey(
                MobMateWhisp.prefs.get("moonshine.model_path", ""),
                moonModelMap
        );
        if (moonKey != null) {
            moonshineModelCombo.setSelectedItem(moonKey);
        }

        int gpuIndex = MobMateWhisp.prefs.getInt("vulkan.gpu.index", -1);
        ensureGpuChoice(gpuIndex);
        selectChoice(gpuSelectCombo, gpuIndex);

        lowGpuCheck.setSelected(MobMateWhisp.prefs.getBoolean("perf.low_gpu_mode", true));
        altLaughCheck.setSelected(MobMateWhisp.prefs.getBoolean("silence.alternate", false));

        speakerEnabledCheck.setSelected(MobMateWhisp.prefs.getBoolean("speaker.enabled", false));
        selectChoice(speakerEnrollCombo, MobMateWhisp.prefs.getInt("speaker.enroll_samples", MobMateWhisp.DEFAULT_SPEAKER_ENROLL_SAMPLES));
        selectChoice(speakerThresholdCombo, MobMateWhisp.prefs.getFloat("speaker.threshold_initial", MobMateWhisp.DEFAULT_SPEAKER_THRESHOLD));

        updateRecognitionUiState(); 

        // ===== TTS =====
        selectChoice(ttsEngineCombo, MobMateWhisp.prefs.get("tts.engine", "auto"));
        refreshPendingSelections();

        windowsVoiceCombo.removeAllItems();
        windowsVoiceCombo.addItem("auto");
        windowsVoiceCombo.addItem("loading...");
        windowsVoiceCombo.setSelectedItem("auto");

        voicevoxSpeakerCombo.removeAllItems();
        voicevoxSpeakerCombo.addItem("loading...");
        reloadPiperPlusModelChoices();

        voicevoxAutoEmotionCheck.setSelected(MobMateWhisp.prefs.getBoolean("voicevox.auto_emotion", true));
        ttsReflectEmotionCheck.setSelected(MobMateWhisp.prefs.getBoolean("tts.reflect_emotion", true));
        selectChoice(ttsContourStrengthCombo, MobMateWhisp.prefs.get("tts.reflect.contour_strength", "normal"));
        selectChoice(ttsToneEmphasisCombo, MobMateWhisp.prefs.get("tts.reflect.tone_emphasis", "normal"));
        selectChoice(voicegerLangCombo, MobMateWhisp.prefs.get("voiceger.tts.lang", "all_ja"));
        updateTtsPrimaryVoiceChoices();
        updatePiperPlusSelectionDetails();
        updateTtsUiState();

        // ===== Text / _outtts =====
        selectStringComboValue(whisperLanguageCombo, app.getTalkLanguage(), app.getTalkLanguage());
        selectStringComboValue(talkTranslateTargetCombo, app.getTalkTranslateTarget(), app.getTalkTranslateTarget());
        initialPromptArea.setText(Config.getString("initial_prompt", ""));
        hearingInitialPromptArea.setText(MobMateWhisp.prefs.get("hearing.initial_prompt", ""));
        voicevoxExeField.setText(Config.getString("voicevox.exe", ""));
        voicevoxApiField.setText(Config.getString("voicevox.api", ""));
        xttsApiField.setText(Config.getString("xtts.api", ""));
        xttsApiChkField.setText(Config.getString("xtts.apichk", ""));
        xttsLanguageField.setText(Config.getString("xtts.language", ""));
        selectChoice(ignoreModeCombo, Config.getString("ignore.mode", "simple"));

        laughsEnableCheck.setSelected(Config.getBool("laughs.enable", true)); 
        laughsDetectField.setText(Config.getString("laughs.detect", ""));
        laughsDetectAutoField.setText(Config.getString("laughs.detect.auto", ""));
        laughsReplaceField.setText(Config.getString("laughs.replace", ""));

        vadSensitivitySpinner.setValue(Math.max(0, Math.min(100, Config.getInt("vad.sensitivity", 50)))); 
        int vadTol = Config.getInt("vad.tolerance", Config.getInt("vad.silence_tolerance", 5));            
        vadToleranceSpinner.setValue(Math.max(1, Math.min(10, vadTol)));

        // ===== Radio / Overlay =====
        refreshRadioSelections();

        // ===== Appearance =====
        selectChoice(uiLangCombo, MobMateWhisp.prefs.get("ui.language", "en"));
        selectChoice(uiFontSizeCombo, MobMateWhisp.prefs.getInt("ui.font.size", 16));
        selectChoice(uiMainModeCombo, MobMateWhisp.prefs.get(MobMateWhisp.PREF_UI_MAIN_MODE, MobMateWhisp.UI_MAIN_MODE_SIMPLE));
        darkThemeCheck.setSelected(MobMateWhisp.prefs.getBoolean("ui.theme.dark", true));
    }

    private void fillVoiceVoxSpeakers() {
        String current = MobMateWhisp.prefs.get("tts.voice", "3");
        voicevoxSpeakerCombo.removeAllItems();

        List<MobMateWhisp.VoiceVoxSpeaker> speakers = app.getVoiceVoxSpeakersForSettings();
        if (speakers == null || speakers.isEmpty()) {
            voicevoxSpeakerCombo.addItem(current);
            voicevoxSpeakerCombo.setSelectedItem(current);
            return;
        }

        String selectedLabel = null;
        for (MobMateWhisp.VoiceVoxSpeaker sp : speakers) {
            String label = sp.id() + ":" + sp.name();
            voicevoxSpeakerCombo.addItem(label);
            if (String.valueOf(sp.id()).equals(current)) {
                selectedLabel = label;
            }
        }

        if (selectedLabel != null) {
            voicevoxSpeakerCombo.setSelectedItem(selectedLabel);
        } else if (voicevoxSpeakerCombo.getItemCount() > 0) {
            voicevoxSpeakerCombo.setSelectedIndex(0);
        }
    }
    private static final class TtsListsBundle {
        List<String> windowsVoices = new ArrayList<>();
        List<MobMateWhisp.VoiceVoxSpeaker> voicevoxSpeakers = new ArrayList<>();
    }

    private void ensureTtsListsLoadedAsync(boolean forceReload) {
        if (forceReload) {
            ttsListsLoaded.set(false);
        }
        if (ttsListsLoaded.get()) return;
        if (!ttsListsLoading.compareAndSet(false, true)) return;

        windowsVoiceCombo.setEnabled(false);
        voicevoxSpeakerCombo.setEnabled(false);

        windowsVoiceCombo.removeAllItems();
        windowsVoiceCombo.addItem("loading...");

        voicevoxSpeakerCombo.removeAllItems();
        voicevoxSpeakerCombo.addItem("loading...");

        new SwingWorker<TtsListsBundle, Void>() {
            @Override
            protected TtsListsBundle doInBackground() {
                TtsListsBundle b = new TtsListsBundle();

                try {
                    List<String> voices = app.getWindowsVoicesForSettings();
                    if (voices != null) {
                        b.windowsVoices.addAll(voices);
                        Collections.sort(b.windowsVoices);
                    }
                } catch (Throwable ignore) {}

                try {
                    List<MobMateWhisp.VoiceVoxSpeaker> speakers = app.getVoiceVoxSpeakersForSettings();
                    if (speakers != null) {
                        b.voicevoxSpeakers.addAll(speakers);
                    }
                } catch (Throwable ignore) {}

                return b;
            }

            @Override
            protected void done() {
                try {
                    TtsListsBundle b = get();
                    applyWindowsVoiceList(b.windowsVoices);
                    applyVoiceVoxSpeakerList(b.voicevoxSpeakers);
                    ttsListsLoaded.set(true);
                } catch (Exception ex) {
                    windowsVoiceCombo.removeAllItems();
                    windowsVoiceCombo.addItem("auto");
                    windowsVoiceCombo.setSelectedItem("auto");

                    voicevoxSpeakerCombo.removeAllItems();
                    voicevoxSpeakerCombo.addItem(MobMateWhisp.prefs.get("tts.voice", "3"));
                } finally {
                    windowsVoiceCombo.setEnabled(true);
                    voicevoxSpeakerCombo.setEnabled(true);
                    ttsListsLoading.set(false);
                }
            }
        }.execute();
    }

    private void applyWindowsVoiceList(List<String> voices) {
        String current = MobMateWhisp.prefs.get("tts.windows.voice", "auto");

        windowsVoiceCombo.removeAllItems();
        windowsVoiceCombo.addItem("auto");

        for (String voice : voices) {
            windowsVoiceCombo.addItem(voice);
        }

        windowsVoiceCombo.setSelectedItem(current);
        if (windowsVoiceCombo.getSelectedItem() == null) {
            windowsVoiceCombo.setSelectedItem("auto");
        }
    }

    private void applyVoiceVoxSpeakerList(List<MobMateWhisp.VoiceVoxSpeaker> speakers) {
        String current = MobMateWhisp.prefs.get("tts.voice", "3");

        voicevoxSpeakerCombo.removeAllItems();

        if (speakers == null || speakers.isEmpty()) {
            voicevoxSpeakerCombo.addItem(current);
            voicevoxSpeakerCombo.setSelectedItem(current);
            return;
        }

        String selectedLabel = null;
        for (MobMateWhisp.VoiceVoxSpeaker sp : speakers) {
            String label = sp.id() + ":" + sp.name();
            voicevoxSpeakerCombo.addItem(label);
            if (String.valueOf(sp.id()).equals(current)) {
                selectedLabel = label;
            }
        }

        if (selectedLabel != null) {
            voicevoxSpeakerCombo.setSelectedItem(selectedLabel);
        } else if (voicevoxSpeakerCombo.getItemCount() > 0) {
            voicevoxSpeakerCombo.setSelectedIndex(0);
        }
    }

    public Map<String, String> collectPresetSnapshotOverrides() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();

        values.put("audio.device", Objects.toString(inputCombo.getSelectedItem(), ""));
        values.put("audio.output.device", Objects.toString(outputCombo.getSelectedItem(), ""));
        String recogEngine = selectedValue(recogEngineCombo);
        values.put("recog.engine", recogEngine);
        values.put("model", Objects.toString(whisperModelCombo.getSelectedItem(), ""));
        String moonKey = Objects.toString(moonshineModelCombo.getSelectedItem(), "");
        File moonDir = moonModelMap.get(moonKey);
        values.put("moonshine.model_path", moonDir != null ? moonDir.getAbsolutePath() : "");
        String talkLang = app.getTalkLanguage();
        if ("moonshine".equalsIgnoreCase(recogEngine)) {
            String moonLangKey = moonKey.trim();
            if (!moonLangKey.isBlank()) {
                talkLang = moonLangKey.toLowerCase(Locale.ROOT);
            }
        } else {
            talkLang = selectedStringOrFallback(whisperLanguageCombo, talkLang);
        }
        values.put("talk.lang", talkLang);
        values.put("tts.engine", selectedValue(ttsEngineCombo));
        String windowsVoice = Objects.toString(windowsVoiceCombo.getSelectedItem(), "auto");
        if (windowsVoice.isBlank() || "loading...".equalsIgnoreCase(windowsVoice)) {
            windowsVoice = MobMateWhisp.prefs.get("tts.windows.voice", "auto");
        }
        values.put("tts.windows.voice", windowsVoice);

        String vvSel = Objects.toString(voicevoxSpeakerCombo.getSelectedItem(), "");
        if (vvSel.isBlank() || "loading...".equalsIgnoreCase(vvSel)) {
            vvSel = MobMateWhisp.prefs.get("tts.voice", "3");
        }
        String vvId = vvSel;
        int colon = vvSel.indexOf(':');
        if (colon > 0) vvId = vvSel.substring(0, colon).trim();
        values.put("tts.voice", vvId);
        values.put("piper.plus.model_id", selectedPiperPlusModelId());

        values.put("voicevox.auto_emotion", String.valueOf(voicevoxAutoEmotionCheck.isSelected()));
        values.put("tts.reflect_emotion", String.valueOf(ttsReflectEmotionCheck.isSelected()));
        values.put("tts.reflect.contour_strength", selectedValue(ttsContourStrengthCombo));
        values.put("tts.reflect.tone_emphasis", selectedValue(ttsToneEmphasisCombo));
        values.put("voiceger.tts.lang", selectedValue(voicegerLangCombo));
        values.put("piper.plus.model_id", selectedPiperPlusModelId());
        PiperPlusCatalog.Entry piperEntry = PiperPlusCatalog.findById(selectedPiperPlusModelId());
        values.put("piper.plus.license", piperEntry != null ? piperEntry.license() : "");
        values.put("piper.plus.source_url", piperEntry != null ? piperEntry.sourcePageUrl() : "");

        values.put("language", talkLang);
        values.put("initial_prompt", oneLine(initialPromptArea.getText()));
        values.put("hearing.initial_prompt", oneLine(hearingInitialPromptArea.getText()));
        values.put("voicevox.exe", voicevoxExeField.getText().trim());
        values.put("voicevox.api", voicevoxApiField.getText().trim());
        values.put("xtts.api", xttsApiField.getText().trim());
        values.put("xtts.apichk", xttsApiChkField.getText().trim());
        values.put("xtts.language", xttsLanguageField.getText().trim());
        values.put("ignore.mode", selectedValue(ignoreModeCombo));

        return values;
    }

    private void saveAll(boolean closeAfter) {
        boolean needSoftRestart = false;

        // ===== General =====
        MobMateWhisp.prefs.put("action", selectedValue(actionCombo));
        MobMateWhisp.prefs.put("trigger-mode", selectedValue(triggerModeCombo));
        MobMateWhisp.prefs.put("hotkey", Objects.toString(hotkeyCombo.getSelectedItem(), "F9"));
        MobMateWhisp.prefs.putBoolean("shift-hotkey", shiftCheck.isSelected());
        MobMateWhisp.prefs.putBoolean("ctrl-hotkey", ctrlCheck.isSelected());
        MobMateWhisp.prefs.putBoolean("silence-detection", silenceDetectionCheck.isSelected());

        // ===== Audio =====
        String oldInput = MobMateWhisp.prefs.get("audio.device", "");
        String newInput = Objects.toString(inputCombo.getSelectedItem(), "");
        if (!Objects.equals(oldInput, newInput)) {
            MobMateWhisp.prefs.put("audio.device.previous", oldInput);
            MobMateWhisp.prefs.put("audio.device", newInput);
            needSoftRestart = true;
        }

        String oldOut = MobMateWhisp.prefs.get("audio.output.device", "");
        String newOut = Objects.toString(outputCombo.getSelectedItem(), "");
        if (!Objects.equals(oldOut, newOut)) {
            MobMateWhisp.prefs.put("audio.output.device.previous", oldOut);
            MobMateWhisp.prefs.put("audio.output.device", newOut);
            needSoftRestart = true;
        }

        int oldMonitorVolume = app.getTtsMonitorVolumePercentForUi();
        String monitorText = Objects.toString(monitorVolumeCombo.getSelectedItem(), oldMonitorVolume + "%");
        int newMonitorVolume = oldMonitorVolume;
        try {
            newMonitorVolume = Integer.parseInt(monitorText.replace("%", "").trim());
        } catch (NumberFormatException ignore) {}
        if (oldMonitorVolume != newMonitorVolume) {
            app.setTtsMonitorVolumePercentFromUi(newMonitorVolume);
        }

        boolean oldAutoGain = MobMateWhisp.prefs.getBoolean("audio.autoGain", true);
        boolean newAutoGain = autoGainCheck.isSelected();
        if (oldAutoGain != newAutoGain) {
            MobMateWhisp.prefs.putBoolean("audio.autoGain", newAutoGain);
        }
        boolean oldAiAssist = MobMateWhisp.prefs.getBoolean("audio.ai_assist", false);
        boolean newAiAssist = aiAssistCheck.isSelected();
        if (oldAiAssist != newAiAssist) {
            MobMateWhisp.prefs.putBoolean("audio.ai_assist", newAiAssist);
        }

        Float gainVal = selectedValue(inputGainCombo);
        if (gainVal != null) {
            MobMateWhisp.prefs.putFloat("audio.inputGainMultiplier", gainVal);
        }
        String prefilterMode = selectedValue(audioPrefilterModeCombo);
        if (prefilterMode != null) {
            MobMateWhisp.setAudioPrefilterMode(prefilterMode);
        }

        // ===== Recognition =====
        String oldEngine = MobMateWhisp.prefs.get("recog.engine", "whisper");
        String newEngine = selectedValue(recogEngineCombo);
        if (!Objects.equals(oldEngine, newEngine)) {
            MobMateWhisp.prefs.put("recog.engine", newEngine);
            needSoftRestart = true;
        }

        String oldWhisperModel = MobMateWhisp.prefs.get("model", "");
        String newWhisperModel = Objects.toString(whisperModelCombo.getSelectedItem(), "");
        if (!Objects.equals(oldWhisperModel, newWhisperModel) && !newWhisperModel.isBlank()) {
            MobMateWhisp.prefs.put("model", newWhisperModel);
            needSoftRestart = true;
        }

        String moonKey = Objects.toString(moonshineModelCombo.getSelectedItem(), "");
        File moonDir = moonModelMap.get(moonKey);
        if (moonDir != null) {
            String oldMoonPath = MobMateWhisp.prefs.get("moonshine.model_path", "");
            String newMoonPath = moonDir.getAbsolutePath();
            if (!Objects.equals(oldMoonPath, newMoonPath)) {
                MobMateWhisp.prefs.put("moonshine.model_path", newMoonPath);
                MobMateWhisp.prefs.put("talk.lang", moonKey.toLowerCase(Locale.ROOT));
                needSoftRestart = true;
            }
        }

        Integer oldGpuIndex = MobMateWhisp.prefs.getInt("vulkan.gpu.index", -1);
        Integer newGpuIndex = selectedValue(gpuSelectCombo);
        if (newGpuIndex != null && !Objects.equals(oldGpuIndex, newGpuIndex)) {
            MobMateWhisp.prefs.putInt("vulkan.gpu.index", newGpuIndex);
            needSoftRestart = true;
        }

        boolean oldLowGpu = MobMateWhisp.prefs.getBoolean("perf.low_gpu_mode", true);
        boolean newLowGpu = lowGpuCheck.isSelected();
        if (oldLowGpu != newLowGpu) {
            MobMateWhisp.prefs.putBoolean("perf.low_gpu_mode", newLowGpu);
            needSoftRestart = true;
        }

        MobMateWhisp.prefs.putBoolean("silence.alternate", altLaughCheck.isSelected());
        MobMateWhisp.prefs.remove("whisper.translate_to_en");

        MobMateWhisp.prefs.putBoolean("speaker.enabled", speakerEnabledCheck.isSelected());
        Integer oldEnroll = MobMateWhisp.prefs.getInt("speaker.enroll_samples", MobMateWhisp.DEFAULT_SPEAKER_ENROLL_SAMPLES);
        Integer enroll = selectedValue(speakerEnrollCombo);
        if (enroll != null) {
            MobMateWhisp.prefs.putInt("speaker.enroll_samples", enroll);
            if (!Objects.equals(oldEnroll, enroll)) needSoftRestart = true;
        }
        Float oldSpkTh = MobMateWhisp.prefs.getFloat("speaker.threshold_initial", MobMateWhisp.DEFAULT_SPEAKER_THRESHOLD);
        Float spkTh = selectedValue(speakerThresholdCombo);
        if (spkTh != null) {
            MobMateWhisp.prefs.putFloat("speaker.threshold_initial", spkTh);
            MobMateWhisp.prefs.putFloat("speaker.threshold_target", spkTh);
            if (!Objects.equals(oldSpkTh, spkTh)) needSoftRestart = true;
        }

        // ===== TTS =====
        String oldTtsEngine = MobMateWhisp.prefs.get("tts.engine", "auto");
        String newTtsEngine = selectedValue(ttsEngineCombo);
        MobMateWhisp.prefs.put("tts.engine", newTtsEngine);
        MobMateWhisp.prefs.put("piper.plus.model_id", selectedPiperPlusModelId());
        boolean newPendingMode = ttsConfirmPendingButton != null && ttsConfirmPendingButton.isSelected();
        app.setConfirmModeFromUi(newPendingMode);
        Integer confirmSec = selectedValue(ttsConfirmSecCombo);
        if (confirmSec != null) {
            app.setConfirmSecondsFromUi(confirmSec);
        }

        if (ttsListsLoaded.get() && !ttsListsLoading.get()) {
            String newWinVoice = Objects.toString(windowsVoiceCombo.getSelectedItem(), "auto");
            if (!newWinVoice.isBlank() && !"loading...".equalsIgnoreCase(newWinVoice)) {
                MobMateWhisp.prefs.put("tts.windows.voice", newWinVoice);
            }

            String vvSel = Objects.toString(voicevoxSpeakerCombo.getSelectedItem(), "");
            String vvId = vvSel;
            int colon = vvSel.indexOf(':');
            if (colon > 0) vvId = vvSel.substring(0, colon).trim();
            if (!vvId.isBlank() && vvId.chars().allMatch(Character::isDigit)) {
                MobMateWhisp.prefs.put("tts.voice", vvId);
                writeOutttsKey("voicevox.speaker", vvId, false);
            }
        }

        MobMateWhisp.prefs.putBoolean("voicevox.auto_emotion", voicevoxAutoEmotionCheck.isSelected());
        MobMateWhisp.prefs.putBoolean("tts.reflect_emotion", ttsReflectEmotionCheck.isSelected());
        MobMateWhisp.prefs.putBoolean("tts.reflect_emotion.user_touched", true);
        MobMateWhisp.prefs.put("tts.reflect.contour_strength", selectedValue(ttsContourStrengthCombo));
        MobMateWhisp.prefs.put("tts.reflect.tone_emphasis", selectedValue(ttsToneEmphasisCombo));

        String oldVoicegerLang = MobMateWhisp.prefs.get("voiceger.tts.lang", "all_ja");
        String newVoicegerLang = selectedValue(voicegerLangCombo);
        MobMateWhisp.prefs.put("voiceger.tts.lang", newVoicegerLang);

        String pv = (oldTtsEngine == null) ? "" : oldTtsEngine.toLowerCase(Locale.ROOT);
        String nv = (newTtsEngine == null) ? "" : newTtsEngine.toLowerCase(Locale.ROOT);

        boolean switchedToVoiceger =
                !Objects.equals(pv, nv) &&
                        (nv.startsWith("voiceger"));

        boolean voicegerLangChangedWhileUsingVoiceger =
                nv.startsWith("voiceger") &&
                        !Objects.equals(oldVoicegerLang, newVoicegerLang);

        if (switchedToVoiceger || voicegerLangChangedWhileUsingVoiceger) {
            app.requestVoicegerRestartForSettings();
            Config.logDebug("[Settings] TTS engine changed: " + oldTtsEngine + " -> " + newTtsEngine + " (restart Voiceger)");
        }

        // ===== Text / _outtts =====
        if (!"moonshine".equalsIgnoreCase(selectedValue(recogEngineCombo))) {
            String oldTalkLang = app.getTalkLanguage();
            String newTalkLang = selectedStringOrFallback(whisperLanguageCombo, oldTalkLang);
            if (!Objects.equals(oldTalkLang, newTalkLang) && app.applyTalkLanguageSelection(newTalkLang)) {
                needSoftRestart = true;
            }
        }
        String oldTranslateTarget = app.getTalkTranslateTarget();
        String newTranslateTarget = selectedStringOrFallback(talkTranslateTargetCombo, oldTranslateTarget);
        if (!Objects.equals(oldTranslateTarget, newTranslateTarget)) {
            app.setTalkTranslateTarget(newTranslateTarget);
            app.prewarmPiperPlusForTalkTargetSelection(this, newTranslateTarget);
            app.maybeRecommendTalkTtsRoute(this, "settings-talk-translate-target");
        }
        writeOutttsKey("language", app.getTalkLanguage(), false);
        writeOutttsKey("initial_prompt", oneLine(initialPromptArea.getText()), false);
        MobMateWhisp.prefs.put("hearing.initial_prompt", oneLine(hearingInitialPromptArea.getText()));
        LocalWhisperCPP.markInitialPromptDirty();

        writeOutttsKey("voicevox.exe", voicevoxExeField.getText().trim(), true);
        writeOutttsKey("voicevox.api", voicevoxApiField.getText().trim(), false);

        writeOutttsKey("xtts.api", xttsApiField.getText().trim(), false);
        writeOutttsKey("xtts.apichk", xttsApiChkField.getText().trim(), false);
        writeOutttsKey("xtts.language", xttsLanguageField.getText().trim(), false);
        writeOutttsKey("piper.plus.model_id", selectedPiperPlusModelId(), false);
        PiperPlusCatalog.Entry piperEntry = PiperPlusCatalog.findById(selectedPiperPlusModelId());
        writeOutttsKey("piper.plus.license", piperEntry != null ? piperEntry.license() : "", false);
        writeOutttsKey("piper.plus.source_url", piperEntry != null ? piperEntry.sourcePageUrl() : "", false);

        writeOutttsKey("ignore.mode", selectedValue(ignoreModeCombo), false);
        writeOutttsKey("laughs.enable", String.valueOf(laughsEnableCheck.isSelected()), false); 
        writeOutttsKey("laughs.detect", laughsDetectField.getText().trim(), false);
        writeOutttsKey("laughs.detect.auto", laughsDetectAutoField.getText().trim(), false);
        writeOutttsKey("laughs.replace", laughsReplaceField.getText().trim(), false);

        // vad.silence_tolerance にも同じ値を書く。
        int oldVadSensitivity = Config.getInt("vad.sensitivity", 50);
        int newVadSensitivity = (Integer) vadSensitivitySpinner.getValue();
        if (oldVadSensitivity != newVadSensitivity) {
            needSoftRestart = true;
        }
        writeOutttsKey("vad.sensitivity", String.valueOf(newVadSensitivity), false);

        int oldVadTolerance = Config.getInt("vad.tolerance", Config.getInt("vad.silence_tolerance", 5));
        int newVadTolerance = (Integer) vadToleranceSpinner.getValue();
        if (oldVadTolerance != newVadTolerance) {
            needSoftRestart = true;
        }
        writeOutttsKey("vad.tolerance", String.valueOf(newVadTolerance), false);
        writeOutttsKey("vad.silence_tolerance", String.valueOf(newVadTolerance), false);

        // ===== Radio / Overlay =====
        Integer radioMod = selectedValue(radioModCombo);
        Integer radioKey = selectedValue(radioKeyCombo);
        if (radioMod != null) MobMateWhisp.prefs.putInt("radio.modMask", radioMod);
        if (radioKey != null) MobMateWhisp.prefs.putInt("radio.keyCode", radioKey);

        app.setRadioOverlayEnabled(overlayEnableCheck.isSelected());
        String overlayPosition = selectedValue(overlayPosCombo);
        app.setRadioOverlayPosition(overlayPosition != null ? overlayPosition : "TOP_LEFT");

        Integer overlayDisplayValue = selectedValue(overlayDisplayCombo);
        app.setRadioOverlayDisplay(overlayDisplayValue != null ? overlayDisplayValue : 0);

        app.setRadioOverlayFontSize((Integer) overlayFontSizeSpinner.getValue());
        app.setRadioOverlayOpacity(overlayOpacitySlider.getValue() / 100f);
        app.setRadioOverlayMargin((Integer) overlayMarginSpinner.getValue());
        app.setRadioOverlayMaxLines((Integer) overlayMaxLinesSpinner.getValue());

        app.setRadioOverlayPageChangeKeyName(
                Objects.toString(overlayPageChangeCombo.getSelectedItem(), "0").trim()
        );

        app.setRadioOverlayColors(overlayBgField.getText().trim(), overlayFgField.getText().trim());

        // ===== Appearance =====
        String oldUiLang = MobMateWhisp.prefs.get("ui.language", "en");
        String newUiLang = selectedValue(uiLangCombo);
        if (!Objects.equals(oldUiLang, newUiLang)) {
            MobMateWhisp.prefs.put("ui.language", newUiLang);
            needSoftRestart = true;
        }

        Integer newFontSize = selectedValue(uiFontSizeCombo);
        if (newFontSize != null) {
            MobMateWhisp.prefs.putInt("ui.font.size", newFontSize);
            applyFontSizeNow(newFontSize);
        }

        String oldMainMode = MobMateWhisp.prefs.get(MobMateWhisp.PREF_UI_MAIN_MODE, MobMateWhisp.UI_MAIN_MODE_SIMPLE);
        String newMainMode = selectedValue(uiMainModeCombo);
        if (!Objects.equals(oldMainMode, newMainMode)) {
            MobMateWhisp.prefs.put(MobMateWhisp.PREF_UI_MAIN_MODE, newMainMode);
            needSoftRestart = true;
        }

        boolean oldDark = MobMateWhisp.prefs.getBoolean("ui.theme.dark", true);
        boolean newDark = darkThemeCheck.isSelected();
        if (oldDark != newDark) {
            MobMateWhisp.prefs.putBoolean("ui.theme.dark", newDark);
            applyThemeNow(newDark);
        }

        try {
            MobMateWhisp.prefs.sync();
        } catch (Exception ignore) {}

        try {
            Config.reload();
            Config.mirrorAllToCloud();
        } catch (Exception ignore) {}

        try {
            app.saveSelectedPresetSnapshotIfAny();
        } catch (Exception ignore) {}

        app.refreshLinkedUiSelectionsForUi();

        if (needSoftRestart) {
            app.requestSoftRestartForSettings();
            dispose();
            return;
        }

        if (closeAfter) {
            dispose();
        }
    }

    private void openDiscordInvite() {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(DISCORD_INVITE_URL));
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    this,
                    "Failed to open Discord link:\n" + ex.getMessage(),
                    "MobMate",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }
    private void openPiperPlusModelGuide() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(PIPER_PLUS_MODEL_GUIDE_URL));
                return;
            }
        } catch (Exception ex) {
            showPiperPlusGuideFallbackDialog(ex.getMessage());
            return;
        }
        showPiperPlusGuideFallbackDialog(null);
    }

    private void showPiperPlusGuideFallbackDialog(String reason) {
        StringBuilder message = new StringBuilder(tt(
                "settings.piperPlus.guideDialog",
                "Open this piper-plus model guide in your browser:"
        )).append("\n")
                .append(PIPER_PLUS_MODEL_GUIDE_URL);
        if (reason != null && !reason.isBlank()) {
            message.append("\n\n")
                    .append(tt("settings.common.reason", "Reason"))
                    .append(": ")
                    .append(reason);
        }
        JOptionPane.showMessageDialog(
                this,
                message.toString(),
                tt("settings.piperPlus.guideTitle", "MobMate"),
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    private boolean isManualGuideOnlyPiperPlusEntry(PiperPlusCatalog.Entry entry) {
        return entry != null && "css10-ja-6lang-zh".equalsIgnoreCase(entry.id());
    }

    private boolean hasExternalPiperPlusModelCard(PiperPlusCatalog.Entry entry) {
        return entry != null
                && entry.sourcePageUrl() != null
                && !entry.sourcePageUrl().isBlank()
                && entry.sourcePageUrl().startsWith("http");
    }

    private void openSelectedPiperPlusModelCard(PiperPlusCatalog.Entry entry) {
        if (!hasExternalPiperPlusModelCard(entry)) return;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(entry.sourcePageUrl()));
                return;
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    this,
                    tt("settings.piperPlus.modelCardDialog", "Open this piper-plus model page in your browser:")
                            + "\n" + entry.sourcePageUrl()
                            + "\n\n" + tt("settings.common.reason", "Reason") + ": " + ex.getMessage(),
                    tt("settings.piperPlus.guideTitle", "MobMate"),
                    JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }
        JOptionPane.showMessageDialog(
                this,
                tt("settings.piperPlus.modelCardDialog", "Open this piper-plus model page in your browser:")
                        + "\n" + entry.sourcePageUrl(),
                tt("settings.piperPlus.guideTitle", "MobMate"),
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    private void handleSelectedPiperPlusPrimaryAction() {
        PiperPlusCatalog.Entry entry = PiperPlusCatalog.findById(selectedPiperPlusModelId());
        if (isManualGuideOnlyPiperPlusEntry(entry)) {
            openPiperPlusModelGuide();
            return;
        }
        if (entry != null && !entry.isDownloadable() && hasExternalPiperPlusModelCard(entry)) {
            openSelectedPiperPlusModelCard(entry);
            return;
        }
        downloadSelectedPiperPlusModel();
    }
    private void openVoiceVoxSetup() {
        FirstLaunchWizard wizard = null;
        try {
            wizard = new FirstLaunchWizard((Frame) getOwner(), app, true);
            wizard.selectVoiceVoxTab();
            wizard.setLocationRelativeTo(this);
            wizard.setVisible(true);
        } catch (FileNotFoundException | NativeHookException ex) {
            throw new RuntimeException(ex);
        } finally {
            try {
                if (wizard != null) wizard.stopAllWizardTests();
            } catch (Throwable ignore) {}
        }
    }
    private void openXttsSetup() {
        FirstLaunchWizard wizard = null;
        try {
            wizard = new FirstLaunchWizard((Frame) getOwner(), app, true);
            wizard.selectXttsTab();
            wizard.setLocationRelativeTo(this);
            wizard.setVisible(true);
        } catch (FileNotFoundException | NativeHookException ex) {
            throw new RuntimeException(ex);
        } finally {
            try {
                if (wizard != null) wizard.stopAllWizardTests();
            } catch (Throwable ignore) {}
        }
    }
    private void openVoicegerSetup() {
        FirstLaunchWizard wizard = null;
        try {
            wizard = new FirstLaunchWizard((Frame) getOwner(), app, true);
            wizard.selectVoicegerTab();
            wizard.setLocationRelativeTo(this);
            wizard.setVisible(true);
        } catch (FileNotFoundException | NativeHookException ex) {
            throw new RuntimeException(ex);
        } finally {
            try {
                if (wizard != null) wizard.stopAllWizardTests();
            } catch (Throwable ignore) {}
        }
    }
    private void reloadPiperPlusModelChoices() {
        String current = MobMateWhisp.prefs.get("piper.plus.model_id", "");
        piperPlusModelCombo.removeAllItems();
        for (PiperPlusCatalog.Entry entry : PiperPlusCatalog.pickerEntries()) {
            piperPlusModelCombo.addItem(entry.comboLabel());
        }
        if (!current.isBlank()) {
            PiperPlusCatalog.Entry saved = PiperPlusCatalog.pickerSelectionForSaved(current);
            if (saved != null) {
                if (!saved.id().equalsIgnoreCase(current)) {
                    MobMateWhisp.prefs.put("piper.plus.model_id", saved.id());
                }
                piperPlusModelCombo.setSelectedItem(saved.comboLabel());
            }
        }
        if (piperPlusModelCombo.getSelectedItem() == null && piperPlusModelCombo.getItemCount() > 0) {
            piperPlusModelCombo.setSelectedIndex(0);
        }
        piperPlusModelCombo.addActionListener(e -> updatePiperPlusSelectionDetails());
    }

    private String selectedPiperPlusModelId() {
        Object selected = (piperPlusModelCombo != null) ? piperPlusModelCombo.getSelectedItem() : null;
        if (selected == null) return MobMateWhisp.prefs.get("piper.plus.model_id", "");
        String label = selected.toString();
        for (PiperPlusCatalog.Entry entry : PiperPlusCatalog.pickerEntries()) {
            if (entry.comboLabel().equals(label)) return entry.id();
        }
        return MobMateWhisp.prefs.get("piper.plus.model_id", "");
    }

    private void updatePiperPlusSelectionDetails() {
        if (piperPlusLicenseLabel == null || piperPlusStatusLabel == null) return;
        PiperPlusCatalog.Entry entry = PiperPlusCatalog.findById(selectedPiperPlusModelId());
        if (entry == null) {
            piperPlusLicenseLabel.setText("License: -");
            piperPlusStatusLabel.setText("Status: No model selected");
            piperPlusProsodyLabel.setText("Prosody: -");
            if (piperPlusDownloadBtn != null) {
                piperPlusDownloadBtn.setText(tt("settings.piperPlus.download", "Download / Update"));
                piperPlusDownloadBtn.setEnabled(false);
                piperPlusDownloadBtn.setToolTipText(null);
            }
            return;
        }
        piperPlusLicenseLabel.setText("License: " + entry.license() + " | Source: " + entry.sourcePageUrl());
        String runtime = PiperPlusModelManager.isRuntimeAvailable() ? "runtime bundled" : "runtime missing";
        piperPlusStatusLabel.setText("Status: " + PiperPlusModelManager.statusText(entry) + " | " + runtime);
        if (piperPlusDownloadBtn != null) {
            boolean manualOnly = isManualGuideOnlyPiperPlusEntry(entry);
            boolean modelCardOnly = !manualOnly && !entry.isDownloadable() && hasExternalPiperPlusModelCard(entry);
            piperPlusDownloadBtn.setText(manualOnly
                    ? tt("settings.piperPlus.guide", "View Setup Guide")
                    : modelCardOnly
                    ? tt("settings.piperPlus.modelCard", "Open Model Card")
                    : tt("settings.piperPlus.download", "Download / Update"));
            piperPlusDownloadBtn.setEnabled(manualOnly || modelCardOnly || entry.isDownloadable());
            piperPlusDownloadBtn.setToolTipText(manualOnly
                    ? tt("settings.piperPlus.guide.tooltip",
                    "Open the piper-plus Model Guide on GitHub for manual Chinese model setup.")
                    : modelCardOnly
                    ? tt("settings.piperPlus.modelCard.tooltip",
                    "This model is manual/local. Open its model card instead of downloading from MobMate.")
                    : null);
        }
        if (isManualGuideOnlyPiperPlusEntry(entry)) {
            piperPlusProsodyLabel.setText("Prosody: Chinese built-in route is unstable; manual model is recommended");
        } else {
            piperPlusProsodyLabel.setText("Prosody: model-native intonation only; no speech-to-speech prosody transfer");
        }
    }

    private void downloadSelectedPiperPlusModel() {
        PiperPlusCatalog.Entry entry = PiperPlusCatalog.findById(selectedPiperPlusModelId());
        if (entry == null) {
            JOptionPane.showMessageDialog(this, "No piper-plus model selected.");
            return;
        }
        piperPlusStatusLabel.setText("Status: Downloading " + entry.displayName() + " ...");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                PiperPlusModelManager.ensureDownloaded(entry);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    MobMateWhisp.prefs.put("piper.plus.model_id", entry.id());
                    updatePiperPlusSelectionDetails();
                } catch (Exception ex) {
                    piperPlusStatusLabel.setText("Status: Download failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void removeSelectedPiperPlusModel() {
        PiperPlusCatalog.Entry entry = PiperPlusCatalog.findById(selectedPiperPlusModelId());
        if (entry == null) return;
        try {
            PiperPlusModelManager.deleteInstalled(entry);
            updatePiperPlusSelectionDetails();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Failed to remove piper-plus model:\n" + ex.getMessage());
        }
    }

    private void openSelectedPiperPlusModelFolder() {
        PiperPlusCatalog.Entry entry = PiperPlusCatalog.findById(selectedPiperPlusModelId());
        if (entry == null) return;
        openLocalFile(PiperPlusModelManager.getModelDir(entry).toFile());
    }
    private void openWizard() {
        final String beforeEngine = MobMateWhisp.prefs.get("recog.engine", "whisper");
        FirstLaunchWizard wizard = null;
        try {
            wizard = new FirstLaunchWizard((Frame) getOwner(), app);
            wizard.setLocationRelativeTo(this);
            wizard.setVisible(true);
        } catch (FileNotFoundException | NativeHookException ex) {
            throw new RuntimeException(ex);
        } finally {
            try {
                if (wizard != null) wizard.stopAllWizardTests();
            } catch (Throwable ignore) {}
        }
        String afterEngine = MobMateWhisp.prefs.get("recog.engine", "whisper");
        if (!Objects.equals(beforeEngine, afterEngine)) {
            app.requestSoftRestartForSettings();
            dispose();
        }
    }

    private void applyFontSizeNow(int fontSize) {
        MobMateWhisp.applyUIFont(fontSize);
        for (Window w : Window.getWindows()) {
            UiFontApplier.refreshAllUI(w);
        }
    }

    private void applyThemeNow(boolean dark) {
        SwingUtilities.invokeLater(() -> {
            try {
                if (dark) {
                    com.formdev.flatlaf.FlatDarkLaf.setup();
                } else {
                    com.formdev.flatlaf.FlatLightLaf.setup();
                }
                for (Window w : Window.getWindows()) {
                    SwingUtilities.updateComponentTreeUI(w);
                }
            } catch (Exception ignore) {
            }
        });
    }

    private void writeOutttsKey(String key, String value, boolean quote) {
        try {
            if (value == null) value = "";
            Path f = Path.of(System.getProperty("user.dir"), "_outtts.txt");

            List<String> lines = Files.exists(f)
                    ? Files.readAllLines(f, StandardCharsets.UTF_8)
                    : new ArrayList<>();

            int markerIndex = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(OUTTTS_MARKER)) {
                    markerIndex = i;
                    break;
                }
            }

            List<String> settings = (markerIndex >= 0)
                    ? new ArrayList<>(lines.subList(0, markerIndex))
                    : new ArrayList<>(lines);

            String formattedValue = quote
                    ? ("\"" + value.replace("\"", "") + "\"")
                    : value;
            String newLine = key + "=" + formattedValue;

            boolean replaced = false;
            for (int i = 0; i < settings.size(); i++) {
                String raw = settings.get(i);
                String t = raw.trim();
                if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue;
                int eq = t.indexOf('=');
                String existingKey = t.substring(0, eq).trim();
                if (existingKey.equals(key)) {
                    settings.set(i, newLine);
                    replaced = true;
                    break;
                }
            }

            if (!replaced) {
                settings.add(newLine);
            }

            List<String> out = new ArrayList<>(settings);
            if (markerIndex >= 0) {
                out.addAll(lines.subList(markerIndex, lines.size()));
            }

            Files.write(f, out, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to write _outtts.txt: " + key, ex);
        }
    }

    private void openLocalFile(File file) {
        try {
            if (file == null) return;
            if (!file.exists()) {
                if (file.getName().contains(".")) {
                    file.createNewFile();
                } else {
                    file.mkdirs();
                }
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file);
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "MobMate", JOptionPane.ERROR_MESSAGE);
        }
    }

    private JPanel wrapPage(String title, String desc, JPanel inner) {
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));

        JLabel descLabel = new JLabel("<html>" + desc + "</html>");
        descLabel.setForeground(Color.GRAY);

        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.add(titleLabel);
        head.add(Box.createVerticalStrut(4));
        head.add(descLabel);

        root.add(head, BorderLayout.NORTH);
        root.add(inner, BorderLayout.CENTER);
        return root;
    }

    private JPanel formPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new EmptyBorder(4, 0, 4, 0));
        return p;
    }

    private void addRow(JPanel form, int row, String label, Component comp) {
        addRow(form, row, label, comp, false);
    }

    private void addRow(JPanel form, int row, String label, Component comp, boolean large) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0;
        lc.gridy = row;
        lc.insets = new Insets(6, 0, 6, 10);
        lc.anchor = GridBagConstraints.NORTHWEST;
        lc.fill = GridBagConstraints.HORIZONTAL;
        lc.weightx = 0.0;

        JComponent l = createRowLabel(label == null ? "" : label, large);
        form.add(l, lc);

        GridBagConstraints cc = new GridBagConstraints();
        cc.gridx = 1;
        cc.gridy = row;
        cc.insets = new Insets(6, 0, 6, 0);
        cc.anchor = GridBagConstraints.NORTHWEST;
        cc.fill = GridBagConstraints.HORIZONTAL;
        cc.weightx = 1.0;
        if (large) cc.weighty = 1.0;

        form.add(adaptSettingsFieldComponent(comp, large), cc);
    }

    private GridBagConstraints sectionGbc(int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 2;
        c.insets = new Insets(12, 0, 2, 0);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        return c;
    }

    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private String oneLine(String s) {
        if (s == null) return "";
        return s.replace("\r", " ").replace("\n", " ").trim();
    }

    private void detectVoiceVoxSettings() {
        String defaultApi = "http://127.0.0.1:50021";
        String api = voicevoxApiField != null ? voicevoxApiField.getText().trim() : "";
        if (api.isBlank()) api = defaultApi;

        Optional<File> exe = findVoiceVoxExecutable();
        if (exe.isPresent()) {
            if (voicevoxExeField != null) {
                voicevoxExeField.setText(exe.get().getAbsolutePath());
            }
            if (voicevoxApiField != null && voicevoxApiField.getText().trim().isBlank()) {
                voicevoxApiField.setText(defaultApi);
            }
            JOptionPane.showMessageDialog(
                    this,
                    tt("settings.voicevox.detect.success", "VOICEVOX was found. Press Apply or OK to save."),
                    tt("settings.voicevox.detect.title", "VOICEVOX auto detect"),
                    JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }

        if (voiceVoxApiLooksAlive(api)) {
            if (voicevoxApiField != null) voicevoxApiField.setText(api);
            JOptionPane.showMessageDialog(
                    this,
                    tt("settings.voicevox.detect.apiOnly", "VOICEVOX API is responding, but the executable was not found. The API URL was set."),
                    tt("settings.voicevox.detect.title", "VOICEVOX auto detect"),
                    JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }

        if (voicevoxApiField != null && voicevoxApiField.getText().trim().isBlank()) {
            voicevoxApiField.setText(defaultApi);
        }
        JOptionPane.showMessageDialog(
                this,
                tt("settings.voicevox.detect.notFound", "VOICEVOX was not found. Install it, then try auto detect again or enter the executable manually."),
                tt("settings.voicevox.detect.title", "VOICEVOX auto detect"),
                JOptionPane.WARNING_MESSAGE
        );
    }

    private Optional<File> findVoiceVoxExecutable() {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        addVoiceVoxCandidate(candidates, voicevoxExeField != null ? voicevoxExeField.getText() : "");
        addVoiceVoxCandidate(candidates, Config.getString("voicevox.exe", ""));

        addVoiceVoxCandidate(candidates, pathJoin(System.getenv("LOCALAPPDATA"), "Programs", "VOICEVOX", "VOICEVOX.exe"));
        addVoiceVoxCandidate(candidates, pathJoin(System.getenv("LOCALAPPDATA"), "VOICEVOX", "VOICEVOX.exe"));
        addVoiceVoxCandidate(candidates, pathJoin(System.getenv("PROGRAMFILES"), "VOICEVOX", "VOICEVOX.exe"));
        addVoiceVoxCandidate(candidates, pathJoin(System.getenv("ProgramFiles(x86)"), "VOICEVOX", "VOICEVOX.exe"));
        addVoiceVoxCandidate(candidates, "M:\\@HyperV\\VOICEVOX\\VOICEVOX.exe");
        addVoiceVoxCandidate(candidates, "M:\\VOICEVOX\\VOICEVOX.exe");

        for (Path candidate : candidates) {
            File f = candidate.toFile();
            if (isVoiceVoxExecutable(f)) return Optional.of(f);
        }

        Optional<File> found = findVoiceVoxUnder(pathJoin(System.getenv("LOCALAPPDATA"), "Programs"), 4);
        if (found.isPresent()) return found;
        found = findVoiceVoxUnder(pathJoin(System.getenv("PROGRAMFILES")), 4);
        if (found.isPresent()) return found;
        return findVoiceVoxUnder(pathJoin(System.getenv("ProgramFiles(x86)")), 4);
    }

    private void addVoiceVoxCandidate(Set<Path> candidates, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return;
        try {
            candidates.add(Path.of(unquote(rawPath.trim())).toAbsolutePath().normalize());
        } catch (Exception ignore) {}
    }

    private String pathJoin(String first, String... rest) {
        if (first == null || first.isBlank()) return "";
        try {
            return Path.of(first, rest).toString();
        } catch (Exception ignore) {
            return "";
        }
    }

    private String unquote(String text) {
        if (text == null) return "";
        String s = text.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private Optional<File> findVoiceVoxUnder(String rootPath, int maxDepth) {
        if (rootPath == null || rootPath.isBlank()) return Optional.empty();
        Path root;
        try {
            root = Path.of(rootPath);
        } catch (Exception ignore) {
            return Optional.empty();
        }
        if (!Files.isDirectory(root)) return Optional.empty();
        try (var paths = Files.find(root, maxDepth, (path, attrs) -> {
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            String full = path.toString().toLowerCase(Locale.ROOT);
            return attrs.isRegularFile()
                    && "VOICEVOX.exe".equalsIgnoreCase(name)
                    && full.contains("voicevox");
        })) {
            return paths.map(Path::toFile)
                    .filter(this::isVoiceVoxExecutable)
                    .findFirst();
        } catch (Exception ignore) {
            return Optional.empty();
        }
    }

    private boolean isVoiceVoxExecutable(File file) {
        if (file == null || !file.isFile()) return false;
        String name = file.getName();
        return "VOICEVOX.exe".equalsIgnoreCase(name);
    }

    private boolean voiceVoxApiLooksAlive(String base) {
        if (base == null || base.isBlank()) return false;
        String normalized = base.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(normalized + "/speakers").openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(900);
            conn.connect();
            try {
                return conn.getResponseCode() == 200;
            } finally {
                conn.disconnect();
            }
        } catch (Exception ignore) {
            return false;
        }
    }

    private String tt(String key, String fallback) {
        try {
            String v = UiText.t(key);
            if (v == null || v.isBlank() || key.equals(v)) return fallback;
            return v;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static final class Choice<T> {
        final String label;
        final T value;

        Choice(String label, T value) {
            this.label = label;
            this.value = value;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private <T> void selectChoice(JComboBox<Choice<T>> combo, T value) {
        if (combo == null) return;
        for (int i = 0; i < combo.getItemCount(); i++) {
            Choice<T> c = combo.getItemAt(i);
            if (Objects.equals(c.value, value)) {
                combo.setSelectedIndex(i);
                return;
            }
            if (c.value instanceof Float && value instanceof Float) {
                if (Math.abs((Float) c.value - (Float) value) < 0.0001f) {
                    combo.setSelectedIndex(i);
                    return;
                }
            }
        }
    }

    private <T> T selectedValue(JComboBox<Choice<T>> combo) {
        Object obj = combo.getSelectedItem();
        if (obj instanceof Choice<?> c) {
            @SuppressWarnings("unchecked")
            T v = (T) c.value;
            return v;
        }
        return null;
    }

    private void applyOverlayThemePreset(String preset) {
        if (preset == null) return;
        String[] pair = overlayThemeColors(preset);
        if (pair == null) return; // custom は何もしない

        overlayThemePresetApplying = true;
        try {
            overlayBgField.setText(pair[0]);
            overlayFgField.setText(pair[1]);
        } finally {
            overlayThemePresetApplying = false;
        }
    }

    private String[] overlayThemeColors(String preset) {
        String p = Objects.toString(preset, "").trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "green" -> new String[]{"#1E5A32", "#FFFFFF"}; // 30,90,50
            case "blue"  -> new String[]{"#1E325A", "#FFFFFF"}; // 30,50,90
            case "gray"  -> new String[]{"#282828", "#FFFFFF"}; // 40,40,40
            case "red"   -> new String[]{"#461E1E", "#FFFFFF"}; // 70,30,30
            default      -> null; // custom
        };
    }

    private String detectOverlayThemePreset(String bgHex, String fgHex) {
        String bg = normalizeHex(bgHex);
        String fg = normalizeHex(fgHex);

        for (String key : List.of("green", "blue", "gray", "red")) {
            String[] pair = overlayThemeColors(key);
            if (pair == null) continue;
            if (bg.equals(pair[0]) && fg.equals(pair[1])) {
                return key;
            }
        }
        return "custom";
    }

    private String normalizeHex(String s) {
        if (s == null) return "";
        String t = s.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) return "";
        if (!t.startsWith("#")) t = "#" + t;
        if (t.length() != 7) return t;
        return t;
    }

    private String toHex(Color color) {
        if (color == null) return "#000000";
        return String.format(Locale.ROOT, "#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private void styleSettingsPendingModeButtons() {
        styleSettingsPendingModeButton(ttsConfirmInstantButton, ttsConfirmInstantButton != null && ttsConfirmInstantButton.isSelected());
        styleSettingsPendingModeButton(ttsConfirmPendingButton, ttsConfirmPendingButton != null && ttsConfirmPendingButton.isSelected());
    }

    private void styleSettingsPendingModeButton(AbstractButton button, boolean active) {
        if (button == null) return;
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBorderPainted(true);
        if (active) {
            button.setForeground(Color.WHITE);
            button.setBackground(new Color(0x74, 0xBC, 0xB6));
            button.setBorder(BorderFactory.createLineBorder(new Color(0x5C, 0xA7, 0xA1)));
        } else {
            button.setForeground(UIManager.getColor("Label.foreground"));
            button.setBackground(UIManager.getColor("Panel.background"));
            Color borderColor = UIManager.getColor("Component.borderColor");
            if (borderColor == null) borderColor = new Color(0x66, 0x66, 0x66);
            button.setBorder(BorderFactory.createLineBorder(borderColor));
        }
    }

    private void repopulateRadioPresetCombo(JComboBox<Choice<String>> combo, boolean background, String currentHex) {
        if (combo == null) return;
        String normalizedCurrent = normalizeHex(currentHex);
        combo.removeAllItems();
        for (Choice<String> choice : radioPresetChoices(background)) {
            combo.addItem(choice);
        }
        boolean matched = false;
        for (int i = 0; i < combo.getItemCount(); i++) {
            Choice<String> choice = combo.getItemAt(i);
            if (Objects.equals(normalizedCurrent, normalizeHex(choice.value))) {
                matched = true;
                break;
            }
        }
        if (!matched && !normalizedCurrent.isBlank()) {
            combo.insertItemAt(
                    new Choice<>(
                            tt("settings.overlay.preset.current", "Current") + "  " + normalizedCurrent,
                            normalizedCurrent
                    ),
                    0
            );
        }
        selectChoice(combo, normalizedCurrent);
    }

    @SuppressWarnings("unchecked")
    private Choice<String>[] radioPresetChoices(boolean background) {
        if (background) {
            return new Choice[]{
                    new Choice<>(tt("settings.overlay.preset.deepGreen", "Deep green  #1D6F5A"), "#1D6F5A"),
                    new Choice<>(tt("settings.overlay.preset.navy", "Navy  #304A6E"), "#304A6E"),
                    new Choice<>(tt("settings.overlay.preset.charcoal", "Charcoal  #2F3A4B"), "#2F3A4B"),
                    new Choice<>(tt("settings.overlay.preset.wine", "Wine  #7A3E4B"), "#7A3E4B")
            };
        }
        return new Choice[]{
                new Choice<>(tt("settings.overlay.preset.white", "White  #FFFFFF"), "#FFFFFF"),
                new Choice<>(tt("settings.overlay.preset.ivory", "Ivory  #F8F3E7"), "#F8F3E7"),
                new Choice<>(tt("settings.overlay.preset.navyText", "Navy  #26374C"), "#26374C"),
                new Choice<>(tt("settings.overlay.preset.yellow", "Yellow  #F4E38A"), "#F4E38A")
        };
    }

    private void installOverlayThemeTracking(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            private void syncPresetLater() {
                if (overlayThemePresetApplying) return;
                if (overlayThemePresetCombo == null) return;

                SwingUtilities.invokeLater(() -> {
                    if (overlayThemePresetApplying) return;
                    if (overlayThemePresetCombo == null) return;
                    if (overlayBgField == null || overlayFgField == null) return;

                    String detected = detectOverlayThemePreset(
                            overlayBgField.getText(),
                            overlayFgField.getText()
                    );

                    String current = selectedValue(overlayThemePresetCombo);
                    if (Objects.equals(current, detected)) {
                        return;
                    }

                    overlayThemePresetApplying = true;
                    try {
                        selectChoice(overlayThemePresetCombo, detected);
                    } finally {
                        overlayThemePresetApplying = false;
                    }
                });
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                syncPresetLater();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                syncPresetLater();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                syncPresetLater();
            }
        });
    }
    private void updateOverlayDetailEnabled() {
        boolean enabled = overlayEnableCheck != null && overlayEnableCheck.isSelected();

        if (overlayPosCombo != null) overlayPosCombo.setEnabled(enabled);
        if (overlayDisplayCombo != null) overlayDisplayCombo.setEnabled(enabled);
        if (overlayFontSizeSpinner != null) overlayFontSizeSpinner.setEnabled(enabled);
        if (overlayOpacitySlider != null) overlayOpacitySlider.setEnabled(enabled);
        if (overlayThemePresetCombo != null) overlayThemePresetCombo.setEnabled(enabled);
        if (overlayMarginSpinner != null) overlayMarginSpinner.setEnabled(enabled);
        if (overlayMaxLinesSpinner != null) overlayMaxLinesSpinner.setEnabled(enabled);
        if (overlayPageChangeCombo != null) overlayPageChangeCombo.setEnabled(enabled);
        if (overlayBgField != null) overlayBgField.setEnabled(enabled);
        if (overlayFgField != null) overlayFgField.setEnabled(enabled);
    }

    private void ensureOverlayDisplayChoice(int displayIndex) {
        if (overlayDisplayCombo == null) return;

        for (int i = 0; i < overlayDisplayCombo.getItemCount(); i++) {
            Choice<Integer> c = overlayDisplayCombo.getItemAt(i);
            if (Objects.equals(c.value, displayIndex)) {
                return;
            }
        }

        overlayDisplayCombo.addItem(new Choice<>(overlayDisplayLabel(displayIndex), displayIndex));
    }

    private String overlayDisplayLabel(int displayIndex) {
        return switch (displayIndex) {
            case 0 -> tt("settings.overlay.display.primary", "Primary");
            case 1 -> tt("settings.overlay.display.second", "2nd display");
            case 2 -> tt("settings.overlay.display.third", "3rd display");
            case 3 -> tt("settings.overlay.display.fourth", "4th display");
            default -> (displayIndex + 1) + "th display";
        };
    }

    private void selectStringComboValue(JComboBox<String> combo, String value, String fallback) {
        if (combo == null) return;

        String v = (value == null || value.isBlank()) ? fallback : value.trim();

        for (int i = 0; i < combo.getItemCount(); i++) {
            String item = combo.getItemAt(i);
            if (item != null && item.equalsIgnoreCase(v)) {
                combo.setSelectedIndex(i);
                return;
            }
        }

        combo.addItem(v);
        combo.setSelectedItem(v);
    }

    private String selectedStringOrFallback(JComboBox<String> combo, String fallback) {
        if (combo == null) {
            return LanguageOptions.normalizeWhisperLang(fallback, fallback);
        }
        Object selected = combo.getSelectedItem();
        return LanguageOptions.normalizeWhisperLang(Objects.toString(selected, fallback), fallback);
    }

    private void reloadGpuChoices() {
        if (gpuSelectCombo == null) return;

        gpuSelectable = false;
        gpuSelectCombo.removeAllItems();
        gpuSelectCombo.addItem(new Choice<>(tt("settings.gpu.auto", "Auto (recommended)"), -1));

        File vulkanDll = new File(app.getExeDir(), "libs/vulkan/whisper.dll");
        if (!vulkanDll.isFile()) {
            gpuSelectCombo.removeAllItems();
            gpuSelectCombo.addItem(new Choice<>(tt("settings.gpu.unavailable", "Vulkan not available"), -1));
            gpuSelectCombo.setEnabled(false);
            return;
        }

        int count = 0;
        try {
            count = VulkanGpuUtil.getGpuCount();
        } catch (Throwable ignore) {
        }

        if (count <= 0) {
            gpuSelectCombo.removeAllItems();
            gpuSelectCombo.addItem(new Choice<>(tt("settings.gpu.notDetected", "Vulkan GPUs not detected"), -1));
            gpuSelectCombo.setEnabled(false);
            return;
        }

        gpuSelectable = true;
        gpuSelectCombo.setEnabled(true);

        gpuSelectCombo.removeAllItems();
        gpuSelectCombo.addItem(new Choice<>(tt("settings.gpu.auto", "Auto (recommended)"), -1));

        for (int i = 0; i < count; i++) {
            String name;
            try {
                name = VulkanGpuUtil.getGpuName(i);
            } catch (Throwable t) {
                name = "Vulkan " + i;
            }
            gpuSelectCombo.addItem(new Choice<>("GPU " + i + ": " + name, i));
        }
    }

    private void ensureGpuChoice(int gpuIndex) {
        if (gpuSelectCombo == null || gpuIndex < 0) return;

        for (int i = 0; i < gpuSelectCombo.getItemCount(); i++) {
            Choice<Integer> c = gpuSelectCombo.getItemAt(i);
            if (Objects.equals(c.value, gpuIndex)) {
                return;
            }
        }

        gpuSelectCombo.addItem(new Choice<>("GPU " + gpuIndex, gpuIndex));
    }

    private void updateRecognitionUiState() {
        String engine = selectedValue(recogEngineCombo);
        boolean whisperMode = !"moonshine".equalsIgnoreCase(engine);
        boolean moonshineMode = !whisperMode;

        if (whisperModelCombo != null) whisperModelCombo.setEnabled(whisperMode);
        if (moonshineModelCombo != null) moonshineModelCombo.setEnabled(moonshineMode);
        if (whisperLanguageCombo != null) whisperLanguageCombo.setEnabled(whisperMode);

        if (gpuSelectCombo != null) {
            gpuSelectCombo.setEnabled(whisperMode && gpuSelectable);
        }

        if (gpuNoteLabel != null) {
            String gpuNoteText;
            String gpuColor;

            if (moonshineMode) {
                gpuNoteText = tt(
                        "settings.gpu.note.moonshine",
                        "GPU selection is for Whisper/Vulkan only. Moonshine ignores this setting."
                );
                gpuColor = "#AAAAAA";
            } else if (!gpuSelectable) {
                gpuNoteText = tt(
                        "settings.gpu.unavailable",
                        "Vulkan not available"
                );
                gpuColor = "#AAAAAA";
            } else {
                gpuNoteText = tt(
                        "settings.gpu.note",
                        "Changing GPU selection takes effect after soft restart"
                );
                gpuColor = "#888888";
            }

            gpuNoteLabel.setText("<html><span style='color:" + gpuColor + ";'>" + gpuNoteText + "</span></html>");
        }

        if (vadLaughNoteLabel != null) {
            vadLaughNoteLabel.setText(
                    "<html><span style='color:#888888;'>"
                            + tt(
                            "settings.vadLaugh.note",
                            "Helps catch short laugh bursts before speech."
                    )
                            + "</span></html>"
            );
        }

    }

    private void updateTtsUiState() {
        String engine = selectedValue(ttsEngineCombo);
        String normalized = (engine == null || engine.isBlank()) ? "auto" : engine.toLowerCase(Locale.ROOT);

        boolean autoMode = "auto".equals(normalized);
        boolean windowsMode = "windows".equals(normalized);
        boolean piperMode = "piper_plus".equals(normalized);
        boolean voicevoxMode = "voicevox".equals(normalized);
        boolean xttsMode = "xtts".equals(normalized);
        boolean voicegerVcMode = "voiceger_vc".equals(normalized) || "voiceger".equals(normalized);
        boolean voicegerTtsMode = "voiceger_tts".equals(normalized);

        boolean showWindows = autoMode || windowsMode;
        boolean showPiper = piperMode;
        boolean showVoicevox = autoMode || voicevoxMode;
        boolean showEmotionReflect = autoMode || windowsMode || piperMode || voicevoxMode;
        boolean showVoicegerLang = voicegerTtsMode;
        boolean showSetupSection = autoMode || voicevoxMode || xttsMode || voicegerVcMode || voicegerTtsMode;

        setRowVisible(ttsWindowsVoiceRow, showWindows);
        setRowVisible(ttsPiperPlusModelRow, showPiper);
        setRowVisible(ttsPiperPlusLicenseRow, showPiper);
        setRowVisible(ttsPiperPlusStatusRow, showPiper);
        setRowVisible(ttsPiperPlusProsodyRow, showPiper);
        setRowVisible(ttsVoicevoxSpeakerRow, showVoicevox);
        setRowVisible(ttsVoicevoxAutoEmotionRow, showVoicevox);
        setRowVisible(ttsReflectEmotionRow, showEmotionReflect);
        setRowVisible(ttsContourStrengthRow, showEmotionReflect);
        setRowVisible(ttsToneEmphasisRow, showEmotionReflect);
        setRowVisible(ttsVoicegerLangRow, showVoicegerLang);

        if (ttsSetupVoicevoxBtn != null) ttsSetupVoicevoxBtn.setVisible(autoMode || voicevoxMode);
        if (ttsSetupXttsBtn != null) ttsSetupXttsBtn.setVisible(autoMode || xttsMode);
        if (ttsSetupVoicegerBtn != null) ttsSetupVoicegerBtn.setVisible(voicegerVcMode || voicegerTtsMode);

        boolean hasVisibleSetupButton =
                (ttsSetupVoicevoxBtn != null && ttsSetupVoicevoxBtn.isVisible())
                        || (ttsSetupXttsBtn != null && ttsSetupXttsBtn.isVisible())
                        || (ttsSetupVoicegerBtn != null && ttsSetupVoicegerBtn.isVisible());
        boolean showSetupRows = showSetupSection && hasVisibleSetupButton;

        if (ttsSetupSectionLabel != null) ttsSetupSectionLabel.setVisible(showSetupRows);
        setRowVisible(ttsSetupButtonsRow, showSetupRows);
        setRowVisible(ttsSetupNoteRow, showSetupRows);

        if (ttsForm != null) {
            ttsForm.revalidate();
            ttsForm.repaint();
        }
    }

    private void updateTtsPrimaryVoiceChoices() {
        if (ttsPrimaryVoiceCombo == null) return;
        String engine = selectedValue(ttsEngineCombo);
        if (engine == null || engine.isBlank()) engine = "auto";
        final String engineValue = engine;
        withSyncingTtsControls(() -> {
            ttsPrimaryVoiceCombo.removeAllItems();
            for (String voice : app.getSimpleTtsVoiceChoices(engineValue)) {
                ttsPrimaryVoiceCombo.addItem(voice);
            }
            ttsPrimaryVoiceCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    setText(displaySettingsVoiceLabel(engineValue, value == null ? "auto" : value.toString()));
                    return this;
                }
            });
            ttsPrimaryVoiceCombo.setSelectedItem(app.getSelectedSimpleTtsVoice(engineValue));
            if (ttsPrimaryVoiceCombo.getSelectedItem() == null && ttsPrimaryVoiceCombo.getItemCount() > 0) {
                ttsPrimaryVoiceCombo.setSelectedIndex(0);
            }
        });
    }

    private void refreshPrimaryVoiceSelectionFromDetailed() {
        if (ttsPrimaryVoiceCombo == null) return;
        String engine = selectedValue(ttsEngineCombo);
        if (engine == null || engine.isBlank()) engine = "auto";
        String selected = switch (engine.toLowerCase(Locale.ROOT)) {
            case "voicevox" -> Objects.toString(voicevoxSpeakerCombo.getSelectedItem(), app.getSelectedSimpleTtsVoice(engine));
            case "windows" -> Objects.toString(windowsVoiceCombo.getSelectedItem(), app.getSelectedSimpleTtsVoice(engine));
            case "piper_plus" -> selectedPiperPlusModelId().isBlank() ? "auto" : selectedPiperPlusModelId();
            case "voiceger_tts", "voiceger" -> {
                String value = selectedValue(voicegerLangCombo);
                yield (value == null || value.isBlank()) ? "auto" : value;
            }
            default -> "auto";
        };
        selectStringComboValue(ttsPrimaryVoiceCombo, selected, "auto");
    }

    private void syncDetailedTtsSelectionFromPrimary() {
        if (ttsPrimaryVoiceCombo == null) return;
        if (ttsPrimaryVoiceCombo.getSelectedItem() == null) return;
        String engine = selectedValue(ttsEngineCombo);
        if (engine == null || engine.isBlank()) engine = "auto";
        String value = Objects.toString(ttsPrimaryVoiceCombo.getSelectedItem(), "auto");
        final String engineValue = engine;
        withSyncingTtsControls(() -> {
            switch (engineValue.toLowerCase(Locale.ROOT)) {
                case "voicevox" -> selectStringComboValue(voicevoxSpeakerCombo, value, "auto");
                case "windows" -> selectStringComboValue(windowsVoiceCombo, value, "auto");
                case "piper_plus" -> {
                    if ("auto".equalsIgnoreCase(value)) {
                        if (piperPlusModelCombo.getItemCount() > 0) piperPlusModelCombo.setSelectedIndex(0);
                    } else {
                        PiperPlusCatalog.Entry entry = PiperPlusCatalog.findById(value);
                        selectStringComboValue(
                                piperPlusModelCombo,
                                entry != null ? entry.comboLabel() : value,
                                piperPlusModelCombo.getItemCount() > 0 ? Objects.toString(piperPlusModelCombo.getItemAt(0), "") : ""
                        );
                    }
                }
                case "voiceger_tts", "voiceger" -> selectChoice(voicegerLangCombo, "auto".equalsIgnoreCase(value) ? "all_ja" : value);
                default -> {
                }
            }
        });
    }

    private void withSyncingTtsControls(Runnable task) {
        boolean old = syncingTtsControls;
        syncingTtsControls = true;
        try {
            task.run();
        } finally {
            syncingTtsControls = old;
        }
    }

    private String displaySettingsTtsEngine(Object value) {
        String raw = value instanceof Choice<?> choice
                ? Objects.toString(choice.value, "auto")
                : Objects.toString(value, "auto");
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "voicevox" -> "VOICEVOX";
            case "windows" -> "Windows";
            case "piper_plus" -> "Piper+";
            case "voiceger_tts" -> "Voiceger";
            case "voiceger_vc" -> "Voiceger (VC)";
            case "xtts" -> "XTTS";
            default -> tt("common.auto", "Auto");
        };
    }

    private String displaySettingsVoiceLabel(String engine, String value) {
        if ("voicevox".equalsIgnoreCase(engine)) {
            return "auto".equalsIgnoreCase(value) ? tt("common.auto", "Auto") : value;
        }
        if ("windows".equalsIgnoreCase(engine)) {
            return "auto".equalsIgnoreCase(value) ? tt("common.auto", "Auto") : value;
        }
        if ("piper_plus".equalsIgnoreCase(engine)) {
            if ("auto".equalsIgnoreCase(value)) return tt("common.auto", "Auto");
            PiperPlusCatalog.Entry entry = PiperPlusCatalog.findById(value);
            return entry != null ? entry.comboLabel() : value;
        }
        if ("voiceger_tts".equalsIgnoreCase(engine) || "voiceger".equalsIgnoreCase(engine)) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "all_ja" -> tt("lang.japanese", "Japanese");
                case "en" -> tt("lang.english", "English");
                case "all_zh" -> tt("lang.chinese", "Chinese");
                case "all_ko" -> tt("lang.korean", "Korean");
                case "all_yue" -> tt("lang.cantonese", "Cantonese");
                default -> "auto".equalsIgnoreCase(value) ? tt("common.auto", "Auto") : value;
            };
        }
        return tt("common.auto", "Auto");
    }

    private FormRow addTrackedRow(JPanel form, int row, String label, Component comp) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0;
        lc.gridy = row;
        lc.insets = new Insets(6, 0, 6, 10);
        lc.anchor = GridBagConstraints.NORTHWEST;
        lc.fill = GridBagConstraints.HORIZONTAL;
        lc.weightx = 0.0;

        JComponent l = createRowLabel(label == null ? "" : label, false);
        form.add(l, lc);

        GridBagConstraints cc = new GridBagConstraints();
        cc.gridx = 1;
        cc.gridy = row;
        cc.insets = new Insets(6, 0, 6, 0);
        cc.anchor = GridBagConstraints.NORTHWEST;
        cc.fill = GridBagConstraints.HORIZONTAL;
        cc.weightx = 1.0;
        Component rowComponent = adaptSettingsFieldComponent(comp, false);
        form.add(rowComponent, cc);
        return new FormRow(l, rowComponent);
    }

    private Component adaptSettingsFieldComponent(Component comp, boolean large) {
        if (!(comp instanceof JComponent jc)) return comp;
        if (large) {
            if (jc instanceof JScrollPane || jc instanceof JTextArea || jc instanceof JPanel || jc instanceof JLabel) {
                return new ResponsiveSingleFieldPanel(jc, 760, 220);
            }
            return comp;
        }
        if (jc instanceof JComboBox<?> || jc instanceof JTextField || jc instanceof JSpinner || jc instanceof JSlider
                || jc instanceof JScrollPane || jc instanceof JTextArea || jc instanceof JButton || jc instanceof JPanel || jc instanceof JLabel) {
            return new ResponsiveSingleFieldPanel(jc, 520, 150);
        }
        return comp;
    }

    private JComponent createRowLabel(String text, boolean large) {
        JTextArea area = new JTextArea(text == null ? "" : text);
        area.setOpaque(false);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setFont(UIManager.getFont("Label.font"));
        area.setForeground(UIManager.getColor("Label.foreground"));
        area.setPreferredSize(new Dimension(180, large ? 80 : 26));
        area.setMinimumSize(new Dimension(120, large ? 48 : 26));
        return area;
    }

    private JTextArea helperNote(String text) {
        JTextArea area = new JTextArea(text == null ? "" : text);
        area.setOpaque(false);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setFont(UIManager.getFont("Label.font"));
        area.setForeground(Color.GRAY);
        return area;
    }

    private JPanel createResponsiveDualFieldPanel(JComponent left, JComponent right) {
        return new ResponsiveDualFieldPanel(left, right, 8, 170, 170, 300, 300);
    }

    private void setRowVisible(FormRow row, boolean visible) {
        if (row == null) return;
        row.setVisible(visible);
        if (row.label != null) row.label.setEnabled(visible);
        setComponentTreeEnabled(row.component, visible);
    }

    private void setComponentTreeEnabled(Component comp, boolean enabled) {
        if (comp == null) return;
        comp.setEnabled(enabled);
        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) {
                setComponentTreeEnabled(child, enabled);
            }
        }
    }

    private static final class FormRow {
        final Component label;
        final Component component;

        FormRow(Component label, Component component) {
            this.label = label;
            this.component = component;
        }

        void setVisible(boolean visible) {
            if (label != null) label.setVisible(visible);
            if (component != null) component.setVisible(visible);
        }

        void setEnabled(boolean enabled) {
            if (label != null) label.setEnabled(enabled);
            if (component != null) component.setEnabled(enabled);
        }
    }

    private static final class ResponsiveSingleFieldPanel extends JPanel {
        private final JComponent child;
        private final int maxWidth;
        private final int minWidth;

        ResponsiveSingleFieldPanel(JComponent child, int maxWidth, int minWidth) {
            super(null);
            this.child = child;
            this.maxWidth = maxWidth;
            this.minWidth = minWidth;
            setOpaque(false);
            add(child);
        }

        @Override
        public void doLayout() {
            Dimension pref = child.getPreferredSize();
            int h = Math.max(pref.height, getHeight());
            int available = Math.max(0, getWidth());
            int target = Math.min(maxWidth, available);
            if (available > 0) {
                target = Math.max(Math.min(minWidth, available), target);
            } else {
                target = Math.max(minWidth, Math.min(maxWidth, pref.width));
            }
            child.setBounds(0, 0, target, h);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension pref = child.getPreferredSize();
            int width = Math.min(maxWidth, Math.max(minWidth, pref.width));
            return new Dimension(width, pref.height);
        }

        @Override
        public Dimension getMinimumSize() {
            Dimension pref = child.getMinimumSize();
            return new Dimension(Math.min(minWidth, Math.max(80, pref.width)), child.getPreferredSize().height);
        }
    }

    private static final class ResponsiveDualFieldPanel extends JPanel {
        private final JComponent left;
        private final JComponent right;
        private final int gap;
        private final int leftMin;
        private final int rightMin;
        private final int leftMax;
        private final int rightMax;

        ResponsiveDualFieldPanel(JComponent left, JComponent right, int gap, int leftMin, int rightMin, int leftMax, int rightMax) {
            super(null);
            this.left = left;
            this.right = right;
            this.gap = gap;
            this.leftMin = leftMin;
            this.rightMin = rightMin;
            this.leftMax = leftMax;
            this.rightMax = rightMax;
            setOpaque(false);
            add(left);
            add(right);
        }

        @Override
        public void doLayout() {
            int available = Math.max(0, getWidth());
            int usable = Math.max(0, available - gap);
            int half = usable / 2;
            int leftWidth = Math.min(leftMax, Math.max(Math.min(leftMin, usable), half));
            int rightWidth = Math.min(rightMax, Math.max(Math.min(rightMin, usable - leftWidth), usable - leftWidth));

            if (leftWidth + rightWidth > usable) {
                int overflow = leftWidth + rightWidth - usable;
                if (rightWidth >= leftWidth) {
                    rightWidth = Math.max(80, rightWidth - overflow);
                } else {
                    leftWidth = Math.max(80, leftWidth - overflow);
                }
            }

            if (usable > 0 && leftWidth + rightWidth < usable) {
                int extra = usable - (leftWidth + rightWidth);
                int leftRoom = Math.max(0, leftMax - leftWidth);
                int addLeft = Math.min(leftRoom, extra / 2);
                leftWidth += addLeft;
                extra -= addLeft;
                int rightRoom = Math.max(0, rightMax - rightWidth);
                int addRight = Math.min(rightRoom, extra);
                rightWidth += addRight;
            }

            int height = Math.max(left.getPreferredSize().height, right.getPreferredSize().height);
            int leftBoxWidth = Math.max(80, leftWidth);
            int rightBoxWidth = Math.max(80, rightWidth);
            left.setBounds(0, 0, leftBoxWidth, height);
            right.setBounds(Math.min(Math.max(0, available - rightBoxWidth), leftBoxWidth + gap), 0, rightBoxWidth, height);
        }

        @Override
        public Dimension getPreferredSize() {
            int height = Math.max(left.getPreferredSize().height, right.getPreferredSize().height);
            int width = Math.min(leftMax, Math.max(leftMin, left.getPreferredSize().width))
                    + gap
                    + Math.min(rightMax, Math.max(rightMin, right.getPreferredSize().width));
            return new Dimension(width, height);
        }

        @Override
        public Dimension getMinimumSize() {
            int height = Math.max(left.getPreferredSize().height, right.getPreferredSize().height);
            return new Dimension(Math.max(200, leftMin + gap + rightMin), height);
        }
    }
}



