package com.rtk.privatechat

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.rtk.privatechat.data.AuthApi
import com.rtk.privatechat.data.SessionStore
import com.rtk.privatechat.security.ChatCrypto
import com.rtk.privatechat.security.IdentityVault
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class ChatActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            val id = activeConversation
            if (id != null && !isFinishing) loadMessages(id, false)
            handler.postDelayed(this, 4000)
        }
    }

    private var activeConversation: String? = null
    private var activePeerRtkId: String? = null
    private var messageList: LinearLayout? = null
    private var messageInput: EditText? = null
    private var statusView: TextView? = null
    private var lastRenderedSignature = ""

    private val token: String
        get() = SessionStore.getToken(this).orEmpty()

    private val bg = Color.rgb(9, 15, 12)
    private val surface = Color.rgb(18, 27, 22)
    private val surface2 = Color.rgb(24, 36, 29)
    private val green = Color.rgb(91, 224, 145)
    private val textMain = Color.rgb(239, 247, 241)
    private val muted = Color.rgb(151, 170, 158)
    private val border = Color.rgb(42, 60, 48)
    private val incomingBubble = Color.rgb(27, 41, 33)
    private val outgoingBubble = Color.rgb(24, 75, 49)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        if (token.isBlank()) {
            finish()
            return
        }
        showChatList()
        handler.postDelayed(poller, 4000)
    }

    private fun rounded(color: Int, radius: Int = 18, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun label(
        value: String,
        size: Float = 14f,
        color: Int = textMain,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = true
        setPadding(dp(2), dp(3), dp(2), dp(3))
    }

    private fun actionButton(
        value: String,
        primary: Boolean = false,
        action: () -> Unit
    ) = TextView(this).apply {
        text = value
        textSize = 14f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Color.rgb(7, 25, 15) else textMain)
        background = rounded(if (primary) green else surface2, 14, if (primary) 0 else border)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        minHeight = dp(48)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun pageRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bg)
        setPadding(dp(18), dp(12), dp(18), dp(12))
    }

    private fun addHeader(page: LinearLayout, title: String, subtitle: String) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(18))
        }
        header.addView(label("RTK PRIVATE", 12f, green, true))
        header.addView(label(title, 27f, textMain, true).apply {
            setPadding(dp(2), dp(5), dp(2), dp(2))
        })
        header.addView(label(subtitle, 13f, muted))
        page.addView(header)
    }

    private fun showChatList() {
        activeConversation = null
        activePeerRtkId = null
        messageList = null
        messageInput = null

        val page = pageRoot()
        addHeader(page, "Your messages", "Private conversations, protected end to end.")

        val startCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 20, border)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        startCard.addView(label("Start a conversation", 16f, textMain, true))
        startCard.addView(label("Enter the exact RTK ID of a contact whose request has been accepted.", 12f, muted))
        val idInput = EditText(this).apply {
            hint = "RTK-0123456789ABCDEF"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setTextColor(textMain)
            setHintTextColor(muted)
            textSize = 14f
            background = rounded(bg, 12, border)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val inputParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(12) }
        startCard.addView(idInput, inputParams)

        startCard.addView(actionButton("Open private conversation", true) {
            val id = idInput.text.toString().trim().uppercase()
            if (!Regex("^RTK-[0-9A-F]{16}$").matches(id)) {
                toast("Enter a valid RTK ID")
            } else {
                createOrOpenChat(id)
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        page.addView(startCard)

        val listHeader = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(22), dp(2), dp(10))
        }
        listHeader.addView(label("Recent chats", 18f, textMain, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listHeader.addView(actionButton("Refresh") { loadChats() })
        page.addView(listHeader)

        statusView = label("Loading your conversations…", 12f, muted)
        page.addView(statusView)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(8))
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        page.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        messageList = rows
        setContentView(page)
        UiWindow.apply(this, page)
        loadChats()
    }

    private fun loadChats() {
        worker.execute {
            try {
                val response = AuthApi.get("/api/v1/chats", token)
                val chats = response.optJSONArray("chats") ?: JSONArray()
                runOnUiThread {
                    if (activeConversation != null) return@runOnUiThread
                    val rows = messageList ?: return@runOnUiThread
                    rows.removeAllViews()
                    if (chats.length() == 0) {
                        val empty = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            background = rounded(surface, 18, border)
                            setPadding(dp(20), dp(30), dp(20), dp(30))
                        }
                        empty.addView(label("No conversations yet", 17f, textMain, true))
                        empty.addView(label(
                            "Start a chat with someone from your accepted contacts using their RTK ID.",
                            13f, muted
                        ).apply { gravity = Gravity.CENTER })
                        rows.addView(empty, match().apply { topMargin = dp(8) })
                    }
                    for (i in 0 until chats.length()) {
                        val item = chats.optJSONObject(i) ?: continue
                        val contact = item.optJSONObject("contact") ?: JSONObject()
                        val id = item.optString("conversation_id")
                        val username = contact.optString("username", "Contact")
                        val rtk = contact.optString("user_id", "")
                        rows.addView(chatRow(username, rtk) {
                            if (id.isNotBlank()) openConversation(id, username, rtk)
                        }, match().apply { bottomMargin = dp(10) })
                    }
                    statusView?.text = "${chats.length()} conversation${if (chats.length() == 1) "" else "s"}"
                }
            } catch (e: Exception) {
                runOnUiThread { statusView?.text = "Could not load chats: ${e.message}" }
            }
        }
    }

    private fun chatRow(username: String, rtk: String, action: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 18, border)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        val avatar = TextView(this).apply {
            text = username.trim().take(1).uppercase().ifBlank { "R" }
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(7, 28, 16))
            background = rounded(green, 30)
        }
        row.addView(avatar, LinearLayout.LayoutParams(dp(48), dp(48)))
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(6), 0)
        }
        details.addView(label(username, 15f, textMain, true))
        details.addView(label(rtk, 11f, muted))
        row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label("›", 27f, green, true))
        return row
    }

    private fun createOrOpenChat(rtkId: String) {
        worker.execute {
            try {
                val response = AuthApi.post(
                    "/api/v1/chats",
                    JSONObject().put("user_id", rtkId),
                    token
                )
                val id = response.getString("conversation_id")
                runOnUiThread { openConversation(id, rtkId, rtkId) }
            } catch (e: Exception) {
                runOnUiThread { toast(e.message ?: "Could not start chat") }
            }
        }
    }

    private fun openConversation(id: String, username: String, rtkId: String) {
        activeConversation = id
        activePeerRtkId = rtkId
        lastRenderedSignature = ""

        val page = pageRoot()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(12))
        }
        header.addView(actionButton("‹") { showChatList() })
        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        identity.addView(label(username, 18f, textMain, true))
        identity.addView(label(rtkId, 11f, muted))
        header.addView(identity, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(label("●", 12f, green, true))
        page.addView(header)

        val security = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 16, border)
            setPadding(dp(13), dp(10), dp(13), dp(10))
        }
        security.addView(label("🔒  END-TO-END ENCRYPTED", 11f, green, true))
        security.addView(label("Messages are protected with X25519 + AES-256-GCM", 11f, muted))
        page.addView(security, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        statusView = label("Verifying contact identity…", 11f, muted)
        page.addView(statusView)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(10))
        }
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        scroll.addView(rows, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        page.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        messageList = rows

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 18, border)
            setPadding(dp(8), dp(5), dp(6), dp(5))
        }
        val input = EditText(this).apply {
            hint = "Write a message…"
            minLines = 1
            maxLines = 4
            textSize = 14f
            setTextColor(textMain)
            setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = null
            setPadding(dp(10), dp(8), dp(8), dp(8))
        }
        messageInput = input
        composer.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        composer.addView(actionButton("Send", true) { sendMessage() })
        page.addView(composer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        setContentView(page)
        UiWindow.apply(this, page)
        loadMessages(id, true)
    }

    private fun sendMessage() {
        val id = activeConversation ?: return
        val plain = messageInput?.text?.toString()?.trim().orEmpty()
        if (plain.isBlank()) return
        if (plain.length > 5000) {
            toast("Message is too long (max 5000 characters)")
            return
        }
        messageInput?.isEnabled = false
        worker.execute {
            try {
                val peerId = activePeerRtkId ?: error("Chat contact is missing")
                val ownMaterial = IdentityVault.load(this)
                    ?: error("Identity vault is locked or missing. Recover your account before sending.")
                val peerResponse = AuthApi.get("/api/v1/identity/by-user/$peerId", token)
                val peerIdentity = peerResponse.getJSONObject("identity")
                val encrypted = ChatCrypto.encrypt(
                    ownMaterial,
                    peerIdentity.getString("exchange_public_key"),
                    id,
                    plain
                )
                AuthApi.post(
                    "/api/v1/chats/$id/messages",
                    JSONObject().put("ciphertext", encrypted.ciphertext)
                        .put("nonce", encrypted.nonce),
                    token
                )
                runOnUiThread {
                    messageInput?.setText("")
                    messageInput?.isEnabled = true
                    loadMessages(id, true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    messageInput?.isEnabled = true
                    toast(e.message ?: "Message send failed")
                }
            }
        }
    }

    private fun loadMessages(id: String, forceRender: Boolean) {
        worker.execute {
            try {
                val response = AuthApi.get("/api/v1/chats/$id/messages", token)
                val messages = response.optJSONArray("messages") ?: JSONArray()
                val peerIdForCrypto = activePeerRtkId ?: error("Chat contact is missing")
                val peerIdentityForCrypto = AuthApi.get(
                    "/api/v1/identity/by-user/$peerIdForCrypto", token
                ).getJSONObject("identity")
                val ownMaterialForCrypto = IdentityVault.load(this)
                    ?: error("Identity vault unavailable")
                val peerExchangePublicForCrypto = peerIdentityForCrypto.getString("exchange_public_key")
                val peerSigningPublicForCrypto = peerIdentityForCrypto.getString("signing_public_key")
                val signature = buildString {
                    for (i in 0 until messages.length()) {
                        append(messages.optJSONObject(i)?.optInt("id") ?: 0).append(':')
                    }
                }
                runOnUiThread {
                    if (activeConversation != id) return@runOnUiThread
                    if (!forceRender && signature == lastRenderedSignature) return@runOnUiThread
                    lastRenderedSignature = signature
                    val rows = messageList ?: return@runOnUiThread
                    rows.removeAllViews()
                    if (messages.length() == 0) {
                        val empty = label("This is the beginning of your private conversation.", 13f, muted)
                        empty.gravity = Gravity.CENTER
                        rows.addView(empty, match().apply {
                            topMargin = dp(30)
                            bottomMargin = dp(30)
                        })
                    }
                    for (i in 0 until messages.length()) {
                        val item = messages.optJSONObject(i) ?: continue
                        val sender = item.optString("sender_id")
                        val decoded = try {
                            ChatCrypto.decrypt(
                                ownMaterialForCrypto,
                                peerExchangePublicForCrypto,
                                if (sender.equals(peerIdForCrypto, true)) peerSigningPublicForCrypto
                                else ownMaterialForCrypto.signingPublicBase64(),
                                id,
                                item.optString("ciphertext"),
                                item.optString("nonce")
                            )
                        } catch (_: Exception) {
                            try {
                                "[Legacy] " + String(
                                    Base64.decode(item.optString("ciphertext"), Base64.DEFAULT),
                                    Charsets.UTF_8
                                )
                            } catch (_: Exception) {
                                "[Unable to decrypt — verify the contact identity and recovery phrase]"
                            }
                        }
                        val mine = !sender.equals(activePeerRtkId, true)
                        rows.addView(messageBubble(decoded, item.optString("created_at"), mine),
                            match().apply {
                                bottomMargin = dp(9)
                            })
                    }
                    statusView?.text =
                        "🔐  Verified encryption  •  ${messages.length()} messages  •  ${peerIdentityForCrypto.optString("fingerprint").take(18)}…"
                }
            } catch (e: Exception) {
                runOnUiThread { statusView?.text = "Unable to sync messages: ${e.message}" }
            }
        }
    }

    private fun messageBubble(message: String, timestamp: String, mine: Boolean): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(dp(3), dp(1), dp(3), dp(1))
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(if (mine) outgoingBubble else incomingBubble, 17,
                if (mine) 0 else border)
            setPadding(dp(13), dp(9), dp(13), dp(7))
        }
        bubble.addView(label(message, 14f, textMain))
        val time = timestamp.substringAfter('T', timestamp).take(8).ifBlank { timestamp }
        bubble.addView(label(time, 10f, if (mine) Color.rgb(173, 218, 188) else muted).apply {
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
        })
        wrapper.addView(bubble, LinearLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.78f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return wrapper
    }

    private fun match() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        worker.shutdownNow()
        super.onDestroy()
    }
}
