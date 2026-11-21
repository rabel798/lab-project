package com.example.empay

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class MerchantsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_merchants)

        findViewById<ImageButton>(R.id.btnMerchantsBack).setOnClickListener { finish() }

        // Pay buttons for each essential merchant matching merchants.html
        findViewById<Button>(R.id.btnPayDailyFresh).setOnClickListener {
            openPayForMerchant("Daily Fresh Supermarket", "35.00")
        }
        findViewById<Button>(R.id.btnPayApexPharmacy).setOnClickListener {
            openPayForMerchant("Apex City Care Pharmacy", "12.00")
        }
        findViewById<Button>(R.id.btnPayLifeLine).setOnClickListener {
            openPayForMerchant("LifeLine Medicos", "25.00")
        }
        findViewById<Button>(R.id.btnPayNationalBook).setOnClickListener {
            openPayForMerchant("National Book Depot", "18.00")
        }
        findViewById<Button>(R.id.btnPayQuickFix).setOnClickListener {
            openPayForMerchant("City QuickFix Electrician", "50.00")
        }

        // Category filter pills
        val rowGroceries = findViewById<View>(R.id.merchantRowDailyFresh)
        val rowPharmacy = findViewById<View>(R.id.merchantRowApexPharmacy)
        val rowLifeLine = findViewById<View>(R.id.merchantRowLifeLine)
        val rowBook = findViewById<View>(R.id.merchantRowNationalBook)
        val rowQuickFix = findViewById<View>(R.id.merchantRowQuickFix)

        findViewById<View>(R.id.catAll).setOnClickListener {
            rowGroceries.visibility = View.VISIBLE
            rowPharmacy.visibility = View.VISIBLE
            rowLifeLine.visibility = View.VISIBLE
            rowBook.visibility = View.VISIBLE
            rowQuickFix.visibility = View.VISIBLE
        }
        findViewById<View>(R.id.catGroceries).setOnClickListener {
            rowGroceries.visibility = View.VISIBLE
            rowPharmacy.visibility = View.GONE
            rowLifeLine.visibility = View.GONE
            rowBook.visibility = View.GONE
            rowQuickFix.visibility = View.GONE
        }
        findViewById<View>(R.id.catHealthcare).setOnClickListener {
            rowGroceries.visibility = View.GONE
            rowPharmacy.visibility = View.VISIBLE
            rowLifeLine.visibility = View.VISIBLE
            rowBook.visibility = View.GONE
            rowQuickFix.visibility = View.GONE
        }
        findViewById<View>(R.id.catRetail).setOnClickListener {
            rowGroceries.visibility = View.GONE
            rowPharmacy.visibility = View.GONE
            rowLifeLine.visibility = View.GONE
            rowBook.visibility = View.VISIBLE
            rowQuickFix.visibility = View.GONE
        }
        findViewById<View>(R.id.catEmergency).setOnClickListener {
            rowGroceries.visibility = View.GONE
            rowPharmacy.visibility = View.GONE
            rowLifeLine.visibility = View.GONE
            rowBook.visibility = View.GONE
            rowQuickFix.visibility = View.VISIBLE
        }
    }

    private fun openPayForMerchant(name: String, amount: String) {
        val intent = Intent(this, PayActivity::class.java).apply {
            putExtra("PAYEE_NAME", name)
            putExtra("DEFAULT_AMOUNT", amount)
            putExtra("CHANNEL", "Bluetooth")
        }
        startActivity(intent)
    }
}
