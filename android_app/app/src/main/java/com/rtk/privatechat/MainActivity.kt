package com.rtk.privatechat

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
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

    private val token: String
        get() = SessionStore.getToken(this).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (token.isBlank()) {
            openAuth()
            return
        }

        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        screenContainer = findViewById(R.id.screen_container)
        bottomNavigation = findViewById(R.id.bottom_navigation)

        toolbar.title = "RTK PRIVATE"

        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> {
                    startActivity(Intent(this, ChatActivity::class.java))
                    true
                }

                R.id.nav_contacts -> {
                    showContactsScreen()
                    true
                }

                R.id.nav_support -> {
                    showScreen(
                        "Support",
                        "RTK PRIVATE support."
                    )
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

        if (savedInstanceState == null) {
            bottomNavigation.selectedItemId = R.id.nav_chats
        }
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
        setPadding(dp(4), dp(7), dp(4), dp(7))
    }

    private fun button(
        label: String,
        action: () -> Unit
    ) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun section(
        parent: LinearLayout,
        title: String
    ): LinearLayout {
        parent.addView(
            text(title, 19f, R.color.rtk_green).apply {
                setPadding(dp(4), dp(20), dp(4), dp(8))
            }
        )

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(getColor(R.color.rtk_surface))

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
        toolbar.title = "Contacts"
        screenContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
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

        root.addView(text("Find people", 24f, R.color.rtk_green))
        root.addView(
            text("Search using their RTK PRIVATE ID.")
        )

        val searchInput = EditText(this).apply {
            hint = "Enter RTK ID"
            isSingleLine = true
            setTextColor(getColor(R.color.rtk_text))
            setHintTextColor(getColor(R.color.rtk_muted))
        }

        contactsSearchInput = searchInput

        root.addView(
            searchInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(55)
            )
        )

        val search = button("Search RTK ID") {
            val id = searchInput.text.toString().trim().uppercase()

            if (id.isBlank()) {
                toast("Enter an RTK ID first")
            } else {
                searchButton?.isEnabled = false
                performSearch(id)
            }
        }

        searchButton = search
        root.addView(
            search,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        searchResultBox = section(root, "Search result")

        contactsRoot = root

        root.addView(
            text("Your contact requests and connections", 14f,
                R.color.rtk_muted)
        )

        loadContacts()
    }

    private fun performSearch(id: String) {
        executor.execute {
            try {
                val response = AuthApi.get(
                    "/api/v1/contacts/search?id=${Uri.encode(id)}",
                    token
                )

                val user = response.getJSONObject("user")

                runOnUiThread {
                    searchButton?.isEnabled = true
                    showSearchResult(user)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    searchButton?.isEnabled = true
                    searchResultBox?.removeAllViews()
                    searchResultBox?.addView(
                        text(e.message ?: "Search failed")
                    )
                }
            }
        }
    }

    private fun showSearchResult(user: JSONObject) {
        val box = searchResultBox ?: return
        box.removeAllViews()

        val publicId = user.optString("user_id")
        val username = user.optString("username")
        val fingerprint = user.optString("fingerprint")

        box.addView(text("Username: $username", 17f))
        box.addView(text("RTK ID: $publicId"))
        box.addView(
            text(
                if (fingerprint.isBlank() || fingerprint == "null") {
                    "Identity fingerprint: Not registered"
                } else {
                    "Fingerprint: $fingerprint"
                },
                12f,
                R.color.rtk_muted
            )
        )

        box.addView(
            button("Add Contact") {
                sendContactRequest(publicId)
            }
        )
    }

    private fun sendContactRequest(publicId: String) {
        executor.execute {
            try {
                val body = JSONObject().put("user_id", publicId)

                AuthApi.post(
                    "/api/v1/contacts/request",
                    body,
                    token
                )

                runOnUiThread {
                    toast("Contact request sent")
                    loadContacts()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast(e.message ?: "Could not send request")
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
        toolbar.title = title
        screenContainer.removeAllViews()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
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

    private fun showSettings() {
        showScreen(
            "Settings",
            "Your account and security settings."
        )

        val container = screenContainer.getChildAt(0) as? LinearLayout
            ?: return

        container.addView(
            button("Logout") {
                logout()
            }
        )
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
