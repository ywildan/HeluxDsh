package id.helux.dsh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.MultiFormatReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen QR scanner. It reports exactly one validated payload through
 * [onScanned] and never navigates anywhere itself.
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        /** Result extra carrying the validated launch URL. */
        const val EXTRA_LAUNCH_URL = "launch-url"

        /** Result extra carrying the hostname the QR belonged to. */
        const val EXTRA_AUTHORITY = "authority"

        /** Input extra: the hostname this device is already paired with. */
        const val EXTRA_EXPECTED_HOST = "expected-host"
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    /** Typed as View: only visibility changes, not the panel's root type. */
    private lateinit var permissionPanel: View
    private lateinit var grantButton: Button
    /** Hostname already paired on this device, or null on the first pairing. */
    private var expectedHost: String? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private val delivered = AtomicBoolean(false)

    /** Reused across frames; the analyzer runs on a single thread. */
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }


    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        expectedHost = intent.getStringExtra(EXTRA_EXPECTED_HOST)?.takeIf { it.isNotEmpty() }

        previewView = findViewById(R.id.preview)
        statusText = findViewById(R.id.scan_status)
        permissionPanel = findViewById(R.id.permission_panel)
        grantButton = findViewById(R.id.grant_camera)

        grantButton.setOnClickListener { requestCamera.launch(Manifest.permission.CAMERA) }
        findViewById<Button>(R.id.cancel_scan).setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            // Ask once here; a denial falls back to the explained panel with a
            // button, so the system dialog is not re-shown on every resume.
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Explain a denied permission and offer the system settings entry.
     */
    private fun showPermissionDenied() {
        permissionPanel.visibility = View.VISIBLE
        statusText.text = getString(R.string.scan_permission_explainer)
        grantButton.text = getString(R.string.scan_permission_grant)
        grantButton.setOnClickListener { requestCamera.launch(Manifest.permission.CAMERA) }
    }

    /**
     * Bind the camera to this lifecycle and start frame analysis.
     */
    private fun startCamera() {
        permissionPanel.visibility = View.GONE
        statusText.text = getString(R.string.scan_hint)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = try {
                providerFuture.get()
            } catch (_: Exception) {
                statusText.text = getString(R.string.scan_camera_failed)
                return@addListener
            }
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val executor = Executors.newSingleThreadExecutor()
            analysisExecutor = executor
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor) { proxy -> analyze(proxy) } }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                statusText.text = getString(R.string.scan_camera_failed)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Decode one camera frame and deliver the first valid payload.
     *
     * Camera frames are not tightly packed (the Y plane usually has
     * `rowStride > width`) and are rotated relative to the screen, while ZXing
     * decodes an upright packed image only. Both are corrected here. A bad
     * frame is skipped rather than allowed to kill the app.
     *
     * @param proxy frame supplied by CameraX; always closed here.
     */
    private fun analyze(proxy: ImageProxy) {
        try {
            if (delivered.get()) return
            val frame = proxy.toUprightLuminance() ?: return
            val source = PlanarYUVLuminanceSource(
                frame.data,
                frame.width,
                frame.height,
                0,
                0,
                frame.width,
                frame.height,
                false,
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = try {
                reader.decodeWithState(bitmap)
            } catch (_: Exception) {
                null
            } finally {
                reader.reset()
            }
            if (result != null && delivered.compareAndSet(false, true)) {
                deliver(result.text)
            }
        } catch (_: Throwable) {
            // A malformed or unsupported frame must never take the app down.
        } finally {
            proxy.close()
        }
    }

    /**
     * Copy the Y plane into a packed buffer and rotate it upright.
     * @return the upright frame, or null when the frame carries no usable plane.
     */
    private fun ImageProxy.toUprightLuminance(): FrameRotation.Frame? {
        val plane = planes.firstOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        val packed = FrameRotation.pack(plane.buffer, width, height, plane.rowStride, plane.pixelStride)
        return FrameRotation.rotate(packed, width, height, imageInfo.rotationDegrees)
    }

    /**
     * Validate the scanned text and hand it back to the caller.
     * @param raw decoded QR text.
     */
    private fun deliver(raw: String) {
        runOnUiThread {
            when (val result = LaunchUrlValidator.validate(raw, expectedHost)) {
                is ScanResult.Launch -> {
                    setResult(
                        RESULT_OK,
                        intent
                            .putExtra(EXTRA_LAUNCH_URL, result.url)
                            .putExtra(EXTRA_AUTHORITY, result.authority),
                    )
                    finish()
                }
                is ScanResult.Rejected -> {
                    // Let the user scan again instead of leaving the scanner.
                    delivered.set(false)
                    statusText.text = result.reason
                    statusText.postDelayed({ if (!isFinishing) statusText.text = getString(R.string.scan_hint) }, 2500)
                }
            }
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        analysisExecutor?.shutdown()
        super.onDestroy()
    }
}
