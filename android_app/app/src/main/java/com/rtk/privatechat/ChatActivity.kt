package com.rtk.privatechat

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
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

    private var root: LinearLayout? = null
    private var activeConversation: String? = null
    private var messageList: LinearLayout? = null
    private var messageInput: EditText? = null
    private var statusView: TextView? = null
    private var lastRenderedSignature = ""

    private val token: String
        get() = SessionStore.getToken(this).orEmpty()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (token.isBlank()) {
            finish()
            return
        }
        showChatList()
        handler.postDelayed(poller, 4000)
    }

    private fun label(value: String, size: Float = 15f): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(0xff202124.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

    private fun makeButton(value: String, action: () -> Unit) =
        Button(this).apply {
            text = value
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun base(title: String): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        page.addView(label("RTK PRIVATE  •  $title", 21f))
        page.addView(label(
            "TEST MODE — Base64 only; NOT end-to-end encrypted.",
            12f
        ).apply { setTextColor(0xffb3261e.toInt()) })
        setContentView(page)
        return page
    }

    private fun showChatList() {
        activeConversation = null
        messageList = null
        messageInput = null
        val page = base("Chats")
        val idInput = EditText(this).apply {
            hint = "Accepted contact RTK ID (RTK-...)"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        page.addView(idInput, match())
        page.addView(makeButton("Start / Open chat") {
            val id = idInput.text.toString().trim().uppercase()
            if (!Regex("^RTK-[0-9A-F]{16}$").matches(id)) {
                toast("Enter a valid RTK ID")
            } else {
                createOrOpenChat(id)
            }
        }, match())
        page.addView(makeButton("Refresh chats") { loadChats() }, match())
        statusView = label("Loading chats…", 13f)
        page.addView(statusView)
        val scroll = ScrollView(this)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows)
        page.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        messageList = rows
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
                        rows.addView(label("No chats yet. Start one using an accepted contact's RTK ID."))
                    }
                    for (i in 0 until chats.length()) {
                        val item = chats.optJSONObject(i) ?: continue
                        val contact = item.optJSONObject("contact") ?: JSONObject()
                        val id = item.optString("conversation_id")
                        val username = contact.optString("username", "Contact")
                        val rtk = contact.optString("user_id", "")
                        rows.addView(makeButton("$username\n$rtk") {
                            if (id.isNotBlank()) openConversation(id, username, rtk)
                        }, match())
                    }
                    statusView?.text = "Chats: ${chats.length()}"
                }
            } catch (e: Exception) {
                runOnUiThread { statusView?.text = "Chat load failed: ${e.message}" }
            }
        }
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
        lastRenderedSignature = ""
        val page = base("Chat with $username")
        page.addView(label(rtkId, 12f))
        page.addView(makeButton("← Back to chats") { showChatList() }, match())
        statusView = label("Loading messages…", 12f)
        page.addView(statusView)
        val scroll = ScrollView(this)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows)
        page.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        messageList = rows
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = "Message"
            minLines = 1
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        messageInput = input
        composer.addView(input, LinearLayout.LayoutParams(0, dp(54), 1f))
        composer.addView(makeButton("Send") { sendMessage() })
        page.addView(composer)
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
                // TEST ONLY: reversible Base64 encoding, not encryption.
                val encoded = Base64.encodeToString(
                    plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                )
                val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val nonceText = Base64.encodeToString(nonce, Base64.NO_WRAP)
                AuthApi.post(
                    "/api/v1/chats/$id/messages",
                    JSONObject().put("ciphertext", encoded).put("nonce", nonceText),
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
                    if (messages.length() == 0) rows.addView(label("No messages yet. Say hello!"))
                    for (i in 0 until messages.length()) {
                        val item = messages.optJSONObject(i) ?: continue
                        val sender = item.optString("sender_id")
                        val decoded = try {
                            String(Base64.decode(item.optString("ciphertext"), Base64.DEFAULT), Charsets.UTF_8)
                        } catch (_: Exception) {
                            "[Unsupported message payload]"
                        }
                        rows.addView(label(
                            "$sender\n$decoded\n${item.optString("created_at")}",
                            14f
                        ).apply {
                            setBackgroundColor(0xffeeeeee.toInt())
                        }, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(6) })
                    }
                    statusView?.text = "Messages: ${messages.length()} • refreshes every 4 seconds"
                }
            } catch (e: Exception) {
                runOnUiThread { statusView?.text = "Message load failed: ${e.message}" }
            }
        }
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
