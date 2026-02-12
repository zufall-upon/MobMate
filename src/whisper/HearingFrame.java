package whisper;

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.prefs.Preferences;
import java.util.Base64;
import java.awt.GraphicsEnvironment;
import java.awt.GraphicsDevice;
import java.awt.GraphicsConfiguration;
import java.awt.DisplayMode;

public class HearingFrame extends JFrame {

    private final Preferences prefs;
    private final Image icon;
    private final MobMateWhisp host;

    private JToggleButton toggle;
    private JComboBox<String> outputCombo;
    private JComboBox<String> langCombo;
    // whisper.cpp(=OpenAI Whisper) の言語コード（ISO 639-1中心）
    private static final String[] WHISPER_LANGS = new String[] {
            "auto",
            "en","ja","zh","de","fr","es","ru","ko","it","pt","nl","pl","tr","uk","vi","th","id","sv","fi","da","no","cs","hu","ro","el","he","hi","ar",
            "af","am","as","az","ba","be","bg","bn","bo","br","bs","ca","cy","et","eu","fa","fo","gl","gu","ha","haw","hr","ht","hy","is","jw","ka","kk",
            "km","kn","la","lb","ln","lo","lt","lv","mg","mi","mk","ml","mn","mr","ms","mt","my","ne","nn","oc","pa","ps","sa","sd","si","sk","sl","sn",
            "so","sq","sr","su","sw","ta","te","tg","tk","tl","tt","ur","uz","yi","yo","yue"
    };
    private JCheckBox translateToEnCheck;

    private GainMeter meter;
    private JButton prefButton;
    private final JPopupMenu prefMenu = new JPopupMenu();

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
    // WASAPI ps1 process guard
    private final Object loopLock = new Object();
    private volatile Thread loopErrThread;
    private volatile boolean ignoreOutputEvent = false;

    // PCMデバッグ用（1秒に1回だけログ）
    private volatile long lastPcmDbgMs = 0;
    private Thread loopProcErrThread;
    private static final int HEARING_W = 380;

    public HearingFrame(Preferences prefs, Image icon, MobMateWhisp host) {
        super("MobMate Hearing (WIP)");
        this.prefs = prefs;
        this.icon = icon;
        this.host = host;

        if (icon != null) setIconImage(icon);
        setResizable(false);

        buildUi();
        restoreBounds();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                cleanup();  // ★変更: 処理を共通メソッドに
            }
        });
    }
    public void cleanup() {
        stopMonitor();
        saveBounds();
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
    private void buildUi() {
        // ★既に構築済みなら何もしない
        if (toggle != null) return;
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        // 上段：メーター（メイン画面の GainMeter を流用）
        meter = new GainMeter();
        meter.setFont(root.getFont());
        Dimension meterSize = new Dimension(170, 26);
        meter.setPreferredSize(meterSize);
        meter.setMinimumSize(meterSize);
        meter.setMaximumSize(meterSize);
        JPanel row1 = new JPanel();
        row1.setOpaque(true);
        row1.setLayout(new BoxLayout(row1, BoxLayout.X_AXIS));
        row1.setAlignmentX(Component.LEFT_ALIGNMENT);
        row1.add(meter);

        // 下段：Output選択 + ON/OFF + Prefs
        outputCombo = new JComboBox<>();
        outputCombo.setFocusable(false);
        Dimension comboSize = new Dimension(200, 26);
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
        Dimension tsize = new Dimension(150, 26);
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
                Config.log("[Hearing][REC] ON: overlay visible");
                startMonitor();
            } else {
                toggle.setText("Hearing: OFF");
                MobMateWhisp.setHearingActive(false);  // ★追加
                stopMonitor();
                hideOverlay();
            }
            pack();
        });



        row1.add(Box.createHorizontalGlue());
        row1.add(Box.createHorizontalStrut(8));
        row1.add(toggle);
        root.add(row1);

        // Hearing専用 Prefs（共通PopupMenuは使わない）
        prefMenu.removeAll();

        // ★Language設定
        JMenu langMenu = new JMenu("Language");
        ButtonGroup langGroup = new ButtonGroup();

        // ★Show Overlay
        // ★Overlay Settings サブメニュー
        JMenu overlayMenu = new JMenu("Overlay Settings");

        // フォントサイズ
        JMenu fontSizeMenu = new JMenu("Font Size");
        ButtonGroup fontGroup = new ButtonGroup();
        int currentFontSize = prefs.getInt("hearing.overlay.font_size", 18);
        for (int size : new int[]{14, 16, 18, 20, 24, 28, 32, 36, 40}) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(size + "pt");
            item.setSelected(size == currentFontSize);
            fontGroup.add(item);
            final int fs = size;
            item.addActionListener(e -> {
                prefs.putInt("hearing.overlay.font_size", fs);
                try { prefs.sync(); } catch (Exception ignore) {}
                if (overlay != null) overlay.updateSettings();
            });
            fontSizeMenu.add(item);
        }
        overlayMenu.add(fontSizeMenu);

        // 背景色
        JMenu bgColorMenu = new JMenu("Background Color");
        ButtonGroup bgGroup = new ButtonGroup();
        String currentBg = prefs.get("hearing.overlay.bg_color", "green");
        String[][] colors = {
                {"Green", "green", "30,90,50"},
                {"Blue", "blue", "30,50,90"},
                {"Gray", "gray", "40,40,40"},
                {"Dark Red", "red", "70,30,30"}
        };
        for (String[] color : colors) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(color[0]);
            item.setSelected(color[1].equals(currentBg));
            bgGroup.add(item);
            final String colorKey = color[1];
            item.addActionListener(e -> {
                prefs.put("hearing.overlay.bg_color", colorKey);
                try { prefs.sync(); } catch (Exception ignore) {}
                if (overlay != null) overlay.updateSettings();
            });
            bgColorMenu.add(item);
        }
        overlayMenu.add(bgColorMenu);

        // 透明度
        JMenu opacityMenu = new JMenu("Opacity");
        ButtonGroup opacityGroup = new ButtonGroup();
        int currentOpacity = prefs.getInt("hearing.overlay.opacity", 72);
        for (int op : new int[]{50, 65, 72, 85, 95, 100}) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(op + "%");
            item.setSelected(op == currentOpacity);
            opacityGroup.add(item);
            final int opacity = op;
            item.addActionListener(e -> {
                prefs.putInt("hearing.overlay.opacity", opacity);
                try { prefs.sync(); } catch (Exception ignore) {}
                if (overlay != null) overlay.updateSettings();
            });
            opacityMenu.add(item);
        }
        overlayMenu.add(opacityMenu);

        // 表示位置
        JMenu positionMenu = new JMenu("Position");
        ButtonGroup posGroup = new ButtonGroup();
        String currentPos = prefs.get("hearing.overlay.position", "bottom_left");
        String[][] positions = {
                {"Bottom Left", "bottom_left"},
                {"Bottom Right", "bottom_right"},
                {"Top Center", "top_center"}
        };
        for (String[] pos : positions) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(pos[0]);
            item.setSelected(pos[1].equals(currentPos));
            posGroup.add(item);
            final String posKey = pos[1];
            item.addActionListener(e -> {
                prefs.put("hearing.overlay.position", posKey);
                try { prefs.sync(); } catch (Exception ignore) {}
                if (overlay != null && overlay.isVisible()) {
                    overlay.updatePosition();
                }
            });
            positionMenu.add(item);
        }
        overlayMenu.add(positionMenu);

        overlayMenu.addSeparator();

        // 履歴サイズ
        JMenu historySizeMenu = new JMenu("History Size");
        ButtonGroup historyGroup = new ButtonGroup();
        int currentHistorySize = prefs.getInt("hearing.overlay.history_size", 6);
        for (int size : new int[]{1, 3, 5, 6, 8, 10}) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(size + " items");
            item.setSelected(size == currentHistorySize);
            historyGroup.add(item);
            final int hs = size;
            item.addActionListener(e -> {
                prefs.putInt("hearing.overlay.history_size", hs);
                try { prefs.sync(); } catch (Exception ignore) {}
                // 履歴サイズが変わったら既存履歴を調整
                trimPartialHistory();
            });
            historySizeMenu.add(item);
        }
        overlayMenu.add(historySizeMenu);

        overlayMenu.addSeparator();

        // ディスプレイ選択
        JMenu displayMenu = new JMenu("Display");
        ButtonGroup displayGroup = new ButtonGroup();
        int currentDisplay = prefs.getInt("hearing.overlay.display", 0);

        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] screens = ge.getScreenDevices();

        for (int i = 0; i < screens.length; i++) {
            final int displayIndex = i;
            String label = "Display " + (i + 1);

            // 追加情報（解像度）
            try {
                DisplayMode dm = screens[i].getDisplayMode();
                label += String.format(" (%dx%d)", dm.getWidth(), dm.getHeight());
            } catch (Exception ignore) {}

            JRadioButtonMenuItem item = new JRadioButtonMenuItem(label);
            item.setSelected(i == currentDisplay);
            displayGroup.add(item);
            item.addActionListener(e -> {
                prefs.putInt("hearing.overlay.display", displayIndex);
                try { prefs.sync(); } catch (Exception ignore2) {}
                if (overlay != null && overlay.isVisible()) {
                    overlay.updatePosition();
                }
            });
            displayMenu.add(item);
        }
        overlayMenu.add(displayMenu);

        prefMenu.add(overlayMenu);

        prefButton = new JButton("Prefs");
        prefButton.setFocusable(false);
        prefButton.addActionListener(e -> prefMenu.show(prefButton, 0, prefButton.getHeight()));


        langCombo = new JComboBox<>(WHISPER_LANGS);
        langCombo.setFocusable(false);
        Dimension langSize = new Dimension(120, 26);
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

        translateToEnCheck = new JCheckBox("to EN");
        translateToEnCheck.setFocusable(false);
        translateToEnCheck.setOpaque(false);

        boolean tr = false;
        try { tr = prefs.getBoolean("hearing.translate_to_en", false); } catch (Exception ignore) {}
        translateToEnCheck.setSelected(tr);

        translateToEnCheck.addActionListener(e -> {
            boolean v = translateToEnCheck.isSelected();
            prefs.putBoolean("hearing.translate_to_en", v);
            try { prefs.sync(); } catch (Exception ignore) {}
            host.setHearingTranslateToEn(v); // ★追加：Whisper側へ即反映
        });

//        row2.add(translateToEnCheck);

        row2.add(Box.createHorizontalGlue());
        row2.add(prefButton);

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

        int maxW = HEARING_W;
        if (getWidth() > maxW) setSize(maxW, getHeight());
        setMinimumSize(new Dimension(maxW, getHeight()));
    }

    public void showWindow() {
        if (isVisible()) {
            toFront();
            requestFocus();
            return;
        }
        setVisible(true);
        toFront();
        requestFocus();
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
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8")
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
        if (LOOPBACK_TOKEN.equals(deviceName)) {
            synchronized (loopLock) {
                if (loopProc != null && loopProc.isAlive()) {
                    Config.log("[Hearing][WASAPI] skip start: loopback process already running");
                    return;
                }
            }
            startWasapiLoopbackProc();
            return;
        }

        String resolvedDevice = deviceName;
        final String useDeviceName = resolvedDevice;
        if (useDeviceName == null || useDeviceName.isEmpty() || useDeviceName.startsWith("(")) {
            // ★Loopback選択で見つからない場合は理由を表示
            if (LOOPBACK_TOKEN.equals(deviceName)) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        this,
                        "System Output (Loopback) を使うには、Windows側で「ステレオ ミキサー / Stereo Mix / What U Hear」等の録音デバイスを有効化する必要があります。\n"
                                + "サウンド設定 → 録音 → 無効なデバイス表示 → ステレオミキサーを有効化 をお試しください。",
                        "Hearing (Loopback)",
                        JOptionPane.INFORMATION_MESSAGE
                ));
            }
            SwingUtilities.invokeLater(() -> {
                if (toggle != null) {
                    ignoreToggleEvent = true;
                    try {
                        toggle.setSelected(false);
                        toggle.setText("Hearing: OFF");
                    } finally {
                        ignoreToggleEvent = false;
                    }
                }
            });
            return;
        }

        running = true;

        worker = new Thread(() -> {
            TargetDataLine l = null;
            try {
                Mixer.Info picked = null;
                for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                    if (useDeviceName.equals(info.getName())) { picked = info; break; }
                }
                if (picked == null) return;

                Mixer mixer = AudioSystem.getMixer(picked);

                AudioFormat[] tries = new AudioFormat[]{
                        new AudioFormat(16000f, 16, 1, true, false),
                        new AudioFormat(44100f, 16, 1, true, false),
                        new AudioFormat(48000f, 16, 1, true, false),
                        new AudioFormat(44100f, 16, 2, true, false),
                };

                Exception lastErr = null;
                for (AudioFormat fmt : tries) {
                    try {
                        DataLine.Info li = new DataLine.Info(TargetDataLine.class, fmt);
                        if (!mixer.isLineSupported(li)) continue; // ★選んだデバイスで判定
                        l = (TargetDataLine) mixer.getLine(li);
                        l.open(fmt); // ★余計な小細工しない方が安定
                        break;
                    } catch (Exception ex) {
                        lastErr = ex;
                        try { if (l != null) l.close(); } catch (Exception ignore) {}
                        l = null;
                    }
                }
                if (l == null) {
                    if (lastErr != null) Config.logError("[Hearing] open failed: " + deviceName, lastErr);
                    return;
                }

                line = l;
                l.start();

                byte[] buf = new byte[2048];
                while (running) {
                    int n = l.read(buf, 0, buf.length);
                    if (n <= 0) continue;

                    int peak = 0;
                    for (int i = 0; i + 1 < n; i += 2) {
                        int v = (short) ((buf[i + 1] << 8) | (buf[i] & 0xFF));
                        int a = Math.abs(v);
                        if (a > peak) peak = a;
                    }
                    int level = (int) Math.min(100, (peak / 32768.0) * 100.0);

                    // ---- 波形データをメーターに渡す ----
                    final byte[] bufCopy = new byte[n];
                    System.arraycopy(buf, 0, bufCopy, 0, n);
                    SwingUtilities.invokeLater(() -> {
                        if (meter != null) {
                            // GainMeterに波形データを渡すメソッドを仮定
                            // meter.pushWaveform(bufCopy, n);
                        }
                    });

                    // ---- Whisperへ投げるPCMを蓄積（できるだけ 16k/mono のときだけ）----
                    if (line != null) {
                        AudioFormat fmt = line.getFormat();
                        boolean ok16kMono = (Math.abs(fmt.getSampleRate() - 16000f) < 1f) && (fmt.getChannels() == 1) && (fmt.getSampleSizeInBits() == 16);
                        if (ok16kMono) {
                            pcmAcc.write(buf, 0, n);

                            // 1.2秒くらい溜まったら送る（雑に区切る：まず動かす用）
                            int triggerBytes = (int)(16000 * 2 * 1.2);
                            if (!transcribing && pcmAcc.size() >= triggerBytes) {
                                transcribing = true;
                                byte[] chunk = pcmAcc.toByteArray();
                                pcmAcc.reset();

                                new Thread(() -> {
                                    try {
//                                        host.transcribeHearingRaw(chunk);
                                        submitHearingChunk(chunk, "MIC");
                                    } finally {
                                        transcribing = false;
                                    }
                                }, "hearing-whisper").start();
                            }
                        }
                    }

                    double db;
                    if (peak <= 0) {
                        db = -60.0;
                    } else {
                        db = 20.0 * Math.log10(peak / 32768.0);
                        if (db < -60.0) db = -60.0;
                        if (db > 0.0) db = 0.0;
                    }

                    final int lv = level;
                    final double dbv = db;

                    SwingUtilities.invokeLater(() -> {
                        if (meter != null) {
                            meter.setValue(lv, dbv, false, 1f, 1f, false);
                        }
                    });
                }
            } catch (Exception ex) {
                Config.logError("[Hearing] monitor crashed", ex);
            } finally {
                try { if (l != null) { l.stop(); l.close(); } } catch (Exception ignore) {}
                line = null;
                running = false;
                SwingUtilities.invokeLater(() -> {
                    if (meter != null) {
                        meter.setValue(0, -60.0, false, 1f, 1f, false);
                    }
                    pcmAcc.reset();

                    transcribing = false;

                    SwingUtilities.invokeLater(() -> {
                        if (meter != null) {
                            meter.setValue(0, -60.0, false, 1f, 1f, false);
                        }
                        pcmAcc.reset();
                        transcribing = false;

                        // ★勝手にOFFに戻さない（ユーザー操作と競合する）
                        // hideOverlay(); // ←必要なら残してOK。まずは外して挙動確認おすすめ
                    });
                });

            }
        }, "hearing-monitor");

        worker.setDaemon(true);
        worker.start();
    }

    private void stopMonitor() {
        stopMonitor(true);
    }

    private void stopMonitor(boolean updateToggleUi) {
        running = false;
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

    // ★Hearing: PCM chunk を Whisper に投げて、Overlay/Lbl を更新する共通処理
    // 直近partialを最大N件保持（古い→新しい）※Nはユーザー設定
    private final java.util.ArrayDeque<String> partialHistory = new java.util.ArrayDeque<>(10);
    private void pushPartialHistory(String s) {
        if (s == null) return;
        s = s.trim();
        if (s.isEmpty()) return;
        // 同じ文字列の連続追加を抑制（任意だけど体感よくなるっす）
        String last = partialHistory.peekLast();
        if (s.equals(last)) return;
        int maxSize = prefs.getInt("hearing.overlay.history_size", 6);
        if (partialHistory.size() >= maxSize) partialHistory.pollFirst();
        partialHistory.addLast(s);
    }
    private void trimPartialHistory() {
        int maxSize = prefs.getInt("hearing.overlay.history_size", 6);
        while (partialHistory.size() > maxSize) {
            partialHistory.pollFirst();
        }
    }
    private String buildHistoryText() {
        if (partialHistory.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(128);
        for (String t : partialHistory) {
            if (sb.length() > 0) sb.append(" ... ");
            sb.append(t);
        }
        return sb.toString();
    }
    private void submitHearingChunk(byte[] pcm16k16mono, String srcTag) {

        // ★メインがtranscribe中は、Hearing側でWhisperは叩かない（競合回避）
        // ただし字幕更新は止めない：メインの lastPartial を拾って overlay を更新する
        if (host.isTranscribing()) {
            Config.logDebug("[Hearing][REC] main busy -> use main partial " + srcTag);

            String lp = MobMateWhisp.getLastPartial();
            if (lp != null && !lp.isEmpty()) {
                pushPartialHistory(lp);
                setOverlayText(buildHistoryText() + " . ");
            }
            return;
        }

        String lastPartial;
        try {
            lastPartial = host.transcribeHearingRaw(pcm16k16mono);
        } catch (Exception ex) {
            Config.log("[Hearing][REC] transcribeHearingRaw failed (" + srcTag + "): " + ex);
            return;
        }

        if (!running) return;

        if (lastPartial != null && !lastPartial.isEmpty()) {
            pushPartialHistory(lastPartial);
            setOverlayText(buildHistoryText() + " . ");
        } else {
            String lp = MobMateWhisp.getLastPartial();
            if (lp != null && !lp.isEmpty()) {
                pushPartialHistory(lp);
                setOverlayText(buildHistoryText() + " . ");
            } else {
                // setOverlayText("Listening.");
            }
        }

        int len = (lastPartial == null) ? 0 : lastPartial.length();
        Config.logDebug("[Hearing][REC] chunk ok (" + srcTag + ") partialLen=" + len);
    }




    private void restoreBounds() {
        int x = prefs.getInt("ui.hearing.x", 30);
        int y = prefs.getInt("ui.hearing.y", 30);

        // ★幅は固定（昔の1000px復元を殺す）
        int h = prefs.getInt("ui.hearing.h", getHeight());
        if (h <= 0) h = getHeight();

        setBounds(x, y, HEARING_W, h);
    }
    private void saveBounds() {
        Rectangle r = getBounds();
        prefs.putInt("ui.hearing.x", r.x);
        prefs.putInt("ui.hearing.y", r.y);

        // ★幅は固定で保存（次回も肥大化しない）
        prefs.putInt("ui.hearing.w", HEARING_W);
        prefs.putInt("ui.hearing.h", r.height);
    }

    private void ensureOverlayVisible() {
        if (overlay == null) overlay = new HearingOverlayWindow();
        overlay.showAtBottomLeft();
    }

    private void hideOverlay() {
        if (overlay != null) overlay.setVisible(false);
    }

    private void setOverlayText(String s) {
        if (overlay == null) return;
        overlay.setText(s);
    }

    private class HearingOverlayWindow extends JWindow {
        private final OutlineLabel label = new OutlineLabel();
        private JPanel contentPanel;

        HearingOverlayWindow() {
            super();
            setAlwaysOnTop(true);
            setFocusableWindowState(false);
            setBackground(new Color(0, 0, 0, 0));

            contentPanel = new JPanel(new BorderLayout());
            contentPanel.setOpaque(true);
            updateSettings(); // ★初期設定を適用
            contentPanel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

            label.setText("");
            contentPanel.add(label, BorderLayout.CENTER);

            setContentPane(contentPanel);
            pack();
        }

        void updateSettings() {
            // 背景色
            String bgColor = prefs.get("hearing.overlay.bg_color", "green");
            Color bg;
            switch (bgColor) {
                case "blue":  bg = new Color(30, 50, 90); break;
                case "gray":  bg = new Color(40, 40, 40); break;
                case "red":   bg = new Color(70, 30, 30); break;
                default:      bg = new Color(30, 90, 50); break; // green
            }
            contentPanel.setBackground(bg);

            // 透明度
            int opacity = prefs.getInt("hearing.overlay.opacity", 72);
            try {
                setOpacity(opacity / 100.0f);
            } catch (Exception ignore) {}

            // フォントサイズ
            int fontSize = prefs.getInt("hearing.overlay.font_size", 18);
            label.setFont(new Font("Dialog", Font.BOLD, fontSize));

            // 再描画
            if (isVisible()) {
                pack();
                updatePosition();
            }
        }

        void updatePosition() {
            String position = prefs.get("hearing.overlay.position", "bottom_left");
            int displayIndex = prefs.getInt("hearing.overlay.display", 0);

            // 指定されたディスプレイの境界を取得
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice[] screens = ge.getScreenDevices();

            Rectangle screenBounds;
            if (displayIndex >= 0 && displayIndex < screens.length) {
                GraphicsConfiguration gc = screens[displayIndex].getDefaultConfiguration();
                screenBounds = gc.getBounds();
            } else {
                // フォールバック：プライマリディスプレイ
                screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice()
                        .getDefaultConfiguration()
                        .getBounds();
            }

            int margin = 14;
            int x, y;

            switch (position) {
                case "bottom_right":
                    x = screenBounds.x + screenBounds.width - getWidth() - margin;
                    y = screenBounds.y + screenBounds.height - getHeight() - margin - 60;
                    break;
                case "top_center":
                    x = screenBounds.x + (screenBounds.width - getWidth()) / 2;
                    y = screenBounds.y + margin;
                    break;
                default: // bottom_left
                    x = screenBounds.x + margin;
                    y = screenBounds.y + screenBounds.height - getHeight() - margin - 60;
                    break;
            }
            setLocation(x, Math.max(screenBounds.y + margin, y));
        }

        void setText(String s) {
            if (s == null) s = "";
            s = s.replace("\r", "").replace("\n", " ").trim();

            int displayIndex = prefs.getInt("hearing.overlay.display", 0);
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice[] screens = ge.getScreenDevices();

            Rectangle screenBounds;
            if (displayIndex >= 0 && displayIndex < screens.length) {
                GraphicsConfiguration gc = screens[displayIndex].getDefaultConfiguration();
                screenBounds = gc.getBounds();
            } else {
                screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice()
                        .getDefaultConfiguration()
                        .getBounds();
            }

            int margin = 14;
            int maxWindowW = (int)(screenBounds.width * 0.90);
            int maxLabelW  = Math.max(600, maxWindowW - (margin * 2));

            Font f = label.getFont();
            if (f == null) {
                int fontSize = prefs.getInt("hearing.overlay.font_size", 18);
                f = new Font("Dialog", Font.BOLD, fontSize);
            }
            FontMetrics fm = label.getFontMetrics(f);

            if (!SwingUtilities.isEventDispatchThread()) {
                final String t = s;
                SwingUtilities.invokeLater(() -> setText(t));
                return;
            }

            String fitted = ellipsizeToFit(s, fm, maxLabelW);
            label.setText(fitted);

            label.invalidate();
            getContentPane().invalidate();

            pack();

            int w = Math.min(getWidth(), maxWindowW);
            if (getWidth() != w) setSize(w, getHeight());

            updatePosition(); // ★位置も更新
        }

        private String ellipsizeToFit(String s, FontMetrics fm, int maxPx) {
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

        void showAtBottomLeft() {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(this::showAtBottomLeft);
                return;
            }
            pack();
            updatePosition(); // ★showAtBottomLeftから改名された処理を呼ぶ
            setVisible(true);
        }

    }

    private static class OutlineLabel extends JComponent {
        private String text = "";

        void setText(String s) {
            this.text = (s == null) ? "" : s;
            repaint();
        }

        @Override public Dimension getPreferredSize() {
            Font f = getFont();
            if (f == null) f = new Font("Dialog", Font.BOLD, 18);
            FontMetrics fm = getFontMetrics(f);
            int w = Math.max(120, fm.stringWidth(text) + 6);
            int h = Math.max(28, fm.getHeight() + 4);
            return new Dimension(w, h);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Font f = getFont();
                if (f == null) f = new Font("Dialog", Font.BOLD, 18);
                g2.setFont(f);

                FontMetrics fm = g2.getFontMetrics();
                int x = 0;
                int y = fm.getAscent();

                // 縁取り（黒）
                g2.setColor(new Color(0,0,0,220));
                g2.drawString(text, x-1, y);
                g2.drawString(text, x+1, y);
                g2.drawString(text, x, y-1);
                g2.drawString(text, x, y+1);

                // 本体（白）
                g2.setColor(Color.WHITE);
                g2.drawString(text, x, y);
            } finally {
                g2.dispose();
            }
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
                try (BufferedReader br = new BufferedReader(new InputStreamReader(loopProc.getErrorStream(), "UTF-8"))) {
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

            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
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
                            int level = (int) Math.min(100, (peakFinal / 32768.0) * 100.0);
                            double db;
                            if (peakFinal <= 0) db = -60.0;
                            else {
                                db = 20.0 * Math.log10(peakFinal / 32768.0);
                                if (db < -60.0) db = -60.0;
                                if (db > 0.0) db = 0.0;
                            }
                            if (meter != null) meter.setValue(level, db, false, 1f, 1f, peakFinal > 0);
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

                        // まずは素直に貯める（helper側が 16k/mono/16bit を吐く前提）
                        pcmAcc.write(pcm, 0, pcm.length);

                        // だいたい 1.2秒ぶん溜まったら Whisper に投げる（まず動かす用）
                        int triggerBytes = (int) (16000 * 2 * 1.2);

                        if (!transcribing && pcmAcc.size() >= triggerBytes) {
                            transcribing = true;

                            byte[] chunk = pcmAcc.toByteArray();
                            pcmAcc.reset();

                            final int peakAtSend = lastPeak;
                            Config.logDebug("[Hearing][ASR] trigger: bytes=" + chunk.length + " peak=" + peakAtSend);

                            new Thread(() -> {
                                long t0 = System.currentTimeMillis();
                                try {
//                                    host.transcribeHearingRaw(chunk);
                                    submitHearingChunk(chunk, "LOOPBACK");

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
                Config.logError("[Hearing] WASAPI helper crashed", ex);
                Config.log("[Hearing][WASAPI] restarting helper in 500ms...");
                new Thread(() -> {
                    try { Thread.sleep(500); } catch (Exception ignore) {}
                    try {
                        if (running) startWasapiLoopbackProc(); // 既存の起動関数名に合わせる
                    } catch (Exception ex2) {
                        Config.logError("[Hearing][WASAPI] restart failed", ex2);
                    }
                }).start();
            } finally {
                running = false;
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
                pcmBytes -= (old != null ? old.length : 0);
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
