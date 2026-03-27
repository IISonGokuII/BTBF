package com.btbf.app

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import com.btbf.app.site.SitePlugin
import com.btbf.app.site.SitePluginRegistry
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.btbf.app.databinding.ActivityMainBinding
import com.btbf.app.databinding.DialogFavoritesBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isFullScreen = false
    private var isNavVisible = false
    private var isCategoryVisible = false
    private var isSplashVisible = true

    /** Aktuelle Quelle (WebView-UA, optionale JS-Hooks). */
    private var activeSitePlugin: SitePlugin? = null
    private var websiteUrl = ""
    private var currentSiteDomain = ""
    private val storagePermissionCode = 100
    private lateinit var favoritesManager: FavoritesManager
    private lateinit var videoDownloadHelper: VideoDownloadHelper
    private lateinit var gestureDetector: GestureDetector
    private val handler = Handler(Looper.getMainLooper())
    private val trackedDownloadIds = mutableSetOf<Long>()
    private var autoPlayRunnable: Runnable? = null

    /** Fire-TV: virtueller Mauszeiger statt nur Fokus-Scroll */
    private var pointerMode = false
    private var pointerX = 0f
    private var pointerY = 0f

    private val mainPrefs by lazy { getSharedPreferences("btbf_main", Context.MODE_PRIVATE) }
    private var siteGridColumns = 1
    /** 0 Standard 100%, 1–4 kleiner bis 65% — WebView-Seite wirkt kleiner (mehr auf einmal). */
    private var webViewDensityMode = 0

    // Video-URL Tracking (direkte + Stream URLs)
    private val capturedDirectUrls = mutableListOf<String>()
    private val capturedStreamUrls = mutableListOf<String>()
    private var downloadReceiver: BroadcastReceiver? = null

    // Auto-Hide Timer
    private val hideNavRunnable = Runnable { hideNavBar() }
    private val hideCategoryRunnable = Runnable { hideCategoryBar() }
    private val NAV_AUTO_HIDE_MS = 8000L
    private val CATEGORY_AUTO_HIDE_MS = 5000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        favoritesManager = FavoritesManager(this)
        videoDownloadHelper = VideoDownloadHelper(this)
        gestureDetector = GestureDetector(this, GestureListener())

        hideSystemUI()
        setupSiteSelector()
        setupButtons()
        registerDownloadReceiver()
        requestPermissions()

        // Fallback: Wenn BrowseActivity eine URL im WebView öffnen will
        intent.getStringExtra("fallback_url")?.let { url ->
            websiteUrl = url
            currentSiteDomain = ""
            binding.siteSelector.visibility = View.GONE
            binding.loadingIndicator.visibility = View.GONE
            isSplashVisible = false
            binding.splashOverlay.visibility = View.GONE
            setupWebView()
            webView.loadUrl(url)
        }
    }

    // ==================== SITE SELECTOR ====================

    private fun setupSiteSelector() {
        loadSiteGridPref()
        loadWebViewDensityPref()
        setupSiteLayoutModeButtons()
        applySitePickerLayout()
        updateSiteLayoutButtonSelection()
        updateWebDensityButtonSelection()
    }

    private fun loadSiteGridPref() {
        siteGridColumns = mainPrefs.getInt(PREF_SITE_GRID_COLUMNS, 1).coerceIn(1, 3)
    }

    private fun setupSiteLayoutModeButtons() {
        binding.btnSiteLayoutList.setOnClickListener { setSiteGridColumns(1) }
        binding.btnSiteLayout2.setOnClickListener { setSiteGridColumns(2) }
        binding.btnSiteLayout3.setOnClickListener { setSiteGridColumns(3) }
    }

    private fun setSiteGridColumns(cols: Int) {
        siteGridColumns = cols.coerceIn(1, 3)
        mainPrefs.edit().putInt(PREF_SITE_GRID_COLUMNS, siteGridColumns).apply()
        applySitePickerLayout()
        updateSiteLayoutButtonSelection()
    }

    private fun updateSiteLayoutButtonSelection() {
        val accent = ContextCompat.getColor(this, R.color.colorPrimary)
        val normal = ContextCompat.getColor(this, R.color.chip_stroke)
        listOf(binding.btnSiteLayoutList, binding.btnSiteLayout2, binding.btnSiteLayout3).forEach {
            it.strokeColor = ColorStateList.valueOf(normal)
        }
        when (siteGridColumns) {
            1 -> binding.btnSiteLayoutList.strokeColor = ColorStateList.valueOf(accent)
            2 -> binding.btnSiteLayout2.strokeColor = ColorStateList.valueOf(accent)
            else -> binding.btnSiteLayout3.strokeColor = ColorStateList.valueOf(accent)
        }
    }

    private fun applySitePickerLayout() {
        val siteItems = SitePluginRegistry.plugins.map { p ->
            SitePickerItem(p.displayName, p.baseUrl, getString(p.subtitleRes))
        }
        binding.rvSitePicker.layoutManager = if (siteGridColumns <= 1) {
            LinearLayoutManager(this)
        } else {
            GridLayoutManager(this, siteGridColumns)
        }
        binding.rvSitePicker.setHasFixedSize(true)
        binding.rvSitePicker.adapter = SitePickerAdapter(siteItems, siteGridColumns) { openSiteInWebView(it) }
        binding.rvSitePicker.post {
            binding.rvSitePicker.getChildAt(0)?.requestFocus()
        }
    }

    /** Native BrowseActivity entfällt: gewählte Site direkt in der WebView laden. */
    private fun openSiteInWebView(item: SitePickerItem) {
        val plugin = SitePluginRegistry.byBaseUrl(item.url) ?: return
        activeSitePlugin = plugin
        websiteUrl = plugin.baseUrl
        currentSiteDomain = plugin.domainMatch
        binding.loadingSiteName.text = plugin.displayName
        binding.siteSelector.visibility = View.GONE
        binding.loadingIndicator.visibility = View.VISIBLE
        if (::webView.isInitialized) {
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true
            applyWebViewPluginUserAgent()
            webView.stopLoading()
            webView.loadUrl(plugin.baseUrl)
        } else {
            setupWebView()
        }
    }

    // ==================== POINTER MODE (Fire TV „Maus“) ====================

    private fun togglePointerModeFromToolbar() {
        if (pointerMode) disablePointerMode()
        else enablePointerMode()
    }

    private fun enablePointerMode() {
        if (!::webView.isInitialized || isFullScreen || isSplashVisible) return
        pointerMode = true
        binding.virtualCursor.visibility = View.VISIBLE
        webView.clearFocus()
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        binding.webViewContainer.isFocusable = true
        binding.webViewContainer.isFocusableInTouchMode = true
        binding.webViewContainer.requestFocus()
        webView.post {
            val w = webView.width.toFloat()
            val h = webView.height.toFloat()
            if (w > 0 && h > 0) {
                pointerX = w / 2f
                pointerY = h / 2f
                syncCursorPosition()
            }
        }
        Toast.makeText(this, R.string.mouse_mode_on, Toast.LENGTH_LONG).show()
    }

    private fun disablePointerMode() {
        pointerMode = false
        binding.virtualCursor.visibility = View.GONE
        if (::webView.isInitialized) {
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true
        }
    }

    private fun movePointer(dx: Float, dy: Float) {
        if (!::webView.isInitialized) return
        val w = webView.width.toFloat()
        val h = webView.height.toFloat()
        if (w <= 0 || h <= 0) return
        pointerX = (pointerX + dx).coerceIn(0f, w - 1f)
        pointerY = (pointerY + dy).coerceIn(0f, h - 1f)
        syncCursorPosition()
    }

    private fun syncCursorPosition() {
        val cw = binding.virtualCursor.width.takeIf { it > 0 }
            ?: (36f * resources.displayMetrics.density).toInt()
        val half = cw / 2f
        binding.virtualCursor.translationX = pointerX - half
        binding.virtualCursor.translationY = pointerY - half
    }

    @Suppress("DEPRECATION")
    private fun injectPointerClick() {
        if (!::webView.isInitialized) return
        val downTime = SystemClock.uptimeMillis()
        val metaState = 0
        val pressure = 1f
        val size = 1f
        val precision = 1f
        val deviceId = 0
        val edgeFlags = 0
        MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN,
            pointerX, pointerY, pressure, size, metaState, precision, precision, deviceId, edgeFlags
        ).apply {
            webView.dispatchTouchEvent(this)
            recycle()
        }
        MotionEvent.obtain(
            downTime, downTime + 60, MotionEvent.ACTION_UP,
            pointerX, pointerY, pressure, size, metaState, precision, precision, deviceId, edgeFlags
        ).apply {
            webView.dispatchTouchEvent(this)
            recycle()
        }
    }

    // ==================== PERMISSIONS ====================

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_VIDEO)
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), storagePermissionCode)
        }
    }

    // ==================== WEBVIEW ====================

    /** Lädt eine beliebige http(s)-URL und setzt [currentSiteDomain] passend (z. B. Favoriten). */
    private fun loadUrlInWebView(url: String) {
        val uri = Uri.parse(url)
        val host = uri.host ?: return
        val scheme = uri.scheme ?: "https"
        activeSitePlugin = SitePluginRegistry.byHost(host)
            ?: SitePluginRegistry.genericFallback(host, "$scheme://$host/")
        currentSiteDomain = activeSitePlugin!!.domainMatch
        websiteUrl = "$scheme://$host/"
        binding.siteSelector.visibility = View.GONE
        binding.splashOverlay.visibility = View.GONE
        isSplashVisible = false
        binding.loadingIndicator.visibility = View.GONE
        binding.bottomNavStack.visibility = View.GONE
        binding.topBarContainer.visibility = View.GONE
        if (::webView.isInitialized) {
            applyWebViewPluginUserAgent()
            webView.stopLoading()
            webView.loadUrl(url)
        } else {
            setupWebView(initialLoadUrl = url)
        }
    }

    private fun applyWebViewPluginUserAgent() {
        if (!::webView.isInitialized) return
        val base = WebSettings.getDefaultUserAgent(this).replace("; wv", "")
        webView.settings.userAgentString = activeSitePlugin?.webViewUserAgent(base) ?: base
    }

    private fun setupWebView(initialLoadUrl: String? = null) {
        webView = binding.webView

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            databaseEnabled = true
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
        }
        applyWebViewPluginUserAgent()
        webView.settings.textZoom = zoomParamsForDensityMode(webViewDensityMode).second

        // Hardware-Beschleunigung fuer bessere Scroll-Performance
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (!url.contains(currentSiteDomain) && !url.startsWith("javascript:")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); return true }
                    catch (_: Exception) { }
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // Direkte Video-URLs abfangen
                if (videoDownloadHelper.isDirectVideoUrl(url)) {
                    synchronized(capturedDirectUrls) {
                        if (!capturedDirectUrls.contains(url)) capturedDirectUrls.add(url)
                        while (capturedDirectUrls.size > MAX_CAPTURED_URLS) capturedDirectUrls.removeAt(0)
                    }
                }

                // HLS/DASH Stream-URLs abfangen
                if (videoDownloadHelper.isStreamUrl(url)) {
                    synchronized(capturedStreamUrls) {
                        if (!capturedStreamUrls.contains(url)) capturedStreamUrls.add(url)
                        while (capturedStreamUrls.size > MAX_CAPTURED_URLS) capturedStreamUrls.removeAt(0)
                    }
                }

                if (isAdUrl(url)) return WebResourceResponse("text/plain", "UTF-8", null)
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
                synchronized(capturedDirectUrls) { capturedDirectUrls.clear() }
                synchronized(capturedStreamUrls) { capturedStreamUrls.clear() }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                binding.loadingIndicator.visibility = View.GONE

                // Site-Picker sichtbar (z. B. nach „Start“): kein JS injizieren / kein Autoplay — sonst
                // feuert Logik auf about:blank und der Zurück-Stapel kann die letzte Seite wieder laden.
                if (binding.siteSelector.visibility == View.VISIBLE) {
                    if (url == "about:blank" && view != null) {
                        view.clearHistory()
                    }
                    // Kein dismissSplash(): Quellenwahl liegt im splashOverlay — sonst wuerde die UI verschwinden.
                    return
                }

                injectComfortScripts()
                injectVideoClickInterceptor()
                autoPlayVideo()
                applyWebViewPageZoom()

                url?.let { pageUrl ->
                    try {
                        val h = Uri.parse(pageUrl).host
                        if (h != null && activeSitePlugin != null) {
                            val plug = SitePluginRegistry.byHost(h)
                            if (plug?.id == activeSitePlugin?.id) {
                                activeSitePlugin?.onPageReadyInjectJs(webView, pageUrl)?.let { js ->
                                    webView.evaluateJavascript(js, null)
                                }
                            }
                        }
                    } catch (_: Exception) { }
                }

                webView.post {
                    if (!isFullScreen && !pointerMode && binding.siteSelector.visibility != View.VISIBLE) {
                        webView.isFocusable = true
                        webView.isFocusableInTouchMode = true
                        webView.requestFocus()
                    }
                }

                if (isSplashVisible) {
                    handler.postDelayed({ dismissSplash() }, 800)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                customViewCallback = callback
                isFullScreen = true
                hideSystemUI()
                hideNavBar()
                hideCategoryBar()
                binding.fullscreenContainer.apply { visibility = View.VISIBLE; addView(view) }
                binding.webViewContainer.visibility = View.GONE
            }

            override fun onHideCustomView() {
                customView?.let { binding.fullscreenContainer.removeView(it); customView = null }
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                isFullScreen = false
                disablePointerMode()
                showSystemUI()
                binding.fullscreenContainer.visibility = View.GONE
                binding.webViewContainer.visibility = View.VISIBLE
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val id = videoDownloadHelper.downloadDirect(url, webView.url ?: websiteUrl, userAgent, contentDisposition, mimetype)
            trackedDownloadIds.add(id)
            Toast.makeText(this, "Download gestartet!", Toast.LENGTH_SHORT).show()
        }

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidInterface")
        webView.loadUrl(initialLoadUrl ?: websiteUrl)
    }

    // ==================== SPLASH SCREEN ====================

    private fun dismissSplash() {
        if (!isSplashVisible) return
        isSplashVisible = false

        binding.splashOverlay.animate()
            .alpha(0f)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                binding.splashOverlay.visibility = View.GONE
                if (::webView.isInitialized) {
                    webView.isFocusable = true
                    webView.isFocusableInTouchMode = true
                    webView.post { webView.requestFocus() }
                }
            }
            .start()

        showHelpOverlay()
    }

    private fun showHelpOverlay() {
        binding.helpOverlay.alpha = 0f
        binding.helpOverlay.visibility = View.VISIBLE
        binding.helpOverlay.animate().alpha(1f).setDuration(300).start()

        handler.postDelayed({
            binding.helpOverlay.animate()
                .alpha(0f)
                .setDuration(500)
                .withEndAction { binding.helpOverlay.visibility = View.GONE }
                .start()
        }, 4000)
    }

    // ==================== NAV BAR ====================

    private fun showNavBar() {
        if (isFullScreen || isSplashVisible) return
        if (!isNavVisible) {
            isNavVisible = true
            binding.bottomNavStack.visibility = View.VISIBLE
            binding.bottomNavStack.alpha = 0f
            binding.bottomNavStack.translationY = 60f
            binding.bottomNavStack.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    binding.bottomNavStack.post { binding.btnHome.requestFocus() }
                }
                .start()
        } else {
            binding.bottomNavStack.post { binding.btnHome.requestFocus() }
        }
        // Auto-Hide Timer zuruecksetzen
        handler.removeCallbacks(hideNavRunnable)
        handler.postDelayed(hideNavRunnable, NAV_AUTO_HIDE_MS)
    }

    private fun hideNavBar() {
        if (!isNavVisible) return
        isNavVisible = false
        handler.removeCallbacks(hideNavRunnable)
        binding.bottomNavStack.animate()
            .alpha(0f)
            .translationY(60f)
            .setDuration(200)
            .withEndAction { binding.bottomNavStack.visibility = View.GONE }
            .start()
    }

    private fun toggleNavBar() {
        if (isNavVisible) hideNavBar() else showNavBar()
    }

    // ==================== CATEGORY BAR ====================

    private fun showCategoryBar() {
        if (isFullScreen || isSplashVisible) return
        if (!isCategoryVisible) {
            isCategoryVisible = true
            binding.topBarContainer.visibility = View.VISIBLE
            binding.topBarContainer.alpha = 0f
            binding.topBarContainer.translationY = -60f
            binding.topBarContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        handler.removeCallbacks(hideCategoryRunnable)
        handler.postDelayed(hideCategoryRunnable, CATEGORY_AUTO_HIDE_MS)
    }

    private fun hideCategoryBar() {
        if (!isCategoryVisible) return
        isCategoryVisible = false
        handler.removeCallbacks(hideCategoryRunnable)
        binding.topBarContainer.animate()
            .alpha(0f)
            .translationY(-60f)
            .setDuration(200)
            .withEndAction { binding.topBarContainer.visibility = View.GONE }
            .start()
    }

    // ==================== BUTTONS ====================

    private fun setupButtons() {
        binding.btnHome.setOnClickListener { hideNavBar(); showSiteSelectorAgain() }
        binding.btnRefresh.setOnClickListener { webView.reload(); hideNavBar() }
        binding.btnDownload.setOnClickListener { findAndDownloadVideo() }
        binding.btnPlay.setOnClickListener { manualPlayVideo(); hideNavBar() }
        binding.btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        binding.btnFavorites.setOnClickListener { showFavoritesDialog() }
        binding.btnPointer.setOnClickListener {
            hideNavBar()
            togglePointerModeFromToolbar()
        }

        binding.btnCatHome.setOnClickListener { webView.loadUrl(websiteUrl); hideCategoryBar() }
        binding.btnCatNew.setOnClickListener { webView.loadUrl("${websiteUrl}new"); hideCategoryBar() }
        binding.btnCatTop.setOnClickListener { webView.loadUrl("${websiteUrl}top"); hideCategoryBar() }
        binding.btnCatRandom.setOnClickListener { webView.loadUrl("${websiteUrl}random"); hideCategoryBar() }

        setupWebDensityButtons()
    }

    private fun loadWebViewDensityPref() {
        if (mainPrefs.contains(PREF_WEBVIEW_DENSITY_V2)) {
            webViewDensityMode = mainPrefs.getInt(PREF_WEBVIEW_DENSITY_V2, 0).coerceIn(0, 4)
            return
        }
        val old = mainPrefs.getInt(PREF_WEBVIEW_DENSITY, 0)
        webViewDensityMode = when (old) {
            0, 1 -> 0
            2 -> 1
            3 -> 2
            4 -> 3
            else -> 0
        }
        mainPrefs.edit().putInt(PREF_WEBVIEW_DENSITY_V2, webViewDensityMode).apply()
    }

    private fun setWebViewDensityMode(mode: Int) {
        webViewDensityMode = mode.coerceIn(0, 4)
        mainPrefs.edit().putInt(PREF_WEBVIEW_DENSITY_V2, webViewDensityMode).apply()
        updateWebDensityButtonSelection()
        applyWebViewPageZoom()
    }

    /** 0 Standard 100%, 1–4 zunehmend kleiner; letzte Stufe 65%. */
    private fun zoomParamsForDensityMode(mode: Int): Pair<Int, Int> = when (mode) {
        0 -> 100 to 100
        1 -> 92 to 94
        2 -> 85 to 88
        3 -> 75 to 80
        4 -> 65 to 72
        else -> 100 to 100
    }

    /** CSS zoom + TextZoom — wirkt auf die meisten Seiten; kein natives Raster wie BrowseActivity. */
    private fun applyWebViewPageZoom() {
        if (!::webView.isInitialized) return
        val (zoomPct, textZoom) = zoomParamsForDensityMode(webViewDensityMode)
        webView.settings.textZoom = textZoom
        val z = zoomPct
        webView.evaluateJavascript(
            """
            (function(){
                var s = '$z%';
                try { document.documentElement.style.zoom = s; } catch(e1) {}
                try { if (document.body) document.body.style.zoom = s; } catch(e2) {}
            })();
            """.trimIndent(),
            null
        )
    }

    private fun setupWebDensityButtons() {
        binding.btnWebDensityStandard.setOnClickListener { setWebViewDensityMode(0) }
        binding.btnWebDensity92.setOnClickListener { setWebViewDensityMode(1) }
        binding.btnWebDensity85.setOnClickListener { setWebViewDensityMode(2) }
        binding.btnWebDensity75.setOnClickListener { setWebViewDensityMode(3) }
        binding.btnWebDensity65.setOnClickListener { setWebViewDensityMode(4) }
    }

    private fun updateWebDensityButtonSelection() {
        val accent = ContextCompat.getColor(this, R.color.colorPrimary)
        val normal = ContextCompat.getColor(this, R.color.chip_stroke)
        val buttons = listOf(
            binding.btnWebDensityStandard,
            binding.btnWebDensity92,
            binding.btnWebDensity85,
            binding.btnWebDensity75,
            binding.btnWebDensity65
        )
        buttons.forEach { it.strokeColor = ColorStateList.valueOf(normal) }
        val selected = when (webViewDensityMode) {
            0 -> binding.btnWebDensityStandard
            1 -> binding.btnWebDensity92
            2 -> binding.btnWebDensity85
            3 -> binding.btnWebDensity75
            4 -> binding.btnWebDensity65
            else -> binding.btnWebDensityStandard
        }
        selected.strokeColor = ColorStateList.valueOf(accent)
    }

    // ==================== AD BLOCKING ====================

    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        val adDomains = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adnxs.com", "adsrvr.org", "adform.net", "moatads.com",
            "exoclick.com", "exosrv.com", "juicyads.com",
            "trafficjunky.com", "trafficfactory.biz", "popads.net",
            "popcash.net", "propellerads.com", "adsterra.com",
            "clickadu.com", "hilltopads.com", "tsyndicate.com",
            "ad-maven.com", "admaven.com", "taboola.com", "outbrain.com",
            "syndication.", "prebid.", "openx.net", "pubmatic.com",
            "advertising.com", "spotxchange.com", "serving-sys.com",
            "revcontent.com", "mgid.com", "zedo.com"
        )
        val adPaths = listOf(
            "/ads/", "/ad/", "/adserver", "/adframe", "/banner",
            "/popup", "/popunder", "/preroll", "/midroll", "/vast/",
            "/vpaid/", "/sponsor/", "/promo/ad"
        )
        val adPatterns = listOf("_ad_", "-ad-", "ad_tag", "adtag", "vast.xml", "vpaid")
        return adDomains.any { lower.contains(it) } ||
               adPaths.any { lower.contains(it) } ||
               adPatterns.any { lower.contains(it) }
    }

    /**
     * Heuristik für Sortierung: höherer Score = oft bessere Qualität (1080 vor 720, HLS-Master etc.).
     * Kein Garant — CDNs codieren Auflösung unterschiedlich.
     */
    private fun scoreVideoUrl(url: String): Int {
        val l = url.lowercase()
        var score = 0
        if (l.contains(".m3u8")) score += 800
        if (l.contains(".mpd")) score += 750
        when {
            l.contains("2160") || l.contains("3840") || l.contains("4k") || l.contains("uhd") -> score += 4000
            l.contains("1440") -> score += 3500
            l.contains("1080") || l.contains("fullhd") -> score += 3000
            l.contains("720") -> score += 2000
            l.contains("480") -> score += 1000
            l.contains("360") -> score += 500
            l.contains("240") -> score += 250
        }
        if (l.contains("master") || (l.contains("index") && l.contains("m3u8"))) score += 200
        return score
    }

    private fun inferQualityHint(url: String): String {
        val l = url.lowercase()
        return when {
            Regex("""(2160|3840|4k|uhd)""").containsMatchIn(l) -> "4K/UHD"
            l.contains("1440") -> "1440p"
            l.contains("1080") || l.contains("fullhd") -> "1080p"
            l.contains("720") -> "720p"
            l.contains("480") -> "480p"
            l.contains("360") -> "360p"
            l.contains("240") -> "240p"
            l.contains("master") -> "Master"
            else -> getString(R.string.video_quality_unknown)
        }
    }

    /** Länge vom Player (HTML5-Video), z. B. „15:30 min“ oder „1:05:00 h“. */
    private fun formatVideoDurationLabel(durationSec: Float): String {
        if (durationSec.isNaN() || durationSec <= 0f || durationSec.isInfinite()) return ""
        val t = durationSec.toInt().coerceIn(0, Int.MAX_VALUE)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format("%d:%02d:%02d h", h, m, s)
        else String.format("%d:%02d min", m, s)
    }

    /** Kurz für Play-Dialog: Auflösung/Typ, ohne URL. */
    private fun inferPlayQualityLabel(url: String): String {
        val l = url.lowercase()
        return when {
            Regex("""(2160|3840|4k|uhd)""").containsMatchIn(l) -> "4K"
            l.contains("1440") -> "1440p"
            l.contains("1080") || l.contains("fullhd") -> "1080p"
            l.contains("720") -> "720p"
            l.contains("480") -> "480p"
            l.contains("360") -> "360p"
            l.contains("240") -> "240p"
            l.contains("master") || (l.contains("index") && l.contains("m3u8")) -> "HLS"
            l.contains(".m3u8") -> "HLS"
            l.contains(".mpd") -> "DASH"
            l.contains(".mp4") -> "MP4"
            l.contains(".webm") -> "WEBM"
            else -> "?"
        }
    }

    /** Eine Zeile: „720p · 15:30 min“ bzw. nur Qualität wenn Länge unbekannt. */
    private fun formatPlaySourceLine(url: String, pageDurationSec: Float): String {
        val q = inferPlayQualityLabel(url)
        val dur = formatVideoDurationLabel(pageDurationSec)
        return if (dur.isNotEmpty()) "$q · $dur" else q
    }

    private fun formatVideoSourceLabel(url: String, index: Int, total: Int): String {
        val type = when {
            url.contains(".m3u8", true) -> "HLS"
            url.contains(".mpd", true) -> "DASH"
            url.contains(".mp4", true) -> "MP4"
            url.contains(".webm", true) -> "WEBM"
            else -> "Video"
        }
        val hint = inferQualityHint(url)
        val host = try {
            Uri.parse(url).host?.removePrefix("www.")?.take(28) ?: ""
        } catch (_: Exception) {
            ""
        }
        val tail = if (url.length > 52) "…${url.takeLast(50)}" else url
        val hostPart = if (host.isNotEmpty()) " · $host" else ""
        return "$index/$total · $type · $hint$hostPart\n$tail"
    }

    // ==================== COMFORT SCRIPTS ====================

    private fun injectComfortScripts() {
        val script = """
            (function() {
                if (window._btbfInjected) return;
                window._btbfInjected = true;

                // Sichere Ad-Selektoren (NICHT .overlay, #overlay - die brechen den Video-Player!)
                var adSel = [
                    'iframe[src*="exoclick"]', 'iframe[src*="juicyads"]', 'iframe[src*="trafficjunky"]',
                    'iframe[src*="popads"]', 'iframe[src*="adsterra"]',
                    '.advertisement', '.modal-ad', '.ad-overlay',
                    'div[class*="video-ad"]', '.pre-roll-ad', '.mid-roll-ad',
                    'a[href*="exoclick"]', 'a[href*="juicyads"]', 'a[href*="trafficjunky"]',
                    'div[class*="exo_"]', 'div[id*="exo_"]',
                    '.ad-container', '.ad-wrapper',
                    'div[class*="sticky-ad"]',
                    '[class*="sponsor"]', '[id*="sponsor"]'
                ];

                function isVideoRelated(el) {
                    if (!el) return false;
                    if (el.querySelector && (el.querySelector('video') || el.querySelector('.player') ||
                        el.querySelector('[class*="player"]') || el.querySelector('[class*="video"]'))) return true;
                    if (el.className && typeof el.className === 'string' &&
                        (el.className.indexOf('player') !== -1 || el.className.indexOf('video') !== -1)) return true;
                    if (el.id && (el.id.indexOf('player') !== -1 || el.id.indexOf('video') !== -1)) return true;
                    return false;
                }

                function clean() {
                    adSel.forEach(function(s) {
                        try {
                            document.querySelectorAll(s).forEach(function(e) {
                                if (!isVideoRelated(e)) e.remove();
                            });
                        } catch(x) {}
                    });
                }
                clean();
                // Nur einmal nach 3s nochmal aufraeumen, KEIN setInterval (Performance!)
                setTimeout(clean, 3000);

                window.open = function() { return null; };

                // Click-Handler: NUR echte Ad-Links blocken, NICHT href="#" (Play-Buttons!)
                document.addEventListener('click', function(e) {
                    var t = e.target;
                    while (t && t !== document.body) {
                        if (t.tagName === 'A') {
                            var h = t.getAttribute('href') || '';
                            if (h.indexOf('exoclick') !== -1 || h.indexOf('juicyads') !== -1 ||
                                h.indexOf('trafficjunky') !== -1 || h.indexOf('popads') !== -1) {
                                e.preventDefault(); e.stopPropagation(); return false;
                            }
                        }
                        t = t.parentElement;
                    }
                }, true);

                // Video + HLS URL Tracking
                window._btbfVideoUrls = window._btbfVideoUrls || [];
                window._btbfStreamUrls = window._btbfStreamUrls || [];

                function trackUrl(url, isStream) {
                    if (!url || url.indexOf('blob:') === 0) return;
                    if (isStream) {
                        if (window._btbfStreamUrls.indexOf(url) === -1) window._btbfStreamUrls.push(url);
                        try { AndroidInterface.reportStreamUrl(url); } catch(e) {}
                    } else {
                        if (window._btbfVideoUrls.indexOf(url) === -1) window._btbfVideoUrls.push(url);
                        try { AndroidInterface.reportVideoUrl(url); } catch(e) {}
                    }
                }

                function isStreamUrl(url) {
                    return url && (url.indexOf('.m3u8') !== -1 || url.indexOf('.mpd') !== -1);
                }

                document.querySelectorAll('video').forEach(function(v) {
                    v.setAttribute('playsinline', '');
                    function track() {
                        var s = v.currentSrc || v.src;
                        if (s) trackUrl(s, isStreamUrl(s));
                        v.querySelectorAll('source').forEach(function(src) {
                            if (src.src) trackUrl(src.src, isStreamUrl(src.src));
                        });
                    }
                    track();
                    v.addEventListener('loadeddata', track);
                    v.addEventListener('playing', track);
                    v.addEventListener('loadedmetadata', track);
                });

                // XHR/Fetch Interceptor fuer HLS (leichtgewichtig)
                var origXHR = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    if (url && typeof url === 'string') {
                        if (isStreamUrl(url)) trackUrl(url, true);
                        else if (url.match(/\.(mp4|webm|mkv|mov)/i)) trackUrl(url, false);
                    }
                    return origXHR.apply(this, arguments);
                };

                var origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function(input) {
                        var url = typeof input === 'string' ? input : (input && input.url);
                        if (url) {
                            if (isStreamUrl(url)) trackUrl(url, true);
                            else if (url.match(/\.(mp4|webm|mkv|mov)/i)) trackUrl(url, false);
                        }
                        return origFetch.apply(this, arguments);
                    };
                }

                // MutationObserver: Nur fuer Video-Elemente, nicht alle Nodes
                new MutationObserver(function(m) {
                    for (var i = 0; i < m.length; i++) {
                        var added = m[i].addedNodes;
                        for (var j = 0; j < added.length; j++) {
                            var n = added[j];
                            if (n.nodeType !== 1) continue;
                            var vid = n.tagName === 'VIDEO' ? n : (n.querySelector ? n.querySelector('video') : null);
                            if (vid) {
                                var s = vid.currentSrc || vid.src;
                                if (s) trackUrl(s, isStreamUrl(s));
                            }
                        }
                    }
                }).observe(document.body, { childList: true, subtree: true });

                // FireTV Focus-Styles (ohne smooth-scroll fuer bessere Performance)
                var st = document.createElement('style');
                st.textContent =
                    ':focus { outline: 3px solid #FFD700 !important; outline-offset: 2px !important; }' +
                    '.cookie-banner, .cookie-consent, .cookie-notice, #cookie-notice, .gdpr-consent { display: none !important; }';
                document.head.appendChild(st);

                // Nur Links und Buttons fokussierbar machen, nicht ALLE Elemente
                document.querySelectorAll('a, button, [role="button"]').forEach(function(el) {
                    if (!el.getAttribute('tabindex')) el.setAttribute('tabindex', '0');
                });
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    // ==================== DOWNLOAD ====================

    private fun findAndDownloadVideo() {
        Toast.makeText(this, "Suche Videos...", Toast.LENGTH_SHORT).show()

        // Alle URLs aus JavaScript + Native Interception sammeln
        webView.evaluateJavascript("""
            (function() {
                var result = { direct: [], streams: [] };

                // Aus Tracking
                if (window._btbfVideoUrls) result.direct = window._btbfVideoUrls.slice();
                if (window._btbfStreamUrls) result.streams = window._btbfStreamUrls.slice();

                // Video-Elemente durchsuchen
                document.querySelectorAll('video').forEach(function(v) {
                    var s = v.currentSrc || v.src;
                    if (s && s.indexOf('blob:') !== 0) {
                        if (s.indexOf('.m3u8') !== -1 || s.indexOf('.mpd') !== -1) {
                            if (result.streams.indexOf(s) === -1) result.streams.push(s);
                        } else {
                            if (result.direct.indexOf(s) === -1) result.direct.push(s);
                        }
                    }
                    v.querySelectorAll('source').forEach(function(src) {
                        if (src.src && src.src.indexOf('blob:') !== 0) {
                            if (src.src.indexOf('.m3u8') !== -1) {
                                if (result.streams.indexOf(src.src) === -1) result.streams.push(src.src);
                            } else {
                                if (result.direct.indexOf(src.src) === -1) result.direct.push(src.src);
                            }
                        }
                    });
                });

                // Download-Links suchen
                document.querySelectorAll('a[href*=".mp4"], a[href*="download"], a[href*=".webm"]').forEach(function(a) {
                    if (a.href && result.direct.indexOf(a.href) === -1) result.direct.push(a.href);
                });

                // og:video Meta-Tag
                var og = document.querySelector('meta[property="og:video"]');
                if (og && og.content && result.direct.indexOf(og.content) === -1) result.direct.push(og.content);

                // Script-Tags durchsuchen
                var p = /https?:\/\/[^\s'"<>]+\.(mp4|m3u8|webm)[^\s'"<>]*/gi;
                document.querySelectorAll('script').forEach(function(s) {
                    var matches = (s.textContent || '').match(p);
                    if (matches) matches.forEach(function(m) {
                        if (m.indexOf('.m3u8') !== -1) {
                            if (result.streams.indexOf(m) === -1) result.streams.push(m);
                        } else {
                            if (result.direct.indexOf(m) === -1) result.direct.push(m);
                        }
                    });
                });

                // data-Attribute
                document.querySelectorAll('[data-src], [data-video], [data-hls]').forEach(function(el) {
                    ['data-src', 'data-video', 'data-hls', 'data-stream'].forEach(function(attr) {
                        var val = el.getAttribute(attr);
                        if (val && val.match(/\.(mp4|m3u8|webm)/i)) {
                            if (val.indexOf('.m3u8') !== -1) {
                                if (result.streams.indexOf(val) === -1) result.streams.push(val);
                            } else {
                                if (result.direct.indexOf(val) === -1) result.direct.push(val);
                            }
                        }
                    });
                });

                return JSON.stringify(result);
            })();
        """.trimIndent()) { jsResult ->
            handleVideoSearchResult(jsResult)
        }
    }

    private fun handleVideoSearchResult(jsResult: String?) {
        val allDirect = mutableListOf<String>()
        val allStreams = mutableListOf<String>()

        // JavaScript-Ergebnisse parsen
        try {
            val clean = jsResult?.trim()?.removeSurrounding("\"")
                ?.replace("\\\"", "\"")?.replace("\\\\/", "/")
                ?.replace("\\\\", "\\")
            if (clean != null && clean != "null") {
                val json = org.json.JSONObject(clean)
                val directArr = json.optJSONArray("direct")
                val streamArr = json.optJSONArray("streams")
                if (directArr != null) for (i in 0 until directArr.length()) allDirect.add(directArr.getString(i))
                if (streamArr != null) for (i in 0 until streamArr.length()) allStreams.add(streamArr.getString(i))
            }
        } catch (_: Exception) { }

        // Native abgefangene URLs hinzufuegen
        synchronized(capturedDirectUrls) {
            capturedDirectUrls.forEach { if (!allDirect.contains(it)) allDirect.add(it) }
        }
        synchronized(capturedStreamUrls) {
            capturedStreamUrls.forEach { if (!allStreams.contains(it)) allStreams.add(it) }
        }

        // Ad-URLs rausfiltern
        allDirect.removeAll { isAdUrl(it) }
        allStreams.removeAll { isAdUrl(it) }

        val totalFound = allDirect.size + allStreams.size

        when {
            totalFound == 0 -> {
                Toast.makeText(this, "Kein Video gefunden. Oeffne zuerst ein Video.", Toast.LENGTH_LONG).show()
            }
            totalFound == 1 -> {
                // Nur eine URL - direkt downloaden
                if (allDirect.isNotEmpty()) {
                    val did = videoDownloadHelper.downloadDirect(allDirect.first(), webView.url, webView.settings.userAgentString)
                    trackedDownloadIds.add(did)
                    Toast.makeText(this, "Download gestartet!", Toast.LENGTH_SHORT).show()
                } else {
                    startHlsDownload(allStreams.first())
                }
            }
            else -> {
                // Mehrere URLs - Dialog zeigen
                showDownloadDialog(allDirect, allStreams)
            }
        }
    }

    private fun showDownloadDialog(directUrls: List<String>, streamUrls: List<String>) {
        val sortedDirect = directUrls.sortedWith(compareByDescending { scoreVideoUrl(it) })
        val sortedStreams = streamUrls.sortedWith(compareByDescending { scoreVideoUrl(it) })
        val items = mutableListOf<Pair<String, String>>()
        val total = sortedDirect.size + sortedStreams.size
        var idx = 1
        sortedDirect.forEach { url ->
            items.add(formatVideoSourceLabel(url, idx, total) to url)
            idx++
        }
        sortedStreams.forEach { url ->
            items.add(formatVideoSourceLabel(url, idx, total) to url)
            idx++
        }

        val labels = items.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.download_sources_title, items.size))
            .setMessage(R.string.video_sources_message)
            .setItems(labels) { _, which ->
                val (_, url) = items[which]
                when {
                    url.contains(".m3u8", true) -> startHlsDownload(url)
                    videoDownloadHelper.isDashUrl(url) -> {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.dash_download_title)
                            .setMessage(R.string.dash_download_message)
                            .setPositiveButton(R.string.ok, null)
                            .show()
                    }
                    else -> {
                        val did = videoDownloadHelper.downloadDirect(url, webView.url, webView.settings.userAgentString)
                        trackedDownloadIds.add(did)
                        Toast.makeText(this, "Download gestartet!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun startHlsDownload(url: String) {
        Toast.makeText(this, "HLS-Stream Download gestartet...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            videoDownloadHelper.downloadHlsStream(
                m3u8Url = url,
                referer = webView.url,
                userAgent = webView.settings.userAgentString,
                onProgress = { progress, message ->
                    // Progress-Toast alle 20%
                    if (progress % 20 == 0 || progress >= 95) {
                        Toast.makeText(this@MainActivity, "$message ($progress%)", Toast.LENGTH_SHORT).show()
                    }
                },
                onComplete = { file ->
                    if (file != null) {
                        Toast.makeText(this@MainActivity, "Download fertig: ${file.name}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Download fehlgeschlagen", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    private fun registerDownloadReceiver() {
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id < 0 || !trackedDownloadIds.remove(id)) return
                Toast.makeText(this@MainActivity, "Download abgeschlossen!", Toast.LENGTH_SHORT).show()
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(downloadReceiver, filter)
    }

    // ==================== FULLSCREEN ====================

    private fun toggleFullscreen() {
        if (isFullScreen) {
            webView.evaluateJavascript("document.exitFullscreen();", null)
        } else {
            webView.evaluateJavascript("""
                (function() {
                    var v = document.querySelector('video');
                    if (v) { if (v.requestFullscreen) v.requestFullscreen(); else if (v.webkitEnterFullscreen) v.webkitEnterFullscreen(); }
                    else { document.documentElement.requestFullscreen(); }
                })();
            """.trimIndent(), null)
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            window.insetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    // ==================== FIRETV NAVIGATION ====================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::webView.isInitialized &&
            pointerMode &&
            !isNavVisible &&
            !isCategoryVisible &&
            !isFullScreen &&
            !isSplashVisible
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val stepBase = 14f * resources.displayMetrics.density
                        val step = stepBase + minOf(event.repeatCount, 20) * (2.5f * resources.displayMetrics.density)
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> movePointer(-step, 0f)
                            KeyEvent.KEYCODE_DPAD_RIGHT -> movePointer(step, 0f)
                            KeyEvent.KEYCODE_DPAD_UP -> movePointer(0f, -step)
                            KeyEvent.KEYCODE_DPAD_DOWN -> movePointer(0f, step)
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN) injectPointerClick()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        disablePointerMode()
                        Toast.makeText(this, R.string.mouse_mode_off, Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        disablePointerMode()
                        Toast.makeText(this, R.string.mouse_mode_off, Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!::webView.isInitialized) return super.onKeyDown(keyCode, event)

        // Site-Auswahl: komplette Fokus-Navigation (RecyclerView, Raster-Buttons) — nicht WebView-Logik
        if (binding.siteSelector.visibility == View.VISIBLE) {
            return super.onKeyDown(keyCode, event)
        }

        if (isSplashVisible) return super.onKeyDown(keyCode, event)

        // Untere Leiste (Zoom + Icons) oder Kategorie-Leiste: D-Pad nur Fokus zwischen Buttons — nicht schließen
        if (isNavVisible || isCategoryVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (isNavVisible) {
                        hideNavBar()
                        return true
                    }
                    if (isCategoryVisible) {
                        hideCategoryBar()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (isNavVisible) {
                        handler.removeCallbacks(hideNavRunnable)
                        handler.postDelayed(hideNavRunnable, NAV_AUTO_HIDE_MS)
                    }
                    if (isCategoryVisible) {
                        handler.removeCallbacks(hideCategoryRunnable)
                        handler.postDelayed(hideCategoryRunnable, CATEGORY_AUTO_HIDE_MS)
                    }
                    return super.onKeyDown(keyCode, event)
                }
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (!isCategoryVisible) { showCategoryBar(); return true }
                scrollWebView("up", 300)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!isNavVisible) { showNavBar(); return true }
                scrollWebView("down", 300)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                scrollWebView("left", 200)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                scrollWebView("right", 200)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event?.repeatCount != 0) return true
                okCenterLongPressConsumed = false
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isFullScreen) { customViewCallback?.onCustomViewHidden(); return true }
                if (isNavVisible) { hideNavBar(); return true }
                if (isCategoryVisible) { hideCategoryBar(); return true }
                if (webView.canGoBack()) { webView.goBack(); return true }
                showSiteSelectorAgain(); return true
            }
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_TV,
            KeyEvent.KEYCODE_INFO -> {
                if (event?.repeatCount == 0) {
                    handler.postDelayed({
                        if (!isMenuLongPress) toggleNavBar()
                        isMenuLongPress = false
                    }, 400)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                manualPlayVideo()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private var isMenuLongPress = false

    /** OK kurz = Klick, OK lang = Schnellaktionen (Maus / Play). */
    private var okCenterLongPressConsumed = false

    private fun isOkSelectKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> true
        else -> false
    }

    private fun showQuickActionsDialog() {
        val items = arrayOf(
            getString(R.string.quick_action_change_source),
            getString(R.string.quick_action_mouse),
            getString(R.string.quick_action_play)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.quick_actions_title)
            .setItems(items) { _, which ->
                hideNavBar()
                hideCategoryBar()
                when (which) {
                    0 -> showSiteSelectorAgain()
                    1 -> togglePointerModeFromToolbar()
                    2 -> manualPlayVideo()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (!::webView.isInitialized) return super.onKeyUp(keyCode, event)
        if (!isOkSelectKey(keyCode)) return super.onKeyUp(keyCode, event)

        if (binding.siteSelector.visibility == View.VISIBLE) return super.onKeyUp(keyCode, event)
        if (isNavVisible || isCategoryVisible) return super.onKeyUp(keyCode, event)
        if (pointerMode) return super.onKeyUp(keyCode, event)
        if (isSplashVisible) return super.onKeyUp(keyCode, event)

        if (!okCenterLongPressConsumed) {
            val down = event?.downTime ?: 0L
            val up = event?.eventTime ?: 0L
            if (up - down < ViewConfiguration.getLongPressTimeout()) {
                clickFocusedElement()
            }
        }
        okCenterLongPressConsumed = false
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (isOkSelectKey(keyCode)) {
            if (!::webView.isInitialized) return super.onKeyLongPress(keyCode, event)
            if (binding.siteSelector.visibility == View.VISIBLE) return super.onKeyLongPress(keyCode, event)
            if (isNavVisible || isCategoryVisible) return super.onKeyLongPress(keyCode, event)
            if (pointerMode) return super.onKeyLongPress(keyCode, event)
            if (isFullScreen || isSplashVisible) return super.onKeyLongPress(keyCode, event)
            okCenterLongPressConsumed = true
            showQuickActionsDialog()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            isMenuLongPress = true
            addCurrentVideoToFavorites()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!::webView.isInitialized) { super.onBackPressed(); return }
        if (isFullScreen) { customViewCallback?.onCustomViewHidden(); return }
        if (isNavVisible) { hideNavBar(); return }
        if (isCategoryVisible) { hideCategoryBar(); return }
        if (webView.canGoBack()) webView.goBack() else showSiteSelectorAgain()
    }

    private fun showSiteSelectorAgain() {
        activeSitePlugin = null
        disablePointerMode()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.clearFocus()
            webView.isFocusable = false
            webView.isFocusableInTouchMode = false
            webView.loadUrl("about:blank")
        }
        isSplashVisible = true
        isNavVisible = false
        isCategoryVisible = false
        binding.bottomNavStack.visibility = View.GONE
        binding.topBarContainer.visibility = View.GONE
        binding.splashOverlay.alpha = 1f
        binding.splashOverlay.visibility = View.VISIBLE
        binding.siteSelector.visibility = View.VISIBLE
        binding.loadingIndicator.visibility = View.GONE
        loadSiteGridPref()
        loadWebViewDensityPref()
        applySitePickerLayout()
        updateSiteLayoutButtonSelection()
        updateWebDensityButtonSelection()
        binding.rvSitePicker.post {
            binding.rvSitePicker.getChildAt(0)?.requestFocus()
                ?: binding.rvSitePicker.requestFocus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        autoPlayRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        downloadReceiver?.let { unregisterReceiver(it) }
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        if (::webView.isInitialized) webView.destroy()
    }

    // ==================== JS INTERFACE ====================

    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun downloadVideo(url: String) {
            CoroutineScope(Dispatchers.Main).launch {
                val did = videoDownloadHelper.downloadDirect(url, webView.url, webView.settings.userAgentString)
                trackedDownloadIds.add(did)
            }
        }

        @JavascriptInterface
        fun playInExoPlayer(url: String) {
            CoroutineScope(Dispatchers.Main).launch {
                if (!isAdUrl(url)) {
                    launchExoPlayer(url)
                }
            }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            CoroutineScope(Dispatchers.Main).launch { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun reportVideoUrl(url: String) {
            synchronized(capturedDirectUrls) {
                if (!capturedDirectUrls.contains(url)) capturedDirectUrls.add(url)
                while (capturedDirectUrls.size > MAX_CAPTURED_URLS) capturedDirectUrls.removeAt(0)
            }
        }

        @JavascriptInterface
        fun reportStreamUrl(url: String) {
            synchronized(capturedStreamUrls) {
                if (!capturedStreamUrls.contains(url)) capturedStreamUrls.add(url)
                while (capturedStreamUrls.size > MAX_CAPTURED_URLS) capturedStreamUrls.removeAt(0)
            }
        }
    }

    // ==================== GESTURES ====================

    inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
            if (e1 == null || isFullScreen) return false
            val dy = e2.y - e1.y
            if (dy > 100 && Math.abs(vY) > 100) { showCategoryBar(); return true }
            if (dy < -100 && Math.abs(vY) > 100) { if (isCategoryVisible) hideCategoryBar() else showNavBar(); return true }
            return false
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!isFullScreen) toggleNavBar()
            return super.onSingleTapUp(e)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    // ==================== EXOPLAYER LAUNCH ====================

    private fun autoPlayVideo() {
        autoPlayRunnable?.let { handler.removeCallbacks(it) }
        autoPlayRunnable = Runnable {
            if (isFinishing) return@Runnable
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed) return@Runnable
            if (!::webView.isInitialized) return@Runnable

            webView.evaluateJavascript("""
                (function() {
                    // Pruefen ob das eine Video-Einzelseite ist (nicht Uebersicht)
                    var isVideoPage = false;

                    // Hat die Seite einen grossen Video-Player?
                    var v = document.querySelector('video');
                    if (v) {
                        var rect = v.getBoundingClientRect();
                        // Video muss sichtbar und gross genug sein (kein Thumbnail)
                        if (rect.width > 200 && rect.height > 150) isVideoPage = true;
                    }

                    // URL-Heuristik: Video-Einzelseiten haben oft /video/, /watch/, /embed/ etc.
                    var loc = window.location.href.toLowerCase();
                    if (loc.match(/\/(video|watch|embed|play|clip|view)\//)) isVideoPage = true;
                    if (loc.match(/\/(video|watch|embed|play|clip|view)\?/)) isVideoPage = true;

                    if (!isVideoPage) return 'not_video_page';

                    // Video-URL holen (NICHT blob:)
                    if (v) {
                        v.pause();
                        var s = v.currentSrc || v.src;
                        if (s && s.indexOf('blob:') !== 0) return s;
                        var sources = v.querySelectorAll('source');
                        for (var i = 0; i < sources.length; i++) {
                            if (sources[i].src && sources[i].src.indexOf('blob:') !== 0) return sources[i].src;
                        }
                    }

                    // Tracked URLs
                    if (window._btbfStreamUrls && window._btbfStreamUrls.length > 0) return window._btbfStreamUrls[0];
                    if (window._btbfVideoUrls && window._btbfVideoUrls.length > 0) return window._btbfVideoUrls[0];

                    return 'no_url_yet';
                })();
            """.trimIndent()) { result ->
                val url = result?.trim()?.removeSurrounding("\"")
                if (!url.isNullOrEmpty() && url != "null" && url != "not_video_page" && url != "no_url_yet") {
                    if (!isAdUrl(url)) {
                        launchExoPlayer(url)
                    }
                }
            }
        }
        handler.postDelayed(autoPlayRunnable!!, 2000)
    }

    private fun injectVideoClickInterceptor() {
        // Faengt Video-Play-Events ab und leitet an ExoPlayer weiter
        webView.evaluateJavascript("""
            (function() {
                if (window._btbfClickInterceptor) return;
                window._btbfClickInterceptor = true;

                var adDomains = ['exoclick','exosrv','juicyads','trafficjunky','trafficfactory',
                    'popads','adsterra','clickadu','hilltopads','doubleclick','googlesyndication',
                    'adnxs','adsrvr','propellerads','popcash','admaven','ad-maven'];

                function isAdVideo(url) {
                    if (!url) return true;
                    var l = url.toLowerCase();
                    for (var i = 0; i < adDomains.length; i++) {
                        if (l.indexOf(adDomains[i]) !== -1) return true;
                    }
                    if (l.indexOf('/ads/') !== -1 || l.indexOf('/ad/') !== -1 || l.indexOf('preroll') !== -1 ||
                        l.indexOf('vast.xml') !== -1 || l.indexOf('vpaid') !== -1) return true;
                    return false;
                }

                function isMainPlayer(v) {
                    // Pruefen ob das Video der Haupt-Player ist (gross genug)
                    var rect = v.getBoundingClientRect();
                    return rect.width > 200 && rect.height > 100;
                }

                // Wenn ein Video zu spielen beginnt: pausieren und an ExoPlayer senden
                document.addEventListener('play', function(e) {
                    var v = e.target;
                    if (!v || v.tagName !== 'VIDEO') return;
                    if (!isMainPlayer(v)) return; // Kleine Thumbnails/Previews ignorieren

                    setTimeout(function() {
                        var url = v.currentSrc || v.src;

                        // Blob-URLs: Tracked URLs verwenden
                        if (!url || url.indexOf('blob:') === 0) {
                            if (window._btbfStreamUrls && window._btbfStreamUrls.length > 0) {
                                url = window._btbfStreamUrls[window._btbfStreamUrls.length - 1];
                            } else if (window._btbfVideoUrls && window._btbfVideoUrls.length > 0) {
                                url = window._btbfVideoUrls[window._btbfVideoUrls.length - 1];
                            } else {
                                return; // Keine URL gefunden
                            }
                        }

                        if (!isAdVideo(url)) {
                            v.pause();
                            try { AndroidInterface.playInExoPlayer(url); } catch(e) {}
                        }
                    }, 800);
                }, true);
            })();
        """.trimIndent(), null)
    }

    private fun manualPlayVideo() {
        Toast.makeText(this, "Suche Video...", Toast.LENGTH_SHORT).show()
        webView.evaluateJavascript("""
            (function() {
                var result = { direct: [], streams: [], durationSec: -1 };

                // Geschätzte Abspieldauer (größtes gültiges video.duration)
                document.querySelectorAll('video').forEach(function(v) {
                    try {
                        var d = v.duration;
                        if (typeof d === 'number' && !isNaN(d) && isFinite(d) && d > 0) {
                            if (result.durationSec < 0 || d > result.durationSec) result.durationSec = d;
                        }
                    } catch (e) {}
                });

                // Video-Elemente
                document.querySelectorAll('video').forEach(function(v) {
                    v.pause();
                    var s = v.currentSrc || v.src;
                    if (s && s.indexOf('blob:') !== 0) {
                        if (s.indexOf('.m3u8') !== -1 || s.indexOf('.mpd') !== -1)
                            result.streams.push(s);
                        else
                            result.direct.push(s);
                    }
                    v.querySelectorAll('source').forEach(function(src) {
                        if (src.src && src.src.indexOf('blob:') !== 0) {
                            if (src.src.indexOf('.m3u8') !== -1)
                                result.streams.push(src.src);
                            else
                                result.direct.push(src.src);
                        }
                    });
                });

                // Tracked URLs
                if (window._btbfStreamUrls) window._btbfStreamUrls.forEach(function(u) {
                    if (result.streams.indexOf(u) === -1) result.streams.push(u);
                });
                if (window._btbfVideoUrls) window._btbfVideoUrls.forEach(function(u) {
                    if (result.direct.indexOf(u) === -1) result.direct.push(u);
                });

                // Script-Tags durchsuchen
                var p = /https?:\/\/[^\s'"<>]+\.(mp4|m3u8|webm)[^\s'"<>]*/gi;
                document.querySelectorAll('script').forEach(function(s) {
                    var matches = (s.textContent || '').match(p);
                    if (matches) matches.forEach(function(m) {
                        if (m.indexOf('.m3u8') !== -1) {
                            if (result.streams.indexOf(m) === -1) result.streams.push(m);
                        } else {
                            if (result.direct.indexOf(m) === -1) result.direct.push(m);
                        }
                    });
                });

                return JSON.stringify(result);
            })();
        """.trimIndent()) { jsResult ->
            handlePlayResult(jsResult)
        }
    }

    private fun handlePlayResult(jsResult: String?) {
        val allUrls = mutableListOf<String>()
        var pageDurationSec = -1f
        try {
            val clean = jsResult?.trim()?.removeSurrounding("\"")
                ?.replace("\\\"", "\"")?.replace("\\\\/", "/")?.replace("\\\\", "\\")
            if (clean != null && clean != "null") {
                val json = org.json.JSONObject(clean)
                val d = json.optDouble("durationSec", -1.0)
                if (!d.isNaN() && d > 0.0) pageDurationSec = d.toFloat()
                val streams = json.optJSONArray("streams")
                val direct = json.optJSONArray("direct")
                // HLS/Streams bevorzugen (bessere Qualitaet)
                if (streams != null) for (i in 0 until streams.length()) allUrls.add(streams.getString(i))
                if (direct != null) for (i in 0 until direct.length()) allUrls.add(direct.getString(i))
            }
        } catch (_: Exception) { }

        // Native captured URLs hinzufuegen
        synchronized(capturedStreamUrls) { capturedStreamUrls.forEach { if (!allUrls.contains(it)) allUrls.add(0, it) } }
        synchronized(capturedDirectUrls) { capturedDirectUrls.forEach { if (!allUrls.contains(it)) allUrls.add(it) } }

        allUrls.removeAll { isAdUrl(it) }

        when {
            allUrls.isEmpty() -> Toast.makeText(this, "Kein Video gefunden. Oeffne zuerst ein Video.", Toast.LENGTH_LONG).show()
            allUrls.size == 1 -> launchExoPlayer(allUrls.first())
            else -> {
                val sorted = allUrls.sortedWith(compareByDescending { scoreVideoUrl(it) })
                Toast.makeText(this, getString(R.string.video_sources_toast, sorted.size), Toast.LENGTH_LONG).show()
                val rawLines = sorted.map { formatPlaySourceLine(it, pageDurationSec) }
                val labels = rawLines.mapIndexed { i, line ->
                    val dup = rawLines.indices.count { rawLines[it] == line } > 1
                    if (dup) "$line (${i + 1})" else line
                }.toTypedArray()

                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.video_sources_title, sorted.size))
                    .setMessage(R.string.video_sources_play_hint)
                    .setItems(labels) { _, which -> launchExoPlayer(sorted[which]) }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        }
    }

    private fun launchExoPlayer(videoUrl: String) {
        val intent = android.content.Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
            putExtra(VideoPlayerActivity.EXTRA_REFERER, webView.url ?: websiteUrl)
            putExtra(VideoPlayerActivity.EXTRA_USER_AGENT, webView.settings.userAgentString)
            putExtra(VideoPlayerActivity.EXTRA_TITLE, webView.title ?: "Video")
        }
        startActivity(intent)
    }

    // ==================== WEBVIEW HELPERS ====================

    private fun scrollWebView(dir: String, amount: Int) {
        val js = when (dir) {
            "up" -> "window.scrollBy(0,-$amount);"
            "down" -> "window.scrollBy(0,$amount);"
            "left" -> "window.scrollBy(-$amount,0);"
            "right" -> "window.scrollBy($amount,0);"
            else -> ""
        }
        webView.evaluateJavascript(js, null)
    }

    private fun clickFocusedElement() {
        webView.evaluateJavascript("(function(){if(document.activeElement)document.activeElement.click();})();", null)
    }

    // ==================== FAVORITEN ====================

    private fun showFavoritesDialog() {
        val db = DialogFavoritesBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(db.root).setCancelable(true).create()

        lateinit var videoAdapter: FavoriteVideosAdapter
        lateinit var actorAdapter: FavoriteActorsAdapter
        videoAdapter = FavoriteVideosAdapter(
            onOpen = { v ->
                dialog.dismiss()
                loadUrlInWebView(v.id)
            },
            onRemove = { v ->
                favoritesManager.removeFavoriteVideo(v.id)
                refreshFavoritesPanel(db, videoAdapter, actorAdapter)
            }
        )
        actorAdapter = FavoriteActorsAdapter(
            onOpen = { a ->
                dialog.dismiss()
                val url = when {
                    a.id.startsWith("http://", true) || a.id.startsWith("https://", true) -> a.id
                    websiteUrl.isNotEmpty() -> "${websiteUrl.trimEnd('/')}/${a.id.trimStart('/')}"
                    currentSiteDomain.isNotEmpty() -> "https://$currentSiteDomain/${a.id.trimStart('/')}"
                    else -> a.id
                }
                loadUrlInWebView(url)
            },
            onRemove = { a ->
                favoritesManager.removeFavoriteActor(a.id)
                refreshFavoritesPanel(db, videoAdapter, actorAdapter)
            }
        )

        db.recyclerFavoriteVideos.layoutManager = LinearLayoutManager(this)
        db.recyclerFavoriteVideos.adapter = videoAdapter
        db.recyclerFavoriteActors.layoutManager = LinearLayoutManager(this)
        db.recyclerFavoriteActors.adapter = actorAdapter

        db.btnCloseFavorites.setOnClickListener { dialog.dismiss() }

        db.favoritesTabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        db.videosTabContent.visibility = View.VISIBLE
                        db.actorsTabContent.visibility = View.GONE
                        refreshFavoritesPanel(db, videoAdapter, actorAdapter)
                    }
                    1 -> {
                        db.videosTabContent.visibility = View.GONE
                        db.actorsTabContent.visibility = View.VISIBLE
                        refreshFavoritesPanel(db, videoAdapter, actorAdapter)
                    }
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        db.btnExportFavorites.setOnClickListener {
            exportFavorites()
            Toast.makeText(this, "Exportiert!", Toast.LENGTH_SHORT).show()
        }
        db.btnClearFavorites.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Alle loeschen?").setMessage("Wirklich alle Favoriten loeschen?")
                .setPositiveButton("Ja") { _, _ ->
                    favoritesManager.clearAllFavorites()
                    Toast.makeText(this, "Geloescht", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton("Nein", null).show()
        }

        db.videosTabContent.visibility = View.VISIBLE
        db.actorsTabContent.visibility = View.GONE
        refreshFavoritesPanel(db, videoAdapter, actorAdapter)
        dialog.show()
    }

    private fun refreshFavoritesPanel(
        db: DialogFavoritesBinding,
        videoAdapter: FavoriteVideosAdapter,
        actorAdapter: FavoriteActorsAdapter
    ) {
        val v = favoritesManager.getFavoriteVideos()
        val a = favoritesManager.getFavoriteActors()
        videoAdapter.submitList(v)
        actorAdapter.submitList(a)
        db.recyclerFavoriteVideos.visibility = if (v.isEmpty()) View.GONE else View.VISIBLE
        db.emptyVideosState.visibility = if (v.isEmpty()) View.VISIBLE else View.GONE
        db.recyclerFavoriteActors.visibility = if (a.isEmpty()) View.GONE else View.VISIBLE
        db.emptyActorsState.visibility = if (a.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun exportFavorites() {
        val videosArr = JSONArray()
        favoritesManager.getFavoriteVideos().forEach { v ->
            videosArr.put(
                JSONObject().apply {
                    put("id", v.id)
                    put("title", v.title)
                    put("thumbnailUrl", v.thumbnailUrl)
                    put("duration", v.duration)
                    put("addedAt", v.addedAt)
                }
            )
        }
        val actorsArr = JSONArray()
        favoritesManager.getFavoriteActors().forEach { a ->
            actorsArr.put(
                JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("imageUrl", a.imageUrl)
                    put("addedAt", a.addedAt)
                }
            )
        }
        val root = JSONObject().apply {
            put("videos", videosArr)
            put("actors", actorsArr)
        }
        val json = root.toString(2)
        val clip = android.content.ClipData.newPlainText("BTBF Favorites", json)
        (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
    }

    fun addCurrentVideoToFavorites() {
        webView.evaluateJavascript("""
            (function() {
                var t = document.querySelector('h1') ? document.querySelector('h1').textContent : document.title;
                var th = ''; var v = document.querySelector('video');
                if (v && v.poster) th = v.poster;
                var og = document.querySelector('meta[property="og:image"]');
                if (!th && og) th = og.content;
                return JSON.stringify({title:t,thumbnail:th});
            })();
        """.trimIndent()) { result ->
            if (result != null && result != "null") {
                try {
                    val clean = result.trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\/", "/")
                    val json = org.json.JSONObject(clean)
                    val pageUrl = webView.url?.trim()?.trimEnd('/')
                    if (pageUrl.isNullOrEmpty()) {
                        Toast.makeText(this, "Keine Seiten-URL", Toast.LENGTH_SHORT).show()
                        return@evaluateJavascript
                    }
                    favoritesManager.addFavoriteVideo(
                        pageUrl,
                        json.optString("title", "Video"),
                        json.optString("thumbnail", "")
                    )
                    Toast.makeText(this, "Favorit gespeichert!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { Toast.makeText(this, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    companion object {
        private const val PREF_SITE_GRID_COLUMNS = "site_grid_columns"
        private const val PREF_WEBVIEW_DENSITY = "webview_density_mode"
        private const val PREF_WEBVIEW_DENSITY_V2 = "webview_density_mode_v2"
        private const val MAX_CAPTURED_URLS = 64
    }
}
