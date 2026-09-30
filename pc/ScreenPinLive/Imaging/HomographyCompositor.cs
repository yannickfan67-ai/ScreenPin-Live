using ScreenPinLive.Networking;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.PixelFormats;

namespace ScreenPinLive.Imaging;

public static class HomographyCompositor
{
    public static Image<Rgba32> Compose(Image<Rgba32> camera, Image<Rgba32> screen, Quad q, float opacity)
    {
        opacity = Math.Clamp(opacity, 0f, 1f);
        var dw = camera.Width; var dh = camera.Height;
        var sw = screen.Width; var sh = screen.Height;
        var dst = new Rgba32[dw * dh]; camera.CopyPixelDataTo(dst);
        var src = new Rgba32[sw * sh]; screen.CopyPixelDataTo(src);

        var d = new[] {
            (x: q.TL.X * (dw - 1), y: q.TL.Y * (dh - 1)),
            (x: q.TR.X * (dw - 1), y: q.TR.Y * (dh - 1)),
            (x: q.BR.X * (dw - 1), y: q.BR.Y * (dh - 1)),
            (x: q.BL.X * (dw - 1), y: q.BL.Y * (dh - 1))
        };
        var s = new[] { (0f, 0f), ((float)sw - 1, 0f), ((float)sw - 1, (float)sh - 1), (0f, (float)sh - 1) };
        var h = SolveHomography(s, d);
        var inv = Invert3x3(h);

        var minX = Math.Clamp((int)Math.Floor(d.Min(p => p.x)), 0, dw - 1);
        var maxX = Math.Clamp((int)Math.Ceiling(d.Max(p => p.x)), 0, dw - 1);
        var minY = Math.Clamp((int)Math.Floor(d.Min(p => p.y)), 0, dh - 1);
        var maxY = Math.Clamp((int)Math.Ceiling(d.Max(p => p.y)), 0, dh - 1);

        Parallel.For(minY, maxY + 1, y =>
        {
            for (var x = minX; x <= maxX; x++)
            {
                if (!InsideConvex(d, x + .5f, y + .5f)) continue;
                var z = inv[6] * x + inv[7] * y + inv[8];
                if (Math.Abs(z) < 1e-9) continue;
                var sx = (inv[0] * x + inv[1] * y + inv[2]) / z;
                var sy = (inv[3] * x + inv[4] * y + inv[5]) / z;
                if (sx < 0 || sy < 0 || sx > sw - 1 || sy > sh - 1) continue;
                var c = Bilinear(src, sw, sh, sx, sy);
                var i = y * dw + x;
                if (opacity >= .999f) dst[i] = c;
                else {
                    var b = dst[i];
                    dst[i] = new Rgba32(
                        (byte)(b.R * (1 - opacity) + c.R * opacity),
                        (byte)(b.G * (1 - opacity) + c.G * opacity),
                        (byte)(b.B * (1 - opacity) + c.B * opacity), 255);
                }
            }
        });
        return Image.LoadPixelData<Rgba32>(dst, dw, dh);
    }

    private static Rgba32 Bilinear(Rgba32[] p, int w, int h, double x, double y)
    {
        var x0 = Math.Clamp((int)x, 0, w - 1); var y0 = Math.Clamp((int)y, 0, h - 1);
        var x1 = Math.Min(x0 + 1, w - 1); var y1 = Math.Min(y0 + 1, h - 1);
        var fx = x - x0; var fy = y - y0;
        var a = p[y0 * w + x0]; var b = p[y0 * w + x1]; var c = p[y1 * w + x0]; var d = p[y1 * w + x1];
        byte mix(byte aa, byte bb, byte cc, byte dd) => (byte)Math.Clamp(
            aa * (1 - fx) * (1 - fy) + bb * fx * (1 - fy) + cc * (1 - fx) * fy + dd * fx * fy, 0, 255);
        return new Rgba32(mix(a.R,b.R,c.R,d.R), mix(a.G,b.G,c.G,d.G), mix(a.B,b.B,c.B,d.B), 255);
    }

    private static bool InsideConvex((float x,float y)[] p, float x, float y)
    {
        float? sign = null;
        for (var i = 0; i < 4; i++) {
            var a = p[i]; var b = p[(i + 1) & 3];
            var cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x);
            if (Math.Abs(cross) < .001f) continue;
            var s = cross > 0;
            if (sign == null) sign = s ? 1 : -1;
            else if ((sign > 0) != s) return false;
        }
        return true;
    }

    private static double[] SolveHomography((float x,float y)[] src, (float x,float y)[] dst)
    {
        var a = new double[8,9];
        for (var i = 0; i < 4; i++) {
            var x=src[i].x; var y=src[i].y; var u=dst[i].x; var v=dst[i].y;
            var r=i*2;
            a[r,0]=x; a[r,1]=y; a[r,2]=1; a[r,6]=-u*x; a[r,7]=-u*y; a[r,8]=u;
            a[r+1,3]=x; a[r+1,4]=y; a[r+1,5]=1; a[r+1,6]=-v*x; a[r+1,7]=-v*y; a[r+1,8]=v;
        }
        for (var col=0; col<8; col++) {
            var pivot=col;
            for (var r=col+1; r<8; r++) if (Math.Abs(a[r,col])>Math.Abs(a[pivot,col])) pivot=r;
            if (Math.Abs(a[pivot,col])<1e-12) throw new InvalidOperationException("Degenerate quad");
            if (pivot!=col) for (var c=col;c<9;c++) (a[col,c],a[pivot,c])=(a[pivot,c],a[col,c]);
            var div=a[col,col]; for (var c=col;c<9;c++) a[col,c]/=div;
            for (var r=0;r<8;r++) if (r!=col) { var f=a[r,col]; for (var c=col;c<9;c++) a[r,c]-=f*a[col,c]; }
        }
        return [a[0,8],a[1,8],a[2,8],a[3,8],a[4,8],a[5,8],a[6,8],a[7,8],1];
    }

    private static double[] Invert3x3(double[] m)
    {
        double a=m[0], b=m[1], c=m[2], d=m[3], e=m[4], f=m[5], g=m[6], h=m[7], i=m[8];
        var A=e*i-f*h; var B=-(d*i-f*g); var C=d*h-e*g;
        var D=-(b*i-c*h); var E=a*i-c*g; var F=-(a*h-b*g);
        var G=b*f-c*e; var H=-(a*f-c*d); var I=a*e-b*d;
        var det=a*A+b*B+c*C;
        if (Math.Abs(det)<1e-12) throw new InvalidOperationException("Degenerate homography");
        return [A/det,D/det,G/det,B/det,E/det,H/det,C/det,F/det,I/det];
    }
}
