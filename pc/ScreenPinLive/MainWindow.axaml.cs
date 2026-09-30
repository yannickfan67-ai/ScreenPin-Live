using System.Diagnostics;
using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Threading;
using ScreenPinLive.Capture;
using ScreenPinLive.Imaging;
using ScreenPinLive.Networking;
using ScreenPinLive.Recording;
using SixLabors.ImageSharp;
using SharpImage = SixLabors.ImageSharp.Image;
using SixLabors.ImageSharp.PixelFormats;

namespace ScreenPinLive;

public partial class MainWindow : Window
{
    private readonly MobileReceiver _receiver = new();
    private readonly DiscoveryBroadcaster _discovery = new();
    private readonly FfmpegRecorder _recorder = new();
    private readonly IDesktopCapture _capture;
    private readonly SemaphoreSlim _frameGate = new(1, 1);
    private WriteableBitmap? _bitmap;
    private bool _serverRunning;
    private long _frames;
    private readonly Stopwatch _fpsWatch = Stopwatch.StartNew();
    private SixLabors.ImageSharp.Image<Rgba32>? _lastFrame;

    public MainWindow()
    {
        InitializeComponent();
        _capture = IDesktopCapture.CreateDefault();
        _receiver.Status += s => Dispatcher.UIThread.Post(() => StatusText.Text = s);
        _receiver.Frame += OnMobileFrame;
        ServerButton.Click += async (_, _) => await ToggleServer();
        RecordButton.Click += (_, _) => ToggleRecord();
        SnapshotButton.Click += (_, _) => Snapshot();
        Opened += (_, _) => StartServer();
        Closed += (_, _) => Cleanup();
    }

    private void StartServer()
    {
        if (_serverRunning) return;
        _receiver.Start(); _serverRunning = true; ServerButton.Content = "Stop server";
        StatusText.Text = $"Listening · {_capture.Description}";
    }

    private async Task ToggleServer()
    {
        if (_serverRunning) { await _receiver.StopAsync(); _serverRunning = false; ServerButton.Content = "Start server"; }
        else StartServer();
    }

    private async Task OnMobileFrame(MobileFrame f)
    {
        if (!await _frameGate.WaitAsync(0)) return;
        try
        {
            using var camera = SharpImage.Load<Rgba32>(f.Jpeg);
            using var screen = _capture.Capture();
            var opacity = await Dispatcher.UIThread.InvokeAsync(() => (float)OpacitySlider.Value);
            using var composed = HomographyCompositor.Compose(camera, screen, f.Quad, opacity);
            if (_recorder.IsRecording) await _recorder.WriteAsync(composed);
            _lastFrame?.Dispose(); _lastFrame = composed.Clone();
            var pixels = new byte[composed.Width * composed.Height * 4]; composed.CopyPixelDataTo(pixels);
            await Dispatcher.UIThread.InvokeAsync(() => UpdatePreview(pixels, composed.Width, composed.Height));
            _frames++;
            if (_fpsWatch.ElapsedMilliseconds > 1000)
            {
                var fps = _frames / _fpsWatch.Elapsed.TotalSeconds;
                _frames = 0; _fpsWatch.Restart();
                Dispatcher.UIThread.Post(() => PerfText.Text = $"{fps:0.0} fps · {f.Width}×{f.Height}");
            }
        }
        catch (Exception ex)
        {
            Dispatcher.UIThread.Post(() => StatusText.Text = $"Frame error: {ex.Message}");
        }
        finally { _frameGate.Release(); }
    }

    private void UpdatePreview(byte[] rgba, int w, int h)
    {
        if (_bitmap == null || _bitmap.PixelSize.Width != w || _bitmap.PixelSize.Height != h)
        {
            _bitmap?.Dispose();
            _bitmap = new WriteableBitmap(new PixelSize(w, h), new Vector(96,96), PixelFormat.Rgba8888, AlphaFormat.Unpremul);
            PreviewImage.Source = _bitmap;
        }
        using var fb = _bitmap.Lock();
        var rowBytes = w * 4;
        for (var y = 0; y < h; y++) Marshal.Copy(rgba, y * rowBytes, fb.Address + y * fb.RowBytes, rowBytes);
    }

    private void ToggleRecord()
    {
        if (_recorder.IsRecording)
        {
            _recorder.Stop(); RecordButton.Content = "Record";
            StatusText.Text = $"Saved: {_recorder.OutputPath}";
            return;
        }
        if (_lastFrame == null) { StatusText.Text = "Wait for the first mobile frame before recording"; return; }
        try
        {
            _recorder.Start(_lastFrame.Width, _lastFrame.Height, 30);
            RecordButton.Content = "Stop recording";
            StatusText.Text = $"Recording → {_recorder.OutputPath}";
        }
        catch (Exception ex) { StatusText.Text = $"Recording requires ffmpeg in PATH: {ex.Message}"; }
    }

    private void Snapshot()
    {
        if (_lastFrame == null) return;
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyPictures), "ScreenPinLive");
        if (string.IsNullOrWhiteSpace(dir)) dir = Path.Combine(Environment.CurrentDirectory, "snapshots");
        Directory.CreateDirectory(dir);
        var path = Path.Combine(dir, $"ScreenPinLive_{DateTime.Now:yyyyMMdd_HHmmss}.png");
        _lastFrame.SaveAsPng(path); StatusText.Text = $"Snapshot: {path}";
    }

    private void Cleanup()
    {
        try { _recorder.Dispose(); } catch { }
        try { _capture.Dispose(); } catch { }
        try { _lastFrame?.Dispose(); } catch { }
        try { _bitmap?.Dispose(); } catch { }
        _ = _receiver.DisposeAsync(); _ = _discovery.DisposeAsync();
    }
}
