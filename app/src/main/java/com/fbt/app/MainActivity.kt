package com.fbt.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var settings: SettingsStore
    private lateinit var cameraExecutor: ExecutorService

    private val oscSender = OscSender()
    private var poseProcessor: PoseProcessor? = null
    private var tracking = false

    // slot order VRChat's OSCTrackers convention expects
    private val slotOrder = listOf("hip", "chest", "ankleL", "ankleR", "kneeL", "kneeR", "elbowL", "elbowR")

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        settings = SettingsStore(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val editHost = findViewById<EditText>(R.id.editHost)
        val editPort = findViewById<EditText>(R.id.editPort)
        editHost.setText(settings.oscHost)
        editPort.setText(settings.oscPort.toString())

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val host = editHost.text.toString().ifBlank { "127.0.0.1" }
            val port = editPort.text.toString().toIntOrNull() ?: 9000
            settings.oscHost = host
            settings.oscPort = port
            oscSender.host = host
            oscSender.port = port
            Toast.makeText(this, "Saved: $host:$port", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnToggle).setOnClickListener { btn ->
            tracking = !tracking
            (btn as Button).text = if (tracking) "Stop tracking" else "Start tracking"
            statusText.text = if (tracking) "Tracking..." else "Stopped"
        }

        oscSender.host = settings.oscHost
        oscSender.port = settings.oscPort
        oscSender.start()

        poseProcessor = PoseProcessor(this) { worldLandmarks -> onPose(worldLandmarks) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (tracking) {
                    val bitmap = imageProxyToBitmap(imageProxy)
                    if (bitmap != null) {
                        poseProcessor?.process(bitmap, System.currentTimeMillis())
                    }
                }
                imageProxy.close()
            }

            val selector = CameraSelector.DEFAULT_FRONT_CAMERA

            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onPose(worldLandmarks: List<Vec3>) {
        val points = BodyMapper.map(worldLandmarks)
        for ((slotIndex, key) in slotOrder.withIndex()) {
            val p = points[key] ?: continue
            val n = slotIndex + 1
            oscSender.sendFloats("/tracking/trackers/$n/position", floatArrayOf(p.position.x, p.position.y, p.position.z))
            val euler = p.rotation.toEulerDegrees()
            oscSender.sendFloats("/tracking/trackers/$n/rotation", floatArrayOf(euler.x, euler.y, euler.z))
        }
    }

    /** Straightforward YUV_420_888 -> Bitmap conversion (simple, not the fastest, fine for v0). */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
            val bytes = out.toByteArray()
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        oscSender.stop()
        poseProcessor?.close()
        cameraExecutor.shutdown()
    }
}
