using System.ComponentModel;
using System.Runtime.InteropServices;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.PixelFormats;

namespace ScreenPinLive.Capture;

public sealed class WindowsDesktopCapture : IDesktopCapture
{
    public string Description => "Windows GDI primary desktop";

    public Image<Rgba32> Capture()
    {
        var sw = GetSystemMetrics(0); var sh = GetSystemMetrics(1);
        var tw = Math.Min(1280, sw); var th = Math.Max(1, (int)Math.Round(sh * (tw / (double)sw)));
        var srcDc = GetDC(IntPtr.Zero);
        var memDc = CreateCompatibleDC(srcDc);
        IntPtr bmp = IntPtr.Zero, old = IntPtr.Zero, bits = IntPtr.Zero;
        try
        {
            var bmi = new BITMAPINFO();
            bmi.bmiHeader.biSize = (uint)Marshal.SizeOf<BITMAPINFOHEADER>();
            bmi.bmiHeader.biWidth = tw;
            bmi.bmiHeader.biHeight = -th;
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = 0;
            bmp = CreateDIBSection(memDc, ref bmi, 0, out bits, IntPtr.Zero, 0);
            if (bmp == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            old = SelectObject(memDc, bmp);
            SetStretchBltMode(memDc, 4);
            if (!StretchBlt(memDc, 0, 0, tw, th, srcDc, 0, 0, sw, sh, 0x00CC0020))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            var raw = new byte[tw * th * 4];
            Marshal.Copy(bits, raw, 0, raw.Length);
            var rgba = new Rgba32[tw * th];
            for (var i = 0; i < rgba.Length; i++)
            {
                var o = i * 4;
                rgba[i] = new Rgba32(raw[o + 2], raw[o + 1], raw[o], 255);
            }
            return Image.LoadPixelData<Rgba32>(rgba, tw, th);
        }
        finally
        {
            if (old != IntPtr.Zero) SelectObject(memDc, old);
            if (bmp != IntPtr.Zero) DeleteObject(bmp);
            DeleteDC(memDc); ReleaseDC(IntPtr.Zero, srcDc);
        }
    }

    public void Dispose() { }

    [StructLayout(LayoutKind.Sequential)] private struct BITMAPINFOHEADER { public uint biSize; public int biWidth; public int biHeight; public ushort biPlanes; public ushort biBitCount; public uint biCompression; public uint biSizeImage; public int biXPelsPerMeter; public int biYPelsPerMeter; public uint biClrUsed; public uint biClrImportant; }
    [StructLayout(LayoutKind.Sequential)] private struct BITMAPINFO { public BITMAPINFOHEADER bmiHeader; public uint bmiColors; }
    [DllImport("user32.dll")] private static extern IntPtr GetDC(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern int ReleaseDC(IntPtr hWnd, IntPtr hDC);
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int nIndex);
    [DllImport("gdi32.dll")] private static extern IntPtr CreateCompatibleDC(IntPtr hdc);
    [DllImport("gdi32.dll", SetLastError = true)] private static extern IntPtr CreateDIBSection(IntPtr hdc, ref BITMAPINFO pbmi, uint usage, out IntPtr ppvBits, IntPtr hSection, uint offset);
    [DllImport("gdi32.dll")] private static extern IntPtr SelectObject(IntPtr hdc, IntPtr obj);
    [DllImport("gdi32.dll")] private static extern bool DeleteObject(IntPtr obj);
    [DllImport("gdi32.dll")] private static extern bool DeleteDC(IntPtr hdc);
    [DllImport("gdi32.dll")] private static extern int SetStretchBltMode(IntPtr hdc, int mode);
    [DllImport("gdi32.dll", SetLastError = true)] private static extern bool StretchBlt(IntPtr hdcDest, int xDest, int yDest, int wDest, int hDest, IntPtr hdcSrc, int xSrc, int ySrc, int wSrc, int hSrc, uint rop);
}
