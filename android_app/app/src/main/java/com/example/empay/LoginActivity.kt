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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.empay.auth.AuthManager

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

        // If user is already remembered/logged in, skip the login screen automatically!
        if (authManager.isLoggedIn()) {
            val rememberedUser = authManager.getActiveAccount()
            if (rememberedUser != null) {
                com.example.empay.bluetooth.BluetoothHelper.broadcastEmPayIdentity(
                    this,
                    rememberedUser.name,
                    rememberedUser.phone
                )
            }
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
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
            try {
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

                val account = authManager.login(phoneOrName, password, rememberMe)
                if (account != null) {
                    try {
                        com.example.empay.bluetooth.BluetoothHelper.broadcastEmPayIdentity(this, account.name, account.phone)
                    } catch (_: Exception) {}
                    Toast.makeText(this, "Welcome, ${account.name}!", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this, "Invalid credentials. Please verify username/phone and password.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                android.util.Log.e("LoginActivity", "Login error: ${e.message}", e)
                Toast.makeText(this, "Sign in failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // Tapping the light helper note fills default credentials for quick testing
        layoutDemoAccountHint.setOnClickListener {
            etLoginPhone.setText("9876543210")
            etLoginPassword.setText("123456")
            Toast.makeText(this, "Rahul Sharma credentials applied", Toast.LENGTH_SHORT).show()
        }

        btnSubmitSignUp.setOnClickListener {
            try {
                val name = etSignUpName.text.toString().trim()
                val phone = etSignUpPhone.text.toString().trim()
                val password = etSignUpPassword.text.toString().trim()
                val balStr = etSignUpEmergencyBal.text.toString().trim()
                val emergencyBal = balStr.toDoubleOrNull() ?: 100.0

                if (name.isEmpty() || phone.isEmpty() || password.isEmpty()) {
                    Toast.makeText(this, "Please fill in all fields to create an account", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Permanently registers account in SQLite & bank server, keeping user logged in
                val newAccount = authManager.signUp(name, phone, password, emergencyBal, rememberMe = true)
                try {
                    com.example.empay.bluetooth.BluetoothHelper.broadcastEmPayIdentity(this, newAccount.name, newAccount.phone)
                } catch (_: Exception) {}
                Toast.makeText(this, "Profile created for ${newAccount.name}!", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } catch (e: Throwable) {
                android.util.Log.e("LoginActivity", "Sign up error: ${e.message}", e)
                Toast.makeText(this, "Registration failed: ${e.message}", Toast.LENGTH_LONG).show()
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
