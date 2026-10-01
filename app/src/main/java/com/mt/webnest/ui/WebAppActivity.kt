package com.mt.webnest.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.mt.webnest.data.AppDatabase
import com.mt.webnest.data.WebApp
import com.mt.webnest.notification.WebAppNotifications
import com.mt.webnest.web.*
import kotlinx.coroutines.launch
import java.util.WeakHashMap

class WebAppActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var webView: WebView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private val popups = mutableListOf<WebView>()
    private val gestures = WeakHashMap<WebView, Long>()
    private var fullScreenView: View? = null
    private var fullScreenCallback: WebChromeClient.CustomViewCallback? = null
    private var app: WebApp? = null
    private var appId = 0L
    private var mainFrameFailed = false
    private var historyResetPending = false
    private val permissions = SitePermissions(this) { appId }
    private val uploads = FileUploads(this)
    private val downloads = WebDownloads(this)
    private val current: WebView get() = popups.lastOrNull() ?: webView
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) app?.let { WebAppNotifications.show(this, it)}}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appId = intent.data?.lastPathSegment?.toLongOrNull() ?: 0L
        if (appId <= 0) { finish(); return }
        buildContent()
        WebAppNotifications.register(appId, this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullScreenView != null -> exitFullScreen()
                    current.canGoBack() -> current.goBack()
                    popups.isNotEmpty() -> closePopup(popups.last())
                    else -> closeWebApp()
                }
            }
        })
        lifecycleScope.launch {
            try {
                val record = AppDatabase.get(this@WebAppActivity).find(appId)
                if (record == null) { closeWebApp(); return@launch }
                app = record
                title = record.name
                @Suppress("DEPRECATION")
                setTaskDescription(android.app.ActivityManager.TaskDescription(record.name))
                applySettings(webView, record)
                val state = savedInstanceState?.getBundle("web_state")
                if (state == null || webView.restoreState(state) == null) webView.loadUrl(record.url)
                consumeCommand()
                AppDatabase.get(this@WebAppActivity).markOpened(appId)
                showControls()
            } catch (_: Exception) { showError("Couldn't open this Web App.")}
        }
    }

    private fun buildContent() {
        root = FrameLayout(this)
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val background = if (dark) Color.rgb(28, 28, 30) else Color.WHITE
        root.setBackgroundColor(background)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        loadingProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        root.addView(loadingProgress, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))
        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(32), dp(24), dp(32), dp(24)); setBackgroundColor(background)
            visibility = View.GONE
        }
        errorText = TextView(this).apply { textSize = 18f; setTextColor(if (dark) Color.WHITE else Color.DKGRAY); gravity = Gravity.CENTER }
        errorPanel.addView(errorText)
        errorPanel.addView(Button(this).apply { text = "Retry"; setOnClickListener { current.reload() } })
        errorPanel.addView(Button(this).apply { text = "Close"; setOnClickListener { if (popups.isNotEmpty()) closePopup(popups.last()) else closeWebApp() } })
        root.addView(errorPanel, FrameLayout.LayoutParams(-1, -1))
        webView = createWebView()
        content.addView(webView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        val owner = this
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            safeBrowsingEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        app?.let { applySettings(this, it) }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (request.hasGesture()) gestures[view] = SystemClock.elapsedRealtime()
                val recentGesture = SystemClock.elapsedRealtime() - (gestures[view] ?: -100000L) < 10000
                val url = request.url.toString()
                return handleLink(view, url, request.hasGesture() || recentGesture)
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                permissions.cancelFor(view); uploads.cancelFor(view)
                if (view === current) mainFrameFailed = false; errorPanel.visibility = View.GONE; loadingProgress.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (app?.desktopMode == true) view.evaluateJavascript("""(function(){let m=document.querySelector('meta[name="viewport"]');if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}m.content='width=1024';})()""", null)
                if (view === current) loadingProgress.visibility = View.GONE
                if (view === webView && historyResetPending) { webView.clearHistory(); historyResetPending = false }
                CookieManager.getInstance().flush()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame && view === current) showError("Couldn't load this page.")}
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (view === current) showError("Couldn't verify this site's secure connection.")
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view !== current) return
                loadingProgress.progress = newProgress
                loadingProgress.visibility = if (newProgress == 100 || mainFrameFailed) View.GONE else View.VISIBLE
            }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture || view !== current || popups.size >= 4) return false
                permissions.cancel(); uploads.cancel()
                view.visibility = View.GONE; view.onPause()
                val child = createWebView()
                popups.add(child)
                content.addView(child, FrameLayout.LayoutParams(-1, -1))
                (resultMsg.obj as WebView.WebViewTransport).webView = child
                resultMsg.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) { closePopup(window)}
            override fun onPermissionRequest(request: PermissionRequest) { if (owner === current) permissions.media(owner, request) else request.deny()}
            override fun onPermissionRequestCanceled(request: PermissionRequest) { permissions.canceled(request)}
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { if (owner === current) permissions.location(owner, origin, callback) else callback.invoke(origin, false, false) }
            override fun onGeolocationPermissionsHidePrompt() { permissions.cancelFor(owner)}
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                if (view !== current) { callback.onReceiveValue(null); return true }
                return uploads.choose(view, callback, params)
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullScreenView != null || owner !== current) { callback.onCustomViewHidden(); return }
                fullScreenView = view; fullScreenCallback = callback
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                WindowCompat.getInsetsController(window, root).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            override fun onHideCustomView() { exitFullScreen()}
        }
        setDownloadListener { url, agent, disposition, mime, _ -> if (owner === current) downloads.download(url, agent, disposition, mime, owner.url)}
    }
    private fun applySettings(view: WebView, record: WebApp) {
        view.settings.userAgentString = WebSettingsPolicy.userAgent(WebSettings.getDefaultUserAgent(this), record)
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, record.thirdPartyCookies)
    }
    private fun closePopup(view: WebView) {
        if (!popups.contains(view)) return
        exitFullScreen()
        permissions.cancelFor(view); uploads.cancelFor(view)
        popups.remove(view); content.removeView(view); view.stopLoading(); view.destroy()
        current.visibility = View.VISIBLE; current.onResume()
        errorPanel.visibility = View.GONE
        loadingProgress.visibility = View.GONE
    }
    private fun exitFullScreen() {
        fullScreenView?.let { root.removeView(it) }
        fullScreenView = null
        val callback = fullScreenCallback; fullScreenCallback = null
        callback?.onCustomViewHidden()
        WindowCompat.getInsetsController(window, root).show(WindowInsetsCompat.Type.systemBars())
    }
    private fun handleLink(view: WebView, url: String, userGesture: Boolean): Boolean {
        if (WebUrls.normalize(url) != null && (url.startsWith("https:", true) || url.startsWith("http:", true))) return false
        val scheme = Uri.parse(url).scheme?.lowercase() ?: return true
        if (!userGesture || scheme in setOf("javascript", "file", "content", "data", "about", "blob", "webnest")) return true
        try {
            val external = if (scheme == "intent") Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            val fallback = external.getStringExtra("browser_fallback_url")
            val target = external.data ?: return true
            if (target.scheme?.lowercase() in setOf("file", "content", "javascript", "data", "webnest", "intent")) return true
            val safe = Intent(Intent.ACTION_VIEW, target).addCategory(Intent.CATEGORY_BROWSABLE)
            safe.`package` = external.`package`
            try { startActivity(safe) }
            catch (_: ActivityNotFoundException) {
                val validFallback = fallback?.let(WebUrls::normalize)
                if (validFallback != null) view.loadUrl(validFallback)
                else Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) { Toast.makeText(this, "Couldn't open this link", Toast.LENGTH_SHORT).show() }
        return true
    }
    private fun showError(message: String) {
        mainFrameFailed = true; loadingProgress.visibility = View.GONE
        errorText.text = message; errorPanel.visibility = View.VISIBLE
    }
    private fun showControls() {
        val record = app ?: return
        WebAppNotifications.show(this, record)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val preferences = getSharedPreferences("webnest", MODE_PRIVATE)
            if (!preferences.getBoolean("asked_notifications", false)) {
                preferences.edit().putBoolean("asked_notifications", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    private fun consumeCommand() {
        if (app == null) return
        val command = intent.getStringExtra(COMMAND)
        intent.removeExtra(COMMAND)
        if (command == "reload") current.reload()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); consumeCommand()
        app?.let { WebAppNotifications.show(this, it) }
    }
    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) current.onResume()
        if (app != null) lifecycleScope.launch {
            val updated = runCatching { AppDatabase.get(this@WebAppActivity).find(appId) }.getOrElse { return@launch }
            if (updated == null) closeWebApp()
            else {
                val previous = app!!
                app = updated; title = updated.name
                (listOf(webView) + popups).forEach { applySettings(it, updated) }
                if (updated.url != previous.url) {
                    popups.toList().forEach(::closePopup)
                    historyResetPending = true; webView.loadUrl(updated.url)
                } else if (updated.desktopMode != previous.desktopMode || updated.userAgent != previous.userAgent || updated.thirdPartyCookies != previous.thirdPartyCookies) current.reload()
                showControls()
            }
        }
    }
    override fun onPause() { if (::webView.isInitialized) current.onPause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) outState.putBundle("web_state", Bundle().also { webView.saveState(it) })
        super.onSaveInstanceState(outState)
    }
    fun closeWebApp() {
        WebAppNotifications.cancel(this, appId)
        if (::webView.isInitialized) (listOf(webView) + popups).forEach { it.stopLoading() }
        finishAndRemoveTask()
    }
    override fun onDestroy() {
        permissions.cancel(); uploads.cancel()
        WebAppNotifications.unregister(appId, this)
        if (isFinishing) WebAppNotifications.cancel(this, appId)
        if (::webView.isInitialized) {
            exitFullScreen()
            (popups.toList() + webView).forEach { content.removeView(it); it.destroy() }
            popups.clear()
        }
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        const val COMMAND = "webnest_command"
        fun intent(context: Context, id: Long) = Intent(context, WebAppActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("webnest://app/$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}