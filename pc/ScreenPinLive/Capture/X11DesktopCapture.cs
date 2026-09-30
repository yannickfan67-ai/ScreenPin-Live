using System.Numerics;
using System.Runtime.InteropServices;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.PixelFormats;
using SixLabors.ImageSharp.Processing;

namespace ScreenPinLive.Capture;

public sealed class X11DesktopCapture : IDesktopCapture
{
    private readonly IntPtr _display;
    private readonly IntPtr _root;
    public string Description => "X11 root desktop";

    public X11DesktopCapture()
    {
        _display = XOpenDisplay(IntPtr.Zero);
        if (_display == IntPtr.Zero) throw new InvalidOperationException("Cannot open X11 display. DISPLAY is missing or this is a native Wayland session.");
        _root = XDefaultRootWindow(_display);
    }

    public Image<Rgba32> Capture()
    {
        if (XGetGeometry(_display, _root, out _, out _, out _, out var w, out var h, out _, out _) == 0)
            throw new InvalidOperationException("XGetGeometry failed");
        var ptr = XGetImage(_display, _root, 0, 0, w, h, ulong.MaxValue, 2);
        if (ptr == IntPtr.Zero) throw new InvalidOperationException("XGetImage failed");
        try
        {
            var xi = Marshal.PtrToStructure<XImage>(ptr);
            if (xi.bits_per_pixel != 32 && xi.bits_per_pixel != 24)
                throw new NotSupportedException($"Unsupported X11 bpp {xi.bits_per_pixel}");
            var pixels = new Rgba32[(int)(w * h)];
            var rShift = BitOperations.TrailingZeroCount(xi.red_mask);
            var gShift = BitOperations.TrailingZeroCount(xi.green_mask);
            var bShift = BitOperations.TrailingZeroCount(xi.blue_mask);
            var rMax = xi.red_mask >> rShift; var gMax = xi.green_mask >> gShift; var bMax = xi.blue_mask >> bShift;
            for (var y = 0; y < h; y++)
            {
                var row = xi.data + y * xi.bytes_per_line;
                for (var x = 0; x < w; x++)
                {
                    ulong px;
                    if (xi.bits_per_pixel == 32) px = unchecked((uint)Marshal.ReadInt32(row, (int)x * 4));
                    else {
                        var o = (int)x * 3;
                        px = (ulong)(byte)Marshal.ReadByte(row, o) | ((ulong)(byte)Marshal.ReadByte(row, o + 1) << 8) | ((ulong)(byte)Marshal.ReadByte(row, o + 2) << 16);
                    }
                    byte cv(ulong mask, int shift, ulong max) => max == 0 ? (byte)0 : (byte)(((px & mask) >> shift) * 255 / max);
                    pixels[(int)(y * w + x)] = new Rgba32(cv(xi.red_mask, rShift, rMax), cv(xi.green_mask, gShift, gMax), cv(xi.blue_mask, bShift, bMax), 255);
                }
            }
            var img = Image.LoadPixelData<Rgba32>(pixels, (int)w, (int)h);
            if (w > 1280)
            {
                var nh = Math.Max(1, (int)Math.Round(h * (1280.0 / w)));
                img.Mutate(c => c.Resize(1280, nh));
            }
            return img;
        }
        finally { XDestroyImage(ptr); }
    }

    public void Dispose() { if (_display != IntPtr.Zero) XCloseDisplay(_display); }

    [StructLayout(LayoutKind.Sequential)]
    private struct XImage
    {
        public int width, height, xoffset, format;
        public IntPtr data;
        public int byte_order, bitmap_unit, bitmap_bit_order, bitmap_pad, depth, bytes_per_line, bits_per_pixel;
        public ulong red_mask, green_mask, blue_mask;
        public IntPtr obdata;
        public IntPtr funcs_create_image, funcs_destroy_image, funcs_get_pixel, funcs_put_pixel, funcs_sub_image, funcs_add_pixel;
    }

    [DllImport("libX11.so.6")] private static extern IntPtr XOpenDisplay(IntPtr display);
    [DllImport("libX11.so.6")] private static extern int XCloseDisplay(IntPtr display);
    [DllImport("libX11.so.6")] private static extern IntPtr XDefaultRootWindow(IntPtr display);
    [DllImport("libX11.so.6")] private static extern int XGetGeometry(IntPtr display, IntPtr drawable, out IntPtr root, out int x, out int y, out uint width, out uint height, out uint borderWidth, out uint depth);
    [DllImport("libX11.so.6")] private static extern IntPtr XGetImage(IntPtr display, IntPtr drawable, int x, int y, uint width, uint height, ulong planeMask, int format);
    [DllImport("libX11.so.6")] private static extern int XDestroyImage(IntPtr image);
}
