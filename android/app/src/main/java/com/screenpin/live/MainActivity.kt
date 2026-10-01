package com.screenpin.live

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: TrackerOverlay
    private lateinit var status: TextView
    private lateinit var host: EditText
    private lateinit var lockButton: Button
    private val analyzer = Executors.newSingleThreadExecutor()
    private val tracker = ScreenTracker()
    private lateinit var streamer: FrameStreamer
    private val discovery = DiscoveryClient()

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "OpenCV failed to initialize", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        streamer = FrameStreamer { msg -> runOnUiThread { status.text = msg } }
        buildUi()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
        overlay = TrackerOverlay(this)
        root.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(0x99000000.toInt())
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = "Tracking locally — not connected"
        }
        host = EditText(this).apply {
            hint = "PC IP"
            setHintTextColor(0xFFAAAAAA.toInt())
            setTextColor(Color.WHITE)
            setSingleLine(true)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val find = Button(this).apply {
            text = "Auto find PC"
            setOnClickListener {
                status.text = "Searching LAN…"
                discovery.find { ip -> runOnUiThread {
                    if (ip != null) {
                        host.setText(ip)
                        status.text = "Found $ip — connecting…"
                        streamer.connect(ip)
                    } else status.text = "PC not found"
                } }
            }
        }
        val connect = Button(this).apply {
            text = "Connect"
            setOnClickListener {
                val ip = host.text.toString().trim()
                if (ip.isNotEmpty()) streamer.connect(ip) else Toast.makeText(this@MainActivity, "Enter PC IP or use Auto find", Toast.LENGTH_SHORT).show()
            }
        }
        lockButton = Button(this).apply {
            text = "Freeze quad"
            setOnClickListener {
                tracker.frozen = !tracker.frozen
                text = if (tracker.frozen) "Resume tracking" else "Freeze quad"
            }
        }
        val clear = Button(this).apply {
            text = "Clear"
            setOnClickListener { tracker.clear(); this@MainActivity.overlay.quad = null }
        }
        row.addView(find)
        row.addView(connect)
        row.addView(lockButton)
        row.addView(clear)
        panel.addView(status)
        panel.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(row)

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        }
        root.addView(panel, lp)
        setContentView(root)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetResolution(Size(960, 540))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analyzer) { image -> process(image) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (t: Throwable) {
                status.text = "Camera error: ${t.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun process(image: ImageProxy) {
        var rgba: Mat? = null
        var rotated: Mat? = null
        try {
            rgba = imageToRgba(image)
            rotated = rotate(rgba, image.imageInfo.rotationDegrees)
            val q = tracker.detect(rotated)
            if (q != null) {
                overlay.quad = q
                val jpeg = encodeJpeg(rotated)
                streamer.offer(OutgoingFrame(System.nanoTime(), rotated.cols(), rotated.rows(), q, jpeg))
            }
        } catch (_: Throwable) {
            // Keep analysis alive; status updates are intentionally not spammed per frame.
        } finally {
            if (rotated !== rgba) rotated?.release()
            rgba?.release()
            image.close()
        }
    }

    private fun imageToRgba(image: ImageProxy): Mat {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val out = Mat(h, w, CvType.CV_8UC4)
        val row = ByteArray(rowStride)
        val compact = ByteArray(w * 4)
        buffer.rewind()
        for (y in 0 until h) {
            val remaining = buffer.remaining()
            if (remaining <= 0) break
            val n = minOf(rowStride, remaining)
            buffer.get(row, 0, n)
            if (pixelStride == 4) {
                System.arraycopy(row, 0, compact, 0, minOf(compact.size, n))
            } else {
                for (x in 0 until w) {
                    val s = x * pixelStride
                    val d = x * 4
                    if (s + 3 < n) {
                        compact[d] = row[s]
                        compact[d + 1] = row[s + 1]
                        compact[d + 2] = row[s + 2]
                        compact[d + 3] = row[s + 3]
                    }
                }
            }
            out.put(y, 0, compact)
        }
        return out
    }

    private fun rotate(src: Mat, degrees: Int): Mat {
        if (degrees == 0) return src
        val dst = Mat()
        when (degrees) {
            90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, dst, Core.ROTATE_180)
            270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> src.copyTo(dst)
        }
        return dst
    }

    private fun encodeJpeg(rgba: Mat): ByteArray {
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        val mob = MatOfByte()
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 78)
        Imgcodecs.imencode(".jpg", bgr, mob, params)
        val bytes = mob.toArray()
        params.release(); mob.release(); bgr.release()
        return bytes
    }

    override fun onDestroy() {
        streamer.disconnect()
        analyzer.shutdownNow()
        super.onDestroy()
    }
}
