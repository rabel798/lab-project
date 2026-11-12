package com.example.empay.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.example.empay.R
import com.example.empay.network.NetworkManager
import java.util.Locale

/**
 * Dialog controller for reserving emergency offline funds from the Central Laptop Bank.
 * Strictly enforces network connectivity and user PIN authorization before execution.
 */
class TransferFundsDialog(
    private val context: Context,
    private val availableOnlineBalance: Double,
    private val onAuthorized: (Double) -> Unit,
    private val onFailed: (String) -> Unit
) {

    private val dialog: Dialog = Dialog(context)

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_transfer_funds, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(true)

        val bannerOfflineWarning: View = view.findViewById(R.id.bannerOfflineWarning)
        val tvAvailableBalance: TextView = view.findViewById(R.id.tvDialogAvailableBalance)
        val etAmount: EditText = view.findViewById(R.id.etTransferAmount)
        val btnChip5: Button = view.findViewById(R.id.btnChip5)
        val btnChip10: Button = view.findViewById(R.id.btnChip10)
        val btnChip20: Button = view.findViewById(R.id.btnChip20)
        val btnCancel: Button = view.findViewById(R.id.btnCancelTransfer)
        val btnConfirm: Button = view.findViewById(R.id.btnConfirmTransfer)

        tvAvailableBalance.text = String.format(Locale.US, "₹%.2f", availableOnlineBalance)

        val isOnline = NetworkManager.isOnline(context)
        if (!isOnline) {
            bannerOfflineWarning.visibility = View.VISIBLE
            btnConfirm.isEnabled = false
            btnConfirm.alpha = 0.5f
            btnConfirm.text = "Offline (Blocked)"
        } else {
            bannerOfflineWarning.visibility = View.GONE
            btnConfirm.isEnabled = true
            btnConfirm.alpha = 1.0f
        }

        btnChip5.setOnClickListener { etAmount.setText("5.00") }
        btnChip10.setOnClickListener { etAmount.setText("10.00") }
        btnChip20.setOnClickListener { etAmount.setText("20.00") }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            val amountStr = etAmount.text.toString().trim()
            val amount = amountStr.toDoubleOrNull()

            if (amount == null || amount <= 0.0) {
                Toast.makeText(context, "Please enter a valid amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (amount > availableOnlineBalance) {
                Toast.makeText(
                    context,
                    String.format(Locale.US, "Insufficient online balance (₹%.2f)", availableOnlineBalance),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Enforce online check again at moment of confirmation
            if (!NetworkManager.isOnline(context)) {
                Toast.makeText(
                    context,
                    "Cannot reserve funds offline. Active bank connection required.",
                    Toast.LENGTH_LONG
                ).show()
                onFailed("Transfer blocked: Device is offline.")
                return@setOnClickListener
            }

            dialog.dismiss()

            // Require 4-digit PIN verification to confirm bank fund reservation
            val pinDialog = PinAuthDialog(
                context = context,
                actionTitle = "Authorize Fund Reservation",
                actionSubtitle = String.format(Locale.US, "Enter PIN to transfer ₹%.2f to Emergency Wallet", amount),
                onSuccess = {
                    onAuthorized(amount)
                },
                onFailed = { failReason ->
                    onFailed("Fund reservation cancelled: $failReason")
                }
            )
            pinDialog.show()
        }

        dialog.show()
    }
}

