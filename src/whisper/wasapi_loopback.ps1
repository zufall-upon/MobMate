param(
    [string]$Device = "",
    [int]$OutRate = 16000,
    [int]$ChunkMs = 20,
    [switch]$AutoPick,
    [int]$ScanMs = 1200,
    [int]$PickThreshold = 200,
    [ValidateSet("render","capture")] [string]$Flow = "render",
    [ValidateSet("peak","raw","pcm")] [string]$Mode = "peak"
)

# stdoutの文字コードを固定（PowerShellの既定UTF-16LE問題を避ける）
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

trap { [Console]::Error.WriteLine("[WASAPI][PCM] FATAL: " + $_); exit 1 }

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ===== helper: log to stderr only =====
function LogErr([string]$s) {
    [Console]::Error.WriteLine("[WASAPI][PCM] $s")
}

# ===== load NAudio (robust) =====
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

# 探索候補：ps1と同階層＋よくあるサブフォルダ（必要なら増やしてOK）
$searchDirs = @(
    $here,
    (Join-Path $here "libs"),
    (Join-Path $here "lib"),
    (Join-Path $here "bin")
) | Where-Object { Test-Path $_ }

$dlls = @()
foreach ($dir in $searchDirs) {
    try {
        $dlls += Get-ChildItem -Path $dir -Filter "NAudio*.dll" -File -ErrorAction SilentlyContinue
    } catch {}
}

# まったく見つからない場合は従来通りの場所を明示して落とす
if ($dlls.Count -eq 0) {
    $naudio = Join-Path $here "NAudio.dll"
    throw "NAudio assemblies not found. Put NAudio*.dll near ps1 or under libs/. Missing: $naudio"
}

# 依存順でハマりにくいように「短い名前 → 長い名前」の順にロード
$dlls = $dlls | Sort-Object { $_.Name.Length }

foreach ($d in $dlls) {
    try {
        Add-Type -Path $d.FullName -ErrorAction Stop
        LogErr "loaded: $($d.Name)"
    } catch {
        # すでにロード済み等は無視してOK
        LogErr "load skip/fail: $($d.Name) ($($_.Exception.Message))"
    }
}

# 念のため型チェック（ここで落ちれば、NAudioの中身が違う）
if (-not ("NAudio.CoreAudioApi.MMDeviceEnumerator" -as [type])) {
    throw "NAudio.CoreAudioApi.MMDeviceEnumerator type not found. Ensure CoreAudioApi assembly is present (e.g., NAudio.CoreAudioApi.dll)."
}

# ===== pick device (render/capture) =====
$enumerator = New-Object NAudio.CoreAudioApi.MMDeviceEnumerator
$df = if ($Flow -eq "capture") { [NAudio.CoreAudioApi.DataFlow]::Capture } else { [NAudio.CoreAudioApi.DataFlow]::Render }
$devices = $enumerator.EnumerateAudioEndPoints($df, [NAudio.CoreAudioApi.DeviceState]::Active)

$selected = $null

# AutoPick: AudioMeterで“今鳴ってる”を拾って一番を採用
if ($AutoPick) {
    # 候補を集める（しきい値以上だけ）
    $cand = @{}
    $end = (Get-Date).AddMilliseconds($ScanMs)

    while ((Get-Date) -lt $end) {
        foreach ($d in $devices) {
            try {
                $p = $d.AudioMeterInformation.MasterPeakValue
                $peak = [int]([math]::Round([math]::Min(1.0,$p) * 32767.0))
                # ★常時クリップ（MAX近い）なメーターは誤判定が多いので除外
                if ($peak -ge 32000) { continue }
                if ($peak -lt $PickThreshold) { continue }

                $name = $d.FriendlyName
                if (-not $cand.ContainsKey($name) -or $peak -gt $cand[$name].peak) {
                    $cand[$name] = @{ dev=$d; peak=$peak }
                }
            } catch {}
        }
        Start-Sleep -Milliseconds 80
    }

    if ($cand.Count -gt 0) {
        # ★Virtual/ミキサー系を優先（loopback PCMが取りやすい）
        # ★Sonarは「Chat/Stream/Media」を最優先、次にその他virtual系
        $prefRe = '(?i)(sonar.*(chat|stream|media)|sonar|virtual audio|virtual audio device|voicemeeter|vb-audio|cable)'
        $best = $null; $bestPeak = -1; $bestScore = -999

        foreach ($k in $cand.Keys) {
            $c = $cand[$k]
            $score = 0
            if ($k -match $prefRe) { $score += 10 }

            # ★ヘッドホン/Bluetooth系は後回し（メーターだけ動いてPCM取れない事がある）
            if ($k -match '(?i)(bose|headphone|ヘッドホン|bluetooth)') { $score -= 5 }
            if ($k -match '(?i)sonar.*gaming') { $score -= 8 }

            if ($score -gt $bestScore -or ($score -eq $bestScore -and $c.peak -gt $bestPeak)) {
                $best = $c.dev; $bestPeak = $c.peak; $bestScore = $score
            }
        }

        $selected = $best
        LogErr ("autopick: {0} (peak={1}, score={2})" -f $selected.FriendlyName, $bestPeak, $bestScore)
    } else {
        LogErr ("autopick: none above threshold={0} -> fallback default" -f $PickThreshold)
    }
}


# 明示Device指定があれば優先
if ($null -eq $selected -and -not [string]::IsNullOrWhiteSpace($Device)) {
    foreach ($d in $devices) { if ($d.FriendlyName -eq $Device) { $selected = $d; break } }
    if ($null -eq $selected) {
        foreach ($d in $devices) { if ($d.FriendlyName -like "*$Device*") { $selected = $d; break } }
    }
}

# 最後にdefault
if ($null -eq $selected) {
    $selected = $enumerator.GetDefaultAudioEndpoint($df, [NAudio.CoreAudioApi.Role]::Multimedia)

    if ($Flow -eq "capture") { LogErr "using capture: $($selected.FriendlyName)" }
    else { LogErr "using render: $($selected.FriendlyName)" }
}

LogErr "using render: $($selected.FriendlyName)"

# ===== capture (loopback or input) =====
if ($Flow -eq "capture") {
    $capture = New-Object NAudio.Wave.WasapiCapture($selected)
    LogErr "capture mode: INPUT (WasapiCapture)"
} else {
    $capture = New-Object NAudio.Wave.WasapiLoopbackCapture($selected)
    LogErr "capture mode: LOOPBACK (WasapiLoopbackCapture)"
}

LogErr ("capture format: {0}Hz {1}ch {2} bits={3}" -f $capture.WaveFormat.SampleRate, $capture.WaveFormat.Channels, $capture.WaveFormat.Encoding, $capture.WaveFormat.BitsPerSample)

# ===== buffer (capture bytes) =====
# peakモードでは e.Buffer から直接PEAK算出するので不要
if ($Mode -ne "peak") {
    $buffered = New-Object NAudio.Wave.BufferedWaveProvider($capture.WaveFormat)
    $buffered.DiscardOnBufferOverflow = $true

    $handler = [System.EventHandler[NAudio.Wave.WaveInEventArgs]]{
        param($s, $e)
        try { $buffered.AddSamples($e.Buffer, 0, $e.BytesRecorded) } catch {}
    }
    $capture.add_DataAvailable($handler)
}

# ===== PEAK mode: compute from captured bytes directly (robust across NAudio variants) =====
if ($Mode -eq "peak" -or $Mode -eq "pcm") {

    $sw = New-Object System.IO.StreamWriter([Console]::OpenStandardOutput(), [Console]::OutputEncoding)
    $sw.AutoFlush = $true
    $sw.WriteLine("PEAK 0")  # 起動確認の1発
    if ($Mode -eq "pcm") {
        LogErr "[WASAPI][PCM] stdout = text lines: PEAK <int> + PCM <base64> (heartbeat+update)"
    } else {
        LogErr "[WASAPI][PCM] stdout = text lines: PEAK <int> (heartbeat+update)"
    }

    # 最新PEAK（DataAvailableで更新、ループで定期送出）
    $script:latestPeak = 0
    $script:lastPcmTick = 0

    $script:cbCount = 0
    $script:lastBytes = 0
    $script:lastDbg = [uint32][Environment]::TickCount

    $handlerUpdate = [System.EventHandler[NAudio.Wave.WaveInEventArgs]]{
        param($s, $e)
        try {
            $script:cbCount++
            $script:lastBytes = $e.BytesRecorded

            # 1秒ごとにstderrへ（DataAvailableが来てるか確認）
            $now = [uint32][Environment]::TickCount
            if (($now - $script:lastDbg) -ge 1000) {
                $script:lastDbg = $now
                [Console]::Error.WriteLine("[WASAPI][PCM] cb/s=" + $script:cbCount + " lastBytes=" + $script:lastBytes + " latestPeak=" + $script:latestPeak)
                $script:cbCount = 0
            }

            if ($capture.WaveFormat.Encoding -eq [NAudio.Wave.WaveFormatEncoding]::IeeeFloat) {
                $peakAbs = 0.0
                for ($i = 0; $i -le ($e.BytesRecorded - 4); $i += 4) {
                    $f = [BitConverter]::ToSingle($e.Buffer, $i)
                    if ([Single]::IsNaN($f) -or [Single]::IsInfinity($f)) { continue }
                    $a = [Math]::Abs([double]$f)
                    if ($a -gt $peakAbs) { $peakAbs = $a }
                }
                if ($peakAbs -gt 1.0) { $peakAbs = 1.0 }
                $script:latestPeak = [int]([math]::Round($peakAbs * 32767.0))
            }
             elseif ($capture.WaveFormat.BitsPerSample -eq 16) {
                $p = 0
                for ($i = 0; $i -le ($e.BytesRecorded - 2); $i += 2) {
                    $v = [BitConverter]::ToInt16($e.Buffer, $i)
                    $a = [Math]::Abs([int]$v)
                    if ($a -gt $p) { $p = $a }
                }
                $script:latestPeak = $p
            } else {
                $script:latestPeak = 0
            }
            $script:lastPcmTick = [uint32][Environment]::TickCount
        } catch {
            try { [Console]::Error.WriteLine("[WASAPI][PCM] handler error: " + $_.Exception.Message) } catch {}
        }
    }

    $capture.add_DataAvailable($handlerUpdate)

    try {
        $script:startedTick = [uint32][Environment]::TickCount
        $capture.StartRecording()  # ★ここ1回だけ

        # ===== PCM (16k mono s16le) streaming state =====
        $script:inRate = $capture.WaveFormat.SampleRate
        $script:channels = $capture.WaveFormat.Channels
        $script:monoBuf = New-Object System.Collections.Generic.List[double]
        $script:srcIndex = 0.0
        $script:outPerTick = [int]([math]::Round($OutRate * ($ChunkMs / 1000.0)))
        if ($script:outPerTick -lt 1) { $script:outPerTick = 1 }

        # DataAvailable: float32 interleaved -> mono buffer (double)
        $handlerPcm = [System.EventHandler[NAudio.Wave.WaveInEventArgs]]{
            param($s, $e)
            try {
                $ch = $script:channels
                if ($ch -lt 1) { $ch = 1 }

                # ★ float32 interleaved
                if ($capture.WaveFormat.Encoding -eq [NAudio.Wave.WaveFormatEncoding]::IeeeFloat) {
                    $frames = [int]($e.BytesRecorded / (4 * $ch))
                    $ofs = 0
                    for ($f = 0; $f -lt $frames; $f++) {
                        $sum = 0.0
                        for ($c = 0; $c -lt $ch; $c++) {
                            $v = [BitConverter]::ToSingle($e.Buffer, $ofs)
                            $ofs += 4
                            if (-not [Single]::IsNaN($v) -and -not [Single]::IsInfinity($v)) { $sum += [double]$v }
                        }
                        $mono = $sum / [double]$ch
                        $script:monoBuf.Add($mono)
                    }
                    $script:lastPcmTick = [uint32][Environment]::TickCount
                    return
                }

                # ★ 16bit PCM interleaved (Stereo Mixがこれ多い)
                if ($capture.WaveFormat.BitsPerSample -eq 16) {
                    $frames = [int]($e.BytesRecorded / (2 * $ch))
                    $ofs = 0
                    for ($f = 0; $f -lt $frames; $f++) {
                        $sum = 0.0
                        for ($c = 0; $c -lt $ch; $c++) {
                            $v16 = [BitConverter]::ToInt16($e.Buffer, $ofs)
                            $ofs += 2
                            $sum += ([double]$v16 / 32768.0)
                        }
                        $mono = $sum / [double]$ch
                        $script:monoBuf.Add($mono)
                    }
                    $script:lastPcmTick = [uint32][Environment]::TickCount
                    return
                }

                # それ以外は未対応（とりあえずPCMは出せない）
                return

            } catch {
                try { [Console]::Error.WriteLine("[WASAPI][PCM] pcm handler error: " + $_.Exception.Message) } catch {}
            }
        }

        if ($Mode -eq "pcm") {
            $capture.add_DataAvailable($handlerPcm)
        }

        $script:lastLoopDbg = [uint32][Environment]::TickCount
        while ($true) {

            # AudioMeter（常に取れる）
            $meterPeak = 0
            try {
                $p = $selected.AudioMeterInformation.MasterPeakValue
                $meterPeak = [int]([math]::Round([math]::Min(1.0,$p) * 32767.0))
            } catch {}

            $now = [uint32][Environment]::TickCount

            # ★PCMが一定時間来ない場合は即FATAL（meterは動くがPCMが取れないケース）
            if ($Mode -eq "pcm") {
                if ($script:lastPcmTick -eq 0) {
                    # 起動後 2秒待っても DataAvailable が一度も来ないなら終了
                    if (($now - $script:startedTick) -gt 2000) {
                        LogErr "[WASAPI][PCM] FATAL: loopback DataAvailable never fired (meter works, but PCM capture not available on this device/mode)"
                        exit 2
                    }
                } else {
                    $age = $now - [uint32]$script:lastPcmTick
                    if ($age -gt 2000) {
                        LogErr ("[WASAPI][PCM] FATAL: loopback DataAvailable stalled (ageMs={0})" -f $age)
                        exit 3
                    }
                }
            }

            # ★PCMが最近更新されてないなら meterPeak を採用（棒が張り付かない）
            $usePeak = $script:latestPeak
            $pcmAge = $now - [uint32]$script:lastPcmTick  # wrap OK

            if ($script:lastPcmTick -eq 0 -or $pcmAge -gt 500) {
                $usePeak = $meterPeak
            }

            # stdout: peak
            $sw.WriteLine(("PEAK {0}" -f $usePeak))
            # ===== output PCM chunks (base64) =====
            if ($Mode -eq "pcm") {
                $need = $script:outPerTick

                # 1tick分の出力を作れるだけ入力が溜まってるか？
                $step = $script:inRate / [double]$OutRate  # input samples per output sample

                $outBytes = New-Object byte[] ($need * 2)  # int16 = 2 bytes
                $written = 0

                while ($written -lt $need) {
                    $i0 = [int][math]::Floor($script:srcIndex)
                    $i1 = $i0 + 1
                    if ($i1 -ge $script:monoBuf.Count) { break }

                    $t = $script:srcIndex - $i0
                    $s0 = $script:monoBuf[$i0]
                    $s1 = $script:monoBuf[$i1]
                    $s = ($s0 * (1.0 - $t)) + ($s1 * $t)  # linear interp

                    # clamp -1..1 -> int16
                    if ($s -gt 1.0) { $s = 1.0 }
                    if ($s -lt -1.0) { $s = -1.0 }
                    $v16 = [int]([math]::Round($s * 32767.0))
                    if ($v16 -gt 32767) { $v16 = 32767 }
                    if ($v16 -lt -32768) { $v16 = -32768 }

                    # little-endian write
                    $outBytes[$written*2]   = [byte]($v16 -band 0xFF)
                    $outBytes[$written*2+1] = [byte](($v16 -shr 8) -band 0xFF)

                    $written++
                    $script:srcIndex += $step
                }

                # 消費済み入力を間引き（バッファ肥大化防止）
                $drop = [int][math]::Floor($script:srcIndex) - 1
                if ($drop -gt 0 -and $drop -lt $script:monoBuf.Count) {
                    $script:monoBuf.RemoveRange(0, $drop)
                    $script:srcIndex -= $drop
                }

                # ちゃんと作れた分だけ送る（短い場合は送らない）
                if ($written -gt 0 -and $written -eq $need) {
                    $b64 = [Convert]::ToBase64String($outBytes)
                    $sw.WriteLine(("PCM {0}" -f $b64))
                }
            }

            if (($now - $script:lastLoopDbg) -ge 1000) {
                $script:lastLoopDbg = $now
                LogErr ("loop dbg: usePeak={0} latestPeak={1} meterPeak={2} pcmAgeMs={3}" -f $usePeak, $script:latestPeak, $meterPeak, $pcmAge)
            }

            Start-Sleep -Milliseconds ([int]([math]::Max(10, $ChunkMs)))
        }

    }
    finally {
        try { $capture.StopRecording() } catch {}
        try { $capture.remove_DataAvailable($handlerUpdate) } catch {}
        try { $sw.Flush(); $sw.Dispose() } catch {}
        try { $capture.Dispose() } catch {}
        try { $enumerator.Dispose() } catch {}
    }
    exit 0
}


# ===== start & pump =====
#$capture.StartRecording()

try {
    while ($true) {
        $read = 0
        try { $read = $w16.Read($buf, 0, $buf.Length) } catch { $read = 0 }

        if ($read -gt 0) {
            if ($Mode -eq "peak") {
                # bufは16bit little endian。最大振幅(abs)を求めて PEAK n を出す
                $peak = 0
                for ($i = 0; $i -lt $read; $i += 2) {
                    $v = [BitConverter]::ToInt16($buf, $i)
                    $a = [Math]::Abs([int]$v)
                    if ($a -gt $peak) { $peak = $a }
                }
                $sw.WriteLine(("PEAK {0}" -f $peak))
            } else {
                $outStream.Write($buf, 0, $read)
                $outStream.Flush()
            }
        } else {
            Start-Sleep -Milliseconds 5
        }
    }
}
finally {
    try { if ($sw -ne $null) { $sw.Flush(); $sw.Dispose() } } catch {}
    try { $capture.StopRecording() } catch {}
    try { $capture.remove_DataAvailable($handler) } catch {}
    try { $capture.Dispose() } catch {}
    try { $enumerator.Dispose() } catch {}
}
