namespace ScreenPinLive.Networking;

public readonly record struct NPoint(float X, float Y);
public readonly record struct Quad(NPoint TL, NPoint TR, NPoint BR, NPoint BL)
{
    public NPoint[] Points => [TL, TR, BR, BL];
}

public sealed record MobileFrame(long TimestampNs, int Width, int Height, Quad Quad, byte[] Jpeg);
