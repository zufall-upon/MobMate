# MobMateWhispTalk
For quiet players who still want to be loud.


![MobMateWhispTalk](https://raw.githubusercontent.com/zufall-upon/MobMate/refs/heads/main/logo01.png)
![MobMateWhispTalk](https://raw.githubusercontent.com/zufall-upon/MobMate/refs/heads/main/mov01.gif)

ローカルで動く音声認識STT＋TTS（匿名発声）ツールです。  
Whisper.cpp / Java / NAudio を利用しています。  
ゲームなどのVCで、話している内容をリアルタイムで **匿名発声→音声出力** します。
短い応答・リアクション専門でのVCコミュニケーションを想定しています。

---

## 🟢 動作環境

- Windows 10 / 11（64bit）
- GPU 推奨（CPU でも可）
  🧩 必要環境
  Visual C++ 2015–2022 (x64) 必須
  https://aka.ms/vs/17/release/vc_redist.x64.exe

3つのパッケージがあります：

| パッケージ | 対応GPU |
|-----------|---------|
| **CPU**   | GPUなし |
| **Vulkan** | Radeon / Intel |
| **CUDA** | NVIDIA |

⚠️ NOTE:
CPU / Vulkan / CUDA は DLL が違うため **混ぜないでください**
app-all.jar は共通ですが、DLL は各フォルダ専用です
---

## 🚀 起動方法

1. zip を展開
2. 対応するフォルダの`_run.bat` をダブルクリック

### 🔹 Whisperモデルの配置

`models` フォルダに以下を配置してください：

👉 **推奨モデル： `ggml-small.bin`**

- Tiny：精度不足
- Medium：巨大 & 遅い
- Small：最適バランス（推奨）

---

## 🎤 基本的な使い方

- Prefsボタンから設定を行ってください。お勧め設定は以下。
    - AutoPaste off
    - Auto type off
    - Slience detection ON!
    - Key trigger mode → start/ stop
    - Audio Inputs → ご使用のマイクを指定
    - Audio outputs → ↑とは違う仮想マイクを指定。 VBAudio Cable等。SteelSeries Sonarでテストしています。
- ホットキー（初期 F9）または「Start」ボタンで録音開始
- 音声認識 → テキストから音声発声。
- `_outtts.txt` の末尾に認識結果が追記される
- その最新行を監視して TTS を再生します

---

## 🔊 匿名発声（TTS）について

VOICEVOX と連携できます（任意）。

voicevox.exe="M:\VOICEVOX\VOICEVOX.exe"
voicevox.api="http://127.0.0.1:50021"
voicevox.speaker=3 // 3 ずんだもん, 26 Whitecal びえん


- VOICEVOX を起動するだけで自動認識します（voicevox.exeに有効なパスがあればプログラム起動時に同時起動します）
- speaker は話者ID（検索して調べてください）
- API ポートは 50021（デフォルト）

※ VOICEVOX は第三者製ツールです **

---

## ⚙ 設定ファイル `_outtts.txt`

このファイルは **設定＋ログ** を兼ねています。

ファイルの **先頭付近に設定を書くと読み込まれます**。  
それより下はログ領域です。

### 🔧 設定例

language=ja
silence=3200
silence_hard=100
voicevox.exe="M:\VOICEVOX\VOICEVOX.exe"
voicevox.api="http://127.0.0.1:50021"
voicevox.speaker=3
laughs=ワハハハハハ,ふふふっ,laughsounds/laughter_lady01.wav


initial_prompt=これはゲーム内の無線チャットです。短く簡潔に話す。字幕、BGM、効果音、顔文字は出力しないで。笑い声は出していい。話している内容だけに集中してください。使われる単語は「了解、敵、味方、助けて」

--------------------------↑設定↓ログ--------------------------

📝 各項目の説明

| 設定 | 説明                                    |
|------|---------------------------------------|
| `language=ja` | Whisperへの言語ヒント                        |
| `silence` | 無音判定（大きいほど判定甘くなる）                     |
| `silence_hard` | ハード無音判定（ノイズと誤認識を減らす）                  |
| `voicevox.exe` | VOICEVOX本体のパス                         |
| `voicevox.api` | APIのURL                               |
| `voicevox.speaker` | 話者ID                                  |
| `initial_prompt` | 事前プロンプト（短いほど安定）                       |
| `laughs` | 笑い声のパターン。カレントディレクトリからのパスでwavファイル指定可能。 |

---

## 📄 `_ignore.txt` について

このファイルに **無視したい単語** を1行ずつ書きます。

例：

えーと
あのー
えっと


一致した語句はログにもTTSにも出なくなります。


---

## 📄 `_dictionary.txt` について

このファイルに **変換したい単語** を1行ずつ書きます。

例：

frag=フラグ, グレネード, 手りゅう弾
tango=敵
fuck=チョメ, Fワード

一致した語句は発声時に変換されます。
動作しない場合は_ignore.txtが優先されるため。語句登録先は適宜調整してください。


---
## 🔖 ライセンス

本ツールはオープンソースコンポーネントを組み合わせています。

含まれる主なOSS：
- MisterWhisper	MIT
- Whisper.cpp (MIT)
- JNA (Apache 2.0)
- NAudio (MIT)
- JNativeHook (BSD)
- VOICEVOX API（各話者ライセンスに従う）

本ツール自体の配布は MIT 相当で可能です。  
VOICEVOX の話者利用は各キャラのライセンスに従ってください。

🙏 Thanks

Whisper.cpp
VOICEVOX
GPTの友達

This project is based on MisterWhisper by openConcerto
Original repository:
https://github.com/openconcerto/MisterWhisper

---
