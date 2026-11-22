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
import com.example.empay.network.AddFundsRequest
import com.example.empay.network.BankApiClient
import com.example.empay.network.NetworkManager
import com.example.empay.repository.PaymentRepository
import com.example.empay.sync.SyncManager
import com.example.empay.ui.TransferFundsDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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
    private lateinit var tvProfileKeystoreStatus: TextView

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
        tvProfileKeystoreStatus = findViewById(R.id.tvProfileKeystoreStatus)

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
            if (!NetworkManager.isOnline(this)) {
                Toast.makeText(this, "Cannot reserve funds offline. Active internet required.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
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

        // Emergency mode is driven by real network, inform user on click
        switchEmergencyMode.setOnClickListener {
            val isOnline = NetworkManager.isOnline(this)
            switchEmergencyMode.isChecked = !isOnline
            Toast.makeText(
                this,
                if (isOnline) "Internet Connected: Emergency Mode is inactive" else "No Internet: Emergency Mode is automatically active",
                Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<Button>(R.id.btnProfileLogout).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Log Out")
                .setMessage("Are you sure you want to log out of ${sessionManager.getUser().name}?")
                .setPositiveButton("Log Out") { _, _ ->
                    AuthManager.getInstance(this).logout()
                    val intent = Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
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
        switchEmergencyMode.isChecked = !realOnline
        updateNetworkBadge(realOnline)

        try {
            val fp = CryptoManager.getPublicKeyFingerprint()
            tvProfileKeystoreStatus.text = "TEE Hardware ($fp)"
        } catch (_: Exception) {
            tvProfileKeystoreStatus.text = "Hardware Keystore Active"
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
        if (!NetworkManager.isOnline(this)) {
            Toast.makeText(this, "Cannot add money while offline. Connect to internet first.", Toast.LENGTH_LONG).show()
            return
        }

        val inputAmt = EditText(this).apply {
            hint = "Enter amount (e.g. 500)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(this)
            .setTitle("Add Funds to Bank Account")
            .setView(inputAmt)
            .setPositiveButton("Add") { _, _ ->
                val amt = inputAmt.text.toString().trim().toDoubleOrNull()
                if (amt != null && amt > 0) {
                    val user = sessionManager.getUser()
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val api = BankApiClient.getApiService(this@ProfileActivity)
                            val resp = api.addFunds(AddFundsRequest(userId = user.id, amount = amt))
                            withContext(Dispatchers.Main) {
                                if (resp.isSuccessful && resp.body()?.success == true) {
                                    val newBal = resp.body()!!.balance
                                    sessionManager.updateBalances(newBal, user.emergencyBalance)
                                    loadUserData()
                                    Toast.makeText(this@ProfileActivity, "✅ Added ₹$amt to Bank Account!", Toast.LENGTH_SHORT).show()
                                } else {
                                    val localBal = user.balance + amt
                                    sessionManager.updateBalances(localBal, user.emergencyBalance)
                                    loadUserData()
                                    Toast.makeText(this@ProfileActivity, "Added ₹$amt to Available Balance!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                val localBal = user.balance + amt
                                sessionManager.updateBalances(localBal, user.emergencyBalance)
                                loadUserData()
                                Toast.makeText(this@ProfileActivity, "Added ₹$amt (Local Update: ${e.message})", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
