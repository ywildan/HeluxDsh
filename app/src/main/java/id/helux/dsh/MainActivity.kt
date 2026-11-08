package id.helux.dsh

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Single-activity shell: pair by QR once, then open the DSH Web UI directly.
 *
 * Flow:
 * 1. No pairing recorded -> pairing screen with a "Scan QR" button.
 * 2. Scan returns a validated launch URL -> load it once; DSH mints the cookie
 *    and redirects to the clean URL. The tokenized URL is dropped from history.
 * 3. Next start -> load the clean URL. A 401 means the cookie expired, so the
 *    app returns to pairing instead of showing DSH's raw error page.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var pairingPanel: FrameLayout
    private lateinit var statusPanel: FrameLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var statusAction: Button
    private lateinit var store: PairingStore

    /** Set while the one-time tokenized URL is in flight; cleared right after. */
    private var pendingLaunchUrl: String? = null

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val launchUrl = data.getStringExtra(ScanActivity.EXTRA_LAUNCH_URL) ?: return@registerForActivityResult
        openLaunchUrl(launchUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = PairingStore(this)
        webView = findViewById(R.id.webview)
        pairingPanel = findViewById(R.id.pairing_panel)
        statusPanel = findViewById(R.id.status_panel)
        statusTitle = findViewById(R.id.status_title)
        statusBody = findViewById(R.id.status_body)
        statusAction = findViewById(R.id.status_action)

        findViewById<Button>(R.id.scan_button).setOnClickListener { startScan() }
        statusAction.setOnClickListener { handleStatusAction() }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // No file, content, or mixed-content access: the UI is remote-only.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(false)
            mediaPlaybackRequiresUserGesture = true
        }
        webView.webViewClient = TunnelWebViewClient()

        if (store.isPaired()) showWebView() else showPairing()
    }

    /**
     * Launch the QR scanner.
     */
    private fun startScan() {
        scanLauncher.launch(Intent(this, ScanActivity::class.java))
    }

    /**
     * Load a validated launch URL exactly once, then forget it.
     * @param launchUrl URL produced by [LaunchUrlValidator].
     */
    private fun openLaunchUrl(launchUrl: String) {
        pendingLaunchUrl = launchUrl
        showWebView()
        webView.loadUrl(launchUrl)
    }

    /**
     * Show the pairing screen.
     */
    private fun showPairing() {
        webView.visibility = View.GONE
        statusPanel.visibility = View.GONE
        pairingPanel.visibility = View.VISIBLE
    }

    /**
     * Show the WebView.
     */
    private fun showWebView() {
        pairingPanel.visibility = View.GONE
        statusPanel.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    /**
     * Show a blocking message with one action button.
     * @param title headline.
     * @param body explanation.
     * @param action action button label.
     * @param actionKind which recovery the action performs.
     */
    private fun showStatus(title: String, body: String, action: String, actionKind: StatusAction) {
        currentStatusAction = actionKind
        webView.visibility = View.GONE
        pairingPanel.visibility = View.GONE
        statusPanel.visibility = View.VISIBLE
        statusTitle.text = title
        statusBody.text = body
        statusAction.text = action
    }

    private var currentStatusAction: StatusAction = StatusAction.RETRY

    /** What the status panel's single button does. */
    private enum class StatusAction { RETRY, REPAIR }

    /**
     * Run the recovery chosen for the visible status panel.
     */
    private fun handleStatusAction() {
        when (currentStatusAction) {
            StatusAction.RETRY -> {
                showWebView()
                webView.loadUrl(HeluxConfig.HOME_URL)
            }
            StatusAction.REPAIR -> {
                store.clear()
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                showPairing()
            }
        }
    }

    /**
     * WebView client that keeps navigation inside this hostname and turns the
     * DSH authentication fence into a pairing prompt.
     */
    private inner class TunnelWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            // Any off-host navigation opens in the system browser instead.
            return if (!url.host.equals(HeluxConfig.HOST, ignoreCase = true)) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (_: Exception) {
                    // No browser installed; keep the user where they are.
                }
                true
            } else {
                false
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            // Once DSH has redirected away from the tokenized URL, drop it.
            val pending = pendingLaunchUrl
            if (pending != null && url != null && !url.contains("${HeluxConfig.TOKEN_PARAM}=")) {
                pendingLaunchUrl = null
                view.clearHistory()
                store.markPaired()
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            // The cookie is minted by the redirect; persist it for the next start.
            CookieManager.getInstance().flush()
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            if (!request.isForMainFrame) return
            when (errorResponse.statusCode) {
                401, 403 -> {
                    // DSH's own fence: the cookie is gone or was never minted.
                    store.clear()
                    pendingLaunchUrl = null
                    showStatus(
                        getString(R.string.status_auth_title),
                        getString(R.string.status_auth_body),
                        getString(R.string.status_repair_action),
                        StatusAction.REPAIR,
                    )
                }
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError,
        ) {
            super.onReceivedError(view, request, error)
            if (!request.isForMainFrame) return
            showStatus(
                getString(R.string.status_offline_title),
                getString(R.string.status_offline_body),
                getString(R.string.status_retry_action),
                StatusAction.RETRY,
            )
        }
    }

    /**
     * Keep navigation inside the WebView for same-host links and handle the
     * hardware back button.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
