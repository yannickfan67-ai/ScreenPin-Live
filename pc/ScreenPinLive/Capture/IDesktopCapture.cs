using SixLabors.ImageSharp;
using SixLabors.ImageSharp.PixelFormats;

namespace ScreenPinLive.Capture;

public interface IDesktopCapture : IDisposable
{
    Image<Rgba32> Capture();
    string Description { get; }

    static IDesktopCapture CreateDefault()
    {
        if (OperatingSystem.IsWindows()) return new WindowsDesktopCapture();
        if (OperatingSystem.IsLinux()) return new X11DesktopCapture();
        throw new PlatformNotSupportedException("Screen capture is implemented for Windows and X11 Linux.");
    }
}
