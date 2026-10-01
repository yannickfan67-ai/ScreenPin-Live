using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace ScreenPinLive.Networking;

public sealed class DiscoveryBroadcaster : IAsyncDisposable
{
    private const int DiscoveryPort = 45901;
    private static readonly byte[] Announcement = Encoding.UTF8.GetBytes("SCREENPIN|1|45900");
    private static readonly byte[] Probe = Encoding.UTF8.GetBytes("SCREENPIN_DISCOVER|1");

    private readonly CancellationTokenSource _cts = new();
    private readonly Task _task;
    private readonly SemaphoreSlim _sendGate = new(1, 1);

    public DiscoveryBroadcaster() => _task = Run(_cts.Token);

    private async Task Run(CancellationToken ct)
    {
        using var udp = new UdpClient(AddressFamily.InterNetwork) { EnableBroadcast = true };
        udp.Client.ExclusiveAddressUse = false;
        udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        udp.Client.Bind(new IPEndPoint(IPAddress.Any, DiscoveryPort));

        var announceTask = AnnounceLoop(udp, ct);
        var receiveTask = ReceiveLoop(udp, ct);
        try { await Task.WhenAll(announceTask, receiveTask); }
        catch (OperationCanceledException) { }
    }

    private async Task ReceiveLoop(UdpClient udp, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            UdpReceiveResult packet;
            try { packet = await udp.ReceiveAsync(ct); }
            catch (OperationCanceledException) { break; }
            catch { continue; }

            if (!packet.Buffer.AsSpan().SequenceEqual(Probe)) continue;
            var reply = new IPEndPoint(packet.RemoteEndPoint.Address, packet.RemoteEndPoint.Port);
            try { await Send(udp, Announcement, reply, ct); } catch { }
        }
    }

    private async Task AnnounceLoop(UdpClient udp, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            foreach (var address in BroadcastTargets())
            {
                try { await Send(udp, Announcement, new IPEndPoint(address, DiscoveryPort), ct); }
                catch { }
            }
            try { await Task.Delay(1000, ct); }
            catch (OperationCanceledException) { break; }
        }
    }

    private async Task Send(UdpClient udp, byte[] payload, IPEndPoint target, CancellationToken ct)
    {
        await _sendGate.WaitAsync(ct);
        try { await udp.SendAsync(payload, target, ct); }
        finally { _sendGate.Release(); }
    }

    private static IEnumerable<IPAddress> BroadcastTargets()
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        if (seen.Add(IPAddress.Broadcast.ToString())) yield return IPAddress.Broadcast;

        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up ||
                ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

            foreach (var ua in ni.GetIPProperties().UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                IPAddress? mask;
                try { mask = ua.IPv4Mask; } catch { continue; }
                if (mask is null) continue;

                var ip = ua.Address.GetAddressBytes();
                var m = mask.GetAddressBytes();
                if (ip.Length != 4 || m.Length != 4) continue;
                var b = new byte[4];
                for (var i = 0; i < 4; i++) b[i] = (byte)((ip[i] & m[i]) | (~m[i] & 0xff));
                var broadcast = new IPAddress(b);
                if (seen.Add(broadcast.ToString())) yield return broadcast;
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        _cts.Cancel();
        try { await _task; } catch { }
        _sendGate.Dispose();
        _cts.Dispose();
    }
}
