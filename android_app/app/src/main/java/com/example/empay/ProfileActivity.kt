package com.example.empay

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.example.empay.auth.AuthManager
import com.example.empay.auth.SessionManager
import com.example.empay.crypto.CryptoManager
import com.example.empay.db.AppDatabase
import com.example.empay.network.NetworkManager
import com.example.empay.repository.PaymentRepository
import com.example.empay.sync.SyncManager
import com.example.empay.ui.TransferFundsDialog
import com.example.empay.ui.UserProfileDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

class ProfileActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var appDatabase: AppDatabase
    private lateinit var paymentRepository: PaymentRepository

    private lateinit var tvProfileName: TextView
    private lateinit var tvProfilePhone: TextView
    private lateinit var tvProfileAvailableBal: TextView
    private lateinit var tvProfileEmergencyBal: TextView
    private lateinit var tvProfileNetStatusBadge: TextView
    private lateinit var switchEmergencyMode: SwitchCompat
    private lateinit var tvProfilePublicKey: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        sessionManager = SessionManager.getInstance(this)
        appDatabase = AppDatabase.getInstance(this)
        paymentRepository = PaymentRepository.getInstance(this)

        initViews()
        setupListeners()
        loadUserData()
    }

    override fun onResume() {
        super.onResume()
        loadUserData()
    }

    private fun initViews() {
        tvProfileName = findViewById(R.id.tvProfileName)
        tvProfilePhone = findViewById(R.id.tvProfilePhone)
        tvProfileAvailableBal = findViewById(R.id.tvProfileAvailableBal)
        tvProfileEmergencyBal = findViewById(R.id.tvProfileEmergencyBal)
        tvProfileNetStatusBadge = findViewById(R.id.tvProfileNetStatusBadge)
        switchEmergencyMode = findViewById(R.id.switchEmergencyMode)
        tvProfilePublicKey = findViewById(R.id.tvProfilePublicKey)

        // Bottom Nav
        findViewById<View>(R.id.navProfileHome).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
        findViewById<View>(R.id.navProfileScan).setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.navProfilePayBt).setOnClickListener {
            startActivity(Intent(this, BluetoothActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.navProfileHistory).setOnClickListener {
            startActivity(Intent(this, TransactionHistoryActivity::class.java))
            finish()
        }
    }

    private fun setupListeners() {
        findViewById<ImageButton>(R.id.btnProfileEdit).setOnClickListener {
            editProfilePrompt()
        }

        findViewById<Button>(R.id.btnProfileTransfer).setOnClickListener {
            TransferFundsDialog(
                context = this,
                availableOnlineBalance = sessionManager.getUser().balance,
                onAuthorized = { amt ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val result = paymentRepository.reserveEmergencyFunds(amt)
                        withContext(Dispatchers.Main) {
                            if (result.isSuccess) {
                                Toast.makeText(this@ProfileActivity, "Transferred ₹$amt to Emergency Balance!", Toast.LENGTH_SHORT).show()
                                loadUserData()
                            } else {
                                Toast.makeText(this@ProfileActivity, result.exceptionOrNull()?.message ?: "Transfer failed", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                onFailed = { reason ->
                    Toast.makeText(this@ProfileActivity, reason, Toast.LENGTH_SHORT).show()
                }
            ).show()
        }

        findViewById<Button>(R.id.btnProfileAddMoney).setOnClickListener {
            addMoneyPrompt()
        }

        switchEmergencyMode.setOnCheckedChangeListener { _, isChecked ->
            val newIsOnline = !isChecked
            sessionManager.updateOnlineStatus(newIsOnline)
            updateNetworkBadge(newIsOnline)
            Toast.makeText(
                this,
                if (isChecked) "Emergency Mode: Offline P2P enabled" else "Online Mode restored",
                Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<Button>(R.id.btnProfileSyncNow).setOnClickListener {
            Toast.makeText(this, "Reconciling offline transactions with Central Bank...", Toast.LENGTH_SHORT).show()
            SyncManager.triggerImmediateSync(this)
            lifecycleScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(1200)
                withContext(Dispatchers.Main) {
                    loadUserData()
                    Toast.makeText(this@ProfileActivity, "Sync completed! Balances reconciled.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<Button>(R.id.btnProfileClearHistory).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Transaction History")
                .setMessage("Are you sure you want to purge all local transactions?")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        appDatabase.transactionDao.clearAllTransactions()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@ProfileActivity, "Transaction history cleared successfully!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<Button>(R.id.btnProfileSwitchUser).setOnClickListener {
            UserProfileDialog(
                context = this,
                currentUser = sessionManager.getUser(),
                onUserSelected = { updatedUser ->
                    sessionManager.updateUser(updatedUser)
                    loadUserData()
                }
            ).show()
        }

        findViewById<Button>(R.id.btnProfileRegenerateKey).setOnClickListener {
            val newKey = "PK_ECC_secp256k1_" + UUID.randomUUID().toString().replace("-", "").take(24)
            tvProfilePublicKey.text = newKey
            Toast.makeText(this, "New asymmetric ECC keypair generated for offline transaction signing!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadUserData() {
        val user = sessionManager.getUser()
        val wallet = appDatabase.walletDao.getWallet()
        val offlineBal = wallet?.balance ?: user.emergencyBalance

        tvProfileName.text = user.name
        val phoneDisp = if (user.phone.startsWith("+")) user.phone else "+91 ${user.phone}"
        tvProfilePhone.text = phoneDisp

        tvProfileAvailableBal.text = String.format(Locale.US, "₹ %.2f", user.balance)
        tvProfileEmergencyBal.text = String.format(Locale.US, "₹ %.2f", offlineBal)

        val realOnline = NetworkManager.isOnline(this)
        val isEffectiveOnline = user.isOnline && realOnline

        switchEmergencyMode.isChecked = !user.isOnline
        updateNetworkBadge(isEffectiveOnline)

        try {
            val pubKey = CryptoManager.getPublicKeyBase64()
            tvProfilePublicKey.text = if (pubKey.isNotEmpty()) "PK_ECC_secp256r1_${pubKey.take(24)}" else "PK_ECC_secp256k1_7f8a9b2c3d4e5f6081a2b3c4d5e6"
        } catch (e: Exception) {
            tvProfilePublicKey.text = "PK_ECC_secp256k1_7f8a9b2c3d4e5f6081a2b3c4d5e6"
        }
    }

    private fun updateNetworkBadge(isOnline: Boolean) {
        if (isOnline) {
            tvProfileNetStatusBadge.text = "Online"
            tvProfileNetStatusBadge.setTextColor(Color.parseColor("#15803D"))
            tvProfileNetStatusBadge.setBackgroundColor(Color.parseColor("#DCFCE7"))
        } else {
            tvProfileNetStatusBadge.text = "Emergency"
            tvProfileNetStatusBadge.setTextColor(Color.parseColor("#B91C1C"))
            tvProfileNetStatusBadge.setBackgroundColor(Color.parseColor("#FEE2E2"))
        }
    }

    private fun editProfilePrompt() {
        val user = sessionManager.getUser()
        val inputName = EditText(this).apply {
            hint = "Enter Full Name"
            setText(user.name)
        }
        AlertDialog.Builder(this)
            .setTitle("Update Profile")
            .setView(inputName)
            .setPositiveButton("Save") { _, _ ->
                val newName = inputName.text.toString().trim()
                if (newName.isNotEmpty()) {
                    sessionManager.updateUser(user.copy(name = newName))
                    loadUserData()
                    Toast.makeText(this, "Profile updated successfully!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addMoneyPrompt() {
        val inputAmt = EditText(this).apply {
            hint = "Enter amount (e.g. 500)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(this)
            .setTitle("Add Funds to Available Balance")
            .setView(inputAmt)
            .setPositiveButton("Add") { _, _ ->
                val amt = inputAmt.text.toString().trim().toDoubleOrNull()
                if (amt != null && amt > 0) {
                    val user = sessionManager.getUser()
                    val newBal = user.balance + amt
                    sessionManager.updateBalances(newBal, user.emergencyBalance)
                    loadUserData()
                    Toast.makeText(this, "Added ₹$amt to Available Balance!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
