using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace ScreenPinLive.Networking;

public sealed class MobileReceiver : IAsyncDisposable
{
    private TcpListener? _listener;
    private CancellationTokenSource? _cts;
    private Task? _acceptLoop;
    public event Action<string>? Status;
    public event Func<MobileFrame, Task>? Frame;
    public bool IsRunning => _listener != null;

    public void Start(int port = 45900)
    {
        if (_listener != null) return;
        _cts = new CancellationTokenSource();
        _listener = new TcpListener(IPAddress.Any, port);
        _listener.Start();
        Status?.Invoke($"Listening on TCP {port}");
        _acceptLoop = AcceptLoop(_cts.Token);
    }

    public async Task StopAsync()
    {
        _cts?.Cancel();
        _listener?.Stop();
        _listener = null;
        if (_acceptLoop != null) {
            try { await _acceptLoop; } catch { }
        }
        Status?.Invoke("Server stopped");
    }

    private async Task AcceptLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _listener != null)
        {
            TcpClient? client = null;
            try
            {
                client = await _listener.AcceptTcpClientAsync(ct);
                client.NoDelay = true;
                var ep = client.Client.RemoteEndPoint?.ToString() ?? "mobile";
                Status?.Invoke($"Connected: {ep}");
                await ReadClient(client, ct);
            }
            catch (OperationCanceledException) { }
            catch (Exception ex) { if (!ct.IsCancellationRequested) Status?.Invoke($"Connection error: {ex.Message}"); }
            finally { client?.Dispose(); }
            if (!ct.IsCancellationRequested) Status?.Invoke("Waiting for mobile…");
        }
    }

    private async Task ReadClient(TcpClient client, CancellationToken ct)
    {
        var s = client.GetStream();
        var magic = new byte[4];
        while (!ct.IsCancellationRequested)
        {
            await ReadExactly(s, magic, ct);
            if (!(magic[0] == (byte)'S' && magic[1] == (byte)'P' && magic[2] == (byte)'L' && magic[3] == (byte)'1'))
                throw new InvalidDataException("Bad frame magic");
            var version = await ReadI32(s, ct);
            if (version != 1) throw new InvalidDataException($"Unsupported protocol {version}");
            var ts = await ReadI64(s, ct);
            var w = await ReadI32(s, ct);
            var h = await ReadI32(s, ct);
            if (w is < 16 or > 8192 || h is < 16 or > 8192) throw new InvalidDataException("Invalid frame size");
            var p = new NPoint[4];
            for (var i = 0; i < 4; i++) p[i] = new NPoint(await ReadF32(s, ct), await ReadF32(s, ct));
            var len = await ReadI32(s, ct);
            if (len is < 64 or > 20_000_000) throw new InvalidDataException("Invalid JPEG size");
            var jpg = new byte[len];
            await ReadExactly(s, jpg, ct);
            var frame = new MobileFrame(ts, w, h, new Quad(p[0], p[1], p[2], p[3]), jpg);
            var handler = Frame;
            if (handler != null) await handler(frame);
        }
    }

    private static async Task ReadExactly(Stream s, byte[] b, CancellationToken ct)
    {
        var off = 0;
        while (off < b.Length)
        {
            var n = await s.ReadAsync(b.AsMemory(off), ct);
            if (n == 0) throw new EndOfStreamException();
            off += n;
        }
    }

    private static async Task<int> ReadI32(Stream s, CancellationToken ct)
    {
        var b = new byte[4]; await ReadExactly(s, b, ct); return BinaryPrimitives.ReadInt32BigEndian(b);
    }
    private static async Task<long> ReadI64(Stream s, CancellationToken ct)
    {
        var b = new byte[8]; await ReadExactly(s, b, ct); return BinaryPrimitives.ReadInt64BigEndian(b);
    }
    private static async Task<float> ReadF32(Stream s, CancellationToken ct)
    {
        var raw = await ReadI32(s, ct); return BitConverter.Int32BitsToSingle(raw);
    }

    public async ValueTask DisposeAsync() => await StopAsync();
}
