package com.example.empay.ui

import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.empay.LoginActivity
import com.example.empay.R
import com.example.empay.auth.AuthManager
import com.example.empay.auth.UserAccount
import com.example.empay.auth.UserSession
import java.util.Locale

/**
 * Interactive Profile Switcher Dialog.
 * Connects to AuthManager so testers can switch between 'Rahul Sharma' (rahul@12345)
 * and custom created profiles on physical devices and emulators.
 */
class UserProfileDialog(
    private val context: Context,
    private val currentUser: UserSession,
    private val onUserSelected: (UserSession) -> Unit
) {

    private val dialog: Dialog = Dialog(context)
    private val authManager = AuthManager.getInstance(context)

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_user_profile, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(true)

        val tvCurrentName = view.findViewById<TextView>(R.id.tvCurrentProfileHeaderName)
        val tvCurrentDetails = view.findViewById<TextView>(R.id.tvCurrentProfileHeaderDetails)
        val containerUsers: LinearLayout = view.findViewById(R.id.containerUsers)
        val btnAddNewProfile: Button = view.findViewById(R.id.btnAddNewProfile)
        val btnLogoutProfile: Button = view.findViewById(R.id.btnLogoutProfile)
        val btnDismiss: Button = view.findViewById(R.id.btnDismissProfileDialog)

        tvCurrentName.text = currentUser.name
        tvCurrentDetails.text = "+91 ${currentUser.phone} · ${currentUser.accountNumber}"

        btnDismiss.setOnClickListener { dialog.dismiss() }

        btnAddNewProfile.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(context, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(intent)
        }

        btnLogoutProfile.setOnClickListener {
            authManager.logout()
            dialog.dismiss()
            val intent = Intent(context, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(intent)
        }

        populateUsers(containerUsers, authManager.getRegisteredAccounts())

        dialog.show()
    }

    private fun populateUsers(container: LinearLayout, accounts: List<UserAccount>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)

        accounts.forEach { account ->
            val itemView = inflater.inflate(R.layout.item_user_profile, container, false)
            val tvName = itemView.findViewById<TextView>(R.id.tvProfileName)
            val tvDetails = itemView.findViewById<TextView>(R.id.tvProfileDetails)
            val tvBalances = itemView.findViewById<TextView>(R.id.tvProfileBalances)
            val tvBadge = itemView.findViewById<TextView>(R.id.tvProfileBadge)

            tvName.text = account.name
            tvDetails.text = "+91 ${account.phone} · ${account.accountNumber}"
            tvBalances.text = String.format(
                Locale.US,
                "Bank: ₹%.2f | Offline: ₹%.2f",
                account.balance,
                account.emergencyBalance
            )

            val isCurrent = account.id == currentUser.id || account.phone == currentUser.phone
            if (isCurrent) {
                tvBadge.text = "Active"
                tvBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
                tvBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.primary_50))
            } else {
                tvBadge.text = "Switch"
                tvBadge.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                tvBadge.setBackgroundColor(Color.parseColor("#F1F5F9"))
            }

            itemView.setOnClickListener {
                authManager.setActiveAccount(account)
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    if (adapter != null && adapter.isEnabled) {
                        adapter.name = "EmPay - ${account.name}"
                    }
                } catch (e: SecurityException) {
                    // Ignored
                }
                val newSession = UserSession(
                    id = account.id,
                    name = account.name,
                    phone = account.phone,
                    accountNumber = account.accountNumber,
                    balance = account.balance,
                    emergencyBalance = account.emergencyBalance,
                    isOnline = true
                )
                onUserSelected(newSession)
                dialog.dismiss()
            }

            container.addView(itemView)
        }
    }
}
