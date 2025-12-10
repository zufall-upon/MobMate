Add-Type -Path ".\NAudio.dll"
Add-Type -Path ".\NAudio.Core.dll"
Add-Type -Path ".\NAudio.Wasapi.dll"
Add-Type -Path ".\NAudio.WinMM.dll"

function Play-Wav($path, $dev) {
    try {
        $reader = New-Object NAudio.Wave.WaveFileReader($path)
        $output = New-Object NAudio.Wave.WaveOutEvent
        $output.DeviceNumber = [int]$dev
        $output.Init($reader)
        $output.Play()

        while ($output.PlaybackState -eq "Playing") {
            Start-Sleep -Milliseconds 20
        }

        $output.Dispose()
        $reader.Dispose()
    }
    catch {
        [Console]::Error.WriteLine("ERR $_")
    }
}

while ($true) {
    $line = [Console]::ReadLine()
    if ($line -eq $null) { break }

    # 先頭は CMD → 残りは JSONで渡す or quoted args
    if ($line.StartsWith("PLAY")) {
        # 正規表現で割り出す
        $match = $line | Select-String 'PLAY\s+"(.+)"\s+(\d+)'
        if ($match) {
            $wav = $match.Matches.Groups[1].Value
            $dev = [int]$match.Matches.Groups[2].Value

            Play-Wav $wav $dev
            $stdout.WriteLine("DONE")
        }
        else {
            $stderr.WriteLine("BAD LINE: $line")
        }
    }
}
