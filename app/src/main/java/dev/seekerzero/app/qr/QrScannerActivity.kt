package dev.seekerzero.app.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import dev.seekerzero.app.util.LogCollector

class QrScannerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_RAW_RESULT = "seekerzero.qr.raw"
        private const val TAG = "QrScannerActivity"
    }

    private lateinit var capture: CaptureManager
    private lateinit var barcodeView: DecoratedBarcodeView

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startScan(null)
            } else {
                LogCollector.w(TAG, "Camera permission denied")
                setResult(RESULT_CANCELED)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        barcodeView = DecoratedBarcodeView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(barcodeView)

        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startScan(savedInstanceState)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startScan(savedInstanceState: Bundle?) {
        capture = CaptureManager(this, barcodeView)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.setShowMissingCameraPermissionDialog(false)
        capture.decode()
        barcodeView.decodeContinuous { result ->
            val text = result.text ?: return@decodeContinuous
            barcodeView.pause()
            val data = Intent().apply { putExtra(EXTRA_RAW_RESULT, text) }
            setResult(RESULT_OK, data)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::capture.isInitialized) capture.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::capture.isInitialized) capture.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::capture.isInitialized) capture.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::capture.isInitialized) capture.onSaveInstanceState(outState)
    }
}
