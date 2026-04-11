# === DLL unblock once only ===
$script:DllUnblocked = $false
if (-not $script:DllUnblocked) {
    try {
        $base = Split-Path -Parent $MyInvocation.MyCommand.Path
        Get-ChildItem -Path $base -Filter *.dll |
                Unblock-File -ErrorAction SilentlyContinue
        $script:DllUnblocked = $true
    } catch {}
}

# === NAudio DLL load ===
Add-Type -Path ".\NAudio.dll"
Add-Type -Path ".\NAudio.Core.dll"
Add-Type -Path ".\NAudio.Wasapi.dll"
Add-Type -Path ".\NAudio.WinMM.dll"

# === Windows TTS (System.Speech) ===
Add-Type -AssemblyName System.Speech

# === Synth buffers ===
$script:ttsTextBuf = @{}
$script:ttsSsmlBuf = @{}
$script:ttsVoice   = @{}
$script:ttsMode    = @{}
$script:ttsRate    = @{}
$script:ttsVolume  = @{}

function Synth-WavB64($text, $voiceName, $rate, $volume) {
    # text -> wav bytes -> base64 (no file)
    $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
    try {
        if ($voiceName -and $voiceName -ne "auto") {
            $s.SelectVoice($voiceName)
        }
        if ($null -ne $rate) {
            $s.Rate = [Math]::Max(-10, [Math]::Min(10, [int]$rate))
        }
        if ($null -ne $volume) {
            $s.Volume = [Math]::Max(0, [Math]::Min(100, [int]$volume))
        }
        $ms = New-Object System.IO.MemoryStream
        $s.SetOutputToWaveStream($ms)
        $s.Speak($text)
        $s.Dispose()

        $bytes = $ms.ToArray()
        $ms.Dispose()

        # base64を長さ制限回避で分割して返す
        $b64 = [Convert]::ToBase64String($bytes)
        $chunk = 12000
        for ($i = 0; $i -lt $b64.Length; $i += $chunk) {
            $end = [Math]::Min($b64.Length, $i + $chunk)
            [Console]::Out.WriteLine("ODATA " + $b64.Substring($i, $end - $i))
        }
        [Console]::Out.WriteLine("OEND")
        [Console]::Out.WriteLine("DONE")
    }
    catch {
        try { $s.Dispose() } catch {}
        [Console]::Out.WriteLine("ERR $_")
    }
}

function Synth-SsmlWavB64($ssml, $voiceName, $rate, $volume) {
    $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
    try {
        if ($voiceName -and $voiceName -ne "auto") {
            $s.SelectVoice($voiceName)
        }
        if ($null -ne $rate) {
            $s.Rate = [Math]::Max(-10, [Math]::Min(10, [int]$rate))
        }
        if ($null -ne $volume) {
            $s.Volume = [Math]::Max(0, [Math]::Min(100, [int]$volume))
        }
        $ms = New-Object System.IO.MemoryStream
        $s.SetOutputToWaveStream($ms)
        $s.SpeakSsml($ssml)
        $s.Dispose()

        $bytes = $ms.ToArray()
        $ms.Dispose()

        $b64 = [Convert]::ToBase64String($bytes)
        $chunk = 12000
        for ($i = 0; $i -lt $b64.Length; $i += $chunk) {
            $end = [Math]::Min($b64.Length, $i + $chunk)
            [Console]::Out.WriteLine("ODATA " + $b64.Substring($i, $end - $i))
        }
        [Console]::Out.WriteLine("OEND")
        [Console]::Out.WriteLine("DONE")
    }
    catch {
        try { $s.Dispose() } catch {}
        [Console]::Out.WriteLine("ERR $_")
    }
}


# === Audio player (singleton) ===
$script:waveOut = $null
$script:bufferedProvider = $null
$script:playbackDeviceKey = ""
$script:playbackFormatKey = ""
$script:reader  = $null
$script:PlaybackPreSilenceMs = 120
$script:wirelessCache = @{}

function Reset-PlayerResources {
    if ($script:waveOut) {
        try { $script:waveOut.Stop() } catch {}
        try { $script:waveOut.Dispose() } catch {}
        $script:waveOut = $null
    }
    $script:bufferedProvider = $null
    $script:playbackDeviceKey = ""
    $script:playbackFormatKey = ""
    if ($script:reader) {
        try { $script:reader.Dispose() } catch {}
        $script:reader = $null
    }
    if ($script:ms) {
        try { $script:ms.Dispose() } catch {}
        $script:ms = $null
    }
}

function Normalize-DeviceName($name) {
    if (-not $name) { return "" }
    return ($name.ToLowerInvariant() -replace '\s+', ' ').Trim()
}

function Decode-DeviceName($deviceNameB64) {
    if (-not $deviceNameB64) { return "" }
    try {
        $bytes = [Convert]::FromBase64String($deviceNameB64)
        return [System.Text.Encoding]::UTF8.GetString($bytes).Trim()
    } catch {
        return ""
    }
}

function Decode-Base64Bytes($b64) {
    if (-not $b64) { return $null }
    try {
        return [Convert]::FromBase64String($b64)
    } catch {
        return $null
    }
}

function Match-Line($line, $pattern) {
    [regex]::Match($line, $pattern)
}

function Add-PreSilenceToWavBytes($wavBytes, $silenceMs) {
    if ($null -eq $wavBytes -or $wavBytes.Length -lt 44) { return $wavBytes }
    if ($silenceMs -le 0) { return $wavBytes }

    $riff = [System.Text.Encoding]::ASCII.GetString($wavBytes, 0, 4)
    $wave = [System.Text.Encoding]::ASCII.GetString($wavBytes, 8, 4)
    $fmt  = [System.Text.Encoding]::ASCII.GetString($wavBytes, 12, 4)
    $data = [System.Text.Encoding]::ASCII.GetString($wavBytes, 36, 4)
    if ($riff -ne "RIFF" -or $wave -ne "WAVE" -or $fmt -ne "fmt " -or $data -ne "data") { return $wavBytes }

    $audioFormat = [BitConverter]::ToInt16($wavBytes, 20)
    if ($audioFormat -ne 1) { return $wavBytes }

    $bytesPerSec = [BitConverter]::ToInt32($wavBytes, 28)
    $blockAlign  = [BitConverter]::ToInt16($wavBytes, 32)
    $origDataLen = [BitConverter]::ToInt32($wavBytes, 40)
    if ($bytesPerSec -le 0 -or $blockAlign -le 0 -or $origDataLen -lt 0) { return $wavBytes }

    $silenceLen = [int][Math]::Round(($bytesPerSec * $silenceMs) / 1000.0)
    $silenceLen = $silenceLen - ($silenceLen % $blockAlign)
    if ($silenceLen -le 0) { return $wavBytes }

    $newTotalLen = $wavBytes.Length + $silenceLen
    $newBytes = New-Object byte[] $newTotalLen
    [Array]::Copy($wavBytes, 0, $newBytes, 0, 44)
    [Array]::Copy([BitConverter]::GetBytes([int32]($newTotalLen - 8)), 0, $newBytes, 4, 4)
    [Array]::Copy([BitConverter]::GetBytes([int32]($origDataLen + $silenceLen)), 0, $newBytes, 40, 4)
    [Array]::Copy($wavBytes, 44, $newBytes, 44 + $silenceLen, $wavBytes.Length - 44)
    $newBytes
}

function Get-PlaybackPreSilenceMsForWav($wavBytes) {
    if ($null -eq $wavBytes -or $wavBytes.Length -lt 44) { return 0 }

    $audioFormat = [BitConverter]::ToInt16($wavBytes, 20)
    $channels = [BitConverter]::ToInt16($wavBytes, 22)
    $sampleRate = [BitConverter]::ToInt32($wavBytes, 24)
    $bitsPerSample = [BitConverter]::ToInt16($wavBytes, 34)

    if ($audioFormat -eq 1 -and $channels -eq 1 -and $bitsPerSample -eq 16 -and $sampleRate -eq 22050) {
        return $script:PlaybackPreSilenceMs
    }
    0
}

function Get-RenderDevicesSafe {
    try {
        $enumerator = New-Object NAudio.CoreAudioApi.MMDeviceEnumerator
        return $enumerator.EnumerateAudioEndPoints(
            [NAudio.CoreAudioApi.DataFlow]::Render,
            [NAudio.CoreAudioApi.DeviceState]::Active
        )
    } catch {
        return $null
    }
}

function Find-PlaybackDeviceByName($deviceName) {
    $target = Normalize-DeviceName $deviceName
    if (-not $target) { return $null }

    $devices = Get-RenderDevicesSafe
    if ($null -eq $devices) { return $null }
    foreach ($device in $devices) {
        try {
            $name = $device.FriendlyName
            $norm = Normalize-DeviceName $name
            if ($norm -eq $target -or $norm.Contains($target) -or $target.Contains($norm)) {
                return $device
            }
        } catch {}
    }
    return $null
}

function Resolve-PlaybackDevice($requestedDev, $deviceNameB64) {
    $name = Decode-DeviceName $deviceNameB64
    $resolved = Find-PlaybackDeviceByName $name
    if ($resolved -ne $null) {
        return $resolved
    }

    $devices = Get-RenderDevicesSafe
    if ($null -ne $devices -and $requestedDev -ge 0 -and $requestedDev -lt $devices.Count) {
        try {
            return $devices.Item($requestedDev)
        } catch {}
    }

    try {
        $enumerator = New-Object NAudio.CoreAudioApi.MMDeviceEnumerator
        return $enumerator.GetDefaultAudioEndpoint(
            [NAudio.CoreAudioApi.DataFlow]::Render,
            [NAudio.CoreAudioApi.Role]::Multimedia
        )
    } catch {
        return $null
    }
}

function Test-IsWirelessDevice($device) {
    if ($null -eq $device) { return $false }

    $cacheKey = ""
    try {
        $cacheKey = "$($device.ID)|$($device.FriendlyName)"
    } catch {
        try { $cacheKey = [string]$device.FriendlyName } catch {}
    }

    if ($cacheKey -and $script:wirelessCache.ContainsKey($cacheKey)) {
        return [bool]$script:wirelessCache[$cacheKey]
    }

    $result = $false
    try {
        $key = New-Object NAudio.CoreAudioApi.PropertyKey(
            [Guid]"{78C34FC8-104A-4ACA-9EA4-524D52996E57}", 256)
        $instanceId = [string]$device.Properties[$key].Value
        if ($instanceId -match 'BTHENUM|BTHLE|bthhfenum|BLUETOOTH') {
            $result = $true
        }
    } catch {}

    if (-not $result) {
        try {
            $friendlyName = [string]$device.FriendlyName
            if ($friendlyName -match 'Bluetooth|Wireless|aptX|aptx') {
                $result = $true
            }
        } catch {}
    }

    if ($cacheKey) {
        $script:wirelessCache[$cacheKey] = $result
    }
    return $result
}

function New-WasapiPlayer($device) {
    if ($null -ne $device) {
        return New-Object NAudio.Wave.WasapiOut(
            $device,
            [NAudio.CoreAudioApi.AudioClientShareMode]::Shared,
            $false,
            200
        )
    }
    return New-Object NAudio.Wave.WasapiOut(
        [NAudio.CoreAudioApi.AudioClientShareMode]::Shared,
        $false,
        200
    )
}

function Get-PlaybackDeviceCacheKey($device) {
    if ($null -eq $device) { return "__default__" }
    try {
        return "$($device.ID)|$($device.FriendlyName)"
    } catch {
        try { return [string]$device.FriendlyName } catch { return "__unknown__" }
    }
}

function Get-DeviceIdSafe($device) {
    if ($null -eq $device) { return "" }
    try {
        return [string]$device.ID
    } catch {
        return ""
    }
}

function Test-IsSamePlaybackDevice($a, $b) {
    if ($null -eq $a -or $null -eq $b) { return $false }

    $aId = Get-DeviceIdSafe $a
    $bId = Get-DeviceIdSafe $b
    if ($aId -and $bId) {
        return $aId -eq $bId
    }

    try {
        return (Normalize-DeviceName $a.FriendlyName) -eq (Normalize-DeviceName $b.FriendlyName)
    } catch {
        return $false
    }
}

function Resolve-MonitorPlaybackDevice($mainDevice) {
    try {
        $enumerator = New-Object NAudio.CoreAudioApi.MMDeviceEnumerator
        $monitorDevice = $enumerator.GetDefaultAudioEndpoint(
            [NAudio.CoreAudioApi.DataFlow]::Render,
            [NAudio.CoreAudioApi.Role]::Multimedia
        )
        if ($null -eq $monitorDevice) { return $null }
        if (Test-IsSamePlaybackDevice $mainDevice $monitorDevice) { return $null }
        return $monitorDevice
    } catch {
        return $null
    }
}

function Parse-MonitorPercent($token) {
    if (-not $token) { return 0 }
    $m = [regex]::Match([string]$token, '^MON(\d{1,3})$')
    if (-not $m.Success) { return 0 }
    return [Math]::Max(0, [Math]::Min(100, [int]$m.Groups[1].Value))
}

function Dispose-MonitorSession($session) {
    if ($null -eq $session) { return }
    foreach ($key in @('Output', 'Channel', 'Reader', 'Source', 'Stream')) {
        if ($session.ContainsKey($key) -and $null -ne $session[$key]) {
            try { $session[$key].Dispose() } catch {}
            $session[$key] = $null
        }
    }
}

if ($null -eq $script:activeMonitorSessions) {
    $script:activeMonitorSessions = New-Object System.Collections.ArrayList
}

function Cleanup-CompletedMonitorSessions([switch]$Force) {
    if ($null -eq $script:activeMonitorSessions) { return }
    for ($i = $script:activeMonitorSessions.Count - 1; $i -ge 0; $i--) {
        $session = $script:activeMonitorSessions[$i]
        $remove = $Force.IsPresent
        if (-not $remove) {
            if ($null -eq $session) {
                $remove = $true
            } elseif (-not $session.ContainsKey('Output') -or $null -eq $session['Output']) {
                $remove = $true
            } else {
                try {
                    $remove = ($session['Output'].PlaybackState -eq [NAudio.Wave.PlaybackState]::Stopped)
                } catch {
                    $remove = $true
                }
            }
        }
        if ($remove) {
            Dispose-MonitorSession $session
            $script:activeMonitorSessions.RemoveAt($i)
        }
    }
}

function Register-MonitorSession($session) {
    if ($null -eq $session) { return }
    Cleanup-CompletedMonitorSessions
    [void]$script:activeMonitorSessions.Add($session)
}

function Wait-MonitorSession($session) {
    if ($null -eq $session) { return }
    try {
        $timeoutAt = [Environment]::TickCount64 + 30000
        while ($session.ContainsKey('Output') -and $null -ne $session['Output']) {
            try {
                if ($session['Output'].PlaybackState -eq [NAudio.Wave.PlaybackState]::Stopped) { break }
            } catch {
                break
            }
            if ([Environment]::TickCount64 -ge $timeoutAt) { break }
            Start-Sleep -Milliseconds 20
        }
    } finally {
        Dispose-MonitorSession $session
    }
}

function Start-MonitorWavSession($wavBytes, $mainDevice, $volumePercent) {
    if ($null -eq $wavBytes -or $wavBytes.Length -le 0) { return $null }
    if ($volumePercent -le 0) { return $null }

    $monitorDevice = Resolve-MonitorPlaybackDevice $mainDevice
    if ($null -eq $monitorDevice) { return $null }

    $session = @{
        Stream = $null
        Reader = $null
        Channel = $null
        Output = $null
    }
    try {
        $session.Stream = [System.IO.MemoryStream]::new($wavBytes)
        $session.Reader = [NAudio.Wave.WaveFileReader]::new($session.Stream)
        $session.Channel = [NAudio.Wave.WaveChannel32]::new($session.Reader)
        $session.Channel.Volume = [Math]::Max(0.0, [Math]::Min(1.0, ([double]$volumePercent / 100.0)))
        $session.Output = New-WasapiPlayer $monitorDevice
        $session.Output.Init($session.Channel)
        $session.Output.Play()
        return $session
    } catch {
        Dispose-MonitorSession $session
        return $null
    }
}

function Start-MonitorRawPcm16MonoSession($bytes, $sampleRate, $mainDevice, $volumePercent) {
    if ($null -eq $bytes -or $bytes.Length -le 0) { return $null }
    if ($volumePercent -le 0) { return $null }

    $monitorDevice = Resolve-MonitorPlaybackDevice $mainDevice
    if ($null -eq $monitorDevice) { return $null }

    $session = @{
        Stream = $null
        Source = $null
        Channel = $null
        Output = $null
    }
    try {
        $waveFormat = [NAudio.Wave.WaveFormat]::new([int]$sampleRate, 16, 1)
        $session.Stream = [System.IO.MemoryStream]::new($bytes)
        $session.Source = [NAudio.Wave.RawSourceWaveStream]::new($session.Stream, $waveFormat)
        $session.Channel = [NAudio.Wave.WaveChannel32]::new($session.Source)
        $session.Channel.Volume = [Math]::Max(0.0, [Math]::Min(1.0, ([double]$volumePercent / 100.0)))
        $session.Output = New-WasapiPlayer $monitorDevice
        $session.Output.Init($session.Channel)
        $session.Output.Play()
        return $session
    } catch {
        Dispose-MonitorSession $session
        return $null
    }
}

function Get-WaveFormatCacheKey($waveFormat) {
    if ($null -eq $waveFormat) { return "" }
    try {
        return "{0}|{1}|{2}|{3}" -f [int]$waveFormat.Encoding, [int]$waveFormat.SampleRate, [int]$waveFormat.BitsPerSample, [int]$waveFormat.Channels
    } catch {
        return ""
    }
}

function Ensure-PlaybackSession($device, $waveFormat) {
    $deviceKey = Get-PlaybackDeviceCacheKey $device
    $formatKey = Get-WaveFormatCacheKey $waveFormat
    if ($script:waveOut -and $script:bufferedProvider -and
        $script:playbackDeviceKey -eq $deviceKey -and
        $script:playbackFormatKey -eq $formatKey) {
        try {
            if ($script:waveOut.PlaybackState -ne [NAudio.Wave.PlaybackState]::Playing) {
                $script:waveOut.Play()
            }
            return $true
        } catch {
            Reset-PlayerResources
        }
    }

    Reset-PlayerResources
    try {
        $script:bufferedProvider = New-Object NAudio.Wave.BufferedWaveProvider($waveFormat)
        $script:bufferedProvider.ReadFully = $true
        $script:bufferedProvider.BufferDuration = [TimeSpan]::FromSeconds(8)
        $script:waveOut = New-WasapiPlayer $device
        $script:waveOut.Init($script:bufferedProvider)
        $script:waveOut.Play()
        $script:playbackDeviceKey = $deviceKey
        $script:playbackFormatKey = $formatKey
        return $true
    } catch {
        $script:lastPlayError = $_.Exception.ToString()
        Reset-PlayerResources
        return $false
    }
}

function Wait-ForBufferedPlaybackDone($baselineBufferedBytes) {
    $timeoutAt = [Environment]::TickCount64 + 30000
    while ($script:waveOut -and $script:bufferedProvider) {
        try {
            if ($script:bufferedProvider.BufferedBytes -le $baselineBufferedBytes) { break }
        } catch {
            break
        }
        if ([Environment]::TickCount64 -ge $timeoutAt) { break }
        Start-Sleep -Milliseconds 20
    }
    try {
        if ($script:waveOut -and
            $script:bufferedProvider -and
            $script:bufferedProvider.BufferedBytes -le $baselineBufferedBytes -and
            $script:waveOut.PlaybackState -eq [NAudio.Wave.PlaybackState]::Playing) {
            $script:waveOut.Pause()
        }
    } catch {}
}

function Wait-ForPlaybackBufferSpace($requiredBytes, $maxWaitMs) {
    if ($null -eq $script:bufferedProvider) { return $false }
    if ($requiredBytes -le 0) { return $true }
    if ($maxWaitMs -le 0) { $maxWaitMs = 2000 }
    $timeoutAt = [Environment]::TickCount64 + $maxWaitMs
    while ($script:bufferedProvider) {
        try {
            $freeBytes = [Math]::Max(0, [int]$script:bufferedProvider.BufferLength - [int]$script:bufferedProvider.BufferedBytes)
            if ($freeBytes -ge $requiredBytes) {
                return $true
            }
        } catch {
            return $false
        }
        if ([Environment]::TickCount64 -ge $timeoutAt) {
            return $false
        }
        Start-Sleep -Milliseconds 15
    }
    return $false
}

function Add-BytesToPlaybackBuffer($bytes, $offset, $count) {
    if ($null -eq $script:bufferedProvider) { return $false }
    if ($null -eq $bytes -or $count -le 0) { return $true }
    $chunkBytes = 8192
    $cursor = $offset
    $remaining = $count
    while ($remaining -gt 0) {
        $step = [Math]::Min($remaining, $chunkBytes)
        if (-not (Wait-ForPlaybackBufferSpace $step 2500)) {
            return $false
        }
        $script:bufferedProvider.AddSamples($bytes, $cursor, $step)
        $cursor += $step
        $remaining -= $step
    }
    return $true
}

function Stop-PlaybackSession {
    try {
        if ($script:waveOut) {
            $script:waveOut.Stop()
        }
    } catch {}
    try {
        if ($script:bufferedProvider) {
            $script:bufferedProvider.ClearBuffer()
        }
    } catch {}
    try {
        if ($script:waveOut -and $script:waveOut.PlaybackState -ne [NAudio.Wave.PlaybackState]::Playing) {
            $script:waveOut.Play()
        }
    } catch {}
    Cleanup-CompletedMonitorSessions -Force
}

function Queue-ReaderToPlaybackSession($reader, $device) {
    if ($null -eq $reader) { return $false }
    if (-not (Ensure-PlaybackSession $device $reader.WaveFormat)) { return $false }

    try {
        $baselineBufferedBytes = 0
        try { $baselineBufferedBytes = [Math]::Max(0, [int]$script:bufferedProvider.BufferedBytes) } catch {}

        $buffer = New-Object byte[] 32768
        while (($read = $reader.Read($buffer, 0, $buffer.Length)) -gt 0) {
            if (-not (Add-BytesToPlaybackBuffer $buffer 0 $read)) {
                throw [System.InvalidOperationException]::new("Buffered playback queue full")
            }
        }

        Wait-ForBufferedPlaybackDone $baselineBufferedBytes
        [Console]::Out.WriteLine("DONE")
        return $true
    } catch {
        $script:lastPlayError = $_.Exception.ToString()
        Reset-PlayerResources
        return $false
    }
}

function Wait-ForPlaybackDone {
    while ($script:waveOut -and $script:waveOut.PlaybackState -ne [NAudio.Wave.PlaybackState]::Stopped) {
        Start-Sleep -Milliseconds 20
    }
}

function Try-PlayPath($path, $device, $monitorPercent = 0) {
    $script:lastPlayError = ""
    try {
        $wavBytes = [System.IO.File]::ReadAllBytes($path)
        return Try-PlayBytes $wavBytes $device $monitorPercent
    }
    catch {
        $script:lastPlayError = $_.Exception.ToString()
        return $false
    }
}

function Play-Wav($path, $dev, $deviceNameB64, $monitorPercent = 0) {
    $targetDevice = Resolve-PlaybackDevice $dev $deviceNameB64
    if (Try-PlayPath $path $targetDevice $monitorPercent) { return }
    if ($script:lastPlayError -match 'BadDeviceId') {
        Start-Sleep -Milliseconds 400
        if (Try-PlayPath $path $targetDevice $monitorPercent) { return }

        if ($null -ne $targetDevice) {
            Start-Sleep -Milliseconds 200
            if (Try-PlayPath $path $null $monitorPercent) { return }
        }
    }
    [Console]::Out.WriteLine("ERR $script:lastPlayError")
}

# === PLAYB64 (wav bytes) support ===
$script:ms = $null
$script:b64Buf = @{}
$script:b64Dev = @{}
$script:b64Name = @{}
$script:b64Rate = @{}
$script:b64Monitor = @{}
function Try-PlayBytes($bytes, $device, $monitorPercent = 0) {
    $script:lastPlayError = ""
    $monitorSession = $null
    try {
        Cleanup-CompletedMonitorSessions
        $script:PlaybackPreSilenceMs = if (Test-IsWirelessDevice $device) { 600 } else { 120 }
        $preSilenceMs = Get-PlaybackPreSilenceMsForWav $bytes
        $bytes = Add-PreSilenceToWavBytes $bytes $preSilenceMs
        $ms = [System.IO.MemoryStream]::new($bytes)
        try {
            $reader = [NAudio.Wave.WaveFileReader]::new($ms)
            try {
                if (-not (Ensure-PlaybackSession $device $reader.WaveFormat)) { return $false }
                $monitorSession = Start-MonitorWavSession $bytes $device $monitorPercent
                if ($monitorSession) {
                    Register-MonitorSession $monitorSession
                    $monitorSession = $null
                }
                return Queue-ReaderToPlaybackSession $reader $device
            } finally {
                if ($monitorSession) { Dispose-MonitorSession $monitorSession }
                try { $reader.Dispose() } catch {}
            }
        } finally {
            try { $ms.Dispose() } catch {}
        }
    }
    catch {
        $script:lastPlayError = $_.Exception.ToString()
        return $false
    }
}

function Play-WavBytes($bytes, $dev, $deviceNameB64, $monitorPercent = 0) {
    $targetDevice = Resolve-PlaybackDevice $dev $deviceNameB64
    if (Try-PlayBytes $bytes $targetDevice $monitorPercent) { return }
    if ($script:lastPlayError -match 'BadDeviceId') {
        Start-Sleep -Milliseconds 400
        if (Try-PlayBytes $bytes $targetDevice $monitorPercent) { return }

        if ($null -ne $targetDevice) {
            Start-Sleep -Milliseconds 200
            if (Try-PlayBytes $bytes $null $monitorPercent) { return }
        }
    }
    [Console]::Out.WriteLine("ERR $script:lastPlayError")
}

function Try-PlayRawPcm16Mono($bytes, $sampleRate, $device, $monitorPercent = 0) {
    $script:lastPlayError = ""
    $monitorSession = $null
    try {
        Cleanup-CompletedMonitorSessions
        if ($null -eq $bytes -or $bytes.Length -le 0) { return $false }
        if ($sampleRate -le 0) { $sampleRate = 22050 }
        $waveFormat = [NAudio.Wave.WaveFormat]::new([int]$sampleRate, 16, 1)
        if (-not (Ensure-PlaybackSession $device $waveFormat)) { return $false }

        $baselineBufferedBytes = 0
        try { $baselineBufferedBytes = [Math]::Max(0, [int]$script:bufferedProvider.BufferedBytes) } catch {}
        $monitorSession = Start-MonitorRawPcm16MonoSession $bytes $sampleRate $device $monitorPercent
        if ($monitorSession) {
            Register-MonitorSession $monitorSession
            $monitorSession = $null
        }
        if (-not (Add-BytesToPlaybackBuffer $bytes 0 $bytes.Length)) {
            throw [System.InvalidOperationException]::new("Buffered playback queue full")
        }
        Wait-ForBufferedPlaybackDone $baselineBufferedBytes
        [Console]::Out.WriteLine("DONE")
        return $true
    }
    catch {
        $script:lastPlayError = $_.Exception.ToString()
        return $false
    } finally {
        if ($monitorSession) { Dispose-MonitorSession $monitorSession }
    }
}

while ($true) {
    $line = [Console]::ReadLine()
    if ($line -eq $null) { break }

    $line = $line.Trim()
    if ($line.Length -eq 0) { continue }

    # =========================================================
    # SYNTHB64 : Windows TTS -> wav bytes (base64) out: ODATA.. OEND DONE
    # =========================================================
    if ($line.StartsWith("SYNTHB64 ") -or $line.StartsWith("SYNTHSSMLB64 ")) {
        # SYNTHB64 <voiceOrAuto> <id>
        # SYNTHSSMLB64 <voiceOrAuto> <id>
        $mode = if ($line.StartsWith("SYNTHSSMLB64 ")) { "ssml" } else { "text" }
        $m = Match-Line $line '^(?:SYNTHB64|SYNTHSSMLB64)\s+(.+?)\s+([0-9A-Za-z_\-]+)$'
        if ($m.Success) {
            $voice = $m.Groups[1].Value
            $id    = $m.Groups[2].Value
            $script:ttsTextBuf[$id] = ""
            $script:ttsVoice[$id]   = $voice
            $script:ttsMode[$id]    = $mode
            $script:ttsRate[$id]    = 0
            $script:ttsVolume[$id]  = 100
        } else {
            [Console]::Out.WriteLine("ERR BAD LINE: $line")
            [Console]::Out.WriteLine("DONE")
        }
        continue
    }

    if ($line.StartsWith("TDATA ")) {
        # TDATA <id> <base64chunk>
        $m = Match-Line $line '^TDATA\s+([0-9A-Za-z_\-]+)\s+(.+)$'
        if ($m.Success) {
            $id = $m.Groups[1].Value
            $chunk = $m.Groups[2].Value
            if ($script:ttsTextBuf.ContainsKey($id)) {
                $script:ttsTextBuf[$id] += $chunk
            } else {
                [Console]::Out.WriteLine("ERR UNKNOWN ID: $id")
                [Console]::Out.WriteLine("DONE")
            }
        } else {
            [Console]::Out.WriteLine("ERR BAD LINE: $line")
            [Console]::Out.WriteLine("DONE")
        }
        continue
    }

    if ($line.StartsWith("TRATE ")) {
        $m = Match-Line $line '^TRATE\s+([0-9A-Za-z_\-]+)\s+(-?\d+)$'
        if ($m.Success) {
            $id = $m.Groups[1].Value
            $rate = [int]$m.Groups[2].Value
            if ($script:ttsRate.ContainsKey($id)) {
                $script:ttsRate[$id] = $rate
            } else {
                [Console]::Out.WriteLine("ERR UNKNOWN ID: $id")
                [Console]::Out.WriteLine("DONE")
            }
        } else {
            [Console]::Out.WriteLine("ERR BAD LINE: $line")
            [Console]::Out.WriteLine("DONE")
        }
        continue
    }

    if ($line.StartsWith("TVOLUME ")) {
        $m = Match-Line $line '^TVOLUME\s+([0-9A-Za-z_\-]+)\s+(\d+)$'
        if ($m.Success) {
            $id = $m.Groups[1].Value
            $volume = [int]$m.Groups[2].Value
            if ($script:ttsVolume.ContainsKey($id)) {
                $script:ttsVolume[$id] = $volume
            } else {
                [Console]::Out.WriteLine("ERR UNKNOWN ID: $id")
                [Console]::Out.WriteLine("DONE")
            }
        } else {
            [Console]::Out.WriteLine("ERR BAD LINE: $line")
            [Console]::Out.WriteLine("DONE")
        }
        continue
    }

    if ($line.StartsWith("TEND ")) {
        # TEND <id>
        $m = Match-Line $line '^TEND\s+([0-9A-Za-z_\-]+)$'
        if ($m.Success) {
            $id = $m.Groups[1].Value
            if ($script:ttsTextBuf.ContainsKey($id)) {
                $b64 = $script:ttsTextBuf[$id]
                $voice = $script:ttsVoice[$id]
                $script:ttsTextBuf.Remove($id) | Out-Null
                $script:ttsVoice.Remove($id)   | Out-Null
                $mode = if ($script:ttsMode.ContainsKey($id)) { $script:ttsMode[$id] } else { "text" }
                $script:ttsMode.Remove($id)    | Out-Null
                $rate = if ($script:ttsRate.ContainsKey($id)) { [int]$script:ttsRate[$id] } else { 0 }
                $script:ttsRate.Remove($id)    | Out-Null
                $volume = if ($script:ttsVolume.ContainsKey($id)) { [int]$script:ttsVolume[$id] } else { 100 }
                $script:ttsVolume.Remove($id)  | Out-Null

                $txtBytes = Decode-Base64Bytes $b64
                if ($null -ne $txtBytes) {
                    $text = [System.Text.Encoding]::UTF8.GetString($txtBytes)
                    if ($mode -eq "ssml") {
                        Synth-SsmlWavB64 $text $voice $rate $volume
                    } else {
                        Synth-WavB64 $text $voice $rate $volume
                    }
                    continue
                } else {
                    [Console]::Out.WriteLine("ERR BAD BASE64")
                    [Console]::Out.WriteLine("DONE")
                }
            } else {
                [Console]::Out.WriteLine("ERR UNKNOWN ID: $id")
                [Console]::Out.WriteLine("DONE")
            }
        } else {
            [Console]::Out.WriteLine("ERR BAD LINE: $line")
            [Console]::Out.WriteLine("DONE")
        }
        continue
    }

    # =========================================================
    # PLAYB64 : wav bytes(base64) in -> NAudio play out: DONE
    # =========================================================
    if ($line.StartsWith("PLAYB64 ")) {
        # PLAYB64 <dev> <id> [MONxx] [deviceNameB64]
        $m = Match-Line $line '^PLAYB64\s+(-?\d+)\s+([0-9A-Za-z_\-]+)(?:\s+(MON\d{1,3}))?(?:\s+([A-Za-z0-9+/=]+))?$'
        if ($m.Success) {
            $dev = [int]$m.Groups[1].Value
            $id  = $m.Groups[2].Value
            $monitorToken = $m.Groups[3].Value
            $nameB64 = $m.Groups[4].Value
            $script:b64Buf[$id] = ""
            $script:b64Dev[$id] = $dev
            $script:b64Name[$id] = $nameB64
            $script:b64Monitor[$id] = Parse-MonitorPercent $monitorToken
        } else {
            [Console]::Out.WriteLine("ERR BAD LINE: $line")
            [Console]::Out.WriteLine("DONE")
        }
        continue
    }

    if ($line.StartsWith("PLAYPCM16B64 ")) {
        # PLAYPCM16B64 <sampleRate> <dev> <id> [MONxx] [deviceNameB64]
        $m = Match-Line $line '^PLAYPCM16B64\s+(\d+)\s+(-?\d+)\s+([0-9A-Za-z_\-]+)(?:\s+(MON\d{1,3}))?(?:\s+([A-Za-z0-9+/=]+))?$'
        if ($m.Success) {
            $sampleRate = [int]$m.Groups[1].Value
            $dev = [int]$m.Groups[2].Value
            $id  = $m.Groups[3].Value
            $monitorToken = $m.Groups[4].Value
            $nameB64 = $m.Groups[5].Value
            $script:b64Buf[$id] = ""
            $script:b64Dev[$id] = $dev
            $script:b64Name[$id] = $nameB64
            $script:b64Rate[$id] = $sampleRate
            $script:b64Monitor[$id] = Parse-MonitorPercent $monitorToken
        } else {
            [Console]::Out.WriteLine("ERR BAD LINE: $line")
            [Console]::Out.WriteLine("DONE")
        }
        continue
    }

    if ($line.StartsWith("DATA ")) {
        # DATA <id> <base64chunk>
        $m = Match-Line $line '^DATA\s+([0-9A-Za-z_\-]+)\s+(.+)$'
        if ($m.Success) {
            $id = $m.Groups[1].Value
            $chunk = $m.Groups[2].Value
            if ($script:b64Buf.ContainsKey($id)) {
                $script:b64Buf[$id] += $chunk
            } else {
                [Console]::Out.WriteLine("ERR UNKNOWN ID: $id")
                [Console]::Out.WriteLine("DONE")
            }
        } else {
            [Console]::Out.WriteLine("ERR BAD LINE: $line")
            [Console]::Out.WriteLine("DONE")
        }
        continue
    }

    if ($line.StartsWith("END ")) {
        # END <id>
        $m = Match-Line $line '^END\s+([0-9A-Za-z_\-]+)$'
        if ($m.Success) {
            $id = $m.Groups[1].Value
            if ($script:b64Buf.ContainsKey($id)) {
                $b64 = $script:b64Buf[$id]
                $dev = $script:b64Dev[$id]
                $nameB64 = $script:b64Name[$id]
                $monitorPercent = if ($script:b64Monitor.ContainsKey($id)) { [int]$script:b64Monitor[$id] } else { 0 }
                $script:b64Buf.Remove($id) | Out-Null
                $script:b64Dev.Remove($id) | Out-Null
                $script:b64Name.Remove($id) | Out-Null
                $script:b64Monitor.Remove($id) | Out-Null

                $bytes = Decode-Base64Bytes $b64
                if ($null -ne $bytes) {
                    Play-WavBytes $bytes $dev $nameB64 $monitorPercent   # この関数が DONE を出す
                    continue
                } else {
                    [Console]::Out.WriteLine("ERR BAD BASE64")
                    [Console]::Out.WriteLine("DONE")
                }
            } else {
                [Console]::Out.WriteLine("ERR UNKNOWN ID: $id")
                [Console]::Out.WriteLine("DONE")
            }
        } else {
            [Console]::Out.WriteLine("ERR BAD LINE: $line")
            [Console]::Out.WriteLine("DONE")
        }
        continue
    }

    if ($line.StartsWith("ENDPCM ")) {
        # ENDPCM <id>
        $m = Match-Line $line '^ENDPCM\s+([0-9A-Za-z_\-]+)$'
        if ($m.Success) {
            $id = $m.Groups[1].Value
            if ($script:b64Buf.ContainsKey($id)) {
                $b64 = $script:b64Buf[$id]
                $dev = $script:b64Dev[$id]
                $nameB64 = $script:b64Name[$id]
                $sampleRate = if ($script:b64Rate.ContainsKey($id)) { [int]$script:b64Rate[$id] } else { 22050 }
                $monitorPercent = if ($script:b64Monitor.ContainsKey($id)) { [int]$script:b64Monitor[$id] } else { 0 }
                $script:b64Buf.Remove($id) | Out-Null
                $script:b64Dev.Remove($id) | Out-Null
                $script:b64Name.Remove($id) | Out-Null
                $script:b64Rate.Remove($id) | Out-Null
                $script:b64Monitor.Remove($id) | Out-Null

                $bytes = Decode-Base64Bytes $b64
                if ($null -ne $bytes) {
                    $targetDevice = Resolve-PlaybackDevice $dev $nameB64
                    if (Try-PlayRawPcm16Mono $bytes $sampleRate $targetDevice $monitorPercent) { continue }
                    if ($script:lastPlayError -match 'BadDeviceId') {
                        Start-Sleep -Milliseconds 400
                        if (Try-PlayRawPcm16Mono $bytes $sampleRate $targetDevice $monitorPercent) { continue }

                        if ($null -ne $targetDevice) {
                            Start-Sleep -Milliseconds 200
                            if (Try-PlayRawPcm16Mono $bytes $sampleRate $null $monitorPercent) { continue }
                        }
                    }
                    [Console]::Out.WriteLine("ERR $script:lastPlayError")
                    [Console]::Out.WriteLine("DONE")
                } else {
                    [Console]::Out.WriteLine("ERR BAD BASE64")
                    [Console]::Out.WriteLine("DONE")
                }
            } else {
                [Console]::Out.WriteLine("ERR UNKNOWN ID: $id")
                [Console]::Out.WriteLine("DONE")
            }
        } else {
            [Console]::Out.WriteLine("ERR BAD LINE: $line")
            [Console]::Out.WriteLine("DONE")
        }
        continue
    }

    # =========================================================
    # PLAY : file path play out: DONE
    # =========================================================
    if ($line.StartsWith("PLAY ")) {
        # PLAY "path" <dev> [MONxx] [deviceNameB64]
        $match = Match-Line $line '^PLAY\s+\"(.+?)\"\s+(-?\d+)(?:\s+(MON\d{1,3}))?(?:\s+([A-Za-z0-9+/=]+))?$'
        if ($match.Success) {
            $wav = $match.Groups[1].Value
            $dev = [int]$match.Groups[2].Value
            $monitorToken = $match.Groups[3].Value
            $nameB64 = $match.Groups[4].Value
            Play-Wav $wav $dev $nameB64 (Parse-MonitorPercent $monitorToken)
        } else {
            [Console]::Out.WriteLine("ERR BAD LINE: $line")
            [Console]::Out.WriteLine("DONE")
        }
        continue
    }

    if ($line -eq "STOP") {
        Stop-PlaybackSession
        [Console]::Out.WriteLine("DONE")
        continue
    }

    # unknown command -> unblock caller
    [Console]::Out.WriteLine("ERR BAD LINE: $line")
    [Console]::Out.WriteLine("DONE")
}
