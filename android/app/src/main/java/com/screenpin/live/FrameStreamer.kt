package com.screenpin.live

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class OutgoingFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val quad: Quad,
    val jpeg: ByteArray
)

class FrameStreamer(private val onStatus: (String) -> Unit) {
    private val queue = ArrayBlockingQueue<OutgoingFrame>(2)
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null

    fun connect(host: String, port: Int = 45900) {
        disconnect()
        running.set(true)
        worker.execute {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 2500)
                socket = s
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 256 * 1024))
                onStatus("Connected: $host:$port")
                while (running.get() && !s.isClosed) {
                    val f = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    out.write(byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte()))
                    out.writeInt(1)
                    out.writeLong(f.timestampNs)
                    out.writeInt(f.width)
                    out.writeInt(f.height)
                    for (p in f.quad.asList()) {
                        out.writeFloat(p.x)
                        out.writeFloat(p.y)
                    }
                    out.writeInt(f.jpeg.size)
                    out.write(f.jpeg)
                    out.flush()
                }
            } catch (t: Throwable) {
                if (running.get()) onStatus("Disconnected: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                try { socket?.close() } catch (_: Throwable) {}
                socket = null
                running.set(false)
            }
        }
    }

    fun offer(frame: OutgoingFrame) {
        if (!running.get()) return
        if (!queue.offer(frame)) {
            queue.poll()
            queue.offer(frame)
        }
    }

    fun disconnect() {
        running.set(false)
        queue.clear()
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
    }
}
