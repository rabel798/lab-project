package com.example.empay

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReceiptActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receipt)

        val amount = intent.getDoubleExtra("AMOUNT", 0.0)
        val payee = intent.getStringExtra("PAYEE") ?: "Merchant"
        val channel = intent.getStringExtra("CHANNEL") ?: "Bluetooth Mesh"
        val txId = intent.getStringExtra("TX_ID") ?: "TXN-OFFLINE"
        val signature = intent.getStringExtra("SIGNATURE") ?: "SIG-SEC256R1"
        val timestamp = intent.getLongExtra("TIMESTAMP", System.currentTimeMillis())

        val dateStr = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp))

        findViewById<TextView>(R.id.tvReceiptAmount).text = String.format(Locale.US, "₹ %.2f", amount)
        findViewById<TextView>(R.id.tvReceiptPayee).text = payee
        findViewById<TextView>(R.id.tvReceiptChannel).text = channel
        findViewById<TextView>(R.id.tvReceiptDateTime).text = dateStr
        findViewById<TextView>(R.id.tvReceiptTxId).text = txId
        findViewById<TextView>(R.id.tvReceiptToken).text = signature

        findViewById<Button>(R.id.btnReceiptHome).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btnReceiptHistory).setOnClickListener {
            startActivity(Intent(this, TransactionHistoryActivity::class.java))
            finish()
        }
    }
}
