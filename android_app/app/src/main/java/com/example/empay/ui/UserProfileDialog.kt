package com.example.empay.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.empay.R
import com.example.empay.auth.UserSession
import com.example.empay.network.BankApiClient
import com.example.empay.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Interactive Profile Switcher Dialog.
 * Allows evaluators and testers to switch between multiple registered bank accounts
 * on physical devices and emulators to test P2P Bluetooth payments.
 */
class UserProfileDialog(
    private val context: Context,
    private val currentUserId: Long,
    private val onUserSelected: (UserSession) -> Unit
) {

    private val dialog: Dialog = Dialog(context)
    private val scope = CoroutineScope(Dispatchers.Main)

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_user_profile, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(true)

        val containerUsers: LinearLayout = view.findViewById(R.id.containerUsers)
        val btnDismiss: Button = view.findViewById(R.id.btnDismissProfileDialog)

        btnDismiss.setOnClickListener { dialog.dismiss() }

        // Start with offline / demo profiles
        populateUsers(containerUsers, UserSession.DEMO_USERS)

        // If online, fetch live balances from Central Bank
        if (NetworkManager.isOnline(context)) {
            scope.launch {
                try {
                    val apiService = BankApiClient.getApiService(context)
                    val response = withContext(Dispatchers.IO) { apiService.getAllUsers() }
                    if (response.isSuccessful && response.body() != null) {
                        val bankUsers = response.body()!!
                        val mergedUsers = UserSession.DEMO_USERS.map { demo ->
                            val match = bankUsers.find { it.id == demo.id }
                            if (match != null) {
                                demo.copy(
                                    balance = match.balance,
                                    emergencyBalance = match.emergencyBalance
                                )
                            } else {
                                demo
                            }
                        }
                        populateUsers(containerUsers, mergedUsers)
                    }
                } catch (e: Exception) {
                    // Fallback to static demo user list if offline or server unreachable
                    e.printStackTrace()
                }
            }
        }

        dialog.show()
    }

    private fun populateUsers(container: LinearLayout, users: List<UserSession>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)

        users.forEach { user ->
            val itemView = inflater.inflate(R.layout.item_user_profile, container, false)
            val tvName = itemView.findViewById<TextView>(R.id.tvProfileName)
            val tvDetails = itemView.findViewById<TextView>(R.id.tvProfileDetails)
            val tvBalances = itemView.findViewById<TextView>(R.id.tvProfileBalances)
            val tvBadge = itemView.findViewById<TextView>(R.id.tvProfileBadge)

            tvName.text = user.name
            tvDetails.text = "+91 ${user.phone} · ${user.accountNumber}"
            tvBalances.text = String.format(
                Locale.US,
                "Bank: ₹%.2f | Reserve: ₹%.2f",
                user.balance,
                user.emergencyBalance
            )

            val isCurrent = (user.id == currentUserId)
            if (isCurrent) {
                tvBadge.text = "✓ ACTIVE"
                tvBadge.setTextColor(Color.WHITE)
                tvBadge.setBackgroundColor(context.getColor(R.color.primary))
            } else {
                tvBadge.text = "Switch"
                tvBadge.setTextColor(context.getColor(R.color.primary_dark))
                tvBadge.setBackgroundResource(R.drawable.bg_badge)

                itemView.setOnClickListener {
                    dialog.dismiss()
                    onUserSelected(user)
                }
            }

            container.addView(itemView)
        }
    }
}

