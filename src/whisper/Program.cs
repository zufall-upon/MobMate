using System;
using System.Threading;
using System.Diagnostics;
using NAudio.CoreAudioApi;
using NAudio.Wave;

class Program
{
    // ===== Output (Whisper-friendly) =====
    static int outRate = 16000;      // Whisper向け 16kHz
    static int chunkMs = 200;        // 200ms/chunk (調整可)
    static int peakEveryMs = 50;     // PEAK送出間隔

    // ===== Input format =====
    static int inRate, inCh;
    static WaveFormat wf;

    // ===== Resample state (simple average decimator) =====
    static double phase;   // 0..inRate (増加: outRate)
    static double sum;
    static int count;

    // ===== Chunk buffer =====
    static short[] outChunk = Array.Empty<short>();
    static int outFill;

    // ===== Peak =====
    static int peakHold;
    static long lastPeakMs;

    static volatile bool started;

    static int Main(string[] args)
    {
        string flow = "render";       // render=loopback, capture=mic
        string deviceHint = "";

        for (int i = 0; i < args.Length; i++)
        {
            var a = args[i];
            if (a == "--flow" && i + 1 < args.Length) flow = args[++i];
            else if (a == "--device" && i + 1 < args.Length) deviceHint = args[++i];
            else if (a == "--rate" && i + 1 < args.Length) int.TryParse(args[++i], out outRate);
            else if (a == "--chunkms" && i + 1 < args.Length) int.TryParse(args[++i], out chunkMs);
            else if (a == "--peakms" && i + 1 < args.Length) int.TryParse(args[++i], out peakEveryMs);
        }

        try
        {
            var df = flow.Equals("capture", StringComparison.OrdinalIgnoreCase) ? DataFlow.Capture : DataFlow.Render;
            Console.Error.WriteLine($"[PCMEXE] start flow={df} outRate={outRate} chunkMs={chunkMs} peakMs={peakEveryMs}");

            var enumerator = new MMDeviceEnumerator();
            MMDevice? dev = null;

            if (!string.IsNullOrEmpty(deviceHint))
            {
                foreach (var d in enumerator.EnumerateAudioEndPoints(df, DeviceState.Active))
                {
                    if (d.FriendlyName.IndexOf(deviceHint, StringComparison.OrdinalIgnoreCase) >= 0)
                    {
                        dev = d;
                        break;
                    }
                }
            }
            dev ??= enumerator.GetDefaultAudioEndpoint(df, Role.Multimedia);

            Console.Error.WriteLine("[PCMEXE] using: " + dev.FriendlyName);

            IWaveIn capture = (df == DataFlow.Capture)
                ? new WasapiCapture(dev)
                : new WasapiLoopbackCapture(dev);

            wf = capture.WaveFormat;
            inRate = wf.SampleRate;
            inCh = wf.Channels;

            Console.Error.WriteLine($"[PCMEXE] inFormat: {inRate}Hz {inCh}ch {wf.Encoding} bits={wf.BitsPerSample}");
            Console.Error.WriteLine($"[PCMEXE] outFormat: {outRate}Hz 1ch PCM16LE");

            // chunk buffer
            int outSamplesPerChunk = Math.Max(1, (outRate * chunkMs) / 1000);
            outChunk = new short[outSamplesPerChunk];
            outFill = 0;

            // resample state
            phase = 0;
            sum = 0;
            count = 0;

            // peak state
            peakHold = 0;
            lastPeakMs = Stopwatch.GetTimestamp();

            capture.DataAvailable += OnDataAvailable;
            capture.RecordingStopped += (s, e) => Console.Error.WriteLine("[PCMEXE] stopped");

            capture.StartRecording();

            // watchdog: 2s
            int t0 = Environment.TickCount;
            while (!started && Environment.TickCount - t0 < 2000) Thread.Sleep(50);
            if (!started)
            {
                Console.Error.WriteLine("[PCMEXE] FATAL: DataAvailable never fired");
                return 2;
            }

            // keep alive
            while (true) Thread.Sleep(1000);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("[PCMEXE] FATAL: " + ex);
            return 1;
        }
    }

    static void OnDataAvailable(object? sender, WaveInEventArgs e)
    {
        started = true;

        try
        {
            if (wf.Encoding == WaveFormatEncoding.IeeeFloat && wf.BitsPerSample == 32)
                DecodeFloat32(e.Buffer, e.BytesRecorded);
            else if (wf.BitsPerSample == 16)
                DecodePcm16(e.Buffer, e.BytesRecorded);
            else
                return;

            // PEAKは一定間隔で出す（行混ざり・スパム防止）
            long nowTicks = Stopwatch.GetTimestamp();
            long nowMs = (nowTicks * 1000) / Stopwatch.Frequency;
            long lastMs = (lastPeakMs * 1000) / Stopwatch.Frequency;

            if (nowMs - lastMs >= peakEveryMs)
            {
                Console.Out.WriteLine("PEAK " + peakHold);
                peakHold = 0;
                lastPeakMs = nowTicks;
            }
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("[PCMEXE] DataAvailable err: " + ex.Message);
        }
    }

    static void DecodeFloat32(byte[] buf, int bytes)
    {
        int frames = bytes / (4 * inCh);
        int ofs = 0;

        for (int f = 0; f < frames; f++)
        {
            double s = 0;
            for (int c = 0; c < inCh; c++)
            {
                float v = BitConverter.ToSingle(buf, ofs);
                ofs += 4;
                if (!float.IsNaN(v) && !float.IsInfinity(v)) s += v;
            }
            PushMono((float)(s / inCh));
        }
    }

    static void DecodePcm16(byte[] buf, int bytes)
    {
        int frames = bytes / (2 * inCh);
        int ofs = 0;

        for (int f = 0; f < frames; f++)
        {
            double s = 0;
            for (int c = 0; c < inCh; c++)
            {
                short v16 = BitConverter.ToInt16(buf, ofs);
                ofs += 2;
                s += (double)v16 / 32768.0;
            }
            PushMono((float)(s / inCh));
        }
    }

    static void PushMono(float mono)
    {
        // peak hold (0..32767)
        int a = (int)(Math.Min(1.0, Math.Abs(mono)) * 32767.0);
        if (a > peakHold) peakHold = a;

        // ===== Simple resample: average samples until phase crosses input rate =====
        // phase += outRate per input sample; if phase >= inRate -> emit one output sample
        phase += outRate;
        sum += mono;
        count++;

        if (phase < inRate) return;

        phase -= inRate;

        float avg = (count > 0) ? (float)(sum / count) : mono;
        sum = 0;
        count = 0;

        int s16 = (int)(avg * 32767.0);
        if (s16 > short.MaxValue) s16 = short.MaxValue;
        if (s16 < short.MinValue) s16 = short.MinValue;

        outChunk[outFill++] = (short)s16;

        if (outFill >= outChunk.Length)
        {
            byte[] bytes = new byte[outChunk.Length * 2];
            Buffer.BlockCopy(outChunk, 0, bytes, 0, bytes.Length);

            // Whisper向け: PCM16LE / 16kHz / mono をBase64で
            Console.Out.WriteLine("PCM " + Convert.ToBase64String(bytes));

            outFill = 0;
        }
    }
}
