param(
  [string]$Root = ".\"
)
$ErrorActionPreference = "Stop"
# --- sanitize Root (remove accidental quotes) ---
if ($null -ne $Root) {
  $Root = $Root.Trim()
  if ($Root.Length -ge 2 -and (($Root.StartsWith('"') -and $Root.EndsWith('"')) -or ($Root.StartsWith("'") -and $Root.EndsWith("'")))) {
    $Root = $Root.Substring(1, $Root.Length - 2)
  }
  $Root = $Root.Replace('"', '').Replace("'", '')
}

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = "SilentlyContinue"

function Ensure-Dir($p) {
  if (!(Test-Path $p)) {
    New-Item -ItemType Directory -Force -Path $p | Out-Null
  }
}

function Download-File($url, $outPath) {
  Ensure-Dir (Split-Path -Parent $outPath)
  if (Test-Path $outPath) {
    Write-Host "Already exists: $outPath"
    return
  }

  $tmp = "$outPath.part"
  if (Test-Path $tmp) { Remove-Item -Force $tmp }

  Write-Host "DL: $url"
  $ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) MobMateVoicegerInstaller/1.0"
  try {
    Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing -Headers @{ "User-Agent" = $ua } -TimeoutSec 60
    Move-Item -Force $tmp $outPath
    return
  } catch {
    $msg = $_.Exception.Message
    $code = $null
    try { $code = $_.Exception.Response.StatusCode.value__ } catch {}
    Write-Host "IWR failed. status=$code msg=$msg"
  }
  try {
    $curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
    if ($curl) {
      & $curl -L -f -A $ua -o $tmp $url | Out-Null
      if (Test-Path $tmp) {
        Move-Item -Force $tmp $outPath
        return
      }
    }
  } catch {
    Write-Host "curl failed: $($_.Exception.Message)"
  }
  try {
    Start-BitsTransfer -Source $url -Destination $tmp -ErrorAction Stop
    Move-Item -Force $tmp $outPath
    return
  } catch {
    Write-Host "BITS failed: $($_.Exception.Message)"
  }

  throw "Failed to download: $url"
}


function Expand-Zip($zip, $dest) {
  $bytes = [System.IO.File]::ReadAllBytes($zip)
  if ($bytes.Length -lt 2 -or $bytes[0] -ne 0x50 -or $bytes[1] -ne 0x4B) {
    throw "Downloaded file is not a ZIP (maybe HTML error page): $zip"
  }

  Ensure-Dir $dest
  if (!(Test-Path $zip)) {
    throw "Zip file not found: $zip"
  }
  Expand-Archive -Force -Path $zip -DestinationPath $dest
}

function Get-GithubZip($ownerRepo, $branch, $outZip) {
  $url = "https://github.com/$ownerRepo/archive/refs/heads/$branch.zip"
  Download-File $url $outZip
}

Write-Host "Root: $Root"
Ensure-Dir $Root
Set-Location $Root

# --- Cleanup (remove downloaded zips / robocopy leftovers) ---
$DO_CLEANUP = $true
$script:CleanupList = New-Object System.Collections.Generic.List[string]
function Register-Cleanup([string]$p) {
  if (-not $DO_CLEANUP) { return }
  if ([string]::IsNullOrWhiteSpace($p)) { return }
  if (-not $script:CleanupList.Contains($p)) { [void]$script:CleanupList.Add($p) }
}
function Invoke-Cleanup() {
  if (-not $DO_CLEANUP) { return }
  Write-Host ""
  Write-Host "Cleanup..."
  foreach ($p in ($script:CleanupList | Select-Object -Unique)) {
    try {
      if (Test-Path $p) {
        Remove-Item -Recurse -Force $p
        Write-Host "  removed: $p"
      }
    } catch {
      Write-Host "  skip: $p ($($_.Exception.Message))"
    }
  }
  try {
    Get-ChildItem -Path $Root -Filter "*.part" -File -ErrorAction SilentlyContinue |
            ForEach-Object { try { Remove-Item -Force $_.FullName } catch {} }
  } catch {}
}


# 0) voiceger_api.py
$internalDir = Join-Path $Root "_internal"
Ensure-Dir $internalDir

# --- voiceger_api.py stdlib---
$apiDst = Join-Path $internalDir "voiceger_api.py"
@'
# -*- coding: utf-8 -*-
# --- hotfix: avoid typeguard using inspect.getsource in frozen env (g2p_en -> inflect -> typeguard) ---
# We provide a stub 'typeguard' module with no-op decorators BEFORE any library imports it.
# --- hotfix: make inspect.getsource safe + patch transformers doc utils for frozen env ---
import sys, types

# 1) inspect.getsource: NEVER return "" (empty) on failure. Return a dummy one-liner.
try:
    import inspect as _inspect
    _orig_getsource = getattr(_inspect, "getsource", None)

    def _safe_getsource(obj):
        if _orig_getsource is None:
            return "pass\n"
        try:
            s = _orig_getsource(obj)
            # transformers/utils/doc.py assumes splitlines()[0] exists
            return s if s and s.strip() else "pass\n"
        except Exception:
            return "pass\n"

    _inspect.getsource = _safe_getsource
except Exception:
    pass

# 2) Patch transformers.utils.doc.get_docstring_indentation_level to be robust
try:
    # NOTE: this import should resolve to the bundled transformers in _internal
    import transformers.utils.doc as _tdoc
    import inspect as _inspect2

    _orig_indent = getattr(_tdoc, "get_docstring_indentation_level", None)

    def _patched_get_docstring_indentation_level(fn):
        try:
            src = _inspect2.getsource(fn) or "pass\n"
            lines = src.splitlines()
            if not lines:
                return 0
            first_line = lines[0]
            return len(first_line) - len(first_line.lstrip(" "))
        except Exception:
            # fallback: no indentation
            return 0

    _tdoc.get_docstring_indentation_level = _patched_get_docstring_indentation_level
except Exception:
    pass
# --- end hotfix ---

if "typeguard" not in sys.modules:
    tg = types.ModuleType("typeguard")
    tgd = types.ModuleType("typeguard._decorators")

    def _typechecked(func=None, **kwargs):
        # supports both @typechecked and @typechecked(...)
        if callable(func):
            return func
        def deco(f):
            return f
        return deco

    # expose decorator in both modules
    tg.typechecked = _typechecked
    tgd.typechecked = _typechecked

    # instrument no-op (safety)
    def _instrument(func, *a, **k):
        return func
    tgd.instrument = _instrument

    sys.modules["typeguard"] = tg
    sys.modules["typeguard._decorators"] = tgd
# --- end hotfix ---


import json
import os
import time
import uuid
import traceback
import builtins
import pydoc
import datetime
import threading
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# --- debug switch ---
DEBUG = os.getenv("VOICEGER_DEBUG", "0") == "1"
QUIET = os.getenv("VOICEGER_QUIET", "1") == "1"
PERF = os.getenv("VOICEGER_PERF", "0") == "1"

def dperf(msg: str):
    if PERF:
        builtins.print(msg, file=sys.__stdout__, flush=True)

def dprint(*args, **kwargs):
    if not DEBUG:
        return
    if "flush" not in kwargs:
        kwargs["flush"] = True
    if "file" not in kwargs:
        kwargs["file"] = sys.__stdout__
    builtins.print(*args, **kwargs)

@contextmanager
def _quiet_io(enabled: bool):
    if not enabled:
        yield
        return
    import io as _io
    _out, _err = sys.stdout, sys.stderr
    try:
        sys.stdout = _io.StringIO()
        sys.stderr = _io.StringIO()
        yield
    finally:
        sys.stdout, sys.stderr = _out, _err

# --- force UTF-8 on Windows console / pipes (avoid cp932 BOM issues) ---
try:
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.stderr.reconfigure(encoding="utf-8", errors="backslashreplace")
except Exception:
    pass

HOST = "127.0.0.1"
PORT = 8501

# --- workaround: typeguard/inspect.getsource crash in frozen/embedded env (g2p_en/inflect) ---
# inflect imports typeguard and applies @typechecked, which may call inspect.getsource() and crash.
try:
    import typeguard  # noqa
    import typeguard._decorators as _tgd  # noqa

    def _typechecked(func=None, **kwargs):
        # supports both @typechecked and @typechecked(...)
        if callable(func):
            return func
        def deco(f):
            return f
        return deco

    # make decorators no-op
    _tgd.typechecked = _typechecked
    try:
        typeguard.typechecked = _typechecked
    except Exception:
        pass

    # also disable instrumentation path (extra safety)
    def _instrument(func, *a, **k):
        return func
    _tgd.instrument = _instrument

    dprint("[boot] patched typeguard decorators -> no-op")
except Exception as _e:
    dprint("[boot] typeguard patch skipped:", repr(_e))

try:
    import inspect as _inspect
    _orig_getsource = _inspect.getsource
    def _safe_getsource(obj):
        try:
            return _orig_getsource(obj)
        except Exception:
            return ""
    _inspect.getsource = _safe_getsource
except Exception:
    pass


_loaded_sid = None
_vc_lock = threading.Lock()

p = os.path.join(os.path.dirname(__file__), "voiceger_boot.log")
with open(p, "a", encoding="utf-8") as f:
    f.write(f"[{datetime.datetime.now()}] CWD={os.getcwd()} weights={os.path.abspath('assets/weights')}\n")

# --- embedded python: make "help" available (site injects it) ---
try:
    import site  # noqa
except Exception:
    pass

# --- numba/librosa cache workaround ---
os.environ.setdefault("NUMBA_DISABLE_CACHING", "1")
os.environ.setdefault("NUMBA_DISABLE_JIT", os.getenv("VOICEGER_NUMBA_JIT", "0") != "1" and "1" or "0")

# --- HARD fallback: neutralize numba JIT (embeddable python safe mode) ---
try:
    # must be BEFORE importing libs that define @njit functions used later
    import numba  # noqa

    # hard-disable in runtime config too
    try:
        numba.config.DISABLE_JIT = 1
    except Exception:
        pass

    def _no_jit(*args, **kwargs):
        def deco(f):
            return f
        return deco

    # override common decorators
    numba.jit = _no_jit
    numba.njit = _no_jit
    numba.vectorize = _no_jit
    numba.guvectorize = _no_jit

    # also patch internal decorator entrypoints (some libs import from here)
    try:
        import numba.core.decorators as _nbd  # noqa
        _nbd.jit = _no_jit
        _nbd.njit = _no_jit
        _nbd.vectorize = _no_jit
        _nbd.guvectorize = _no_jit
    except Exception:
        pass

    dprint("[tts] numba JIT neutralized", flush=True)
except Exception as _e:
    dprint("[tts] numba patch skipped:", repr(_e))

# ------------------------------------------------------------
# Paths
# ------------------------------------------------------------
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))   # VOICEGER_DIR
INTERNAL = os.path.join(ROOT, "_internal")
V2 = os.path.join(ROOT, "voiceger_v2")


# embedded python help
if not hasattr(builtins, "help"):
    builtins.help = pydoc.help
if not hasattr(builtins, "exit"):
    builtins.exit = lambda *a, **k: None
if not hasattr(builtins, "quit"):
    builtins.quit = lambda *a, **k: None

def pick_model_file(dir_path: str, exts, preferred_contains=None):
    if not dir_path or not os.path.isdir(dir_path):
        return None
    files = []
    for name in os.listdir(dir_path):
        p = os.path.join(dir_path, name)
        if os.path.isfile(p) and any(name.lower().endswith(e) for e in exts):
            files.append(p)
    if not files:
        return None
    # 優先キーワードがあればそれを優先
    if preferred_contains:
        for kw in preferred_contains:
            for p in files:
                if kw.lower() in os.path.basename(p).lower():
                    return p
    return sorted(files)[0]

def pick_existing(*cands):
    for p in cands:
        if p and os.path.exists(p):
            return p
    return cands[0] if cands else None

# GPT-SoVITS
SOVITS_DIR = pick_existing(
    os.path.join(ROOT, "GPT-SoVITS"),
    os.path.join(V2, "GPT-SoVITS"),
)

RVC_DIR = os.path.join(SOVITS_DIR, "Retrieval-based-Voice-Conversion-WebUI") if SOVITS_DIR else None

# reference
REFERENCE_DIR = pick_existing(
    os.path.join(ROOT, "reference"),
    os.path.join(V2, "reference"),
)

DEFAULT_REF_WAV  = os.path.join(REFERENCE_DIR, "reference.wav") if REFERENCE_DIR else None
DEFAULT_REF_TEXT = os.path.join(REFERENCE_DIR, "ref_text.txt") if REFERENCE_DIR else None
REF16 = os.path.join(REFERENCE_DIR, "reference_16k.wav") if REFERENCE_DIR else None
if REF16 and os.path.exists(REF16):
    DEFAULT_REF_WAV = REF16

def get_work_directory():
    local_appdata = os.getenv("LOCALAPPDATA")
    if local_appdata:
        work_dir = os.path.join(local_appdata, "voiceger")
    else:
        work_dir = os.path.join(os.getenv("TEMP", "."), "voiceger_temp")
    output_dir = os.path.join(work_dir, "output")
    os.makedirs(output_dir, exist_ok=True)
    return work_dir

WORK_DIR = get_work_directory()
OUTPUT_DIR = os.path.join(WORK_DIR, "output")

# --- numba/librosa cache workaround for embeddable python ---
os.environ.setdefault("NUMBA_DISABLE_JIT", os.getenv("VOICEGER_NUMBA_JIT", "0") != "1" and "1" or "0")
os.environ.setdefault("NUMBA_CACHE_DIR", os.path.join(WORK_DIR, "numba_cache"))
os.makedirs(os.environ["NUMBA_CACHE_DIR"], exist_ok=True)

def sanitize_filename(name: str, suffix: str = ".wav") -> str:
    invalid_chars = '<>:"/\\|?*'
    cleaned = "".join(c for c in name if c not in invalid_chars)
    cleaned = cleaned.strip()[:64] or "output"
    return f"{cleaned}_{int(time.time())}_{uuid.uuid4().hex[:8]}{suffix}"

def _norm_lang_code(x: str) -> str:
    if not x:
        return "ja"
    l = str(x).strip().lower()
    if l.startswith("all_"):
        l = l[4:]
    if l in ("ja", "jp", "jpn"):
        return "ja"
    if l in ("en", "eng"):
        return "en"
    if l in ("zh", "zh-cn", "zh-hans", "cn", "zho"):
        return "zh"
    if l in ("ko", "kr", "kor"):
        return "ko"
    if l in ("yue", "cantonese", "hk"):
        return "yue"
    if l == "auto":
        return "ja"  # autoは後回し。今は日本語に倒す
    return x

# ------------------------------------------------------------
# sys.path / env
# ------------------------------------------------------------
for p in [INTERNAL, ROOT, V2]:
    if p and os.path.isdir(p) and p not in sys.path:
        sys.path.insert(0, p)

if SOVITS_DIR and os.path.isdir(SOVITS_DIR):
    if SOVITS_DIR not in sys.path:
        sys.path.insert(0, SOVITS_DIR)
    gpt_sovits_pkg = os.path.join(SOVITS_DIR, "GPT_SoVITS")
    if os.path.isdir(gpt_sovits_pkg) and gpt_sovits_pkg not in sys.path:
        sys.path.append(gpt_sovits_pkg)

if RVC_DIR and os.path.isdir(RVC_DIR) and RVC_DIR not in sys.path:
    sys.path.insert(0, RVC_DIR)

GPT_MODEL_PATH = pick_model_file(os.path.join(SOVITS_DIR, "GPT_weights_v2"), [".ckpt"], preferred_contains=["zudamon", "zundamon", "zunko"])
SOVITS_MODEL_PATH = pick_model_file(os.path.join(SOVITS_DIR, "SoVITS_weights_v2"), [".pth"], preferred_contains=["zudamon", "zundamon", "zunko"])

if GPT_MODEL_PATH:   os.environ["gpt_path"] = os.path.abspath(GPT_MODEL_PATH)
if SOVITS_MODEL_PATH: os.environ["sovits_path"] = os.path.abspath(SOVITS_MODEL_PATH)
try:
    p = os.path.join(os.path.dirname(__file__), "voiceger_boot.log")
    with open(p, "a", encoding="utf-8") as f:
        f.write(f"GPT_MODEL_PATH={GPT_MODEL_PATH} exists={bool(GPT_MODEL_PATH and os.path.exists(GPT_MODEL_PATH))}\n")
        f.write(f"SOVITS_MODEL_PATH={SOVITS_MODEL_PATH} exists={bool(SOVITS_MODEL_PATH and os.path.exists(SOVITS_MODEL_PATH))}\n")
except Exception:
    pass

# pretrained / rmvpe / g2pw
PRETRAINED_DIR = os.path.join(SOVITS_DIR, "GPT_SoVITS", "pretrained_models") if SOVITS_DIR else None
BERT_DIR = os.path.join(PRETRAINED_DIR, "chinese-roberta-wwm-ext-large") if PRETRAINED_DIR else None
CNHUBERT_DIR = os.path.join(PRETRAINED_DIR, "chinese-hubert-base") if PRETRAINED_DIR else None
G2PW_MODEL_DIR = os.path.join(SOVITS_DIR, "GPT_SoVITS", "text", "G2PWModel") if SOVITS_DIR else None
RMVPE_ROOT = os.path.join(RVC_DIR, "assets", "rmvpe") if RVC_DIR else None

if BERT_DIR and os.path.exists(BERT_DIR): os.environ["bert_path"] = os.path.abspath(BERT_DIR)
if CNHUBERT_DIR and os.path.exists(CNHUBERT_DIR): os.environ["cnhubert_base_path"] = os.path.abspath(CNHUBERT_DIR)
if G2PW_MODEL_DIR and os.path.exists(G2PW_MODEL_DIR): os.environ["G2PW_MODEL_DIR"] = os.path.abspath(G2PW_MODEL_DIR)
if RMVPE_ROOT and os.path.exists(RMVPE_ROOT): os.environ["rmvpe_root"] = os.path.abspath(RMVPE_ROOT)

# --- perf-critical: choose half precision automatically when CUDA is available ---
# CUDA初期化が「古いドライバ」等で警告/例外になる環境では、CPUへ強制フォールバックする
try:
    import torch
    import warnings

    _cuda = False
    _cuda_warn = ""

    # torch.cuda.is_available() が「警告だけ出して False を返す」ケースも拾う
    with warnings.catch_warnings(record=True) as _ws:
        warnings.simplefilter("always")
        try:
            _cuda = bool(torch.cuda.is_available())
        except Exception as _e:
            _cuda = False
            _cuda_warn = repr(_e)

        # 代表的な警告（ドライバ古い / CUDA init失敗）を検知したら CUDA を無効化
        try:
            for _w in _ws:
                _m = str(getattr(_w, "message", _w))
                if ("CUDA initialization" in _m) or ("driver on your system is too old" in _m) or ("too old" in _m and "NVIDIA" in _m):
                    _cuda = False
                    _cuda_warn = _m
                    break
        except Exception:
            pass

    if not _cuda and _cuda_warn:
        # 以降の処理でCUDA分岐に入らないように完全遮断
        os.environ["CUDA_VISIBLE_DEVICES"] = ""
        dprint("[boot] CUDA unusable -> force CPU:", _cuda_warn)

    os.environ["is_half"] = "True" if _cuda else "False"

    # optional: some torch perf toggles
    if _cuda:
        try:
            torch.backends.cudnn.benchmark = True
        except Exception:
            pass

    # boot info (only when PERF or DEBUG)
    if os.getenv("VOICEGER_PERF", "0") == "1" or os.getenv("VOICEGER_DEBUG", "0") == "1":
        dev = ""
        try:
            dev = torch.cuda.get_device_name(0) if _cuda else "CPU"
        except Exception:
            dev = "CPU"
        builtins.print(
            f"[boot] torch={getattr(torch,'__version__','?')} cuda={_cuda} dev={dev} is_half={os.environ.get('is_half')}",
            file=sys.__stdout__, flush=True
        )

except Exception as _e:
    # torch自体が死んでも落とさずCPUへ
    os.environ["CUDA_VISIBLE_DEVICES"] = ""
    os.environ["is_half"] = "False"
    if os.getenv("VOICEGER_PERF", "0") == "1" or os.getenv("VOICEGER_DEBUG", "0") == "1":
        builtins.print(f"[boot] torch import failed -> force CPU is_half=False err={_e!r}", file=sys.__stdout__, flush=True)
# --- end ---

# --- CPU latency tuning (safe even on CUDA; mostly affects CPU ops) ---
try:
    import torch
    # 環境変数で上書きできるようにする（未指定なら控えめ固定）
    _t = int(os.getenv("VOICEGER_TORCH_THREADS", "8"))
    _it = int(os.getenv("VOICEGER_TORCH_INTEROP", "1"))
    torch.set_num_threads(_t)
    torch.set_num_interop_threads(_it)
except Exception:
    pass
# --- end ---

# ffmpeg.exe
def add_ffmpeg_to_path():
    for base in [SOVITS_DIR, ROOT]:
        if base and os.path.exists(os.path.join(base, "ffmpeg.exe")):
            os.environ["PATH"] = base + os.pathsep + os.environ.get("PATH", "")
            return base
    return None

FFMPEG_DIR = add_ffmpeg_to_path()

# ------------------------------------------------------------
# TTS helpers (GPT-SoVITS)
# ------------------------------------------------------------
_tts_lock = threading.Lock()
_tts_ready = False
_i18n = None
_get_tts_wav = None

def _map_lang(lang: str) -> str:
    # GPT-SoVITS inference_webui expects these KEYS:
    # Chinese / English / Japanese / Cantonese / Korean / ... Mixed / Multilingual Mixed
    if not lang:
        return "Japanese"

    s = str(lang).strip()
    l = s.lower()

    valid = {
        "Chinese", "English", "Japanese", "Cantonese", "Korean",
        "Chinese-English Mixed", "Japanese-English Mixed", "Cantonese-English Mixed", "Korean-English Mixed",
        "Multilingual Mixed", "Multilingual Mixed (Cantonese)"
    }
    if s in valid:
        return s

    # common aliases -> valid keys
    if l in ("ja", "jp", "japanese") or s in ("日本語", "日文", "日?"):
        return "Japanese"
    if l in ("en", "english") or s in ("英語", "英文", "英?"):
        return "English"
    if l in ("zh", "zh-cn", "zh-hans", "cn", "chinese") or s in ("中国語", "中文", "?体中文", "繁體中文", "繁体中文"):
        return "Chinese"
    if l in ("ko", "kr", "korean") or s in ("韓国語", "?文", "???"):
        return "Korean"
    if l in ("yue", "cantonese") or s in ("広東語", "粤?"):
        return "Cantonese"

    # mixed (optional)
    if "ja" in l and "en" in l:
        return "Japanese-English Mixed"
    if "zh" in l and "en" in l:
        return "Chinese-English Mixed"
    if "ko" in l and "en" in l:
        return "Korean-English Mixed"
    if "yue" in l and "en" in l:
        return "Cantonese-English Mixed"

    return "Japanese"



# ------------------------------------------------------------
# TTS helpers (GPT-SoVITS)
# ------------------------------------------------------------
_tts_lock = threading.Lock()
_tts_ready = False
_iw = None  # inference_webui module

def ensure_tts_loaded():
    global _tts_ready, _iw
    if _tts_ready:
        return
    with _tts_lock:
        if _tts_ready:
            return
        try:
            import numba  # noqa: F401
        except Exception:
            pass

        # --- force numba safe mode (must be BEFORE importing libs that use numba) ---
        os.environ["NUMBA_DISABLE_CACHING"] = "1"
        os.environ["NUMBA_DISABLE_JIT"] = "1"
        # librosa sometimes reads this too
        os.environ.setdefault("NUMBA_CACHE_DIR", os.path.join(WORK_DIR, "numba_cache"))
        os.makedirs(os.environ["NUMBA_CACHE_DIR"], exist_ok=True)

        if not SOVITS_DIR or not os.path.isdir(SOVITS_DIR):
            raise FileNotFoundError(f"SOVITS_DIR not found: {SOVITS_DIR}")
        if not (_exists(GPT_MODEL_PATH) and _exists(SOVITS_MODEL_PATH)):
            raise FileNotFoundError("GPT/SoVITS model not found. Check GPT_MODEL_PATH / SOVITS_MODEL_PATH.")

        # --- avoid resampy+numba crash: patch librosa.core.audio.resample ---
        try:
            import librosa  # noqa
            import numpy as np  # noqa

            try:
                from scipy.signal import resample_poly  # noqa

                def _safe_resample_audio(y, orig_sr, target_sr, **kwargs):
                    if orig_sr == target_sr:
                        return y
                    from math import gcd
                    g = gcd(int(orig_sr), int(target_sr))
                    up = int(target_sr // g)
                    down = int(orig_sr // g)
                    return resample_poly(np.asarray(y), up, down)

                # IMPORTANT: patch the function actually used by librosa.load() path
                import librosa.core.audio as _lca  # noqa
                _lca.resample = _safe_resample_audio

                # (optional) also patch alias
                librosa.resample = _safe_resample_audio

                dprint("[tts] patched librosa.core.audio.resample (scipy resample_poly)", flush=True)

            except Exception as _e:
                # If scipy not present, try a pure numpy fallback (slower but avoids numba)
                import librosa.core.audio as _lca  # noqa

                def _safe_resample_linear(y, orig_sr, target_sr, **kwargs):
                    if orig_sr == target_sr:
                        return y
                    y = np.asarray(y)
                    n = int(round(len(y) * (float(target_sr) / float(orig_sr))))
                    if n <= 1:
                        return y[:1]
                    x_old = np.linspace(0.0, 1.0, num=len(y), endpoint=False)
                    x_new = np.linspace(0.0, 1.0, num=n, endpoint=False)
                    return np.interp(x_new, x_old, y).astype(y.dtype, copy=False)

                _lca.resample = _safe_resample_linear
                librosa.resample = _safe_resample_linear

                dprint("[tts] patched librosa.core.audio.resample (numpy interp fallback)", flush=True)

        except Exception as _e:
            dprint("[tts] librosa resample patch skipped:", repr(_e), flush=True)

        with pushd(SOVITS_DIR):
            import GPT_SoVITS.inference_webui as iw
            _iw = iw
            # ensure i18n callable
            try:
                if not callable(getattr(iw, "i18n", None)):
                    from tools.i18n.i18n import I18nAuto
                    iw.i18n = I18nAuto()
            except Exception:
                pass


            # モデルロード（初回のみ）
            _iw.change_gpt_weights(gpt_path=os.path.abspath(GPT_MODEL_PATH))
            _iw.change_sovits_weights(sovits_path=os.path.abspath(SOVITS_MODEL_PATH))

        _tts_ready = True


# ------------------------------------------------------------
# VC helpers
# ------------------------------------------------------------
@contextmanager
def pushd(new_dir):
    prev = os.getcwd()
    os.chdir(new_dir)
    try:
        yield
    finally:
        os.chdir(prev)

def ensure_rvc_env():
    os.environ["weight_root"] = os.path.join(RVC_DIR, "assets", "weights")
    os.environ["index_root"] = os.path.join(RVC_DIR, "assets", "indices")
    os.environ["outside_index_root"] = os.path.join(RVC_DIR, "assets", "indices")

def ensure_hubert_asset():
    hubert_path = os.path.join(RVC_DIR, "assets", "hubert", "hubert_base.pt")
    if not os.path.exists(hubert_path):
        raise FileNotFoundError(f"HuBERT model not found: {hubert_path}")

_vc_model = None

def get_vc_model():
    global _vc_model
    if _vc_model is None:
        ensure_rvc_env()
        ensure_hubert_asset()
        with pushd(RVC_DIR):
            from configs.config import Config
            from infer.modules.vc.modules import VC
            cfg = Config()
            _vc_model = VC(cfg)
    return _vc_model

# ------------------------------------------------------------
# VC load cache (speed)
# ------------------------------------------------------------
F0_DEFAULT = os.environ.get("VOICEGER_F0_METHOD", "pm")          # fast default: pm
INDEX_RATE_DEFAULT = float(os.environ.get("VOICEGER_INDEX_RATE", "0.0"))  # fast default: 0.0 (disable index)

_index_cache = {}  # sid -> file_index str (optional)

def ensure_vc_loaded(vc_model, sid: str):
    global _loaded_sid
    with _vc_lock:
        if _loaded_sid != sid:
            t0 = time.perf_counter()
            vc_model.get_vc(sid)   # heavy: load weights
            _loaded_sid = sid
            dprint(f"[VG] model_loaded sid={sid} ms={(time.perf_counter()-t0)*1000:.0f}", flush=True)

def get_file_index_for_sid(get_index_path_from_model, sid: str, index_rate: float) -> str:
    # index is optional; skip if disabled
    if index_rate <= 0.0:
        return ""
    cached = _index_cache.get(sid)
    if cached and os.path.exists(str(cached)):
        return cached
    try:
        p = get_index_path_from_model(sid)
        if p is None:
            return ""
        p = str(p)
        if os.path.exists(p):
            _index_cache[sid] = p
            return p
    except Exception:
        pass
    return ""

def pick_default_sid():
    weights_dir = os.path.join(RVC_DIR, "assets", "weights")
    preferred = os.path.join(weights_dir, "train-0814-2.pth")
    if os.path.exists(preferred):
        return os.path.basename(preferred)
    if os.path.exists(weights_dir):
        files = [f for f in os.listdir(weights_dir) if f.endswith(".pth")]
        if files:
            return sorted(files)[0]
    raise FileNotFoundError("No RVC model file found in assets/weights/")

# ------------------------------------------------------------
# HTTP server utils
# ------------------------------------------------------------
def _send(handler, code, body, ctype="application/json; charset=utf-8"):
    if isinstance(body, (dict, list)):
        body = json.dumps(body, ensure_ascii=False).encode("utf-8")
    elif isinstance(body, str):
        body = body.encode("utf-8")
    handler.send_response(code)
    handler.send_header("Content-Type", ctype)
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    try:
        handler.wfile.write(body)
    except (BrokenPipeError, ConnectionAbortedError):
        return

def _read_json(handler):
    length = int(handler.headers.get("Content-Length", "0"))
    raw = handler.rfile.read(length) if length > 0 else b"{}"
    return json.loads(raw.decode("utf-8"))

def _exists(p): return bool(p and os.path.exists(p))

class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        return

    def do_GET(self):
        if self.path == "/" or self.path.startswith("/health"):
            return _send(self, 200, {
                "status": "ok",
                "server": "voiceger_api_stdlib",
                "root": ROOT,
                "work_dir": WORK_DIR,
                "output_dir": OUTPUT_DIR,
                "sovits_dir": SOVITS_DIR,
                "rvc_dir": RVC_DIR,
                "ffmpeg_dir": FFMPEG_DIR,
                "endpoints": ["/health", "/probe", "/vc/single", "/vc/bytes", "/tts"],
            })

        if self.path.startswith("/probe"):
            info = {
                "ok": True,
                "root": ROOT,
                "internal": INTERNAL,
                "v2": V2,
                "sovits_dir": SOVITS_DIR,
                "rvc_dir": RVC_DIR,
                "exists": {
                    "GPT_MODEL_PATH": _exists(GPT_MODEL_PATH),
                    "SOVITS_MODEL_PATH": _exists(SOVITS_MODEL_PATH),
                    "BERT_DIR": _exists(BERT_DIR),
                    "CNHUBERT_DIR": _exists(CNHUBERT_DIR),
                    "G2PW_MODEL_DIR": _exists(G2PW_MODEL_DIR),
                    "RMVPE_ROOT": _exists(RMVPE_ROOT),
                    "DEFAULT_REF_WAV": _exists(DEFAULT_REF_WAV),
                    "DEFAULT_REF_TEXT": _exists(DEFAULT_REF_TEXT),
                },
                "sys_path_head": sys.path[:10],
            }
            # import
            try:
                with pushd(RVC_DIR):
                    from configs.config import Config  # noqa
                    from infer.modules.vc.modules import VC  # noqa
                info["imports"] = {"rvc": True}
            except Exception as e:
                info["imports"] = {"rvc": False, "error": repr(e)}
            return _send(self, 200, info)

        return _send(self, 404, {"error": "not found", "path": self.path})

    def do_POST(self):
        if self.path.startswith("/vc/single"):
            try:
                req = _read_json(self)
                input_audio_path = req.get("input_audio_path")
                f0_method = req.get("f0_method", F0_DEFAULT)
                index_rate = float(req.get("index_rate", INDEX_RATE_DEFAULT))
                out_dir = os.path.abspath(req.get("output_dir") or OUTPUT_DIR)
                sid = req.get("sid")

                if not input_audio_path:
                    return _send(self, 400, {"message": "error", "detail": "input_audio_path is required"})

                input_path = os.path.abspath(input_audio_path)
                if not os.path.exists(input_path):
                    return _send(self, 400, {"message": "error", "detail": f"Input audio not found: {input_path}"})

                os.makedirs(out_dir, exist_ok=True)

                # soundfile
                try:
                    import soundfile as sf
                except Exception as e:
                    return _send(self, 500, {"message": "error", "detail": f"soundfile not available: {e}"})

                # example/voiceger_api.py
                ensure_rvc_env()
                ensure_hubert_asset()

                with pushd(RVC_DIR):
                    from infer.modules.vc.utils import get_index_path_from_model

                    sid = sid or pick_default_sid()
                    vc_model = get_vc_model()

                    # Load model (cached)
                    ensure_vc_loaded(vc_model, sid)

                    # index path (cached / optional)
                    file_index = get_file_index_for_sid(get_index_path_from_model, sid, index_rate)

                    ret = vc_model.vc_single(
                        sid=0,
                        input_audio_path=input_path,
                        f0_up_key=0,
                        f0_file=None,
                        f0_method=f0_method,
                        file_index=file_index,
                        file_index2=file_index,
                        index_rate=index_rate,
                        filter_radius=3,
                        resample_sr=0,
                        rms_mix_rate=0.25,
                        protect=0.33,
                    )

                    # return format compatibility
                    if isinstance(ret, tuple):
                        if len(ret) == 3:
                            info, opt, _download_path = ret
                        elif len(ret) == 2:
                            info, opt = ret
                        else:
                            info, opt = "", None
                    else:
                        info, opt = str(ret), None

                    if not isinstance(opt, tuple):
                        raise RuntimeError(f"VC failed: Invalid return format, info={info}")

                    sr, audio = opt
                    if sr is None or audio is None:
                        raise RuntimeError(f"VC failed: {info}")

                    base = os.path.splitext(os.path.basename(input_path))[0]
                    outfile = os.path.join(out_dir, sanitize_filename(base, ".wav"))
                    sf.write(outfile, audio, sr)

                return _send(self, 200, {
                    "message": "success",
                    "info": str(info),
                    "file_path": outfile,
                    "sampling_rate": sr,
                    "sid": sid,
                })

            except Exception as e:
                return _send(self, 500, {"message": "error", "detail": str(e), "trace": traceback.format_exc()})
        if self.path.startswith("/vc/bytes"):
            try:
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length) if length > 0 else b""

                if not raw:
                    return _send(self, 400, {"message": "error", "detail": "empty body (audio/wav expected)"})

                # temp input
                in_name = f"in_{int(time.time())}_{uuid.uuid4().hex[:8]}.wav"
                in_path = os.path.join(WORK_DIR, in_name)
                with open(in_path, "wb") as f:
                    f.write(raw)

                # soundfile
                try:
                    import soundfile as sf
                except Exception as e:
                    return _send(self, 500, {"message": "error", "detail": f"soundfile not available: {e}"})

                ensure_rvc_env()
                ensure_hubert_asset()

                with pushd(RVC_DIR):
                    from infer.modules.vc.utils import get_index_path_from_model

                    sid = pick_default_sid()
                    vc_model = get_vc_model()

                    # Load model (cached)
                    ensure_vc_loaded(vc_model, sid)

                    # fast defaults for bytes endpoint (can be overridden by env)
                    f0_method = os.environ.get("VOICEGER_F0_METHOD_BYTES", F0_DEFAULT)
                    index_rate = float(os.environ.get("VOICEGER_INDEX_RATE_BYTES", str(INDEX_RATE_DEFAULT)))

                    file_index = get_file_index_for_sid(get_index_path_from_model, sid, index_rate)

                    ret = vc_model.vc_single(
                        sid=0,
                        input_audio_path=os.path.abspath(in_path),
                        f0_up_key=0,
                        f0_file=None,
                        f0_method=f0_method,
                        file_index=file_index,
                        file_index2=file_index,
                        index_rate=index_rate,
                        filter_radius=3,
                        resample_sr=0,
                        rms_mix_rate=0.25,
                        protect=0.33,
                    )

                    if isinstance(ret, tuple):
                        if len(ret) == 3:
                            info, opt, _download_path = ret
                        elif len(ret) == 2:
                            info, opt = ret
                        else:
                            info, opt = "", None
                    else:
                        info, opt = str(ret), None

                    if not isinstance(opt, tuple):
                        raise RuntimeError(f"VC failed: Invalid return format, info={info}")

                    sr, audio = opt
                    if sr is None or audio is None:
                        raise RuntimeError(f"VC failed: {info}")

                    import io
                    bio = io.BytesIO()
                    sf.write(bio, audio, sr, format="WAV")
                    out_bytes = bio.getvalue()

                try:
                    os.remove(in_path)
                except Exception:
                    pass

                # bytes
                self.send_response(200)
                self.send_header("Content-Type", "audio/wav")
                self.send_header("Content-Length", str(len(out_bytes)))
                self.end_headers()
                self.wfile.write(out_bytes)
                return

            except Exception as e:
                return _send(self, 500, {"message": "error", "detail": str(e), "trace": traceback.format_exc()})

        if self.path.startswith("/tts"):
            try:
                t0 = time.perf_counter()

                req = _read_json(self)
                text = (req.get("text") or "").strip()
                if not text:
                    return _send(self, 400, {"message": "error", "detail": "text is required"})

                # language: "ja/en/zh/ko" も "日文/英文/中文/?文" もOK
                text_lang = _norm_lang_code(req.get("language") or req.get("text_language") or "ja")

                # reference (prompt)
                ref_wav = req.get("ref_wav") or DEFAULT_REF_WAV
                ref_text_path = req.get("ref_text_path") or DEFAULT_REF_TEXT
                ref_lang_raw = req.get("ref_language") or req.get("prompt_language") or "all_ja"
                ref_lang = _norm_lang_code(ref_lang_raw)
                dprint(f"[tts] ref_language(raw)={ref_lang_raw} -> norm={ref_lang} text_lang={text_lang}", flush=True)

                if not ref_wav or not os.path.exists(ref_wav):
                    return _send(self, 500, {"message": "error", "detail": f"ref_wav not found: {ref_wav}"})
                if not ref_text_path or not os.path.exists(ref_text_path):
                    return _send(self, 500, {"message": "error", "detail": f"ref_text not found: {ref_text_path}"})

                with open(ref_text_path, "r", encoding="utf-8-sig") as f:
                    prompt_text = f.read().strip()

                top_p = float(req.get("top_p", 1))
                temperature = float(req.get("temperature", 1))

                # ---- load once ----
                ensure_tts_loaded()

                # ---- pick callable functions safely ----
                if os.getenv("VOICEGER_LOG_LANG", "0") == "1":
                    dprint(f"[tts] lang ref={_map_lang(ref_lang)} text={_map_lang(text_lang)} ref_wav={ref_wav}")

                tts_fn = getattr(_iw, "get_tts_wav", None)
                if not callable(tts_fn):
                    raise RuntimeError("get_tts_wav is not available (None/cannot call).")

                i18n_fn = getattr(_iw, "i18n", None)
                if not callable(i18n_fn):
                    # fallback (should not happen, but keep it robust)
                    try:
                        from tools.i18n.i18n import I18nAuto
                        i18n_fn = I18nAuto()
                    except Exception:
                        i18n_fn = lambda x: x

                t_parse = time.perf_counter()

                # ---- run inference ----
                with pushd(SOVITS_DIR):
                    # try canonical first, then i18n(label)
                    pl_a = _map_lang(ref_lang)       # e.g. "日文"
                    tl_a = _map_lang(text_lang)      # e.g. "日文"
                    pl_b = _iw.i18n(pl_a) if callable(getattr(_iw, "i18n", None)) else pl_a  # e.g. "日本語"
                    tl_b = _iw.i18n(tl_a) if callable(getattr(_iw, "i18n", None)) else tl_a

                    def run_tts(prompt_lang, text_lang):
                      quiet_run = (QUIET and (not DEBUG))  # 普段は黙らせる。デバッグ中は見えるようにする
                      with _quiet_io(quiet_run):
                          gen = tts_fn(
                              ref_wav_path=os.path.abspath(ref_wav),
                              prompt_text=prompt_text,
                              prompt_language=_map_lang(prompt_lang),
                              text=text,
                              text_language=_map_lang(text_lang),
                              top_p=top_p,
                              temperature=temperature,
                          )

                          # --- collect all yielded chunks and concat ---
                          import numpy as np
                          sr0 = None
                          parts = []

                          i = 0
                          for it in gen:
                              i += 1
                              try:
                                  sra, aa = it
                              except Exception:
                                  continue

                              if sr0 is None:
                                  sr0 = int(sra)

                              a = aa
                              try:
                                  if hasattr(a, "detach"):
                                      a = a.detach().cpu().numpy()
                                  a = np.asarray(a)
                              except Exception:
                                  continue

                              parts.append(a)

                      # quietブロックの外：ここから先は最小限だけ（必要ならdprintで）
                      if not parts or sr0 is None:
                          raise RuntimeError("TTS failed: empty result")

                      try:
                          audio = np.concatenate(parts, axis=0)
                      except Exception:
                          audio = np.concatenate([p.reshape(-1) for p in parts], axis=0)

                      return (sr0, audio)

                    try:
                        sr, audio = run_tts(pl_a, tl_a)
                        t_tts = time.perf_counter()
                    except KeyError as e:
                        # Typical: KeyError('日文') or KeyError('日本語')
                        try:
                            sr, audio = run_tts(pl_b, tl_b)
                        except Exception:
                            raise

                # --- build wav bytes safely (force PCM16 mono) ---
                try:
                    _dtype = getattr(getattr(audio, "dtype", None), "name", None) or str(getattr(audio, "dtype", ""))
                    _shape = getattr(audio, "shape", None)
                except Exception:
                    _dtype, _shape = "?", "?"
                dprint(f"[tts] sr={sr} dtype={_dtype} shape={_shape}", flush=True)
                dprint(f"[tts] sr={sr} audio_shape={getattr(__import__('numpy').asarray(audio), 'shape', None)}", flush=True)
                # --- build wav bytes safely (robust PCM16) ---
                out_bytes = None
                try:
                    import io
                    import numpy as np
                    import soundfile as sf

                    a = np.asarray(audio)

                    # torch tensor / list / etc -> numpy
                    if hasattr(a, "detach"):
                        a = a.detach().cpu().numpy()

                    # shape: (n, ch) -> pick ch0 (mean can cause weird phase issues)
                    if a.ndim == 2:
                        a = a[:, 0]

                    # sanitize NaN/Inf
                    a = np.nan_to_num(a, nan=0.0, posinf=0.0, neginf=0.0)

                    # if already int16 PCM, keep it (just in case)
                    if a.dtype == np.int16:
                        a16 = a

                    else:
                        # convert to float32 for safe scaling
                        af = a.astype(np.float32, copy=False)

                        # detect value range:
                        mx = float(np.max(np.abs(af))) if af.size else 0.0
                        if mx <= 0.0:
                            a16 = np.zeros_like(af, dtype=np.int16)
                        else:
                            if mx > 10.0:
                                # probably already in int-like scale
                                af = af / mx
                                mx = 1.0

                            # ★ quiet boost: ほぼ無音のときだけ持ち上げる（上げすぎ防止で上限あり）
                            # 目標ピークを0.20くらいに（十分聞こえる＆破綻しにくい）
                            if mx < 0.05:
                                gain = min(20.0, 0.20 / max(mx, 1e-6))
                                af = af * gain
                                mx = float(np.max(np.abs(af))) if af.size else mx

                            af = np.clip(af, -1.0, 1.0)
                            af = af * 0.95  # headroom

                            a16 = (af * 32767.0).astype(np.int16)
                    bio = io.BytesIO()
                    try:
                        import numpy as np
                        _n = int(len(a16)) if hasattr(a16, "__len__") else -1
                        _sec = (_n / float(sr)) if (_n > 0 and sr) else -1
                        _mn = int(np.min(a16)) if _n > 0 else 0
                        _mx = int(np.max(a16)) if _n > 0 else 0
                        dprint(f"[tts] pcm16 n={_n} sec~={_sec:.3f} min={_mn} max={_mx}", flush=True)
                    except Exception:
                        pass

                    sf.write(bio, a16, int(sr), format="WAV", subtype="PCM_16")
                    out_bytes = bio.getvalue()
                    dprint(f"[tts] out_bytes_len={0 if out_bytes is None else len(out_bytes)}", flush=True)
                    t_wav = time.perf_counter()
                    dperf(f"[perf] parse={(t_parse-t0)*1000:.0f}ms tts={(t_tts-t_parse)*1000:.0f}ms wav={(t_wav-t_tts)*1000:.0f}ms total={(t_wav-t0)*1000:.0f}ms")

                except Exception as ee:
                    raise RuntimeError(f"Failed to encode wav bytes: {ee}")

                if (not out_bytes) or (len(out_bytes) < 44) or (not out_bytes.startswith(b"RIFF")):
                    raise RuntimeError("TTS produced invalid WAV (no RIFF header)")

                # --- response (only after out_bytes is valid) ---
                self.send_response(200)
                self.send_header("Content-Type", "audio/wav")
                self.send_header("Content-Length", str(len(out_bytes)))
                self.end_headers()
                self.wfile.write(out_bytes)
                return

            except Exception as e:
                tr = traceback.format_exc()
                print("[tts] ERROR:", str(e), flush=True)
                print(tr, flush=True)
                return _send(self, 500, {"message": "error", "detail": str(e), "trace_head": tr[:2000]})


        return _send(self, 404, {"error": "not found", "path": self.path})

def main():
    dprint(f"[voiceger_api_stdlib] http://{HOST}:{PORT}/health", flush=True)
    dprint(f"ROOT={ROOT}", flush=True)
    dprint(f"SOVITS_DIR={SOVITS_DIR}", flush=True)
    dprint(f"RVC_DIR={RVC_DIR}", flush=True)
    srv = ThreadingHTTPServer((HOST, PORT), Handler)
    try:
        ensure_tts_loaded()
        # ほんとに軽いテキストで1回だけ
        # ※ ref_wav / ref_text は存在してる前提
        with pushd(SOVITS_DIR):
            tts_fn = getattr(_iw, "get_tts_wav", None)
            if callable(tts_fn) and DEFAULT_REF_WAV and DEFAULT_REF_TEXT and os.path.exists(DEFAULT_REF_TEXT):
                with open(DEFAULT_REF_TEXT, "r", encoding="utf-8-sig") as f:
                    prompt_text = f.read().strip()
                gen = tts_fn(
                    ref_wav_path=os.path.abspath(DEFAULT_REF_WAV),
                    prompt_text=prompt_text,
                    prompt_language=_map_lang("ja"),
                    text="はい。",
                    text_language=_map_lang("ja"),
                    top_p=1, temperature=1
                )
                _ = list(gen)[-1]
        dprint("[tts] warmup done", flush=True)
    except Exception as e:
        dprint("[tts] warmup skipped:", repr(e), flush=True)
    srv.serve_forever()

if __name__ == "__main__":
    main()
'@ | Set-Content -Encoding UTF8 -Path $apiDst

Write-Host "Wrote: $apiDst"

# --- Embedded Python (portable) _internal\py ---
$pyVer = "3.10.11"
$pyZip = Join-Path $Root "python-$pyVer-embed-amd64.zip"
$pyUrl = "https://www.python.org/ftp/python/$pyVer/python-$pyVer-embed-amd64.zip"
Register-Cleanup $pyZip

$pyDir = Join-Path $internalDir "py"
$pyExe = Join-Path $pyDir "python.exe"

if (!(Test-Path $pyExe)) {
  Write-Host "DL: $pyUrl"
  Download-File $pyUrl $pyZip
  Ensure-Dir $pyDir
  Expand-Archive -Force -Path $pyZip -DestinationPath $pyDir
  try { Unblock-File $pyExe } catch {}
  Write-Host "Embedded python installed: $pyExe"
} else {
  Write-Host "Embedded python already exists: $pyExe"
}

# --- NLTK data (English tagger) provision to _internal\py\nltk_data ---
try {
  $nltkDir = Join-Path $pyDir "nltk_data"
  Ensure-Dir $nltkDir
  Ensure-Dir (Join-Path $nltkDir "taggers")

  # Make sure NLTK downloader (if used) targets our folder.
  $env:NLTK_DATA = $nltkDir

  $needDir = Join-Path $nltkDir "taggers\averaged_perceptron_tagger_eng"
  if (!(Test-Path $needDir)) {
    Write-Host ""
    Write-Host "[NLTK] provisioning resources to: $nltkDir"

    # 1) Try normal nltk.download (may silently fail in some environments)
    & $pyExe -c @"
import os, sys
d = os.environ.get("NLTK_DATA") or r"$nltkDir"
os.makedirs(d, exist_ok=True)
try:
  import nltk
  nltk.download("averaged_perceptron_tagger_eng", download_dir=d, quiet=True)
  nltk.download("punkt", download_dir=d, quiet=True)
  nltk.download("cmudict", download_dir=d, quiet=True)
  print("[NLTK] downloader attempted:", d)
except Exception as e:
  print("[NLTK] downloader exception:", repr(e))
"@

    # 2) Compatibility: sometimes only averaged_perceptron_tagger is created.
    $oldDir = Join-Path $nltkDir "taggers\averaged_perceptron_tagger"
    if ((Test-Path $oldDir) -and !(Test-Path $needDir)) {
      Write-Host "[NLTK] compat: copying averaged_perceptron_tagger -> averaged_perceptron_tagger_eng"
      Copy-Item -Recurse -Force $oldDir $needDir
    }

    # 3) Verify via nltk.data.find (hard check)
    & $pyExe -c @"
import os, sys
d = r"$nltkDir"
try:
  import nltk
  if d not in nltk.data.path:
    nltk.data.path.insert(0, d)
  nltk.data.find("taggers/averaged_perceptron_tagger_eng")
  print("[NLTK] verified OK")
  sys.exit(0)
except Exception as e:
  print("[NLTK] verify FAILED:", repr(e))
  sys.exit(2)
"@
    $verifyOk = ($LASTEXITCODE -eq 0)

    if (-not $verifyOk) {
      Write-Host "[NLTK] downloader path failed; fallback to manual gh-pages.zip fetch..."

      # 4) Fallback: download full nltk_data gh-pages and extract the needed tagger zip.
      # Ref: common workaround when downloader cannot fetch resources. (gh-pages.zip)
      $ghZip = Join-Path $Root "nltk_data-gh-pages.zip"
      $ghUrl = "https://github.com/nltk/nltk_data/archive/refs/heads/gh-pages.zip"
      Register-Cleanup $ghZip
      Download-File $ghUrl $ghZip

      $tmp = Join-Path $Root "_tmp_nltk_data"
      Register-Cleanup $tmp
      Ensure-Dir $tmp
      Expand-Archive -Force -Path $ghZip -DestinationPath $tmp

      # Locate the package zip inside repo: packages/taggers/averaged_perceptron_tagger_eng.zip
      $pkgZip = Get-ChildItem -Path $tmp -Recurse -File -Filter "averaged_perceptron_tagger_eng.zip" |
              Select-Object -First 1

      if ($null -eq $pkgZip) {
        throw "[NLTK] fallback failed: averaged_perceptron_tagger_eng.zip not found in gh-pages.zip"
      }

      $taggersDir = Join-Path $nltkDir "taggers"
      Ensure-Dir $taggersDir
      Expand-Archive -Force -Path $pkgZip.FullName -DestinationPath $taggersDir

      # Verify again
      & $pyExe -c @"
import os, sys
d = r"$nltkDir"
import nltk
if d not in nltk.data.path:
  nltk.data.path.insert(0, d)
nltk.data.find("taggers/averaged_perceptron_tagger_eng")
print("[NLTK] verified OK (fallback)")
"@
    }

    # Final: print what we have (debug)
    if (Test-Path (Join-Path $nltkDir "taggers")) {
      Write-Host "[NLTK] taggers dir:"
      Get-ChildItem (Join-Path $nltkDir "taggers") -ErrorAction SilentlyContinue |
              Select-Object -First 20 | ForEach-Object { Write-Host "  - $($_.Name)" }
    }
  } else {
    Write-Host "[NLTK] already exists: $needDir"
  }

} catch {
  Write-Host "[NLTK] provision skipped/failed: $($_.Exception.Message)"
}
# --- end NLTK data provision ---

# 1) voiceger_v2 ZIP (skip download if already extracted)
$voicegerDir = Join-Path $Root "voiceger_v2"
$voicegerZip = Join-Path $Root "voiceger_v2-main.zip"

# 目安ファイル（展開済み判定用）
$voicegerMarker = Join-Path $Root "GPT-SoVITS\GPT_SoVITS\inference_webui.py"

if (Test-Path $voicegerMarker) {
  Write-Host "[SKIP] voiceger_v2 already extracted: $voicegerDir"
} else {
  Get-GithubZip "zunzun999/voiceger_v2" "main" $voicegerZip
  Register-Cleanup $voicegerZip

  Expand-Zip $voicegerZip $Root
  $extracted = Join-Path $Root "voiceger_v2-main"
  if (Test-Path $extracted) {
    if (Test-Path $voicegerDir) { Remove-Item -Recurse -Force $voicegerDir }
    Rename-Item $extracted "voiceger_v2"
  }
}


# 2) DL
$gptRoot = Join-Path $voicegerDir "GPT-SoVITS"
$pretrained = Join-Path $gptRoot "GPT_SoVITS\pretrained_models"
$g2pwText = Join-Path $gptRoot "GPT_SoVITS\text"
$rvcRoot = Join-Path $gptRoot "Retrieval-based-Voice-Conversion-WebUI"
$hubertDir = Join-Path $rvcRoot "assets\hubert"
$rmvpeDir  = Join-Path $rvcRoot "assets\rmvpe"

Ensure-Dir $pretrained
Ensure-Dir $g2pwText
Ensure-Dir $hubertDir
Ensure-Dir $rmvpeDir

# 2-1) ffmpeg/ffprobe
Download-File "https://huggingface.co/lj1995/VoiceConversionWebUI/resolve/main/ffmpeg.exe"  (Join-Path $gptRoot "ffmpeg.exe")
Download-File "https://huggingface.co/lj1995/VoiceConversionWebUI/resolve/main/ffprobe.exe" (Join-Path $gptRoot "ffprobe.exe")

# 2-2) Hubert / RMVPE
Download-File "https://huggingface.co/lj1995/VoiceConversionWebUI/resolve/main/hubert_base.pt" (Join-Path $hubertDir "hubert_base.pt")
Download-File "https://huggingface.co/lj1995/VoiceConversionWebUI/resolve/main/rmvpe.pt"      (Join-Path $rmvpeDir  "rmvpe.pt")

# 2-3) GPT-SoVITS pretrained_models
Ensure-Dir (Join-Path $pretrained "chinese-roberta-wwm-ext-large")
Ensure-Dir (Join-Path $pretrained "chinese-hubert-base")

# 2-4) G2PWModel?izip?j
# --- skip G2PWModel download/extract if already installed ---
$g2pwFinal = Join-Path $g2pwText "G2PWModel"
$g2pwTest = Join-Path $Root "GPT-SoVITS\GPT_SoVITS\text\G2PWModel"
if (Test-Path $g2pwTest) {
  Write-Host "[SKIP] G2PWModel already exists: $g2pwTest"
} else {
  $g2pwZip = Join-Path $Root "G2PWModel_1.1.zip"
  $g2pwUrl = "https://huggingface.co/L-jasmine/GPT_Sovits/resolve/main/G2PWModel_1.1.zip"
  Register-Cleanup $g2pwZip
  try {
    Download-File $g2pwUrl $g2pwZip
    try { Unblock-File $g2pwZip } catch {}

    Expand-Zip $g2pwZip $g2pwText

    $cand = Get-ChildItem -Path $g2pwText -Directory | Where-Object { $_.Name -like "G2PWModel*" } | Select-Object -First 1
    if (-not $cand) { throw "G2PWModel folder not found after extract: $g2pwText" }

    $g2pwFinal = Join-Path $g2pwText "G2PWModel"
    if (Test-Path $g2pwFinal) { Remove-Item -Recurse -Force $g2pwFinal }
    Rename-Item -Path $cand.FullName -NewName "G2PWModel"

    Write-Host "G2PWModel installed successfully"
  } catch {
    Write-Host "WARNING: G2PWModel download/extract failed."
    Write-Host "  Error: $($_.Exception.Message)"
    Write-Host "  Try manual download and extract to: $g2pwText\G2PWModel"
  }
}
# --- end skip ---

# 2-5) reference
$refDir = Join-Path $voicegerDir "reference"
Ensure-Dir $refDir
# --- write ref_text.txt as UTF-8 (no BOM) ---
$refTextPath = Join-Path $refDir "ref_text.txt"
$refTextValue = "流し切りが完全に入れば、デバフの効果が付与される。"
try {
  [System.IO.File]::WriteAllText($refTextPath, $refTextValue, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host "ref_text.txt updated: $refTextPath"
} catch {
  Write-Host "WARNING: failed to write ref_text.txt ($($_.Exception.Message))"
}

# --- reference optimize (16k mono) ---
$refDir = Join-Path $voicegerDir "reference"
$refWav = Join-Path $refDir "reference.wav"
$ref16k = Join-Path $refDir "reference_16k.wav"

# ffmpeg 探す（GPT-SoVITS配下 or ルート）
$ffmpeg = $null
$ff1 = Join-Path $voicegerDir "GPT-SoVITS\ffmpeg.exe"
$ff2 = Join-Path $voicegerDir "ffmpeg.exe"
if (Test-Path $ff1) { $ffmpeg = $ff1 }
elseif (Test-Path $ff2) { $ffmpeg = $ff2 }

if ($ffmpeg -and (Test-Path $refWav)) {
  Write-Host "[ref] make 16k mono: $ref16k"
  & $ffmpeg -y -hide_banner -loglevel error -i $refWav -ac 1 -ar 16000 $ref16k | Out-Null
  if (Test-Path $ref16k) {
    Write-Host "[ref] ok: reference_16k.wav"
  } else {
    Write-Host "[ref] failed: keep original reference.wav"
  }
} else {
  Write-Host "[ref] ffmpeg or reference.wav not found: skip"
}


Write-Host ''
Write-Host 'DONE (git-less).'
Write-Host "voiceger_v2 folder: $voicegerDir"
Write-Host 'Next: download full pretrained_models (large) separately or via a second script.'
Write-Host "Set MobMate: prefs voiceger.dir = $voicegerDir"


# --- 99) voiceger_v2 Root robocopy ---
$v2 = Join-Path $Root "voiceger_v2"
if (Test-Path $v2) {

  function RoboSync($src, $dst) {
    Ensure-Dir $dst
    & robocopy $src $dst /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
    $code = $LASTEXITCODE
    if ($code -gt 7) { throw "robocopy failed ($code): $src -> $dst" }
  }

  $moveList = @(
    "GPT-SoVITS",
    "reference",
    "LangSegment-0.3.5",
    "nltk_data",
    "imgs",
    "imgs_icons"
  )

  foreach ($name in $moveList) {
    $src = Join-Path $v2 $name
    $dst = Join-Path $Root $name
    if (Test-Path $src) {
      Write-Host "Sync to Root: $name"
      RoboSync $src $dst
      if ($DO_CLEANUP) {
        try { Remove-Item -Recurse -Force $src } catch { Write-Host "  cleanup skip: $src ($($_.Exception.Message))" }
      }

    } else {
      Write-Host "Skip (not found): $src"
    }
  }

  Write-Host "Sync complete: voiceger_v2 -> Root"
} else {
  Write-Host "voiceger_v2 not found, skip sync."
}

# --- 100) RVC Weights (assets/weights) ---
try {
  # RVCの最終配置先（Sync後のRoot優先、無ければvoiceger_v2側）
  $rvcRootFinal = Join-Path $Root "GPT-SoVITS\Retrieval-based-Voice-Conversion-WebUI"
  if (!(Test-Path $rvcRootFinal)) { $rvcRootFinal = $rvcRoot }

  $weightsDir = Join-Path $rvcRootFinal "assets\weights"
  $indicesDir = Join-Path $rvcRootFinal "assets\indices"
  Ensure-Dir $weightsDir
  Ensure-Dir $indicesDir

  # 公式（zundamon_RVC）
  $wUrl = "https://huggingface.co/zunzunpj/zundamon_RVC/resolve/main/zumdaon_rvc_indices_weights/train-0814-2.pth"
  $iUrl = "https://huggingface.co/zunzunpj/zundamon_RVC/resolve/main/zumdaon_rvc_indices_weights/train-0814-2_IVF256_Flat_nprobe_1_train-0814-2_v2.index"

  $wDst = Join-Path $weightsDir "train-0814-2.pth"
  $iDst = Join-Path $indicesDir "train-0814-2_IVF256_Flat_nprobe_1_train-0814-2_v2.index"

  Write-Host ""
  Write-Host "Downloading RVC weights/index to:"
  Write-Host "  weights: $wDst"
  Write-Host "  index  : $iDst"

  Download-File $wUrl $wDst
  Download-File $iUrl $iDst

  Write-Host "RVC weights ready."
} catch {
  Write-Host "WARNING: RVC weights download failed."
  Write-Host "  Error: $($_.Exception.Message)"
  Write-Host "  You can manually put a .pth into: $Root\GPT-SoVITS\Retrieval-based-Voice-Conversion-WebUI\assets\weights"
}

# ------------------------------------------------------------
# (ADD) TTS models for /tts (GPT-SoVITS fine-tuned Zundamon)
#   - GPT_weights_v2/*.ckpt
#   - SoVITS_weights_v2/*.pth
# Source: https://huggingface.co/zunzunpj/zundamon_GPT-SoVITS
# License: https://zunko.jp/con_ongen_kiyaku.html
# ------------------------------------------------------------
try {
  $ttsRoot = Join-Path $Root "GPT-SoVITS"
  $gptDst  = Join-Path $ttsRoot "GPT_weights_v2\zudamon_style_1-e15.ckpt"
  $sovDst  = Join-Path $ttsRoot "SoVITS_weights_v2\zudamon_style_1_e8_s96.pth"

  $gptUrl = "https://huggingface.co/zunzunpj/zundamon_GPT-SoVITS/resolve/main/GPT_weights_v2/zudamon_style_1-e15.ckpt"
  $sovUrl = "https://huggingface.co/zunzunpj/zundamon_GPT-SoVITS/resolve/main/SoVITS_weights_v2/zudamon_style_1_e8_s96.pth"

  Write-Host ""
  Write-Host "Downloading GPT-SoVITS TTS fine-tuned models (for /tts)..."
  Write-Host "  Source : HF zunzunpj/zundamon_GPT-SoVITS"
  Write-Host "  License: https://zunko.jp/con_ongen_kiyaku.html"

  if (!(Test-Path $gptDst)) {
    Write-Host "DL: $gptUrl"
    Download-File $gptUrl $gptDst
    try { Unblock-File $gptDst } catch {}
  } else {
    Write-Host "Already exists: $gptDst"
  }

  if (!(Test-Path $sovDst)) {
    Write-Host "DL: $sovUrl"
    Download-File $sovUrl $sovDst
    try { Unblock-File $sovDst } catch {}
  } else {
    Write-Host "Already exists: $sovDst"
  }

  Write-Host "TTS models ready."
} catch {
  Write-Host "WARNING: TTS models download failed."
  Write-Host "  Error: $($_.Exception.Message)"
  Write-Host "  You can manually put:"
  Write-Host "   - GPT_weights_v2/*.ckpt  into: $Root\GPT-SoVITS\GPT_weights_v2"
  Write-Host "   - SoVITS_weights_v2/*.pth into: $Root\GPT-SoVITS\SoVITS_weights_v2"
}
# ------------------------------------------------------------
# (ADD) GPT-SoVITS pretrained models (required for /tts tokenizer etc.)
#   Place into: GPT-SoVITS\GPT_SoVITS\pretrained_models\
# Source: https://huggingface.co/lj1995/GPT-SoVITS (MIT)
# ------------------------------------------------------------
try {
  $ttsRoot = Join-Path $Root "GPT-SoVITS"
  $pmRoot  = Join-Path $ttsRoot "GPT_SoVITS\pretrained_models"

  # --- chinese-roberta-wwm-ext-large (tokenizer+model) ---
  $robertaDir = Join-Path $pmRoot "chinese-roberta-wwm-ext-large"
  $robertaFiles = @(
    @{ url="https://huggingface.co/lj1995/GPT-SoVITS/resolve/main/chinese-roberta-wwm-ext-large/config.json";         dst=(Join-Path $robertaDir "config.json") },
    @{ url="https://huggingface.co/lj1995/GPT-SoVITS/resolve/main/chinese-roberta-wwm-ext-large/tokenizer.json";      dst=(Join-Path $robertaDir "tokenizer.json") },
    @{ url="https://huggingface.co/lj1995/GPT-SoVITS/resolve/main/chinese-roberta-wwm-ext-large/pytorch_model.bin";   dst=(Join-Path $robertaDir "pytorch_model.bin") }
  )

  # --- chinese-hubert-base ---
  $hubertDir = Join-Path $pmRoot "chinese-hubert-base"
  $hubertFiles = @(
    @{ url="https://huggingface.co/lj1995/GPT-SoVITS/resolve/main/chinese-hubert-base/config.json";                  dst=(Join-Path $hubertDir "config.json") },
    @{ url="https://huggingface.co/lj1995/GPT-SoVITS/resolve/main/chinese-hubert-base/preprocessor_config.json";    dst=(Join-Path $hubertDir "preprocessor_config.json") },
    @{ url="https://huggingface.co/lj1995/GPT-SoVITS/resolve/main/chinese-hubert-base/pytorch_model.bin";           dst=(Join-Path $hubertDir "pytorch_model.bin") }
  )

  # --- gsv-v2final-pretrained ---
  $gsvDir = Join-Path $pmRoot "gsv-v2final-pretrained"
  $gsvFiles = @(
    @{ url="https://huggingface.co/lj1995/GPT-SoVITS/resolve/main/gsv-v2final-pretrained/s1bert25hz-5kh-longer-epoch=12-step=369668.ckpt"; dst=(Join-Path $gsvDir "s1bert25hz-5kh-longer-epoch=12-step=369668.ckpt") },
    @{ url="https://huggingface.co/lj1995/GPT-SoVITS/resolve/main/gsv-v2final-pretrained/s2D2333k.pth";                                                     dst=(Join-Path $gsvDir "s2D2333k.pth") },
    @{ url="https://huggingface.co/lj1995/GPT-SoVITS/resolve/main/gsv-v2final-pretrained/s2G2333k.pth";                                                     dst=(Join-Path $gsvDir "s2G2333k.pth") }
  )

  Write-Host ""
  Write-Host "Downloading GPT-SoVITS pretrained models (for /tts tokenizer & acoustic)..."
  Write-Host "  Source: HF lj1995/GPT-SoVITS (MIT)"

  foreach ($dir in @($robertaDir, $hubertDir, $gsvDir)) {
    if (!(Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
  }

  foreach ($x in ($robertaFiles + $hubertFiles + $gsvFiles)) {
    if (!(Test-Path $x.dst)) {
      Write-Host "DL: $($x.url)"
      Download-File $x.url $x.dst
      try { Unblock-File $x.dst } catch {}
    } else {
      Write-Host "Already exists: $($x.dst)"
    }
  }

  Write-Host "Pretrained models ready: $pmRoot"
} catch {
  Write-Host "WARNING: pretrained models download failed."
  Write-Host "  Error: $($_.Exception.Message)"
  Write-Host "  Need folders under: $Root\GPT-SoVITS\GPT_SoVITS\pretrained_models\"
  Write-Host "   - chinese-roberta-wwm-ext-large (config.json, tokenizer.json, pytorch_model.bin)"
  Write-Host "   - chinese-hubert-base (config.json, preprocessor_config.json, pytorch_model.bin)"
  Write-Host "   - gsv-v2final-pretrained (s1bert*.ckpt, s2D2333k.pth, s2G2333k.pth)"
}

Invoke-Cleanup
