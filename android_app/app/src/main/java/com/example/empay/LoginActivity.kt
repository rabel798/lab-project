package com.example.empay

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
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
    private lateinit var btnSubmitLogin: Button
    private lateinit var btnQuickRahulLogin: Button

    private lateinit var etSignUpName: EditText
    private lateinit var etSignUpPhone: EditText
    private lateinit var etSignUpPassword: EditText
    private lateinit var etSignUpEmergencyBal: EditText
    private lateinit var btnSubmitSignUp: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authManager = AuthManager.getInstance(this)
        if (authManager.isLoggedIn()) {
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
        btnSubmitLogin = findViewById(R.id.btnSubmitLogin)
        btnQuickRahulLogin = findViewById(R.id.btnQuickRahulLogin)

        etSignUpName = findViewById(R.id.etSignUpName)
        etSignUpPhone = findViewById(R.id.etSignUpPhone)
        etSignUpPassword = findViewById(R.id.etSignUpPassword)
        etSignUpEmergencyBal = findViewById(R.id.etSignUpEmergencyBal)
        btnSubmitSignUp = findViewById(R.id.btnSubmitSignUp)

        // Pre-fill Rahul Sharma's credentials in login inputs
        etLoginPhone.setText("9876543210")
        etLoginPassword.setText("rahul@12345")
    }

    private fun setupListeners() {
        btnTabSignIn.setOnClickListener { switchTab(isSignIn = true) }
        btnTabSignUp.setOnClickListener { switchTab(isSignIn = false) }

        btnSubmitLogin.setOnClickListener {
            val phoneOrName = etLoginPhone.text.toString().trim()
            val password = etLoginPassword.text.toString().trim()

            if (phoneOrName.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter your username/phone and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val account = authManager.login(phoneOrName, password)
            if (account != null) {
                updateBluetoothName(account.name)
                Toast.makeText(this, "Welcome back, ${account.name}!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this, "Invalid credentials. Use phone '9876543210' & password 'rahul@12345'", Toast.LENGTH_LONG).show()
            }
        }

        btnQuickRahulLogin.setOnClickListener {
            val account = authManager.login("9876543210", "rahul@12345")
            if (account != null) {
                updateBluetoothName(account.name)
                Toast.makeText(this, "Logged in as Rahul Sharma", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
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

            val newAccount = authManager.signUp(name, phone, password, emergencyBal)
            updateBluetoothName(newAccount.name)
            Toast.makeText(this, "Profile created for ${newAccount.name}! Ready for Bluetooth testing.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun switchTab(isSignIn: Boolean) {
        val activeBg = ContextCompat.getColorStateList(this, R.color.primary)
        val inactiveBg = ContextCompat.getColorStateList(this, android.R.color.transparent)
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
