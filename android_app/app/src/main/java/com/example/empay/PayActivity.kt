package com.example.empay

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.empay.auth.SessionManager
import com.example.empay.crypto.CryptoManager
import com.example.empay.db.AppDatabase
import com.example.empay.db.TransactionEntity
import com.example.empay.repository.PaymentRepository
import com.example.empay.ui.PinAuthDialog
import java.util.Locale
import java.util.UUID

class PayActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var appDatabase: AppDatabase
    private lateinit var paymentRepository: PaymentRepository

    private lateinit var etPayRecipient: EditText
    private lateinit var etPayAmountInput: EditText
    private lateinit var tvPayHeroTitle: TextView
    private lateinit var tvPayNetworkBadge: TextView
    private lateinit var tvPayEmergencyBalPreview: TextView
    private lateinit var tvPayOnlineBalPreview: TextView
    private lateinit var btnSubmitPayment: Button

    private var selectedChannel = "Bluetooth"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pay)

        sessionManager = SessionManager.getInstance(this)
        appDatabase = AppDatabase.getInstance(this)
        paymentRepository = PaymentRepository.getInstance(this)

        initViews()
        setupListeners()
        loadSessionData()
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.btnPayBack).setOnClickListener { finish() }

        etPayRecipient = findViewById(R.id.etPayRecipient)
        etPayAmountInput = findViewById(R.id.etPayAmountInput)
        tvPayHeroTitle = findViewById(R.id.tvPayHeroTitle)
        tvPayNetworkBadge = findViewById(R.id.tvPayNetworkBadge)
        tvPayEmergencyBalPreview = findViewById(R.id.tvPayEmergencyBalPreview)
        tvPayOnlineBalPreview = findViewById(R.id.tvPayOnlineBalPreview)
        btnSubmitPayment = findViewById(R.id.btnSubmitPayment)

        val targetPayee = intent.getStringExtra("PAYEE_NAME") ?: "Apex City Care Pharmacy"
        val defaultAmt = intent.getStringExtra("DEFAULT_AMOUNT") ?: "10.00"
        selectedChannel = intent.getStringExtra("CHANNEL") ?: "Bluetooth"

        etPayRecipient.setText(targetPayee)
        etPayAmountInput.setText(defaultAmt)
        tvPayHeroTitle.text = targetPayee
        btnSubmitPayment.text = "Pay via $selectedChannel"
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.chipAmount10).setOnClickListener { etPayAmountInput.setText("10.00") }
        findViewById<Button>(R.id.chipAmount25).setOnClickListener { etPayAmountInput.setText("25.00") }
        findViewById<Button>(R.id.chipAmount50).setOnClickListener { etPayAmountInput.setText("50.00") }
        findViewById<Button>(R.id.chipAmount100).setOnClickListener { etPayAmountInput.setText("100.00") }

        findViewById<android.view.View>(R.id.channelBt).setOnClickListener {
            selectedChannel = "Bluetooth"
            btnSubmitPayment.text = "Pay via Bluetooth Mesh"
        }
        findViewById<android.view.View>(R.id.channelSound).setOnClickListener {
            selectedChannel = "Sound Wave"
            btnSubmitPayment.text = "Pay via Sound Wave"
        }
        findViewById<android.view.View>(R.id.channelQr).setOnClickListener {
            selectedChannel = "QR Mesh"
            btnSubmitPayment.text = "Pay via QR Mesh"
        }

        btnSubmitPayment.setOnClickListener {
            processPayment()
        }
    }

    private fun loadSessionData() {
        val user = sessionManager.getUser()
        val wallet = appDatabase.walletDao.getWallet()
        val offlineBal = wallet?.balance ?: user.emergencyBalance

        tvPayEmergencyBalPreview.text = String.format(Locale.US, "₹ %.2f", offlineBal)
        tvPayOnlineBalPreview.text = String.format(Locale.US, "₹ %.2f", user.balance)

        if (user.isOnline) {
            tvPayNetworkBadge.text = "● Online"
            tvPayNetworkBadge.setTextColor(ContextCompat.getColor(this, R.color.status_online_text))
        } else {
            tvPayNetworkBadge.text = "● Emergency"
            tvPayNetworkBadge.setTextColor(ContextCompat.getColor(this, R.color.emergency_dark))
        }
    }

    private fun processPayment() {
        val amountStr = etPayAmountInput.text.toString().trim()
        val amount = amountStr.toDoubleOrNull()
        val payeeName = etPayRecipient.text.toString().trim().ifEmpty { "Apex City Care Pharmacy" }

        val currentUser = paymentRepository.getCurrentUser()
        if (payeeName.trim().equals(currentUser.phone.trim(), ignoreCase = true) ||
            payeeName.trim().equals(currentUser.accountNumber.trim(), ignoreCase = true) ||
            payeeName.equals("Self", ignoreCase = true) ||
            payeeName.equals("Myself", ignoreCase = true)) {
            Toast.makeText(this, "Self-transfer not allowed: Cannot send money to yourself.", Toast.LENGTH_LONG).show()
            return
        }

        if (amount == null || amount <= 0.0) {
            Toast.makeText(this, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
            return
        }

        val localWallet = appDatabase.walletDao.getWallet()
        val currentBal = localWallet?.balance ?: sessionManager.getUser().emergencyBalance

        if (currentBal < amount) {
            Toast.makeText(this, "Insufficient offline wallet balance (₹$currentBal)", Toast.LENGTH_LONG).show()
            return
        }

        // Open PIN Authentication Dialog matching pin.html
        PinAuthDialog(
            context = this,
            actionTitle = "UPI Authorization",
            actionSubtitle = "Enter 4-digit PIN to authorize payment",
            onSuccess = {
                // Deduct from offline wallet
                appDatabase.walletDao.deductBalance(amount)
                val newWalletBal = appDatabase.walletDao.getWallet()?.balance ?: 0.0
                sessionManager.updateBalances(sessionManager.getUser().balance, newWalletBal)

                // Generate cryptographic token
                val txId = "TXN-" + UUID.randomUUID().toString().take(12).uppercase()
                val nonce = UUID.randomUUID().toString().take(8)
                val timestamp = System.currentTimeMillis()
                val signature = try {
                    val canonicalBytes = CryptoManager.getCanonicalBytes(
                        txId = txId,
                        senderId = currentUser.id.toString(),
                        receiverId = payeeName,
                        amount = amount,
                        timestamp = timestamp,
                        nonce = nonce
                    )
                    CryptoManager.signData(canonicalBytes)
                } catch (e: Exception) {
                    "SIG-" + UUID.randomUUID().toString().take(16).uppercase()
                }

                // Insert into local SQLite ledger
                val txEntity = TransactionEntity(
                    txId = txId,
                    senderId = currentUser.id.toString(),
                    senderName = currentUser.name,
                    receiverId = payeeName,
                    receiverName = payeeName,
                    amount = amount,
                    timestamp = timestamp,
                    nonce = nonce,
                    signature = signature,
                    status = TransactionEntity.STATUS_PENDING_SYNC,
                    direction = TransactionEntity.DIRECTION_SENT
                )
                appDatabase.transactionDao.insertTransaction(txEntity)

                // Navigate to ReceiptActivity matching receipt.html
                val receiptIntent = Intent(this, ReceiptActivity::class.java).apply {
                    putExtra("AMOUNT", amount)
                    putExtra("PAYEE", payeeName)
                    putExtra("CHANNEL", selectedChannel)
                    putExtra("TX_ID", txId)
                    putExtra("SIGNATURE", signature)
                    putExtra("TIMESTAMP", timestamp)
                }
                startActivity(receiptIntent)
                finish()
            },
            onFailed = { error ->
                Toast.makeText(this, "PIN Authentication Failed: $error", Toast.LENGTH_SHORT).show()
            }
        ).show()
    }
}
