package com.example.empay

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.empay.auth.AuthManager
import com.example.empay.network.BankApiClient
import com.example.empay.network.LoginRequest
import com.example.empay.network.NetworkManager
import com.example.empay.network.RegisterUserRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var authManager: AuthManager

    private lateinit var btnTabSignIn: Button
    private lateinit var btnTabSignUp: Button
    private lateinit var layoutSignInSection: LinearLayout
    private lateinit var layoutSignUpSection: LinearLayout

    private lateinit var etLoginPhone: EditText
    private lateinit var etLoginPassword: EditText
    private lateinit var cbRememberMe: CheckBox
    private lateinit var btnSubmitLogin: Button
    private lateinit var layoutDemoAccountHint: View

    private lateinit var etSignUpName: EditText
    private lateinit var etSignUpPhone: EditText
    private lateinit var etSignUpPassword: EditText
    private lateinit var etSignUpEmergencyBal: EditText
    private lateinit var btnSubmitSignUp: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authManager = AuthManager.getInstance(this)

        // If user is already remembered/logged in, verify active session before entering
        if (authManager.isLoggedIn()) {
            val rememberedUser = authManager.getActiveAccount()
            val deviceId = authManager.getDeviceId()
            if (rememberedUser != null && NetworkManager.isOnline(this)) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val api = BankApiClient.getApiService(applicationContext)
                        val resp = api.verifySession(rememberedUser.id, deviceId)
                        if (!resp.isSuccessful || resp.body()?.valid == false) {
                            withContext(Dispatchers.Main) {
                                authManager.logout()
                                AlertDialog.Builder(this@LoginActivity)
                                    .setTitle("⚠️ Session Terminated")
                                    .setMessage("Security Alert: Account '${rememberedUser.name}' is currently active on another device.\n\nSimultaneous logins are prohibited. You have been logged out on this device.")
                                    .setIcon(android.R.drawable.ic_dialog_alert)
                                    .setPositiveButton("OK", null)
                                    .show()
                                setContentView(R.layout.activity_login)
                                initViews()
                                setupListeners()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                com.example.empay.bluetooth.BluetoothHelper.broadcastEmPayIdentity(
                                    this@LoginActivity,
                                    rememberedUser.name,
                                    rememberedUser.phone
                                )
                                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                                finish()
                            }
                        }
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                            finish()
                        }
                    }
                }
                return
            } else if (rememberedUser != null) {
                com.example.empay.bluetooth.BluetoothHelper.broadcastEmPayIdentity(
                    this,
                    rememberedUser.name,
                    rememberedUser.phone
                )
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                return
            }
        }

        setContentView(R.layout.activity_login)

        initViews()
        setupListeners()
    }

    private fun initViews() {
        btnTabSignIn = findViewById(R.id.btnTabSignIn)
        btnTabSignUp = findViewById(R.id.btnTabSignUp)
        layoutSignInSection = findViewById(R.id.layoutSignInSection)
        layoutSignUpSection = findViewById(R.id.layoutSignUpSection)

        etLoginPhone = findViewById(R.id.etLoginPhone)
        etLoginPassword = findViewById(R.id.etLoginPassword)
        cbRememberMe = findViewById(R.id.cbRememberMe)
        btnSubmitLogin = findViewById(R.id.btnSubmitLogin)
        layoutDemoAccountHint = findViewById(R.id.layoutDemoAccountHint)

        etSignUpName = findViewById(R.id.etSignUpName)
        etSignUpPhone = findViewById(R.id.etSignUpPhone)
        etSignUpPassword = findViewById(R.id.etSignUpPassword)
        etSignUpEmergencyBal = findViewById(R.id.etSignUpEmergencyBal)
        btnSubmitSignUp = findViewById(R.id.btnSubmitSignUp)

        // Fields start empty by default — user enters their username/phone and password
        etLoginPhone.setText("")
        etLoginPassword.setText("")
        cbRememberMe.isChecked = true

        // Lock button colors so they remain intact without changing when clicked
        btnSubmitLogin.backgroundTintList = ContextCompat.getColorStateList(this, R.color.btn_primary_solid)
        btnSubmitSignUp.backgroundTintList = ContextCompat.getColorStateList(this, R.color.btn_primary_solid)
        switchTab(isSignIn = true)
    }

    private fun setupListeners() {
        btnTabSignIn.setOnClickListener { switchTab(isSignIn = true) }
        btnTabSignUp.setOnClickListener { switchTab(isSignIn = false) }

        btnSubmitLogin.setOnClickListener {
            val phoneOrName = etLoginPhone.text.toString().trim()
            val password = etLoginPassword.text.toString().trim()
            val rememberMe = cbRememberMe.isChecked

            if (phoneOrName.isEmpty()) {
                Toast.makeText(this, "Please enter your username or phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password.isEmpty()) {
                Toast.makeText(this, "Please enter your password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val isOnline = NetworkManager.isOnline(this@LoginActivity)
                val deviceId = authManager.getDeviceId()

                if (isOnline) {
                    try {
                        val api = BankApiClient.getApiService(applicationContext)
                        val resp = api.loginUser(LoginRequest(phoneOrName, password, deviceId))
                        if (!resp.isSuccessful) {
                            val errBody = resp.errorBody()?.string() ?: ""
                            if (resp.code() == 403 || errBody.contains("SIMULTANEOUS_LOGIN_RESTRICTED") || errBody.contains("actively signed in on another device")) {
                                AlertDialog.Builder(this@LoginActivity)
                                    .setTitle("⚠️ Simultaneous Login Restricted")
                                    .setMessage("Security Alert: This account is already actively signed in on another device.\n\nSimultaneous logins are strictly prohibited to prevent duplicate spending. You must sign out from the other device first.")
                                    .setIcon(android.R.drawable.ic_dialog_alert)
                                    .setPositiveButton("OK", null)
                                    .show()
                                return@launch
                            } else {
                                Toast.makeText(this@LoginActivity, "Invalid credentials. Please verify username/phone and password.", Toast.LENGTH_LONG).show()
                                return@launch
                            }
                        }

                        val body = resp.body()
                        if (body != null && body.success && body.user != null) {
                            val serverUser = body.user
                            val localAccount = authManager.saveOrUpdateServerUser(serverUser, password, rememberMe)
                            try {
                                com.example.empay.bluetooth.BluetoothHelper.broadcastEmPayIdentity(this@LoginActivity, localAccount.name, localAccount.phone)
                            } catch (_: Exception) {}
                            Toast.makeText(this@LoginActivity, "Welcome, ${localAccount.name}!", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                            finish()
                            return@launch
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("LoginActivity", "Online login error: ${e.message}", e)
                    }
                }

                // Local offline login
                val account = authManager.login(phoneOrName, password, rememberMe)
                if (account != null) {
                    try {
                        com.example.empay.bluetooth.BluetoothHelper.broadcastEmPayIdentity(this@LoginActivity, account.name, account.phone)
                    } catch (_: Exception) {}
                    Toast.makeText(this@LoginActivity, "Welcome, ${account.name}!", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this@LoginActivity, "Invalid credentials. Please verify username/phone and password.", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Tapping the light helper note fills default credentials for quick testing
        layoutDemoAccountHint.setOnClickListener {
            etLoginPhone.setText("9876543210")
            etLoginPassword.setText("123456")
            Toast.makeText(this, "Rahul Sharma credentials applied", Toast.LENGTH_SHORT).show()
        }

        btnSubmitSignUp.setOnClickListener {
            val name = etSignUpName.text.toString().trim()
            val phone = etSignUpPhone.text.toString().trim()
            val password = etSignUpPassword.text.toString().trim()
            val balStr = etSignUpEmergencyBal.text.toString().trim()
            val emergencyBal = balStr.toDoubleOrNull() ?: 100.0

            if (name.isEmpty() || phone.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please fill in all fields to create an account", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val rememberMe = cbRememberMe.isChecked
            val deviceId = authManager.getDeviceId()

            lifecycleScope.launch {
                if (NetworkManager.isOnline(this@LoginActivity)) {
                    try {
                        val api = BankApiClient.getApiService(applicationContext)
                        val resp = api.registerUser(
                            RegisterUserRequest(
                                name = name,
                                phone = phone,
                                username = name,
                                password = password,
                                emergencyBalance = emergencyBal,
                                deviceId = deviceId
                            )
                        )
                        if (!resp.isSuccessful) {
                            val errBody = resp.errorBody()?.string() ?: ""
                            if (resp.code() == 403 || errBody.contains("SIMULTANEOUS_LOGIN_RESTRICTED")) {
                                AlertDialog.Builder(this@LoginActivity)
                                    .setTitle("⚠️ Simultaneous Login Restricted")
                                    .setMessage("Security Alert: An account with this phone number is already actively signed in on another device. You must sign out from that device first.")
                                    .setIcon(android.R.drawable.ic_dialog_alert)
                                    .setPositiveButton("OK", null)
                                    .show()
                                return@launch
                            }
                        }
                    } catch (_: Exception) {}
                }

                val newAccount = authManager.signUp(name, phone, password, emergencyBal, rememberMe = rememberMe)
                try {
                    com.example.empay.bluetooth.BluetoothHelper.broadcastEmPayIdentity(this@LoginActivity, newAccount.name, newAccount.phone)
                } catch (_: Exception) {}
                Toast.makeText(this@LoginActivity, "Profile created for ${newAccount.name}!", Toast.LENGTH_LONG).show()
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun switchTab(isSignIn: Boolean) {
        val activeBg = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
        val inactiveBg = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
        val activeText = ContextCompat.getColor(this, R.color.white)
        val inactiveText = ContextCompat.getColor(this, R.color.text_muted)

        if (isSignIn) {
            btnTabSignIn.backgroundTintList = activeBg
            btnTabSignIn.setTextColor(activeText)
            btnTabSignUp.backgroundTintList = inactiveBg
            btnTabSignUp.setTextColor(inactiveText)
            layoutSignInSection.visibility = View.VISIBLE
            layoutSignUpSection.visibility = View.GONE
        } else {
            btnTabSignUp.backgroundTintList = activeBg
            btnTabSignUp.setTextColor(activeText)
            btnTabSignIn.backgroundTintList = inactiveBg
            btnTabSignIn.setTextColor(inactiveText)
            layoutSignInSection.visibility = View.GONE
            layoutSignUpSection.visibility = View.VISIBLE
        }
    }

    private fun updateBluetoothName(userName: String) {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && adapter.isEnabled) {
                adapter.name = "EmPay - $userName"
            }
        } catch (e: SecurityException) {
            // Ignored if bluetooth permissions not yet granted
        }
    }
}
