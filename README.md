# ![MobMateWhispTalk](https://github.com/zufall-upon/MobMate/releases/tag/release)
**For quiet players who still want to be loud.**

![MobMateWhispTalk](https://github.com/zufall-upon/MobMate/blob/main/logo01.png)

ローカルで動作する **STT + TTS（匿名発声）ツール** です。  
This is a **local-only speech-to-text + text-to-speech (anonymous voice)** tool.

Whisper.cpp / Java / NAudio を使用しています。  
Built with Whisper.cpp / Java / NAudio.

ゲームなどのVCで、話した内容をリアルタイムに  
**匿名音声として再発声**します。  
It converts your voice into **anonymous synthesized speech** in real time for voice chats (VC).

短いリアクション・応答向けのVC利用を想定しています。  
Designed mainly for **short reactions and quick responses**.

ラジオチャット（聞き専・コメント主体）のコミュニケーションに慣れたユーザーが、
無理なくボイスチャットに参加するためのツールです。
Designed for users who are comfortable with radio-style, listener-based communication,
and want a low-pressure way to participate in voice chat.

![MobMateWhispTalk](https://raw.githubusercontent.com/zufall-upon/MobMate/refs/heads/main/mov01.gif)

## 💡 Why MobMate / コンセプト

### 🇯🇵 日本語

MobMate は、**完全ローカルで動作する音声認識（STT）＋音声合成（TTS）による匿名発声ツール**です。  
Whisper.cpp / Java / NAudio を利用し、ボイスチャット上の音声をリアルタイムに  
**「音声 → テキスト → 別の声で再発声」** します。

リアルタイム配信における「コメント読み上げ」のような仕組みを、  
**ゲーム内ボイスチャット（VC）向けに再構成**したツールです。

ゲームなどのVC上で、自分の声の特徴（声質・年齢・性別など）を直接出さずに、  
テキストを合成音声として流すことができます。

たとえば以下のような用途を想定しています：

- 「了解」「待って」「敵いた」などの短い指示
- 笑い声などの軽いリアクション
- VCに積極的に参加しづらい状況での意思表示

話すことにハードルを感じる理由があっても、  
**コミュニケーションそのものを諦めなくていい**。  
MobMate は、そんな「聞き専寄り」の人のための  
**汎用・擬似ラジオチャットツール**です。

---

### 🇺🇸 English

MobMate is a **fully local Speech-to-Text (STT) and Text-to-Speech (TTS) tool** designed for anonymous voice output.  
Using Whisper.cpp, Java, and NAudio, it converts your voice in real time from  
**speech → text → synthesized anonymous voice**.

You can think of it as a *live comment reader*—  
but reimagined for **in-game voice chat**.

Instead of transmitting your raw voice, MobMate outputs synthesized speech,  
allowing you to communicate without revealing your natural voice characteristics  
(such as tone, age, or gender).

Typical use cases include:

- Short commands like “OK”, “Wait”, or “Enemy spotted”
- Simple reactions such as laughter
- Participating in VC when speaking directly feels difficult

Even if you have reasons to stay quiet,  
**you shouldn’t have to give up communicating altogether**.

MobMate is designed as a  
**general-purpose pseudo radio-style chat tool**  
for players who usually just listen—but still want to be heard.

---

### 🧩 Features at a glance / 主な特徴

#### 🇯🇵 日本語
- **完全ローカル動作**（クラウド送信なし・Ping安定）
- **Whisper.cpp による音声認識（STT）**
- **VOICEVOX API 連携による TTS（匿名発声）** に対応（任意）
- **短い応答・リアクション専用**の VC コミュニケーションを想定

#### 🇺🇸 English
- **Fully local execution** (no cloud, stable ping)
- **Speech recognition powered by Whisper.cpp (STT)**
- **Optional TTS via VOICEVOX API** (anonymous voice output)
- Designed for **short responses and quick reactions** in voice chat

---
### 😂 笑い検知・置換機能 / Natural Laugh Detection

MobMateWhispTalk には、話している最中の **自然な笑い声** を自動で検知し、
別の表現（テキストまたは効果音）に変換する機能があります。

MobMateWhispTalk includes a **natural laugh detection** feature  
that detects actual laughter in your voice and converts it into text or sound.

Whisper.cpp（例: ggml-small.bin）の特性により、  
マイクの前で「フフッ」「ハハッ」と笑うだけで、  
音声認識結果に `(笑)` や `LOL` といった **笑いトークン** が含まれることがあります。

Thanks to Whisper.cpp models (e.g. ggml-small.bin),  
natural laughter like “haha” or “heh” is often automatically recognized  
as laugh tokens such as `(笑)` or `LOL` in the transcription.

MobMate はこの挙動を利用し、  
**意図的に言葉を発しなくても**、感情としての「笑い」を拾って処理します。

MobMate leverages this behavior to capture **emotional laughter**,  
even when you are not explicitly saying words like “lol” or “haha”.

#### ⚙ 笑いの変換処理 / Laugh Replacement

検知された笑い表現は、  
あらかじめ設定した **文字列** または **WAV ファイル** に置き換えられます。

Detected laughter can be replaced with custom **text expressions**  
or **WAV-based sound effects**.

```
laughs.enable=true
laughs.detect=（笑）,(笑),笑,草,ｗ,www,L�v,lol,lolol,lmao,rofl,laugh
laughs.detect.auto=（笑）,笑,草,ｗ,www,lol,lmao,rofl,ㅋㅋ,ㅎㅎ,哈哈,呵呵
laugh.replace=ワハハハ,ふふふっ,laughsounds/laughter01.wav
```

- 自然な笑い声 → Whisper が (笑) 等を生成
- MobMate がそれを検知して変換
- VC には 別の声・効果音として再生

Natural laughter → Whisper generates a laugh marker
→ MobMate detects it → replaces it with another voice or sound

## 🟢 動作環境 / System Requirements

- Windows 10 / 11 (64bit)

### 🧩 必須ランタイム / Required Runtime
Visual C++ 2015–2022 (x64)

https://aka.ms/vs/17/release/vc_redist.x64.exe

---

## 🚀 ダウンロード / Download
https://github.com/zufall-upon/MobMate/releases/tag/release

---

## 🚀 起動方法 / How to Run

1. zip を展開  
   Extract the zip file
2. `_run.bat` をダブルクリック  
   Double-click `_run.bat`

### 🟢 First Launch / 初回起動について

On first launch, if `_outtts.txt` does not exist,
MobMate will ask you to select a language.

初回起動時、`_outtts.txt` が存在しない場合は  
言語選択ダイアログが表示されます。

- English
- 日本語
- 中文・简体 (Chinese Simplified),
- 中文・繁體 (Chinese Traditional),
- 한국어 (Korean)

Based on your selection, a preset configuration file
will be copied automatically.

選択した言語に応じた初期設定ファイルが自動で作成されます。

---

## 🔹 Whisperモデルの配置 / Whisper Model Setup

`models` フォルダに Whisper のモデルファイルを配置してください。  
Place the Whisper model file inside the `models` folder.

- `ggml-small.bin`  
  https://huggingface.co/ggerganov/whisper.cpp/tree/main

---

### 👉 推奨モデル / Recommended Model

**`ggml-small.bin`（推奨 / Recommended）**

精度・速度・安定性のバランスが最も良く、  
MobMateWhispTalk の用途（短い発話・リアクション）に最適です。

Offers the best balance of accuracy, speed, and stability,  
and is well suited for short voice chat interactions.

#### 他モデルとの比較 / Model Comparison

- **Tiny**  
  精度不足で誤認識が多い  
  Low accuracy, frequent misrecognition

- **Medium**  
  正常動作するが、サイズが大きく動作が重い  
  Works correctly, but large and noticeably slower

- **Large / Large-v3-turbo**  
  出力が不安定でスパム的な認識が増えやすい  
  Unstable output, prone to repetitive or spam-like transcription

👉 **結論 / Conclusion**  
まずは `ggml-small.bin` の使用を強く推奨します ⭐


---

## 🎧 Virtual Audio Setup / 仮想オーディオ設定

MobMate requires a **virtual audio device** to route TTS output into games or voice chat software.  
MobMate は、TTS の音声をゲームやボイスチャットに送るために  
**仮想オーディオデバイス**が必要です。

---

### Recommended tools / 推奨ツール

- **VB-Audio Virtual Cable (Windows)**  
  https://vb-audio.com/Cable/  

  Simple and lightweight virtual audio cable.  
  シンプルで軽量な定番の仮想オーディオケーブルです。

- **SteelSeries Sonar (Windows)**  
  https://jp.steelseries.com/gg/sonar  

  Useful if Windows keeps changing the default audio device.  
  Windows が毎回既定のオーディオデバイスを変更してしまう場合に便利です。

  ※ Recommended for users who struggle with unstable default device switching  
  ※ 既定デバイスが勝手に変わる環境の方向け

---

### Setup concept / 設定の考え方
- **Audio Input**: Your physical microphone  
  入力：実際に話すマイク
- **Audio Output**: Virtual audio device  
  出力：仮想オーディオデバイス
- Configure your game or voice chat app to use the virtual device as its microphone  
  ゲームや VC 側では、その仮想デバイスをマイクとして設定します

---

## 🎤 Basic Usage / 基本的な使い方

### Initial setup / 初期設定
Open the **Prefs** button and configure the following recommended settings.  
**Prefs ボタンから以下の推奨設定を行ってください。**

**Recommended settings / 推奨設定**
- **AutoPaste**: OFF  
  自動貼り付けを無効にします
- **Auto type**: OFF  
  自動入力を無効にします
- **Silence detection**: ON  
  無音検出を有効にします
- **Key trigger mode**: Start / Stop  
  キーで録音の開始・停止を切り替えます
- **Audio Inputs**: Select your physical microphone  
  使用するマイクを指定してください
- **Audio Outputs**: Select a virtual audio device  
  別の仮想マイクを指定します  
  （VB-Audio Virtual Cable や SteelSeries Sonar で動作確認済み）

---

### How to use / 使い方
- Press the hotkey (**Default: F9**) or click **Start** to begin recording  
  ホットキー（初期設定：F9）または「Start」ボタンで録音を開始します
- Your voice is transcribed (STT) and converted into speech (TTS)  
  音声が認識され、テキスト化された後に音声として再生されます
- Recognition results are appended to the end of `_outtts.txt`  
  認識結果は `_outtts.txt` の末尾に追記されます
- The program monitors the latest line and plays it via TTS  
  ファイルの最新行を監視し、その内容を TTS で再生します

---

### Notes / 補足
- Designed for **short reactions and quick voice responses**  
  短いリアクションや即時発声向けに設計されています

---

## 🔊 匿名発声（TTS） / Anonymous TTS

MobMate can optionally integrate with **VOICEVOX** for text-to-speech output.  
MobMate は **VOICEVOX** と連携して匿名発声（TTS）を行うことができます（任意）。

---

### VOICEVOX Integration / VOICEVOX 連携

```ini
voicevox.exe="M:\VOICEVOX\VOICEVOX.exe"
voicevox.api="http://127.0.0.1:50021"
voicevox.speaker=3
initial_prompt=This is an in-game radio communication. Speak briefly and clearly. Do not output subtitles, background music, sound effects, or emoticons. Laughter is allowed. Focus only on spoken content. Common words include "roger", "enemy", "ally", "help".
```

```ini
voicevox.exe="M:\VOICEVOX\VOICEVOX.exe"
voicevox.api="http://127.0.0.1:50021"
voicevox.speaker=3
initial_prompt=これはゲーム内の無線チャットです。短く簡潔に話す。字幕、BGM、効果音、顔文字は出力しないで。笑い声は出していい。話している内容だけに集中してください。使われる単語は「了解、敵、味方、助けて」
--------------------------↑設定↓ログ--------------------------
```
## 📝 Configuration Options / 各項目の説明

The `_outtts.txt` file works as both **configuration** and **log**.  
設定はファイル上部に記述してください。下部はログ領域になります。

---

### 🔧 Available Settings / 設定項目一覧

| Setting | Description (EN) | 説明 (JP) |
|--------|------------------|-----------|
| `language` | Whisper language hint (`ja / en / ko / zh / auto`) | Whisper の言語ヒント（ja / en / ko / zh / auto） |
| `initial_prompt` | Initial prompt for Whisper (shorter = more stable) | Whisper 用の事前プロンプト（短いほど安定） |
| `silence` | Silence detection threshold (ms, higher = more tolerant) | 無音判定（ミリ秒。大きいほど判定が甘くなる） |
| `silence_hard` | Hard silence detection to suppress noise | ハード無音判定（ノイズ誤認識を抑制） |
| `voicevox.exe` | Path to VOICEVOX executable (optional) | VOICEVOX 実行ファイルのパス（任意） |
| `voicevox.api` | VOICEVOX API endpoint | VOICEVOX API の URL |
| `voicevox.speaker` | VOICEVOX speaker ID | VOICEVOX 話者 ID |
| `laughs.enable` | Enable natural laugh detection | 笑い検知機能の有効 / 無効 |
| `laughs.detect` | Laugh tokens for the selected language | 言語別の笑い検知トークン |
| `laughs.detect.auto` | Laugh tokens for auto language mode | auto モード用の多言語笑い検知 |
| `laughs.replace` | Replacement text or WAV paths for laughter | 笑いを置換する文字列または WAV |
| `ignore.mode` | Ignore filter mode (`simple` or `regex`) | 無視フィルタ方式（simple / regex） |

---

### 🔊 VOICEVOX Notes / VOICEVOX に関する注意

- VOICEVOX is automatically detected if `voicevox.exe` path is valid  
  `voicevox.exe` に有効なパスが設定されていれば自動認識されます
- VOICEVOX will be launched automatically when MobMate starts  
  MobMate 起動時に VOICEVOX も自動起動します
- Default API port is **50021**  
  API ポートのデフォルトは **50021** です
- Speaker ID must be checked separately  
  話者 ID は VOICEVOX 側で調べてください

⚠ **VOICEVOX is a third-party tool**  
⚠ **VOICEVOX は第三者製ツールです**

https://voicevox.hiroshiba.jp/

---

---

## 📄 `_ignore.txt` (Ignored Words)

このファイルには **無視したい単語やフレーズ** を 1 行ずつ記述します。  
Write **words or phrases to be ignored**, one per line.

例 / Example:  simple(部分一致）
```ini
えーと  uh
あのー  um
えっと  aa
```
例 / Example:  regex(正規表現)
```ini
ん$　　　Aa$
^えー+   Ye$
```

このリストに含まれる語句は、**ログにも TTS 出力にも表示・再生されません**。  
Any phrase listed here will be **excluded from both logs and TTS output**.

短いフィラー音（例: “uh”, “um”, “えーと”）を除外する用途に向いています。  
Useful for filtering filler words such as “uh”, “um”, etc.


---

## 📄 `_dictionary.txt` (Word Replacement Dictionary)

このファイルには **発声時に変換したい単語** を 1 行ずつ記述します。  
Write **words you want to be replaced during TTS output**, one per line.

一致した語句は **音声発声時に自動的に変換** されます。  
Matched words will be **automatically replaced at TTS playback time**.

※ 動作しない場合、`_ignore.txt` が優先されている可能性があります。  
※ If it does not work, the word may be overridden by `_ignore.txt`.

語句の登録先は用途に応じて調整してください。  
Adjust which file to use depending on your purpose.

例：
```
frag=フラグ, グレネード, 手りゅう弾        # frag → grenade / explosive
tango=敵                                   # tango → enemy (NATO phonetic slang)
fuck=チョメ, Fワード                       # fuck → censored / softened expression
```
---
## 🔖 License / ライセンス

本ツールは、複数のオープンソースソフトウェア（OSS）を組み合わせて構成されています。  
This tool is built using multiple open-source software components.

### 📦 Included OSS / 使用している主なOSS

- MisterWhisper — MIT License  
- Whisper.cpp — MIT License  
- JNA — Apache License 2.0  
- NAudio — MIT License  
- JNativeHook — BSD License  
- VOICEVOX API — Subject to each character's individual license  

### 📄 Distribution / 配布について

本ツール自体の配布は **MIT 相当ライセンス**で可能です。  
This tool itself may be distributed under an MIT-equivalent license.

VOICEVOX の話者（キャラクター）利用については、  
**各キャラクターごとのライセンス条件に従ってください。**  
Usage of VOICEVOX voices must comply with each character's license terms.

---

🙏 **Thanks / 謝辞**

- Whisper.cpp  
- VOICEVOX  
- GPT の友達 / GPT, my coding companion  

---

### 🔗 Based on / ベースプロジェクト

This project is based on **MisterWhisper** by openConcerto.  
本プロジェクトは openConcerto による MisterWhisper をベースにしています。

Original repository:  
https://github.com/openconcerto/MisterWhisper

