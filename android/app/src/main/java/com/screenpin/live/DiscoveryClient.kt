package com.screenpin.live

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DiscoveryClient {
    private val executor = Executors.newCachedThreadPool()

    fun find(timeoutMs: Int = 3500, callback: (String?) -> Unit) {
        val done = AtomicBoolean(false)
        val workersLeft = AtomicInteger(2)
        val deadline = System.currentTimeMillis() + timeoutMs

        fun finish(ip: String?) {
            if (ip != null) {
                if (done.compareAndSet(false, true)) callback(ip)
                return
            }
            if (workersLeft.decrementAndGet() == 0 && done.compareAndSet(false, true)) {
                callback(null)
            }
        }

        executor.execute {
            finish(findByUdp(deadline) { done.get() })
        }
        executor.execute {
            finish(findBySubnetScan(deadline) { done.get() })
        }
    }

    private fun findByUdp(deadline: Long, cancelled: () -> Boolean): String? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(45901).apply {
                broadcast = true
                reuseAddress = true
            }

            val probe = "SCREENPIN_DISCOVER|1".toByteArray(StandardCharsets.UTF_8)
            val targets = linkedSetOf<InetAddress>()
            targets += InetAddress.getByName("255.255.255.255")

            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    ia.broadcast?.let { targets += it }
                }
            }

            for (target in targets) {
                try {
                    socket.send(DatagramPacket(probe, probe.size, target, 45901))
                } catch (_: Throwable) {
                }
            }

            val buf = ByteArray(512)
            while (!cancelled()) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                socket.soTimeout = minOf(350, left.toInt().coerceAtLeast(1))
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    if (text.startsWith("SCREENPIN|1|")) {
                        return packet.address.hostAddress
                    }
                } catch (_: SocketTimeoutException) {
                    // Re-probe while we still have time. This helps on lossy Wi-Fi.
                    for (target in targets) {
                        try {
                            socket.send(DatagramPacket(probe, probe.size, target, 45901))
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            socket?.close()
        }
        return null
    }

    private fun findBySubnetScan(deadline: Long, cancelled: () -> Boolean): String? {
        val local = findPrivateIpv4() ?: return null
        val octets = local.address.map { it.toInt() and 0xff }
        if (octets.size != 4) return null

        // Fast fallback for the common home/LAN case. Even if the actual prefix is /16,
        // the PC and phone are normally on the same /24 when they can directly reach each other.
        val prefix = "${octets[0]}.${octets[1]}.${octets[2]}."
        val pool = Executors.newFixedThreadPool(48)
        val completion = ExecutorCompletionService<String?>(pool)
        var submitted = 0
        try {
            for (host in 1..254) {
                val ip = prefix + host
                if (ip == local.hostAddress) continue
                submitted++
                completion.submit {
                    if (cancelled()) return@submit null
                    try {
                        Socket().use { s ->
                            s.tcpNoDelay = true
                            s.connect(InetSocketAddress(ip, 45900), 140)
                            ip
                        }
                    } catch (_: Throwable) {
                        null
                    }
                }
            }

            var completed = 0
            while (completed < submitted && !cancelled()) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                val f = completion.poll(minOf(80, left).coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: continue
                completed++
                val hit = try { f.get() } catch (_: Throwable) { null }
                if (hit != null) return hit
            }
        } finally {
            pool.shutdownNow()
        }
        return null
    }

    private fun findPrivateIpv4(): Inet4Address? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var fallback: Inet4Address? = null
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val a = addresses.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        if (a.isSiteLocalAddress) return a
                        if (fallback == null) fallback = a
                    }
                }
            }
            fallback
        } catch (_: Throwable) {
            null
        }
    }
}
