package com.example.empay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.empay.bluetooth.BluetoothPaymentEngine
import com.example.empay.ui.BluetoothDeviceDialog
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CompoundBarcodeView

class ScanActivity : AppCompatActivity() {

    private lateinit var barcodeScannerView: CompoundBarcodeView
    private lateinit var bluetoothEngine: BluetoothPaymentEngine

    private var isTorchOn = false
    private var hasScanned = false

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        bluetoothEngine = BluetoothPaymentEngine(this)
        barcodeScannerView = findViewById(R.id.barcodeScannerView)

        findViewById<ImageButton>(R.id.btnScanBack).setOnClickListener {
            finish()
        }

        initCameraScanner()
        setupControls()
    }

    private fun initCameraScanner() {
        // Hide default status text from barcode view
        barcodeScannerView.statusView?.visibility = View.GONE

        // Check & request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        } else {
            startCameraScanning()
        }
    }

    private fun startCameraScanning() {
        barcodeScannerView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                if (result == null || result.text.isNullOrEmpty() || hasScanned) return
                hasScanned = true

                vibrateOnScan()
                handleScannedData(result.text)
            }

            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {}
        })
    }

    private fun handleScannedData(rawText: String) {
        var payeeName = "Scanned Merchant POS"
        var amount = "15.00"

        try {
            if (rawText.startsWith("upi://pay", ignoreCase = true)) {
                val uri = Uri.parse(rawText)
                val pn = uri.getQueryParameter("pn")
                val pa = uri.getQueryParameter("pa")
                val am = uri.getQueryParameter("am")
                payeeName = pn ?: pa ?: "UPI Merchant"
                if (!am.isNullOrEmpty()) amount = am
            } else if (rawText.contains(":") || rawText.contains("|")) {
                val parts = rawText.split(":", "|")
                if (parts.isNotEmpty()) payeeName = parts[0].trim()
                if (parts.size > 1 && parts[1].toDoubleOrNull() != null) amount = parts[1].trim()
            } else {
                payeeName = rawText.take(24)
            }
        } catch (e: Exception) {
            payeeName = rawText.take(20)
        }

        Toast.makeText(this, "Scanned: $payeeName", Toast.LENGTH_SHORT).show()

        val intent = Intent(this, PayActivity::class.java).apply {
            putExtra("PAYEE_NAME", payeeName)
            putExtra("DEFAULT_AMOUNT", amount)
            putExtra("CHANNEL", "QR Code")
        }
        startActivity(intent)
        finish()
    }

    private fun vibrateOnScan() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(100)
            }
        } catch (e: Exception) {
            // Ignored
        }
    }

    private fun setupControls() {
        val btnTorch = findViewById<View>(R.id.btnScanTorch)
        val iconTorchBg = findViewById<FrameLayout>(R.id.iconTorchBg)
        val ivTorchIcon = findViewById<ImageView>(R.id.ivTorchIcon)

        btnTorch.setOnClickListener {
            isTorchOn = !isTorchOn
            if (isTorchOn) {
                barcodeScannerView.setTorchOn()
                iconTorchBg.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
                ivTorchIcon.setColorFilter(ContextCompat.getColor(this, R.color.white))
            } else {
                barcodeScannerView.setTorchOff()
                iconTorchBg.backgroundTintList = ContextCompat.getColorStateList(this, R.color.card_bg)
                ivTorchIcon.setColorFilter(ContextCompat.getColor(this, R.color.white))
            }
        }

        findViewById<View>(R.id.btnScanBluetoothTerminals).setOnClickListener {
            val picker = BluetoothDeviceDialog(
                context = this,
                bluetoothEngine = bluetoothEngine,
                onRequestPermissions = null,
                onDeviceSelected = { name, _, _ ->
                    val intent = Intent(this, PayActivity::class.java).apply {
                        putExtra("PAYEE_NAME", name)
                        putExtra("DEFAULT_AMOUNT", "25.00")
                        putExtra("CHANNEL", "Bluetooth")
                    }
                    startActivity(intent)
                    finish()
                }
            )
            picker.show()
        }

        findViewById<View>(R.id.btnScanEnterCode).setOnClickListener {
            val input = EditText(this).apply {
                hint = "Enter Merchant / Device Code"
                setSingleLine()
            }
            AlertDialog.Builder(this)
                .setTitle("Manual Code Entry")
                .setView(input)
                .setPositiveButton("Proceed") { _, _ ->
                    val code = input.text.toString().trim()
                    val targetName = if (code.isNotEmpty()) "Terminal #$code" else "Apex City Care Pharmacy"
                    val intent = Intent(this, PayActivity::class.java).apply {
                        putExtra("PAYEE_NAME", targetName)
                        putExtra("DEFAULT_AMOUNT", "15.00")
                        putExtra("CHANNEL", "Manual Code")
                    }
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraScanning()
            } else {
                Toast.makeText(this, "Camera permission needed for QR scanning", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasScanned = false
        barcodeScannerView.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeScannerView.pause()
    }
}
