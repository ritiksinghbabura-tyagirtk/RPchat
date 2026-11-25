package com.rtk.privatechat

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.rtk.privatechat.data.AuthApi
import com.rtk.privatechat.data.SessionStore
import com.rtk.privatechat.security.IdentityQr
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var screenContainer: FrameLayout
    private lateinit var bottomNavigation: BottomNavigationView

    private val executor = Executors.newSingleThreadExecutor()

    private var contactsSearchInput: EditText? = null

    private val qrScanLauncher =
        registerForActivityResult(ScanContract()) { result ->
            val raw = result.contents

            if (!raw.isNullOrBlank()) {
                val candidate = try {
                    JSONObject(raw).optString("user_id").ifBlank { raw }
                } catch (_: Exception) {
                    raw
                }

                val scannedId = candidate.trim().uppercase()

                if (Regex("^RTK-[0-9A-F]{16}$").matches(scannedId)) {
                    bottomNavigation.selectedItemId = R.id.nav_contacts
                    contactsSearchInput?.setText(scannedId)
                    searchButton?.performClick()
                } else {
                    toast("Invalid RTK PRIVATE QR code")
                }
            }
        }

    private var contactsRoot: LinearLayout? = null
    private var searchResultBox: LinearLayout? = null
    private var searchButton: Button? = null
    private var searchStatusView: TextView? = null

    private val token: String
        get() = SessionStore.getToken(this).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (token.isBlank()) {
            openAuth()
            return
        }

        setContentView(R.layout.activity_main)
        UiWindow.apply(this, findViewById(R.id.main_root))

        toolbar = findViewById(R.id.toolbar)
        screenContainer = findViewById(R.id.screen_container)
        bottomNavigation = findViewById(R.id.bottom_navigation)

        toolbar.title = "RTK PRIVATE"

        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> {
                    // Keep a useful MainActivity page underneath ChatActivity.
                    showScreen("Chats", "Your private conversations open in the chat workspace. Tap Chats to reopen them.")
                    startActivity(Intent(this, ChatActivity::class.java))
                    true
                }

                R.id.nav_contacts -> {
                    showContactsScreen()
                    true
                }

                R.id.nav_support -> {
                    showSupportScreen()
                    true
                }

                R.id.nav_profile -> {
                    showProfileScreen()
                    true
                }

                R.id.nav_settings -> {
                    showSettings()
                    true
                }

                else -> false
            }
        }

        // Render a fallback first; this prevents a blank content area after Activity restore.
        showScreen("Chats", "Your private conversations open in the chat workspace. Tap Chats to open them.")
        val restoredTab = savedInstanceState?.getInt("rtk_selected_tab", R.id.nav_chats)
            ?: R.id.nav_chats
        bottomNavigation.selectedItemId = restoredTab
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("rtk_selected_tab", bottomNavigation.selectedItemId)
        super.onSaveInstanceState(outState)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun text(
        value: String,
        size: Float = 15f,
        color: Int = R.color.rtk_text
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(color))
        typeface = android.graphics.Typeface.create(
            if (size >= 18f) "sans-serif-medium" else "sans-serif",
            if (size >= 18f) android.graphics.Typeface.NORMAL else android.graphics.Typeface.NORMAL
        )
        includeFontPadding = true
        setPadding(dp(4), dp(7), dp(4), dp(7))
    }

    private fun button(
        label: String,
        action: () -> Unit
    ) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        minHeight = dp(48)
        setTextColor(getColor(R.color.rtk_background))
        backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.rtk_green))
        elevation = dp(1).toFloat()
        setOnClickListener { action() }
    }

    private fun section(
        parent: LinearLayout,
        title: String
    ): LinearLayout {
        parent.addView(
            text(title, 17f, R.color.rtk_text).apply {
                typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
                setPadding(dp(4), dp(18), dp(4), dp(9))
            }
        )

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(getColor(R.color.rtk_surface))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), getColor(R.color.rtk_border))
            }

            parent.addView(
                this,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(12)
                }
            )
        }
    }

    private fun showContactsScreen() {
        contactsRoot = null
        searchResultBox = null
        contactsSearchInput = null
        searchButton = null
        searchStatusView = null
        toolbar.title = "Contacts"
        screenContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(24))
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
        screenContainer.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        root.addView(text("Find people", 24f, R.color.rtk_green))
        root.addView(text("Connect securely using a person's unique RTK PRIVATE ID.", 13f, R.color.rtk_muted))

        val searchCard = section(root, "Search by RTK ID")
        searchCard.addView(text(
            "Enter the complete ID shared by your contact. Example: RTK-0123456789ABCDEF",
            12f, R.color.rtk_muted
        ))
        val searchInput = EditText(this).apply {
            hint = "RTK-0123456789ABCDEF"
            isSingleLine = true
            textSize = 16f
            letterSpacing = 0.04f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setTextColor(getColor(R.color.rtk_text))
            setHintTextColor(getColor(R.color.rtk_muted))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(getColor(R.color.rtk_input))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), getColor(R.color.rtk_border))
            }
            setPadding(dp(15), dp(13), dp(15), dp(13))
        }
        contactsSearchInput = searchInput
        searchCard.addView(searchInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
        ).apply { topMargin = dp(10) })

        searchStatusView = text("Search checks the exact RTK ID you enter.", 12f, R.color.rtk_muted)
        searchCard.addView(searchStatusView)

        val search = button("Search RTK ID") {
            val entered = searchInput.text.toString().trim().uppercase()
            if (!Regex("^RTK-[0-9A-F]{16}$").matches(entered)) {
                searchStatusView?.text = "Invalid format. Enter RTK- followed by 16 hexadecimal characters (0–9, A–F)."
                searchStatusView?.setTextColor(getColor(R.color.rtk_muted))
                searchResultBox?.removeAllViews()
                searchResultBox?.addView(text("Check the ID and search again.", 13f, R.color.rtk_muted))
            } else {
                performSearch(entered)
            }
        }
        searchButton = search
        searchCard.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
        ).apply { topMargin = dp(10) })

        searchResultBox = section(root, "Search result")
        searchResultBox?.addView(text(
            "A matching public profile will appear here.", 13f, R.color.rtk_muted
        ))

        root.addView(text("Your contact requests and connections", 17f, R.color.rtk_text).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
            setPadding(dp(4), dp(12), dp(4), dp(8))
        })
        contactsRoot = root
        loadContacts()
    }

    private fun performSearch(id: String) {
        val rootAtRequest = contactsRoot ?: return
        val resultAtRequest = searchResultBox ?: return
        val statusAtRequest = searchStatusView

        searchButton?.isEnabled = false
        searchStatusView?.text = "Searching for $id…"
        searchStatusView?.setTextColor(getColor(R.color.rtk_muted))
        resultAtRequest.removeAllViews()
        resultAtRequest.addView(text("Looking up this RTK ID…", 13f, R.color.rtk_muted))

        executor.execute {
            try {
                val response = AuthApi.get(
                    "/api/v1/contacts/search?id=${Uri.encode(id)}",
                    token
                )
                val user = response.getJSONObject("user")
                runOnUiThread {
                    if (isFinishing || contactsRoot !== rootAtRequest ||
                        searchResultBox !== resultAtRequest) return@runOnUiThread
                    searchButton?.isEnabled = true
                    statusAtRequest?.text = "Account found"
                    statusAtRequest?.setTextColor(getColor(R.color.rtk_green))
                    showSearchResult(user)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || contactsRoot !== rootAtRequest ||
                        searchResultBox !== resultAtRequest) return@runOnUiThread
                    searchButton?.isEnabled = true
                    statusAtRequest?.text = e.message ?: "No matching account found."
                    statusAtRequest?.setTextColor(getColor(R.color.rtk_muted))
                    resultAtRequest.removeAllViews()
                    resultAtRequest.addView(text(
                        "No profile to display. Verify the RTK ID and try again.",
                        13f, R.color.rtk_muted
                    ))
                }
            }
        }
    }

    private fun showSearchResult(user: JSONObject) {
        val box = searchResultBox ?: return
        box.removeAllViews()

        val publicId = user.optString("user_id").trim().uppercase()
        val username = user.optString("username").trim().ifBlank { "RTK PRIVATE user" }
        val fingerprint = user.optString("fingerprint").trim()

        if (!Regex("^RTK-[0-9A-F]{16}$").matches(publicId)) {
            box.addView(text("The server returned an invalid RTK ID.", 13f, R.color.rtk_muted))
            return
        }

        box.addView(text(username, 19f, R.color.rtk_text).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        })
        box.addView(text("RTK ID  •  $publicId", 13f, R.color.rtk_green))
        box.addView(text(
            if (fingerprint.isBlank() || fingerprint == "null")
                "Identity fingerprint is not available."
            else "Identity fingerprint  •  $fingerprint",
            12f, R.color.rtk_muted
        ))
        box.addView(button("Add contact") {
            sendContactRequest(publicId)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
        ).apply { topMargin = dp(10) })
    }

    private fun sendContactRequest(publicId: String) {
        val rootAtRequest = contactsRoot ?: return
        val resultAtRequest = searchResultBox ?: return
        resultAtRequest.removeAllViews()
        resultAtRequest.addView(text("Sending contact request…", 13f, R.color.rtk_muted))

        executor.execute {
            try {
                AuthApi.post(
                    "/api/v1/contacts/request",
                    JSONObject().put("user_id", publicId),
                    token
                )
                runOnUiThread {
                    if (isFinishing || contactsRoot !== rootAtRequest ||
                        searchResultBox !== resultAtRequest) return@runOnUiThread
                    resultAtRequest.removeAllViews()
                    resultAtRequest.addView(text("Contact request sent", 17f, R.color.rtk_green))
                    resultAtRequest.addView(text(
                        "The person must accept your request before a private chat can start.",
                        13f, R.color.rtk_muted
                    ))
                    toast("Contact request sent")
                    loadContacts()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || contactsRoot !== rootAtRequest ||
                        searchResultBox !== resultAtRequest) return@runOnUiThread
                    resultAtRequest.removeAllViews()
                    resultAtRequest.addView(text(
                        e.message ?: "Could not send contact request.",
                        13f, R.color.rtk_muted
                    ))
                }
            }
        }
    }

    private fun loadContacts() {
        val root = contactsRoot ?: return

        executor.execute {
            try {
                val response = AuthApi.get(
                    "/api/v1/contacts",
                    token
                )

                val contacts = response.optJSONArray("contacts")
                    ?: JSONArray()

                runOnUiThread {
                    if (
                        bottomNavigation.selectedItemId ==
                        R.id.nav_contacts &&
                        contactsRoot === root
                    ) {
                        renderContactRows(root, contacts)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (contactsRoot === root) {
                        toast(e.message ?: "Could not load contacts")
                    }
                }
            }
        }
    }

    private fun renderContactRows(
        root: LinearLayout,
        contacts: JSONArray
    ) {
        // Remove old contact sections while preserving search UI.
        val oldStart = root.indexOfChild(
            root.findViewWithTag("contact_sections")
        )

        if (oldStart >= 0) {
            root.removeViews(
                oldStart,
                root.childCount - oldStart
            )
        }

        val marker = LinearLayout(this).apply {
            tag = "contact_sections"
            orientation = LinearLayout.VERTICAL
        }
        root.addView(marker)

        val incoming = mutableListOf<JSONObject>()
        val outgoing = mutableListOf<JSONObject>()
        val accepted = mutableListOf<JSONObject>()

        for (i in 0 until contacts.length()) {
            val item = contacts.optJSONObject(i) ?: continue
            when {
                item.optString("status") == "accepted" ->
                    accepted.add(item)

                item.optString("status") == "pending" &&
                    item.optString("direction") == "incoming" ->
                    incoming.add(item)

                item.optString("status") == "pending" &&
                    item.optString("direction") == "outgoing" ->
                    outgoing.add(item)
            }
        }

        val incomingBox = section(marker, "Incoming requests (${incoming.size})")

        if (incoming.isEmpty()) {
            incomingBox.addView(text("No incoming requests", 13f,
                R.color.rtk_muted))
        }

        incoming.forEach { item ->
            val user = item.optJSONObject("user") ?: JSONObject()
            val requestId = item.optInt("request_id")

            incomingBox.addView(
                text(
                    "${user.optString("username")}\n" +
                    "RTK ID: ${user.optString("user_id")}"
                )
            )

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            actions.addView(
                button("Accept") {
                    contactAction("accept", requestId)
                },
                LinearLayout.LayoutParams(0, dp(50), 1f)
            )

            actions.addView(
                button("Reject") {
                    contactAction("reject", requestId)
                },
                LinearLayout.LayoutParams(0, dp(50), 1f)
            )

            incomingBox.addView(actions)
        }

        val acceptedBox = section(marker, "My contacts (${accepted.size})")

        if (accepted.isEmpty()) {
            acceptedBox.addView(
                text("No accepted contacts yet", 13f, R.color.rtk_muted)
            )
        }

        accepted.forEach { item ->
            val user = item.optJSONObject("user") ?: JSONObject()
            acceptedBox.addView(
                text(
                    "${user.optString("username")}\n" +
                    "RTK ID: ${user.optString("user_id")}"
                )
            )
        }

        val outgoingBox = section(marker, "Sent requests (${outgoing.size})")

        if (outgoing.isEmpty()) {
            outgoingBox.addView(
                text("No pending sent requests", 13f, R.color.rtk_muted)
            )
        }

        outgoing.forEach { item ->
            val user = item.optJSONObject("user") ?: JSONObject()
            outgoingBox.addView(
                text(
                    "${user.optString("username")}\n" +
                    "RTK ID: ${user.optString("user_id")}\nPending"
                )
            )
        }

        marker.addView(
            button("Refresh contacts") {
                loadContacts()
            }
        )
    }

    private fun contactAction(action: String, requestId: Int) {
        executor.execute {
            try {
                AuthApi.post(
                    "/api/v1/contacts/$action",
                    JSONObject().put("request_id", requestId),
                    token
                )

                runOnUiThread {
                    toast(
                        if (action == "accept") {
                            "Contact request accepted"
                        } else {
                            "Contact request rejected"
                        }
                    )
                    loadContacts()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast(e.message ?: "Request failed")
                }
            }
        }
    }

    private fun showScreen(title: String, message: String) {
        contactsRoot = null
        searchResultBox = null
        contactsSearchInput = null
        searchButton = null
        searchStatusView = null
        toolbar.title = title
        screenContainer.removeAllViews()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(getColor(R.color.rtk_background))
        }

        container.addView(text(title, 26f, R.color.rtk_green))
        container.addView(
            text(message, 15f, R.color.rtk_muted)
        )

        screenContainer.addView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }


    private fun startQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan an RTK PRIVATE ID QR code")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }

        qrScanLauncher.launch(options)
    }

    private fun showProfileScreen() {
        contactsRoot = null
        searchResultBox = null
        contactsSearchInput = null

        toolbar.title = "My Profile"
        screenContainer.removeAllViews()

        var publicId = ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(getColor(R.color.rtk_background))
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        screenContainer.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(text("Your RTK Identity", 25f, R.color.rtk_green))

        val usernameView = text("Loading username...")
        val idView = text("Loading RTK ID...")
        val statusView = text("Fetching profile from server...",
            13f, R.color.rtk_muted)

        root.addView(usernameView)
        root.addView(idView)

        val qrImage = ImageView(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }

        root.addView(
            qrImage,
            LinearLayout.LayoutParams(dp(250), dp(250)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(14)
                bottomMargin = dp(8)
            }
        )

        root.addView(
            text("Show this QR code to let others find your account.",
                13f, R.color.rtk_muted)
        )

        root.addView(
            button("Copy RTK ID") {
                if (publicId.isBlank()) {
                    toast("RTK ID is not loaded yet")
                } else {
                    val clipboard =
                        getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("RTK PRIVATE ID", publicId)
                    )

                    toast("RTK ID copied")
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        root.addView(
            button("Share RTK ID") {
                if (publicId.isBlank()) {
                    toast("RTK ID is not loaded yet")
                } else {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "My RTK PRIVATE ID: $publicId"
                        )
                    }

                    startActivity(
                        Intent.createChooser(shareIntent, "Share RTK ID")
                    )
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        root.addView(
            button("Scan QR to Add Contact") {
                startQrScanner()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        root.addView(statusView)

        root.addView(
            button("Refresh Profile") {
                showProfileScreen()
            }
        )

        executor.execute {
            try {
                val response = AuthApi.get(
                    "/api/v1/auth/me",
                    token
                )

                val user = response.optJSONObject("user")
                    ?: throw IllegalStateException("Profile data missing")

                val id = user.optString("user_id").trim().uppercase()
                val username = user.optString("username")

                if (!Regex("^RTK-[0-9A-F]{16}$").matches(id)) {
                    throw IllegalStateException("Invalid RTK ID received")
                }

                val qrBitmap = IdentityQr.createContactQr(id)

                runOnUiThread {
                    if (
                        isFinishing ||
                        bottomNavigation.selectedItemId != R.id.nav_profile
                    ) return@runOnUiThread

                    publicId = id
                    usernameView.text = "Username: $username"
                    idView.text = "RTK ID: $id"
                    qrImage.setImageBitmap(qrBitmap)
                    statusView.text = "Profile loaded successfully"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (
                        !isFinishing &&
                        bottomNavigation.selectedItemId == R.id.nav_profile
                    ) {
                        statusView.text =
                            "Profile error: ${e.message ?: "Server unavailable"}"
                    }
                }
            }
        }
    }


    private fun showSupportScreen() {
        contactsRoot = null
        searchResultBox = null
        contactsSearchInput = null
        toolbar.title = "Support"
        screenContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
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

        screenContainer.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(26), dp(20), dp(26))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(13, 25, 20))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), android.graphics.Color.rgb(35, 105, 69))
            }
        }

        header.addView(text("RTK PRIVATE", 27f, R.color.rtk_green).apply {
            gravity = Gravity.CENTER
        })

        header.addView(text("PRIVACY. CONTROL. CONNECTION.", 11f, R.color.rtk_muted).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setPadding(0, dp(7), 0, dp(12))
        })

        header.addView(text("Your private communication space.", 15f, R.color.rtk_text).apply {
            gravity = Gravity.CENTER
        })

        header.addView(text(
            "Need help? Contact the RTK PRIVATE developer support team.",
            13f, R.color.rtk_muted
        ).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, dp(10), 0, 0)
        })

        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(20) })

        val features = section(root, "App features")

        features.addView(text("Private messaging", 15f, R.color.rtk_text))
        features.addView(text(
            "Message content is encrypted by the app's device-side cryptographic implementation.",
            12f, R.color.rtk_muted
        ))

        features.addView(text("RTK ID contact system", 15f, R.color.rtk_text).apply {
            setPadding(0, dp(13), 0, 0)
        })
        features.addView(text(
            "Find and connect with other users using their RTK ID.",
            12f, R.color.rtk_muted
        ))

        features.addView(text("QR contact sharing", 15f, R.color.rtk_text).apply {
            setPadding(0, dp(13), 0, 0)
        })
        features.addView(text(
            "Share your identity QR code or scan another user's QR code to add a contact.",
            12f, R.color.rtk_muted
        ))

        features.addView(text("Identity key protection", 15f, R.color.rtk_text).apply {
            setPadding(0, dp(13), 0, 0)
        })
        features.addView(text(
            "The app uses cryptographic identity keys and locally protected session storage.",
            12f, R.color.rtk_muted
        ))

        val recovery = section(root, "Important: account recovery")

        recovery.addView(text(
            "When creating your account, save both of these details:",
            13f, R.color.rtk_muted
        ))

        recovery.addView(text("1. Your RTK ID", 15f, R.color.rtk_text).apply {
            setPadding(0, dp(10), 0, dp(3))
        })
        recovery.addView(text("2. Your 12-word recovery phrase", 15f, R.color.rtk_text))
        recovery.addView(text(
            "Keep your recovery phrase somewhere private and secure. You may need it for the account recovery process.",
            12f, R.color.rtk_muted
        ).apply {
            setPadding(0, dp(7), 0, 0)
        })

        val warning = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(38, 17, 19))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), android.graphics.Color.rgb(155, 55, 60))
            }
        }

        warning.addView(text(
            "SECURITY REMINDER",
            14f,
            android.R.color.holo_red_light
        ))

        warning.addView(text(
            "Never share your password or 12-word recovery phrase with anyone, including the support team. Support will never need these secret details. Anyone who obtains your recovery credentials may put your account at risk.",
            13f,
            R.color.rtk_text
        ).apply {
            setPadding(0, dp(8), 0, 0)
        })

        root.addView(warning, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(16)
            bottomMargin = dp(20)
        })

        val contact = section(root, "Contact support")

        contact.addView(text(
            "For app-related questions or technical assistance, contact:",
            13f, R.color.rtk_muted
        ))

        contact.addView(text(
            "rtkprivatechat@gmail.com",
            15f, R.color.rtk_green
        ).apply {
            setPadding(0, dp(12), 0, dp(8))
            setOnClickListener { openSupportEmail() }
        })

        contact.addView(button("Email Support") {
            openSupportEmail()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50)
        ))

        root.addView(text(
            "Developed by RTK Development Team",
            13f, R.color.rtk_green
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(5))
        })

        root.addView(text(
            "RTK PRIVATE • Support",
            11f, R.color.rtk_muted
        ).apply {
            gravity = Gravity.CENTER
        })
    }

    private fun openSupportEmail() {
        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("mailto:rtkprivatechat@gmail.com")
        ).apply {
            putExtra(Intent.EXTRA_SUBJECT, "RTK PRIVATE Support")
        }

        try {
            startActivity(Intent.createChooser(intent, "Contact Support"))
        } catch (_: Exception) {
            toast("No email app found on this device")
        }
    }

    private fun showSettings() {
        contactsRoot = null
        searchResultBox = null
        contactsSearchInput = null
        toolbar.title = "Settings"
        screenContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(24))
            setBackgroundColor(getColor(R.color.rtk_background))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        screenContainer.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        root.addView(text("Your account", 23f, R.color.rtk_text))
        root.addView(text("Manage this device's active RTK PRIVATE session.", 13f, R.color.rtk_muted))

        val accountCard = section(root, "Account")
        accountCard.addView(text("Signed in on this device", 15f))
        accountCard.addView(text("Your RTK identity and current session are stored locally for this app.", 12f, R.color.rtk_muted))

        val securityCard = section(root, "Privacy & security")
        securityCard.addView(text("End-to-end encrypted conversations", 15f))
        securityCard.addView(text("Message encryption is performed on your device. Keep your recovery phrase private and secure.", 12f, R.color.rtk_muted))

        root.addView(text("Signing out clears the saved session from this device.", 12f, R.color.rtk_muted).apply {
            setPadding(dp(4), dp(10), dp(4), dp(10))
        })
        root.addView(button("Log out") { logout() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ))
    }

    private fun logout() {
        val oldToken = token

        if (oldToken.isNotBlank()) {
            executor.execute {
                try {
                    AuthApi.post(
                        "/api/v1/auth/logout",
                        JSONObject(),
                        oldToken
                    )
                } catch (_: Exception) {
                }
            }
        }

        SessionStore.clear(this)
        toast("Logged out on this device")
        openAuth()
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openAuth() {
        startActivity(Intent(this, AuthActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        contactsRoot = null
        executor.shutdown()
        super.onDestroy()
    }
}
