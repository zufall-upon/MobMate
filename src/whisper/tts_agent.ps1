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
$script:ttsVoice   = @{}

function Synth-WavB64($text, $voiceName) {
    # text -> wav bytes -> base64 (no file)
    $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
    try {
        if ($voiceName -and $voiceName -ne "auto") {
            $s.SelectVoice($voiceName)
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
        [Console]::Error.WriteLine("ERR $_")
    }
}


# === Audio player (singleton) ===
$script:waveOut = $null
$script:reader  = $null

function Play-Wav($path, $dev) {
    try {
        if ($script:waveOut) {
            try { $script:waveOut.Stop() } catch {}
            try { $script:waveOut.Dispose() } catch {}
            $script:waveOut = $null
        }
        if ($script:reader) {
            try { $script:reader.Dispose() } catch {}
            $script:reader = $null
        }

        $script:reader = New-Object NAudio.Wave.WaveFileReader($path)
        $script:waveOut = New-Object NAudio.Wave.WaveOutEvent
        $script:waveOut.DeviceNumber = [int]$dev
        $script:waveOut.Init($script:reader)

        $script:waveOut.Play()
        [Console]::Out.WriteLine("DONE")
    }
    catch {
        [Console]::Error.WriteLine("ERR $_")
    }
}

# === PLAYB64 (wav bytes) support ===
$script:ms = $null
$script:b64Buf = @{}
$script:b64Dev = @{}
function Play-WavBytes($bytes, $dev) {
    try {
        if ($script:waveOut) {
            try { $script:waveOut.Stop() } catch {}
            try { $script:waveOut.Dispose() } catch {}
            $script:waveOut = $null
        }
        if ($script:reader) {
            try { $script:reader.Dispose() } catch {}
            $script:reader = $null
        }
        if ($script:ms) {
            try { $script:ms.Dispose() } catch {}
            $script:ms = $null
        }

        $script:ms = [System.IO.MemoryStream]::new($bytes)
        $script:reader = [NAudio.Wave.WaveFileReader]::new($script:ms)
        $script:waveOut = New-Object NAudio.Wave.WaveOutEvent
        $script:waveOut.DeviceNumber = [int]$dev
        $script:waveOut.Init($script:reader)

        $script:waveOut.Play()
        [Console]::Out.WriteLine("DONE")
    }
    catch {
        [Console]::Error.WriteLine("ERR $_")
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
    if ($line.StartsWith("SYNTHB64 ")) {
        # SYNTHB64 <voiceOrAuto> <id>
        $m = $line | Select-String '^SYNTHB64\s+(.+?)\s+([0-9A-Za-z_\-]+)$'
        if ($m) {
            $voice = $m.Matches.Groups[1].Value
            $id    = $m.Matches.Groups[2].Value
            $script:ttsTextBuf[$id] = ""
            $script:ttsVoice[$id]   = $voice
        } else {
            [Console]::Out.WriteLine("ERR BAD LINE: $line")
            [Console]::Out.WriteLine("DONE")
        }
        continue
    }

    if ($line.StartsWith("TDATA ")) {
        # TDATA <id> <base64chunk>
        $m = $line | Select-String '^TDATA\s+([0-9A-Za-z_\-]+)\s+(.+)$'
        if ($m) {
            $id = $m.Matches.Groups[1].Value
            $chunk = $m.Matches.Groups[2].Value
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

    if ($line.StartsWith("TEND ")) {
        # TEND <id>
        $m = $line | Select-String '^TEND\s+([0-9A-Za-z_\-]+)$'
        if ($m) {
            $id = $m.Matches.Groups[1].Value
            if ($script:ttsTextBuf.ContainsKey($id)) {
                $b64 = $script:ttsTextBuf[$id]
                $voice = $script:ttsVoice[$id]
                $script:ttsTextBuf.Remove($id) | Out-Null
                $script:ttsVoice.Remove($id)   | Out-Null

                try {
                    $txtBytes = [Convert]::FromBase64String($b64)
                    $text = [System.Text.Encoding]::UTF8.GetString($txtBytes)
                    Synth-WavB64 $text $voice   # この関数が ODATA.. OEND DONE を出す
                } catch {
                    [Console]::Out.WriteLine("ERR $_")
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
        # PLAYB64 <dev> <id>
        $m = $line | Select-String '^PLAYB64\s+(-?\d+)\s+([0-9A-Za-z_\-]+)$'
        if ($m) {
            $dev = [int]$m.Matches.Groups[1].Value
            $id  = $m.Matches.Groups[2].Value
            $script:b64Buf[$id] = ""
            $script:b64Dev[$id] = $dev
        } else {
            [Console]::Out.WriteLine("ERR BAD LINE: $line")
            [Console]::Out.WriteLine("DONE")
        }
        continue
    }

    if ($line.StartsWith("DATA ")) {
        # DATA <id> <base64chunk>
        $m = $line | Select-String '^DATA\s+([0-9A-Za-z_\-]+)\s+(.+)$'
        if ($m) {
            $id = $m.Matches.Groups[1].Value
            $chunk = $m.Matches.Groups[2].Value
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
        $m = $line | Select-String '^END\s+([0-9A-Za-z_\-]+)$'
        if ($m) {
            $id = $m.Matches.Groups[1].Value
            if ($script:b64Buf.ContainsKey($id)) {
                $b64 = $script:b64Buf[$id]
                $dev = $script:b64Dev[$id]
                $script:b64Buf.Remove($id) | Out-Null
                $script:b64Dev.Remove($id) | Out-Null

                try {
                    $bytes = [Convert]::FromBase64String($b64)
                    Play-WavBytes $bytes $dev   # この関数が DONE を出す
                } catch {
                    [Console]::Out.WriteLine("ERR $_")
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
        # PLAY "path" <dev>
        $match = $line | Select-String '^PLAY\s+\"(.+?)\"\s+(-?\d+)$'
        if ($match) {
            $wav = $match.Matches.Groups[1].Value
            $dev = [int]$match.Matches.Groups[2].Value
            Play-Wav $wav $dev
        } else {
            [Console]::Out.WriteLine("ERR BAD LINE: $line")
            [Console]::Out.WriteLine("DONE")
        }
        continue
    }

    # unknown command -> unblock caller
    [Console]::Out.WriteLine("ERR BAD LINE: $line")
    [Console]::Out.WriteLine("DONE")
}
