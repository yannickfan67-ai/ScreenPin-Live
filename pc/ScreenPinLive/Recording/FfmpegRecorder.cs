using System.Diagnostics;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.PixelFormats;

namespace ScreenPinLive.Recording;

public sealed class FfmpegRecorder : IDisposable
{
    private Process? _process;
    private Stream? _stdin;
    public bool IsRecording => _process is { HasExited: false };
    public string? OutputPath { get; private set; }

    public void Start(int width, int height, double fps = 30)
    {
        if (IsRecording) return;
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyVideos), "ScreenPinLive");
        if (string.IsNullOrWhiteSpace(dir)) dir = Path.Combine(Environment.CurrentDirectory, "recordings");
        Directory.CreateDirectory(dir);
        OutputPath = Path.Combine(dir, $"ScreenPinLive_{DateTime.Now:yyyyMMdd_HHmmss}.mp4");
        var psi = new ProcessStartInfo("ffmpeg") {
            UseShellExecute = false, RedirectStandardInput = true, RedirectStandardError = false, CreateNoWindow = true,
            Arguments = $"-hide_banner -loglevel warning -y -f rawvideo -pix_fmt rgba -s {width}x{height} -r {fps:0.###} -i - -an -c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p \"{OutputPath}\""
        };
        _process = Process.Start(psi) ?? throw new InvalidOperationException("Failed to start ffmpeg");
        _stdin = _process.StandardInput.BaseStream;
    }

    public async Task WriteAsync(Image<Rgba32> frame)
    {
        if (!IsRecording || _stdin == null) return;
        var bytes = new byte[frame.Width * frame.Height * 4];
        frame.CopyPixelDataTo(bytes);
        await _stdin.WriteAsync(bytes);
    }

    public void Stop()
    {
        try { _stdin?.Flush(); _stdin?.Dispose(); } catch { }
        _stdin = null;
        try { if (_process is { HasExited: false }) _process.WaitForExit(2500); } catch { try { _process?.Kill(true); } catch { } }
        _process?.Dispose(); _process = null;
    }

    public void Dispose() => Stop();
}
