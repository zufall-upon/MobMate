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
import json
import os
import sys
import time
import uuid
import traceback
import builtins
import pydoc
import datetime
import threading
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = 8501

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
os.environ.setdefault("NUMBA_DISABLE_JIT", "1")

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
os.environ.setdefault("NUMBA_DISABLE_JIT", "1")
os.environ.setdefault("NUMBA_CACHE_DIR", os.path.join(WORK_DIR, "numba_cache"))
os.makedirs(os.environ["NUMBA_CACHE_DIR"], exist_ok=True)

def sanitize_filename(name: str, suffix: str = ".wav") -> str:
    invalid_chars = '<>:"/\\|?*'
    cleaned = "".join(c for c in name if c not in invalid_chars)
    cleaned = cleaned.strip()[:64] or "output"
    return f"{cleaned}_{int(time.time())}_{uuid.uuid4().hex[:8]}{suffix}"

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

GPT_MODEL_PATH = os.path.join(SOVITS_DIR, 'GPT_weights_v2', 'zudamon_style_1-e15.ckpt') if SOVITS_DIR else None
SOVITS_MODEL_PATH = os.path.join(SOVITS_DIR, 'SoVITS_weights_v2', 'zudamon_style_1_e8_s96.pth') if SOVITS_DIR else None

if GPT_MODEL_PATH:   os.environ["gpt_path"] = os.path.abspath(GPT_MODEL_PATH)
if SOVITS_MODEL_PATH: os.environ["sovits_path"] = os.path.abspath(SOVITS_MODEL_PATH)

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

os.environ["is_half"] = "False"

# ffmpeg.exe
def add_ffmpeg_to_path():
    for base in [SOVITS_DIR, ROOT]:
        if base and os.path.exists(os.path.join(base, "ffmpeg.exe")):
            os.environ["PATH"] = base + os.pathsep + os.environ.get("PATH", "")
            return base
    return None

FFMPEG_DIR = add_ffmpeg_to_path()

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
            print(f"[VG] model_loaded sid={sid} ms={(time.perf_counter()-t0)*1000:.0f}", flush=True)

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
    handler.wfile.write(body)

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
                "endpoints": ["/health", "/probe", "/vc/single", "/vc/bytes"],
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

        return _send(self, 404, {"error": "not found", "path": self.path})

def main():
    print(f"[voiceger_api_stdlib] http://{HOST}:{PORT}/health", flush=True)
    print(f"ROOT={ROOT}", flush=True)
    print(f"SOVITS_DIR={SOVITS_DIR}", flush=True)
    print(f"RVC_DIR={RVC_DIR}", flush=True)
    srv = ThreadingHTTPServer((HOST, PORT), Handler)
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

# 1) voiceger_v2 ZIP
$voicegerZip = Join-Path $Root "voiceger_v2-main.zip"
Get-GithubZip "zunzun999/voiceger_v2" "main" $voicegerZip
Register-Cleanup $voicegerZip

$voicegerDir = Join-Path $Root "voiceger_v2"
if (!(Test-Path $voicegerDir)) {
  Expand-Zip $voicegerZip $Root
  $extracted = Join-Path $Root "voiceger_v2-main"
  if (Test-Path $extracted) {
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

# 2-5) reference
$refDir = Join-Path $voicegerDir "reference"
Ensure-Dir $refDir
$refTextPath = Join-Path $refDir "ref_text.txt"
if (!(Test-Path $refTextPath)) {
  Set-Content -Encoding UTF8 -Path $refTextPath -Value 'test'
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
Invoke-Cleanup
