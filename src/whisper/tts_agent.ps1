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

while ($true) {
    $line = [Console]::ReadLine()
    if ($line -eq $null) { break }

    if ($line.StartsWith("PLAY")) {
        $match = $line | Select-String '^PLAY\s+\"(.+?)\"\s+(-?\d+)$'
        if ($match) {
            $wav = $match.Matches.Groups[1].Value
            $dev = [int]$match.Matches.Groups[2].Value

            Play-Wav $wav $dev
        }
        else {
            [Console]::Error.WriteLine("BAD LINE: $line")
        }
    }
}
