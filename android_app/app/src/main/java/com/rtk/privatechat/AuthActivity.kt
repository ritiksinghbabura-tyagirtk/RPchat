package com.rtk.privatechat

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.Base64

import com.rtk.privatechat.data.AuthApi
import com.rtk.privatechat.data.SessionStore
import com.rtk.privatechat.security.IdentityCrypto
import com.rtk.privatechat.security.IdentityVault

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject

import java.util.concurrent.Executors
import kotlin.random.Random


class AuthActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var submitButton: Button
    private lateinit var toggleButton: Button
    private lateinit var recoveryButton: Button
    private lateinit var statusText: TextView

    private var registerMode = false
    private var pendingPhrase: String? = null
    private var pendingToken: String? = null
    private var pendingPublicId: String? = null

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!SessionStore.getToken(this).isNullOrBlank()) {
            openMain()
            return
        }

        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(28), dp(22), dp(24))
            setBackgroundColor(getColor(R.color.rtk_background))
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        val brandMark = TextView(this).apply {
            text = "R"
            textSize = 25f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-black", 0)
            setTextColor(getColor(R.color.rtk_background))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.rtk_green))
            }
        }
        root.addView(brandMark, LinearLayout.LayoutParams(dp(58), dp(58)).apply {
            bottomMargin = dp(14)
        })

        root.addView(TextView(this).apply {
            text = "RTK PRIVATE"
            textSize = 27f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
            setTextColor(getColor(R.color.rtk_text))
        }, match())

        root.addView(TextView(this).apply {
            text = "Private conversations. Your identity stays yours."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(24))
            setTextColor(getColor(R.color.rtk_muted))
        }, match())

        val formCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(18))
            background = GradientDrawable().apply {
                setColor(getColor(R.color.rtk_surface))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), getColor(R.color.rtk_border))
            }
        }

        formCard.addView(TextView(this).apply {
            text = "Welcome back"
            textSize = 20f
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
            setTextColor(getColor(R.color.rtk_text))
            setPadding(dp(2), 0, dp(2), dp(5))
        }, match())
        formCard.addView(TextView(this).apply {
            text = "Sign in or create your secure account."
            textSize = 13f
            setTextColor(getColor(R.color.rtk_muted))
            setPadding(dp(2), 0, dp(2), dp(16))
        }, match())

        fun styleField(field: EditText, hintText: String, inputTypeValue: Int) {
            field.hint = hintText
            field.isSingleLine = true
            field.inputType = inputTypeValue
            field.textSize = 15f
            field.setTextColor(getColor(R.color.rtk_text))
            field.setHintTextColor(getColor(R.color.rtk_muted))
            field.background = GradientDrawable().apply {
                setColor(getColor(R.color.rtk_input))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), getColor(R.color.rtk_border))
            }
            field.setPadding(dp(14), dp(13), dp(14), dp(13))
        }

        usernameInput = EditText(this)
        styleField(usernameInput, "Username", InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        passwordInput = EditText(this)
        styleField(passwordInput, "Password (minimum 12 characters)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        statusText = TextView(this).apply {
            text = "Your password is protected using Argon2id."
            textSize = 12f
            setPadding(dp(2), dp(13), dp(2), dp(12))
            setTextColor(getColor(R.color.rtk_muted))
        }

        submitButton = Button(this).apply {
            isAllCaps = false
            textSize = 15f
            minHeight = dp(52)
            setTextColor(getColor(R.color.rtk_background))
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.rtk_green))
        }
        toggleButton = Button(this).apply {
            isAllCaps = false
            textSize = 13f
            minHeight = dp(48)
            setTextColor(getColor(R.color.rtk_green))
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.rtk_surface_elevated))
        }
        recoveryButton = Button(this).apply {
            isAllCaps = false
            textSize = 13f
            minHeight = dp(46)
            setTextColor(getColor(R.color.rtk_muted))
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.rtk_surface))
        }

        submitButton.setOnClickListener { submit() }
        toggleButton.setOnClickListener {
            registerMode = !registerMode
            updateMode()
        }
        recoveryButton.text = "Forgot RTK ID / Password?"
        recoveryButton.setOnClickListener { showRecoveryDialog() }

        fun addSpaced(view: android.view.View, top: Int = 0) {
            formCard.addView(view, match().apply { topMargin = dp(top) })
        }
        addSpaced(usernameInput, 2)
        addSpaced(passwordInput, 12)
        addSpaced(submitButton, 18)
        addSpaced(toggleButton, 9)
        addSpaced(recoveryButton, 4)
        addSpaced(statusText, 6)
        root.addView(formCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(TextView(this).apply {
            text = "END-TO-END ENCRYPTED  •  PRIVATE BY DESIGN"
            textSize = 10f
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
            setTextColor(getColor(R.color.rtk_muted))
            setPadding(dp(8), dp(20), dp(8), dp(8))
        }, match())

        setContentView(scroll)
        UiWindow.apply(this, scroll)
        updateMode()
    }

    private fun match() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun updateMode() {
        submitButton.text = if (registerMode) "Create Account" else "Login"

        toggleButton.text = if (registerMode) {
            "Already have an account? Login"
        } else {
            "New user? Create account"
        }

        recoveryButton.visibility =
            if (registerMode) android.view.View.GONE else android.view.View.VISIBLE

        statusText.text = if (registerMode) {
            "A 12-word recovery phrase will be generated. You must confirm 5 randomly selected words."
        } else {
            "Sign in with your username and password."
        }
    }

    private fun submit() {
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()

        if (username.isBlank() || password.isBlank()) {
            toast("Enter username and password")
            return
        }

        if (registerMode && password.length < 12) {
            toast("Password must contain at least 12 characters")
            return
        }

        submitButton.isEnabled = false
        statusText.text = "Connecting to server..."

        executor.execute {
            try {
                val body = JSONObject().apply {
                    put("username", username)
                    put("password", password)
                }

                val path = if (registerMode) {
                    "/api/v1/auth/register"
                } else {
                    "/api/v1/auth/login"
                }

                val response = AuthApi.post(path, body)
                val token = response.getString("session_token")
                val user = response.optJSONObject("user")
                    ?: throw IllegalStateException("User data missing")
                val publicId = user.optString("user_id")

                if (registerMode) {
                    val phrase = IdentityCrypto.generateMnemonic(this)
                    pendingPhrase = phrase
                    pendingToken = token
                    pendingPublicId = publicId

                    runOnUiThread {
                        submitButton.isEnabled = true
                        showPhraseDialog(phrase)
                    }
                } else {
                    SessionStore.saveToken(this, token)
                    runOnUiThread {
                        toast("Welcome. RTK ID: $publicId")
                        openMain()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = e.message ?: "Connection failed"
                    submitButton.isEnabled = true
                }
            }
        }
    }

    private fun showPhraseDialog(phrase: String) {
        val words = phrase.split(" ")

        val message = buildString {
            append("Write these 12 words down in the exact order.\n\n")
            words.forEachIndexed { index, word ->
                append("${index + 1}. $word\n")
            }
            append("\nNever share this phrase. It is not sent to the server.")
        }

        AlertDialog.Builder(this)
            .setTitle("Your recovery phrase")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("I saved my phrase") { _, _ ->
                showFiveWordCheck(words)
            }
            .setNegativeButton("Cancel") { _, _ ->
                pendingPhrase = null
                pendingToken = null
                pendingPublicId = null
                statusText.text =
                    "Account was created, but identity setup is incomplete. Do not uninstall the app."
            }
            .show()
    }

    private fun showFiveWordCheck(words: List<String>) {
        val positions = (0 until 12).shuffled().take(5).sorted()
        val fields = mutableListOf<EditText>()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        positions.forEach { position ->
            layout.addView(TextView(this).apply {
                text = "Word at position ${position + 1}"
            })

            val field = EditText(this).apply {
                hint = "Enter word ${position + 1}"
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }

            fields.add(field)
            layout.addView(field)
        }

        AlertDialog.Builder(this)
            .setTitle("Confirm 5 random words")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Verify", null)
            .setNegativeButton("Cancel") { _, _ ->
                pendingPhrase = null
                pendingToken = null
            }
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener {
                            val valid = positions.indices.all { index ->
                                fields[index].text.toString().trim()
                                    .equals(words[positions[index]], true)
                            }

                            if (!valid) {
                                toast("Incorrect words. Please try again.")
                                return@setOnClickListener
                            }

                            dialog.dismiss()
                            finishIdentityRegistration()
                        }
                }
                dialog.show()
            }
    }

    private fun finishIdentityRegistration() {
        val phrase = pendingPhrase ?: return
        val token = pendingToken ?: return

        submitButton.isEnabled = false
        statusText.text = "Securing your identity..."

        executor.execute {
            try {
                val material = IdentityCrypto.fromMnemonic(this, phrase)

                // Private seeds remain encrypted in Android Keystore vault.
                IdentityVault.save(this, material)

                val body = JSONObject().apply {
                    put("signing_public_key", material.signingPublicBase64())
                    put("exchange_public_key", material.exchangePublicBase64())
                }

                AuthApi.post("/api/v1/identity/create", body, token)

                SessionStore.saveToken(this, token)

                pendingPhrase = null
                pendingToken = null
                pendingPublicId = null

                runOnUiThread {
                    toast("Identity registered successfully")
                    openMain()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text =
                        "Identity setup failed: ${e.message ?: "Unknown error"}. Keep your recovery phrase safe."
                    submitButton.isEnabled = true
                }
            }
        }
    }

    private fun showRecoveryDialog() {
        val idField = EditText(this).apply { hint = "RTK ID"; isSingleLine = true }
        AlertDialog.Builder(this)
            .setTitle("Recover RPchat account")
            .setMessage("Enter your RTK ID first. Your 12-word phrase will be requested one word at a time and stays on this device.")
            .setView(idField)
            .setPositiveButton("Continue", null)
            .setNegativeButton("Cancel", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val id = idField.text.toString().trim().uppercase()
                        if (!Regex("^RTK-[0-9A-F]{16}$").matches(id)) {
                            toast("Enter a valid RTK ID")
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        collectRecoveryWords(id)
                    }
                }
                dialog.show()
            }
    }

    private fun collectRecoveryWords(publicId: String) {
        val words = mutableListOf<String>()
        fun askWord(position: Int) {
            val field = EditText(this).apply {
                hint = "Recovery word ${position + 1} of 12"
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            AlertDialog.Builder(this)
                .setTitle("Recovery phrase • ${position + 1}/12")
                .setMessage("Enter only word ${position + 1}. Keep the exact original order.")
                .setView(field)
                .setCancelable(false)
                .setPositiveButton(if (position == 11) "Verify phrase" else "Next", null)
                .setNegativeButton("Cancel") { _, _ -> words.clear() }
                .create().also { dialog ->
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val word = field.text.toString().trim().lowercase()
                            if (word.isBlank() || word.any { it.isWhitespace() }) {
                                toast("Enter one valid word")
                                return@setOnClickListener
                            }
                            words.add(word)
                            dialog.dismiss()
                            if (position < 11) askWord(position + 1)
                            else {
                                val phrase = words.joinToString(" ")
                                if (!IdentityCrypto.validateMnemonic(this, phrase)) {
                                    words.clear()
                                    toast("Phrase is invalid or the word order is incorrect. Start again.")
                                    collectRecoveryWords(publicId)
                                } else askNewRecoveryPassword(publicId, phrase)
                            }
                        }
                    }
                    dialog.show()
                }
        }
        askWord(0)
    }

    private fun askNewRecoveryPassword(publicId: String, phrase: String) {
        val field = EditText(this).apply {
            hint = "New password (minimum 12 characters)"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Set a new password")
            .setMessage("The recovery phrase is never sent to the server.")
            .setView(field)
            .setPositiveButton("Recover account", null)
            .setNegativeButton("Cancel", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val password = field.text.toString()
                        if (password.length < 12) {
                            toast("New password must contain at least 12 characters")
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        performRecovery(publicId, phrase, password)
                    }
                }
                dialog.show()
            }
    }

    private fun performRecovery(
        publicId: String,
        phrase: String,
        newPassword: String
    ) {
        recoveryButton.isEnabled = false
        statusText.text = "Verifying recovery identity..."

        executor.execute {
            try {
                // Derivation happens locally; phrase is never put in a request.
                val material = IdentityCrypto.fromMnemonic(this, phrase)

                val challengeResponse = AuthApi.post(
                    "/api/v1/recovery/challenge",
                    JSONObject().put("user_id", publicId)
                )

                val challengeId =
                    challengeResponse.getString("challenge_id")
                val challengeText =
                    challengeResponse.getString("challenge")

                val challengeBytes = Base64.decode(
                    challengeText, Base64.DEFAULT
                )

                val privateKey = Ed25519PrivateKeyParameters(
                    material.signingSeed, 0
                )

                val signer = Ed25519Signer()
                signer.init(true, privateKey)
                signer.update(challengeBytes, 0, challengeBytes.size)

                val signature = signer.generateSignature()
                val signatureText =
                    Base64.encodeToString(signature, Base64.NO_WRAP)

                val verifyBody = JSONObject().apply {
                    put("challenge_id", challengeId)
                    put("challenge", challengeText)
                    put("signature", signatureText)
                    put("new_password", newPassword)
                }

                val result = AuthApi.post(
                    "/api/v1/recovery/verify",
                    verifyBody
                )

                val user = result.optJSONObject("user")
                val recoveredId = user?.optString("user_id") ?: publicId

                // The same phrase deterministically restores the original identity keys.
                IdentityVault.save(this, material)

                runOnUiThread {
                    recoveryButton.isEnabled = true
                    AlertDialog.Builder(this)
                        .setTitle("Recovery successful")
                        .setMessage(
                            "RTK ID: $recoveredId\n\n" +
                            "Your password has been changed. " +
                            "All previous sessions have been revoked. " +
                            "Please log in with your new password."
                        )
                        .setPositiveButton("Login") { _, _ ->
                            usernameInput.setText("")
                            passwordInput.setText("")
                            registerMode = false
                            updateMode()
                        }
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    recoveryButton.isEnabled = true
                    statusText.text =
                        e.message ?: "Recovery failed"
                }
            }
        }
    }

    private fun openMain() {
        startActivity(
            android.content.Intent(this, MainActivity::class.java)
        )
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
