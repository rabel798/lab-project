package com.example.empay

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.empay.bluetooth.BluetoothPaymentEngine
import com.example.empay.ui.BluetoothDeviceDialog

class ScanActivity : AppCompatActivity() {

    private lateinit var bluetoothEngine: BluetoothPaymentEngine
    private var isTorchOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        bluetoothEngine = BluetoothPaymentEngine(this)

        findViewById<ImageButton>(R.id.btnScanBack).setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.btnScanTorch).setOnClickListener {
            isTorchOn = !isTorchOn
            Toast.makeText(this, if (isTorchOn) "Torch ON" else "Torch OFF", Toast.LENGTH_SHORT).show()
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
            val input = android.widget.EditText(this).apply {
                hint = "Enter Merchant / Device Code"
            }
            AlertDialog.Builder(this)
                .setTitle("Manual Code Entry")
                .setView(input)
                .setPositiveButton("Proceed") { _, _ ->
                    val code = input.text.toString().trim()
                    val targetName = if (code.isNotEmpty()) "Terminal #$code" else "Spidey Store POS"
                    val intent = Intent(this, PayActivity::class.java).apply {
                        putExtra("PAYEE_NAME", targetName)
                        putExtra("DEFAULT_AMOUNT", "15.00")
                        putExtra("CHANNEL", "QR Code")
                    }
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
