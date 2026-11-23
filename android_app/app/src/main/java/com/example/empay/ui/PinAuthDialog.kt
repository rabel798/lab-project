package com.example.empay.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.TextView
import com.example.empay.R
import com.example.empay.auth.PinAuthManager

/**
 * Interactive 4-dot PIN authorization dialog with custom keypad and shake error feedback.
 */
class PinAuthDialog(
    private val context: Context,
    private val actionTitle: String = "Offline Authorization",
    private val actionSubtitle: String = "Enter 4-digit PIN to authorize action",
    private val amount: Double? = null,
    private val payee: String? = null,
    private val channel: String? = null,
    private val onSuccess: () -> Unit,
    private val onFailed: ((String) -> Unit)? = null
) {

    private val dialog: Dialog = Dialog(context)
    private val enteredPin = StringBuilder()
    private val pinAuthManager = PinAuthManager.getInstance(context)

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvError: TextView
    private lateinit var dotsContainer: View
    private lateinit var dot1: View
    private lateinit var dot2: View
    private lateinit var dot3: View
    private lateinit var dot4: View

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_pin_auth, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(true)

        bindViews(view)
        setupKeypad(view)

        dialog.show()
    }

    private fun bindViews(view: View) {
        tvTitle = view.findViewById(R.id.tvPinDialogTitle)
        tvSubtitle = view.findViewById(R.id.tvPinDialogSubtitle)
        tvError = view.findViewById(R.id.tvPinError)
        dotsContainer = view.findViewById(R.id.pinDotsContainer)
        dot1 = view.findViewById(R.id.pinDot1)
        dot2 = view.findViewById(R.id.pinDot2)
        dot3 = view.findViewById(R.id.pinDot3)
        dot4 = view.findViewById(R.id.pinDot4)

        tvTitle.text = actionTitle
        tvSubtitle.text = actionSubtitle

        val layoutSummaryCard = view.findViewById<View>(R.id.layoutPinSummaryCard)
        val tvPinAmount = view.findViewById<TextView>(R.id.tvPinAmount)
        val tvPinPayee = view.findViewById<TextView>(R.id.tvPinPayee)
        val tvPinChannel = view.findViewById<TextView>(R.id.tvPinChannel)

        if (amount != null) {
            layoutSummaryCard?.visibility = View.VISIBLE
            tvPinAmount?.text = String.format(java.util.Locale.US, "₹ %.2f", amount)
            tvPinPayee?.text = payee ?: "To: Recipient"
            tvPinChannel?.text = channel ?: "Via Bluetooth Mesh"
        } else if (payee != null) {
            layoutSummaryCard?.visibility = View.VISIBLE
            tvPinAmount?.text = ""
            tvPinPayee?.text = payee
            tvPinChannel?.text = channel ?: ""
        } else {
            layoutSummaryCard?.visibility = View.GONE
        }
    }

    private fun setupKeypad(view: View) {
        val numberButtons = listOf(
            R.id.btnKey0 to "0", R.id.btnKey1 to "1", R.id.btnKey2 to "2",
            R.id.btnKey3 to "3", R.id.btnKey4 to "4", R.id.btnKey5 to "5",
            R.id.btnKey6 to "6", R.id.btnKey7 to "7", R.id.btnKey8 to "8",
            R.id.btnKey9 to "9"
        )

        for ((btnId, digit) in numberButtons) {
            view.findViewById<Button>(btnId)?.setOnClickListener {
                appendDigit(digit)
            }
        }

        view.findViewById<Button>(R.id.btnKeyBackspace)?.setOnClickListener {
            removeLastDigit()
        }

        view.findViewById<Button>(R.id.btnKeyCancel)?.setOnClickListener {
            dialog.dismiss()
        }
    }

    private fun appendDigit(digit: String) {
        if (enteredPin.length < 4) {
            enteredPin.append(digit)
            updateDots()

            if (enteredPin.length == 4) {
                verifyInput()
            }
        }
    }

    private fun removeLastDigit() {
        if (enteredPin.isNotEmpty()) {
            enteredPin.deleteCharAt(enteredPin.length - 1)
            tvError.visibility = View.INVISIBLE
            updateDots()
        }
    }

    private fun updateDots() {
        val count = enteredPin.length
        dot1.setBackgroundResource(if (count >= 1) R.drawable.pin_dot_filled else R.drawable.pin_dot_empty)
        dot2.setBackgroundResource(if (count >= 2) R.drawable.pin_dot_filled else R.drawable.pin_dot_empty)
        dot3.setBackgroundResource(if (count >= 3) R.drawable.pin_dot_filled else R.drawable.pin_dot_empty)
        dot4.setBackgroundResource(if (count >= 4) R.drawable.pin_dot_filled else R.drawable.pin_dot_empty)
    }

    private fun verifyInput() {
        val pin = enteredPin.toString()
        val isValid = pinAuthManager.verifyPin(pin)

        if (isValid) {
            dialog.dismiss()
            onSuccess.invoke()
        } else {
            // Play error shake animation
            val shake = AnimationUtils.loadAnimation(context, R.anim.shake)
            dotsContainer.startAnimation(shake)

            tvError.visibility = View.VISIBLE
            tvError.text = "Incorrect PIN. Try default: 1234"
            onFailed?.invoke("Invalid PIN entered: $pin")

            // Reset dots
            enteredPin.clear()
            dotsContainer.postDelayed({
                updateDots()
            }, 600)
        }
    }
}

