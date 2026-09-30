using System.Net;
using System.Net.Sockets;
using System.Text;

namespace ScreenPinLive.Networking;

public sealed class DiscoveryBroadcaster : IAsyncDisposable
{
    private readonly CancellationTokenSource _cts = new();
    private readonly Task _task;

    public DiscoveryBroadcaster() => _task = Run(_cts.Token);

    private static async Task Run(CancellationToken ct)
    {
        using var udp = new UdpClient { EnableBroadcast = true };
        var data = Encoding.UTF8.GetBytes("SCREENPIN|1|45900");
        var ep = new IPEndPoint(IPAddress.Broadcast, 45901);
        while (!ct.IsCancellationRequested)
        {
            try { await udp.SendAsync(data, ep, ct); } catch { }
            try { await Task.Delay(1000, ct); } catch { }
        }
    }

    public async ValueTask DisposeAsync()
    {
        _cts.Cancel();
        try { await _task; } catch { }
        _cts.Dispose();
    }
}
