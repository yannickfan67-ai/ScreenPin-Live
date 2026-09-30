package com.screenpin.live

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class DiscoveryClient {
    private val executor = Executors.newSingleThreadExecutor()

    fun find(timeoutMs: Int = 3500, callback: (String?) -> Unit) {
        executor.execute {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(45901).apply {
                    soTimeout = timeoutMs
                    broadcast = true
                    reuseAddress = true
                }
                val buf = ByteArray(512)
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                if (text.startsWith("SCREENPIN|1|")) callback(packet.address.hostAddress) else callback(null)
            } catch (_: SocketTimeoutException) {
                callback(null)
            } catch (_: Throwable) {
                callback(null)
            } finally {
                socket?.close()
            }
        }
    }
}
