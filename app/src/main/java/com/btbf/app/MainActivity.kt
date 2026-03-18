package com.btbf.app

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.btbf.app.databinding.ActivityMainBinding
import com.btbf.app.databinding.DialogFavoritesBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isFullScreen = false
    private var isNavVisible = false
    private var isCategoryVisible = false
    private var isSplashVisible = true

    // Site-Konfiguration
    private data class SiteConfig(val name: String, val url: String, val domain: String)
    private val sites = listOf(
        SiteConfig("BTBF", "https://de.borntobefuck.com/", "borntobefuck"),
        SiteConfig("CamCaps", "https://camcaps.tv/", "camcaps.tv"),
        SiteConfig("FyxXR", "https://fyxxr.com/", "fyxxr.com"),
        SiteConfig("SheeshFans", "https://sheeshfans.com/", "sheeshfans.com"),
        SiteConfig("LeakPorner", "https://leakporner.com/", "leakporner.com")
    )
    private var websiteUrl = ""
    private var currentSiteDomain = ""
    private val storagePermissionCode = 100
    private lateinit var favoritesManager: FavoritesManager
    private lateinit var videoDownloadHelper: VideoDownloadHelper
    private lateinit var gestureDetector: GestureDetector
    private val handler = Handler(Looper.getMainLooper())

    // Video-URL Tracking (direkte + Stream URLs)
    private val capturedDirectUrls = mutableListOf<String>()
    private val capturedStreamUrls = mutableListOf<String>()
    private var downloadReceiver: BroadcastReceiver? = null

    // Auto-Hide Timer
    private val hideNavRunnable = Runnable { hideNavBar() }
    private val hideCategoryRunnable = Runnable { hideCategoryBar() }
    private val NAV_AUTO_HIDE_MS = 4000L
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
    }

    // ==================== SITE SELECTOR ====================

    private fun setupSiteSelector() {
        binding.btnSiteBtbf.setOnClickListener { selectSite(0) }
        binding.btnSiteCamcaps.setOnClickListener { selectSite(1) }
        binding.btnSiteFyxxr.setOnClickListener { selectSite(2) }
        binding.btnSiteSheeshfans.setOnClickListener { selectSite(3) }
        binding.btnSiteLeakporner.setOnClickListener { selectSite(4) }

        // Ersten Eintrag fokussieren (FireTV)
        binding.btnSiteBtbf.requestFocus()
    }

    private fun selectSite(index: Int) {
        val site = sites[index]
        websiteUrl = site.url
        currentSiteDomain = site.domain

        // Auswahl ausblenden, Lade-Anzeige einblenden
        binding.siteSelector.visibility = View.GONE
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.loadingSiteName.text = site.name

        // WebView jetzt erst initialisieren und laden
        setupWebView()
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

    private fun setupWebView() {
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
            userAgentString = userAgentString.replace("; wv", "")
        }

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
                    }
                }

                // HLS/DASH Stream-URLs abfangen
                if (videoDownloadHelper.isStreamUrl(url)) {
                    synchronized(capturedStreamUrls) {
                        if (!capturedStreamUrls.contains(url)) capturedStreamUrls.add(url)
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
                injectComfortScripts()
                autoPlayVideo()

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
            videoDownloadHelper.downloadDirect(url, webView.url ?: websiteUrl, userAgent, contentDisposition, mimetype)
            Toast.makeText(this, "Download gestartet!", Toast.LENGTH_SHORT).show()
        }

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidInterface")
        webView.loadUrl(websiteUrl)
    }

    // ==================== SPLASH SCREEN ====================

    private fun dismissSplash() {
        if (!isSplashVisible) return
        isSplashVisible = false

        binding.splashOverlay.animate()
            .alpha(0f)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { binding.splashOverlay.visibility = View.GONE }
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
            binding.buttonContainer.visibility = View.VISIBLE
            binding.buttonContainer.alpha = 0f
            binding.buttonContainer.translationY = 60f
            binding.buttonContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        handler.removeCallbacks(hideNavRunnable)
        handler.postDelayed(hideNavRunnable, NAV_AUTO_HIDE_MS)
    }

    private fun hideNavBar() {
        if (!isNavVisible) return
        isNavVisible = false
        handler.removeCallbacks(hideNavRunnable)
        binding.buttonContainer.animate()
            .alpha(0f)
            .translationY(60f)
            .setDuration(200)
            .withEndAction { binding.buttonContainer.visibility = View.GONE }
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
        binding.btnFullscreen.setOnClickListener { toggleFullscreen() }
        binding.btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        binding.btnFavorites.setOnClickListener { showFavoritesDialog() }

        binding.btnCatHome.setOnClickListener { webView.loadUrl(websiteUrl); hideCategoryBar() }
        binding.btnCatNew.setOnClickListener { webView.loadUrl("${websiteUrl}new"); hideCategoryBar() }
        binding.btnCatTop.setOnClickListener { webView.loadUrl("${websiteUrl}top"); hideCategoryBar() }
        binding.btnCatRandom.setOnClickListener { webView.loadUrl("${websiteUrl}random"); hideCategoryBar() }
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
            "ad-maven.com", "admaven.com", "taboola.com", "outbrain.com"
        )
        val adPaths = listOf("/ads/", "/ad/", "/adserver", "/adframe", "/banner", "/popup", "/popunder")
        return adDomains.any { lower.contains(it) } || adPaths.any { lower.contains(it) }
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
                    videoDownloadHelper.downloadDirect(allDirect.first(), webView.url, webView.settings.userAgentString)
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
        val items = mutableListOf<Pair<String, String>>() // label -> url

        directUrls.forEachIndexed { i, url ->
            val ext = when {
                url.lowercase().contains(".mp4") -> "MP4"
                url.lowercase().contains(".webm") -> "WEBM"
                else -> "Video"
            }
            val shortUrl = if (url.length > 60) "...${url.takeLast(50)}" else url
            items.add("[$ext] Video ${i + 1} - $shortUrl" to url)
        }

        streamUrls.forEachIndexed { i, url ->
            val shortUrl = if (url.length > 60) "...${url.takeLast(50)}" else url
            items.add("[HLS Stream] ${i + 1} - $shortUrl" to url)
        }

        val labels = items.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("${items.size} Videos gefunden")
            .setItems(labels) { _, which ->
                val (label, url) = items[which]
                if (label.startsWith("[HLS")) {
                    startHlsDownload(url)
                } else {
                    videoDownloadHelper.downloadDirect(url, webView.url, webView.settings.userAgentString)
                    Toast.makeText(this, "Download gestartet!", Toast.LENGTH_SHORT).show()
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
                if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                    Toast.makeText(this@MainActivity, "Download abgeschlossen!", Toast.LENGTH_SHORT).show()
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(downloadReceiver, filter, RECEIVER_NOT_EXPORTED)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!::webView.isInitialized) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isNavVisible) { hideNavBar(); showCategoryBar(); return true }
                if (!isCategoryVisible) { showCategoryBar(); return true }
                scrollWebView("up", 300)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isCategoryVisible) { hideCategoryBar(); return true }
                if (!isNavVisible && !isCategoryVisible) { showNavBar(); return true }
                scrollWebView("down", 300)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isNavVisible || isCategoryVisible) return super.onKeyDown(keyCode, event)
                scrollWebView("left", 200)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isNavVisible || isCategoryVisible) return super.onKeyDown(keyCode, event)
                scrollWebView("right", 200)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!isNavVisible && !isCategoryVisible) {
                    clickFocusedElement()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isFullScreen) { customViewCallback?.onCustomViewHidden(); return true }
                if (isNavVisible) { hideNavBar(); return true }
                if (isCategoryVisible) { hideCategoryBar(); return true }
                if (webView.canGoBack()) { webView.goBack(); return true }
                showSiteSelectorAgain(); return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event?.repeatCount == 0) {
                    handler.postDelayed({
                        if (!isMenuLongPress) toggleNavBar()
                        isMenuLongPress = false
                    }, 400)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                webView.evaluateJavascript("(function(){var v=document.querySelector('video');if(v){if(v.paused)v.play();else v.pause();}})();", null)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private var isMenuLongPress = false

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
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
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
        }
        isSplashVisible = true
        isNavVisible = false
        isCategoryVisible = false
        binding.buttonContainer.visibility = View.GONE
        binding.topBarContainer.visibility = View.GONE
        binding.splashOverlay.alpha = 1f
        binding.splashOverlay.visibility = View.VISIBLE
        binding.siteSelector.visibility = View.VISIBLE
        binding.loadingIndicator.visibility = View.GONE
        binding.btnSiteBtbf.requestFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
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
                videoDownloadHelper.downloadDirect(url, webView.url, webView.settings.userAgentString)
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
            }
        }

        @JavascriptInterface
        fun reportStreamUrl(url: String) {
            synchronized(capturedStreamUrls) {
                if (!capturedStreamUrls.contains(url)) capturedStreamUrls.add(url)
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

    // ==================== AUTOPLAY ====================

    private fun autoPlayVideo() {
        // Versuche Video zu finden, automatisch abzuspielen und Vollbild zu aktivieren
        webView.evaluateJavascript("""
            (function() {
                var v = document.querySelector('video');
                if (!v) return false;
                v.muted = false;
                v.play().then(function() {
                    // Vollbild anfordern
                    if (v.requestFullscreen) v.requestFullscreen();
                    else if (v.webkitEnterFullscreen) v.webkitEnterFullscreen();
                    else if (v.webkitRequestFullScreen) v.webkitRequestFullScreen();
                }).catch(function() {
                    // Autoplay blockiert - versuche muted
                    v.muted = true;
                    v.play().then(function() {
                        if (v.requestFullscreen) v.requestFullscreen();
                        else if (v.webkitEnterFullscreen) v.webkitEnterFullscreen();
                    }).catch(function() {});
                });
                return true;
            })();
        """.trimIndent(), null)
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

        db.btnCloseFavorites.setOnClickListener { dialog.dismiss() }

        db.favoritesTabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { db.videosTabContent.visibility = View.VISIBLE; db.actorsTabContent.visibility = View.GONE; loadFavs(db) }
                    1 -> { db.videosTabContent.visibility = View.GONE; db.actorsTabContent.visibility = View.VISIBLE; loadActors(db) }
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        db.btnExportFavorites.setOnClickListener { exportFavorites(); Toast.makeText(this, "Exportiert!", Toast.LENGTH_SHORT).show() }
        db.btnClearFavorites.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Alle loeschen?").setMessage("Wirklich alle Favoriten loeschen?")
                .setPositiveButton("Ja") { _, _ -> favoritesManager.clearAllFavorites(); Toast.makeText(this, "Geloescht", Toast.LENGTH_SHORT).show(); dialog.dismiss() }
                .setNegativeButton("Nein", null).show()
        }

        db.videosTabContent.visibility = View.VISIBLE
        db.actorsTabContent.visibility = View.GONE
        loadFavs(db)
        dialog.show()
    }

    private fun loadFavs(db: DialogFavoritesBinding) {
        val v = favoritesManager.getFavoriteVideos()
        db.recyclerFavoriteVideos.visibility = if (v.isEmpty()) View.GONE else View.VISIBLE
        db.emptyVideosState.visibility = if (v.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadActors(db: DialogFavoritesBinding) {
        val a = favoritesManager.getFavoriteActors()
        db.recyclerFavoriteActors.visibility = if (a.isEmpty()) View.GONE else View.VISIBLE
        db.emptyActorsState.visibility = if (a.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun exportFavorites() {
        val json = StringBuilder().apply {
            appendLine("{")
            appendLine("  \"videos\": [")
            favoritesManager.getFavoriteVideos().forEachIndexed { i, v ->
                appendLine("    {\"id\":\"${v.id}\",\"title\":\"${v.title}\"}${if (i < favoritesManager.getFavoriteVideos().lastIndex) "," else ""}")
            }
            appendLine("  ],\"actors\": [")
            favoritesManager.getFavoriteActors().forEachIndexed { i, a ->
                appendLine("    {\"id\":\"${a.id}\",\"name\":\"${a.name}\"}${if (i < favoritesManager.getFavoriteActors().lastIndex) "," else ""}")
            }
            appendLine("  ]}")
        }.toString()
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
                    val id = webView.url?.hashCode()?.toString() ?: System.currentTimeMillis().toString()
                    favoritesManager.addFavoriteVideo(id, json.optString("title", "Video"), json.optString("thumbnail", ""))
                    Toast.makeText(this, "Favorit gespeichert!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { Toast.makeText(this, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
