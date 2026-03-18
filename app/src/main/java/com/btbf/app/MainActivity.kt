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
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isFullScreen = false

    private val websiteUrl = "https://de.borntobefuck.com/"

    private val storagePermissionCode = 100

    // Favoriten Manager
    private lateinit var favoritesManager: FavoritesManager

    // Gesture Detector fuer Swipe-Steuerung
    private lateinit var gestureDetector: GestureDetector
    private var lastYPosition = 0f

    // Gesammelte Video-URLs aus Netzwerk-Requests
    private val capturedVideoUrls = mutableListOf<String>()
    private var lastCapturedVideoUrl: String? = null

    // Video-Erweiterungen die wir tracken
    private val videoExtensions = listOf(".mp4", ".webm", ".mkv", ".avi", ".mov", ".m4v")

    // Download-Tracking
    private var downloadReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Statusbar fuer immersive Erfahrung ausblenden
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Favoriten Manager initialisieren
        favoritesManager = FavoritesManager(this)

        // Gesture Detector initialisieren
        gestureDetector = GestureDetector(this, GestureListener())

        setupWebView()
        setupButtons()
        registerDownloadReceiver()

        // Berechtigungen anfordern
        requestPermissions()
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_MEDIA_VIDEO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                storagePermissionCode
            )
        }
    }

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
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            // User-Agent anpassen damit die Seite korrekt laedt
            userAgentString = userAgentString.replace("; wv", "")
        }

        // Cookies erlauben
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // WebViewClient mit Video-URL-Tracking und Ad-Blocking auf Netzwerk-Ebene
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (!url.contains("borntobefuck") && !url.startsWith("javascript:")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        return true
                    } catch (_: Exception) { }
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // Video-URLs abfangen und speichern
                if (isVideoUrl(url)) {
                    synchronized(capturedVideoUrls) {
                        if (!capturedVideoUrls.contains(url)) {
                            capturedVideoUrls.add(url)
                            lastCapturedVideoUrl = url
                        }
                    }
                }

                // Werbung auf Netzwerk-Ebene blockieren
                if (isAdUrl(url)) {
                    return WebResourceResponse("text/plain", "UTF-8", null)
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
                synchronized(capturedVideoUrls) {
                    capturedVideoUrls.clear()
                    lastCapturedVideoUrl = null
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                injectComfortScripts()
                enableMouseMode()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(
                view: View,
                callback: CustomViewCallback
            ) {
                customView = view
                customViewCallback = callback
                isFullScreen = true

                hideSystemUI()

                binding.fullscreenContainer.apply {
                    visibility = View.VISIBLE
                    addView(view)
                }

                binding.webViewContainer.visibility = View.GONE
                binding.buttonContainer.visibility = View.GONE
                binding.categoryScroll.visibility = View.GONE
            }

            override fun onHideCustomView() {
                customView?.let {
                    binding.fullscreenContainer.removeView(it)
                    customView = null
                }
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                isFullScreen = false

                showSystemUI()

                binding.fullscreenContainer.visibility = View.GONE
                binding.webViewContainer.visibility = View.VISIBLE
                binding.buttonContainer.visibility = View.VISIBLE
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                if (newProgress == 100) {
                    binding.progressBar.visibility = View.GONE
                } else {
                    binding.progressBar.visibility = View.VISIBLE
                }
            }
        }

        // Download Listener fuer direkte Downloads
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            startDownload(url, contentDisposition, mimetype, userAgent)
        }

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidInterface")

        webView.loadUrl(websiteUrl)
    }

    private fun isVideoUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        if (videoExtensions.any { lowerUrl.contains(it) }) return true
        if (lowerUrl.contains("mime=video") || lowerUrl.contains("type=video")) return true
        if (lowerUrl.contains("/video/") && (lowerUrl.contains(".mp4") || lowerUrl.contains("format="))) return true
        return false
    }

    private fun isAdUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        val adDomains = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adnxs.com", "adsrvr.org", "adform.net", "ads.yahoo.com",
            "moatads.com", "serving-sys.com", "exponential.com",
            "quantserve.com", "scorecardresearch.com", "zedo.com",
            "pubmatic.com", "openx.net", "rubiconproject.com",
            "exoclick.com", "exosrv.com", "juicyads.com",
            "trafficjunky.com", "trafficfactory.biz", "popads.net",
            "popcash.net", "propellerads.com", "adsterra.com",
            "clickadu.com", "hilltopads.com", "tsyndicate.com",
            "a-ads.com", "ad-maven.com", "admaven.com",
            "revcontent.com", "mgid.com", "taboola.com", "outbrain.com"
        )
        val adPaths = listOf(
            "/ads/", "/ad/", "/adserver", "/adframe",
            "/banner", "/popup", "/popunder", "/clickunder"
        )
        return adDomains.any { lowerUrl.contains(it) } ||
               adPaths.any { lowerUrl.contains(it) }
    }

    private fun injectComfortScripts() {
        val comfortScript = """
            (function() {
                if (window._btbfComfortInjected) return;
                window._btbfComfortInjected = true;

                // === WERBEBLOCKER (DOM-Level) ===
                var adSelectors = [
                    'iframe[src*="ad"]', 'iframe[src*="ads"]', 'iframe[src*="banner"]',
                    'iframe[src*="pop"]', 'iframe[src*="click"]',
                    'div[class*="ad-"]', 'div[class*="ad_"]', 'div[class*="ads-"]',
                    'div[id*="ad-"]', 'div[id*="ad_"]', 'div[id*="ads-"]',
                    '.advertisement', '.advertisement-wrapper', '.ads', '.ads-wrapper',
                    '#ads', '#ads-wrapper', '[class*="sponsor"]', '[id*="sponsor"]',
                    '.popup', '.modal-ad', '.ad-overlay', '.overlay-ad',
                    'div[class*="video-ad"]', 'div[id*="video-ad"]',
                    '.pre-roll-ad', '.mid-roll-ad',
                    'div[class*="banner"]', 'div[id*="banner"]',
                    'a[href*="exoclick"]', 'a[href*="juicyads"]',
                    'a[href*="trafficjunky"]', 'a[href*="popads"]',
                    'div[class*="exo"]', 'div[id*="exo"]',
                    '.ad-container', '.ad-wrapper', '.ad-block',
                    '#overlay', '.overlay', '.modal-overlay',
                    'div[class*="popup"]', 'div[id*="popup"]',
                    'div[class*="float"]', 'div[class*="sticky-ad"]'
                ];

                function removeAds() {
                    adSelectors.forEach(function(selector) {
                        try {
                            document.querySelectorAll(selector).forEach(function(el) {
                                el.remove();
                            });
                        } catch(e) {}
                    });

                    // Unsichtbare Overlays entfernen die Klicks abfangen
                    document.querySelectorAll('div, a').forEach(function(el) {
                        var style = window.getComputedStyle(el);
                        if (style.position === 'fixed' && parseInt(style.zIndex) > 999 &&
                            (style.opacity === '0' || el.offsetWidth >= window.innerWidth * 0.8)) {
                            if (!el.querySelector('video') && !el.classList.contains('player')) {
                                el.remove();
                            }
                        }
                    });
                }

                removeAds();
                setInterval(removeAds, 2000);

                // Popups und Redirects blockieren
                window.open = function() { return null; };

                // Click-Hijacking verhindern
                document.addEventListener('click', function(e) {
                    var target = e.target;
                    while (target && target !== document.body) {
                        if (target.tagName === 'A') {
                            var href = target.getAttribute('href') || '';
                            if (href.indexOf('exoclick') !== -1 || href.indexOf('juicyads') !== -1 ||
                                href.indexOf('trafficjunky') !== -1 || href.indexOf('popads') !== -1 ||
                                href.indexOf('clickadu') !== -1 || href === '#' ||
                                href.indexOf('javascript:void') === 0) {
                                e.preventDefault();
                                e.stopPropagation();
                                return false;
                            }
                        }
                        target = target.parentElement;
                    }
                }, true);

                // === VIDEO DOWNLOAD HELPER ===
                document.querySelectorAll('video').forEach(function(video) {
                    video.setAttribute('playsinline', '');
                    video.setAttribute('webkit-playsinline', '');

                    function trackSource() {
                        var src = video.currentSrc || video.src;
                        if (src && src.indexOf('blob:') !== 0) {
                            window._btbfVideoUrl = src;
                        }
                        video.querySelectorAll('source').forEach(function(source) {
                            var sourceSrc = source.src;
                            if (sourceSrc && sourceSrc.indexOf('blob:') !== 0) {
                                window._btbfVideoUrl = sourceSrc;
                            }
                        });
                    }

                    trackSource();
                    video.addEventListener('loadeddata', trackSource);
                    video.addEventListener('playing', trackSource);
                    video.addEventListener('canplay', trackSource);
                });

                // MutationObserver fuer dynamisch geladene Videos
                var observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        mutation.addedNodes.forEach(function(node) {
                            if (node.nodeType === 1) {
                                var video = node.tagName === 'VIDEO' ? node : (node.querySelector ? node.querySelector('video') : null);
                                if (video) {
                                    var src = video.currentSrc || video.src;
                                    if (src && src.indexOf('blob:') !== 0) {
                                        window._btbfVideoUrl = src;
                                    }
                                }
                            }
                        });
                    });
                });
                observer.observe(document.body, { childList: true, subtree: true });

                // === KOMFORT-FUNKTIONEN ===
                var style = document.createElement('style');
                style.textContent =
                    'a, button, input, select { min-height: 44px; min-width: 44px; }' +
                    'html { scroll-behavior: smooth; }' +
                    'body { -webkit-text-size-adjust: 100%; }' +
                    '.cookie-banner, .cookie-consent, .newsletter-popup, ' +
                    '.cookie-notice, #cookie-notice, .gdpr-consent { display: none !important; }';
                document.head.appendChild(style);
            })();
        """.trimIndent()

        webView.evaluateJavascript(comfortScript, null)
    }

    private fun setupButtons() {
        binding.btnHome.setOnClickListener {
            webView.loadUrl(websiteUrl)
        }

        binding.btnRefresh.setOnClickListener {
            webView.reload()
        }

        // Download Button - VERBESSERT mit mehreren Strategien
        binding.btnDownload.setOnClickListener {
            findAndDownloadVideo()
        }

        binding.btnFullscreen.setOnClickListener {
            toggleFullscreen()
        }

        binding.btnBack.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }

        binding.btnFavorites.setOnClickListener {
            showFavoritesDialog()
        }

        binding.btnCatHome.setOnClickListener {
            webView.loadUrl(websiteUrl)
            hideCategoryBar()
        }

        binding.btnCatNew.setOnClickListener {
            webView.loadUrl("${websiteUrl}new")
            hideCategoryBar()
        }

        binding.btnCatTop.setOnClickListener {
            webView.loadUrl("${websiteUrl}top")
            hideCategoryBar()
        }

        binding.btnCatRandom.setOnClickListener {
            webView.loadUrl("${websiteUrl}random")
            hideCategoryBar()
        }

        binding.categoryScroll.postDelayed({
            hideCategoryBar()
        }, 3000)
    }

    /**
     * Findet die Video-URL ueber mehrere Strategien und startet den Download
     */
    private fun findAndDownloadVideo() {
        Toast.makeText(this, "Suche Video-URL...", Toast.LENGTH_SHORT).show()

        // Strategie 1: Per JavaScript die Video-URL aus dem DOM holen
        webView.evaluateJavascript("""
            (function() {
                // Gespeicherte URL aus dem Tracking
                if (window._btbfVideoUrl) return window._btbfVideoUrl;

                // Video-Elemente durchsuchen
                var videos = document.querySelectorAll('video');
                for (var i = 0; i < videos.length; i++) {
                    var src = videos[i].currentSrc || videos[i].src;
                    if (src && src.indexOf('blob:') !== 0) return src;

                    var sources = videos[i].querySelectorAll('source');
                    for (var j = 0; j < sources.length; j++) {
                        if (sources[j].src && sources[j].src.indexOf('blob:') !== 0) {
                            return sources[j].src;
                        }
                    }
                }

                // Links mit Video-Dateiendungen suchen
                var links = document.querySelectorAll('a[href*=".mp4"], a[href*=".webm"], a[href*="download"]');
                for (var k = 0; k < links.length; k++) {
                    return links[k].href;
                }

                // og:video Meta-Tag pruefen
                var ogVideo = document.querySelector('meta[property="og:video"]');
                if (ogVideo) return ogVideo.content;
                var ogVideoUrl = document.querySelector('meta[property="og:video:url"]');
                if (ogVideoUrl) return ogVideoUrl.content;

                return null;
            })();
        """.trimIndent()) { jsResult ->
            val jsUrl = jsResult?.replace("\"", "")?.takeIf { it != "null" && it.isNotEmpty() }

            if (jsUrl != null && !jsUrl.startsWith("blob:")) {
                startDownload(jsUrl, null, "video/mp4", null)
                return@evaluateJavascript
            }

            // Strategie 2: Aus den abgefangenen Netzwerk-Requests
            val capturedUrl = synchronized(capturedVideoUrls) {
                lastCapturedVideoUrl ?: capturedVideoUrls.lastOrNull()
            }

            if (capturedUrl != null) {
                startDownload(capturedUrl, null, "video/mp4", null)
                return@evaluateJavascript
            }

            // Strategie 3: Seiten-Quellcode nach Video-URLs durchsuchen
            val currentUrl = webView.url
            if (currentUrl != null && currentUrl != websiteUrl) {
                webView.evaluateJavascript("""
                    (function() {
                        var scripts = document.querySelectorAll('script');
                        var urlPattern = /https?:\/\/[^\s'"<>]+\.mp4[^\s'"<>]*/gi;
                        for (var i = 0; i < scripts.length; i++) {
                            var text = scripts[i].textContent || scripts[i].innerText;
                            var match = text.match(urlPattern);
                            if (match) return match[0];
                        }

                        var elements = document.querySelectorAll('[data-src*=".mp4"], [data-video*=".mp4"], [data-url*=".mp4"]');
                        for (var j = 0; j < elements.length; j++) {
                            return elements[j].getAttribute('data-src') ||
                                   elements[j].getAttribute('data-video') ||
                                   elements[j].getAttribute('data-url');
                        }

                        return null;
                    })();
                """.trimIndent()) { deepResult ->
                    val deepUrl = deepResult?.replace("\"", "")?.takeIf { it != "null" && it.isNotEmpty() }
                    if (deepUrl != null) {
                        startDownload(deepUrl, null, "video/mp4", null)
                    } else {
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "Kein herunterladbares Video gefunden. Bitte oeffne zuerst ein Video.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } else {
                Toast.makeText(
                    this,
                    "Bitte oeffne zuerst eine Video-Seite.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Startet den Download mit dem Android DownloadManager
     */
    private fun startDownload(
        url: String,
        contentDisposition: String?,
        mimetype: String?,
        userAgent: String?
    ) {
        try {
            val fileName = if (contentDisposition != null) {
                URLUtil.guessFileName(url, contentDisposition, mimetype)
            } else {
                "BTBF_Video_${System.currentTimeMillis()}.mp4"
            }

            val cookieString = CookieManager.getInstance().getCookie(url)

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("BTBF Download")
                setDescription("Lade herunter: $fileName")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setMimeType(mimetype ?: "video/mp4")
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

                // Headers setzen fuer authentifizierten Download
                if (!cookieString.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookieString)
                }
                addRequestHeader("Referer", webView.url ?: websiteUrl)
                addRequestHeader("User-Agent", userAgent ?: webView.settings.userAgentString)
            }

            val downloadManager = getSystemService<DownloadManager>()
            downloadManager?.enqueue(request)

            Toast.makeText(
                this,
                "Download gestartet: $fileName",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Download fehlgeschlagen: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun registerDownloadReceiver() {
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                    Toast.makeText(
                        this@MainActivity,
                        "Download abgeschlossen!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun hideCategoryBar() {
        binding.categoryScroll.animate()
            .translationY(-binding.categoryScroll.height.toFloat())
            .setDuration(300)
            .withEndAction {
                binding.categoryScroll.visibility = View.GONE
            }
            .start()
    }

    private fun showCategoryBar() {
        binding.categoryScroll.visibility = View.VISIBLE
        binding.categoryScroll.animate()
            .translationY(0f)
            .setDuration(300)
            .start()
    }

    private fun toggleFullscreen() {
        if (isFullScreen) {
            webView.evaluateJavascript("document.exitFullscreen();", null)
        } else {
            webView.evaluateJavascript("""
                (function() {
                    var video = document.querySelector('video');
                    if (video) {
                        if (video.requestFullscreen) video.requestFullscreen();
                        else if (video.webkitRequestFullscreen) video.webkitRequestFullscreen();
                        else if (video.webkitEnterFullscreen) video.webkitEnterFullscreen();
                    } else {
                        if (document.documentElement.requestFullscreen) document.documentElement.requestFullscreen();
                    }
                })();
            """.trimIndent(), null)
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(
                android.view.WindowInsets.Type.statusBars() or
                android.view.WindowInsets.Type.navigationBars()
            )
            window.insetsController?.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(
                android.view.WindowInsets.Type.statusBars() or
                android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (binding.categoryScroll.visibility == View.GONE) {
                    navigateToPreviousElement()
                } else {
                    showCategoryBar()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                navigateToNextElement()
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
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                clickFocusedElement()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isFullScreen) {
                    customViewCallback?.onCustomViewHidden()
                    return true
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event?.repeatCount == 0) {
                    binding.root.postDelayed({
                        if (!isMenuLongPress) {
                            showFavoritesDialog()
                        }
                        isMenuLongPress = false
                    }, 500)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                webView.evaluateJavascript("""
                    (function() {
                        var v = document.querySelector('video');
                        if (v) { if (v.paused) v.play(); else v.pause(); }
                    })();
                """.trimIndent(), null)
                return true
            }
            KeyEvent.KEYCODE_S -> {
                scrollWebView("down", 500)
                return true
            }
            KeyEvent.KEYCODE_W -> {
                scrollWebView("up", 500)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private var isMenuLongPress = false

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                isMenuLongPress = true
                addCurrentVideoToFavorites()
                return true
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isFullScreen) {
            customViewCallback?.onCustomViewHidden()
            return
        }
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadReceiver?.let { unregisterReceiver(it) }
        if (customView != null) {
            customViewCallback?.onCustomViewHidden()
            customView = null
            customViewCallback = null
        }
        webView.destroy()
    }

    // JavaScript Interface
    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun downloadVideo(url: String) {
            CoroutineScope(Dispatchers.Main).launch {
                startDownload(url, null, "video/mp4", null)
            }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun reportVideoUrl(url: String) {
            synchronized(capturedVideoUrls) {
                if (!capturedVideoUrls.contains(url)) {
                    capturedVideoUrls.add(url)
                    lastCapturedVideoUrl = url
                }
            }
        }
    }

    // === GESTURE STEUERUNG ===

    inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        private val SWIPE_THRESHOLD = 100
        private val SWIPE_VELOCITY_THRESHOLD = 100

        override fun onDown(e: MotionEvent): Boolean {
            lastYPosition = e.y
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null) return false

            val diffY = e2.y - e1.y
            val diffYAbs = Math.abs(diffY)
            val velocityYAbs = Math.abs(velocityY)

            if (diffY > 0 && diffYAbs > SWIPE_THRESHOLD && velocityYAbs > SWIPE_VELOCITY_THRESHOLD) {
                if (binding.categoryScroll.visibility == View.GONE && !isFullScreen) {
                    showCategoryBar()
                    return true
                }
            }

            if (diffY < 0 && diffYAbs > SWIPE_THRESHOLD && velocityYAbs > SWIPE_VELOCITY_THRESHOLD) {
                if (binding.categoryScroll.visibility == View.VISIBLE) {
                    hideCategoryBar()
                    return true
                }
            }

            return false
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (binding.buttonContainer.visibility == View.GONE && !isFullScreen) {
                binding.buttonContainer.visibility = View.VISIBLE
                binding.buttonContainer.postDelayed({
                    if (!isFullScreen) {
                        binding.buttonContainer.visibility = View.GONE
                    }
                }, 3000)
            }
            return super.onSingleTapUp(e)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    // ==================== FAVORITEN FUNKTIONEN ====================

    private fun showFavoritesDialog() {
        val dialogBinding = DialogFavoritesBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialogBinding.btnCloseFavorites.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.favoritesTabLayout.addOnTabSelectedListener(
            object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                    when (tab?.position) {
                        0 -> {
                            dialogBinding.videosTabContent.visibility = View.VISIBLE
                            dialogBinding.actorsTabContent.visibility = View.GONE
                            loadFavoriteVideos(dialogBinding.recyclerFavoriteVideos, dialogBinding.emptyVideosState)
                        }
                        1 -> {
                            dialogBinding.videosTabContent.visibility = View.GONE
                            dialogBinding.actorsTabContent.visibility = View.VISIBLE
                            loadFavoriteActors(dialogBinding.recyclerFavoriteActors, dialogBinding.emptyActorsState)
                        }
                    }
                }
                override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
                override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            }
        )

        dialogBinding.btnExportFavorites.setOnClickListener {
            exportFavorites()
            Toast.makeText(this, "Favoriten exportiert", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnClearFavorites.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Alle Favoriten loeschen?")
                .setMessage("Moechtest du wirklich alle Favoriten loeschen?")
                .setPositiveButton("Loeschen") { _, _ ->
                    favoritesManager.clearAllFavorites()
                    Toast.makeText(this, "Alle Favoriten geloescht", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }

        dialogBinding.videosTabContent.visibility = View.VISIBLE
        dialogBinding.actorsTabContent.visibility = View.GONE
        loadFavoriteVideos(dialogBinding.recyclerFavoriteVideos, dialogBinding.emptyVideosState)

        dialog.show()
    }

    private fun loadFavoriteVideos(recyclerView: androidx.recyclerview.widget.RecyclerView, emptyView: View) {
        val videos = favoritesManager.getFavoriteVideos()

        if (videos.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
    }

    private fun loadFavoriteActors(recyclerView: androidx.recyclerview.widget.RecyclerView, emptyView: View) {
        val actors = favoritesManager.getFavoriteActors()

        if (actors.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
    }

    private fun exportFavorites() {
        val videos = favoritesManager.getFavoriteVideos()
        val actors = favoritesManager.getFavoriteActors()

        val json = StringBuilder().apply {
            appendLine("{")
            appendLine("  \"videos\": [")
            videos.forEachIndexed { index, video ->
                appendLine("    {\"id\": \"${video.id}\", \"title\": \"${video.title}\"}${if (index < videos.lastIndex) "," else ""}")
            }
            appendLine("  ],")
            appendLine("  \"actors\": [")
            actors.forEachIndexed { index, actor ->
                appendLine("    {\"id\": \"${actor.id}\", \"name\": \"${actor.name}\"}${if (index < actors.lastIndex) "," else ""}")
            }
            appendLine("  ]")
            appendLine("}")
        }.toString()

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("BTBF Favorites", json)
        clipboard.setPrimaryClip(clip)
    }

    /**
     * Aktuelles Video zu Favoriten hinzufuegen - MIT FIX fuer JSON Parsing
     */
    fun addCurrentVideoToFavorites() {
        webView.evaluateJavascript("""
            (function() {
                var title = document.querySelector('h1') ? document.querySelector('h1').textContent : document.title;
                var thumbnail = '';
                var video = document.querySelector('video');
                if (video && video.poster) thumbnail = video.poster;
                var ogImage = document.querySelector('meta[property="og:image"]');
                if (!thumbnail && ogImage) thumbnail = ogImage.content;
                return JSON.stringify({title: title, thumbnail: thumbnail});
            })();
        """.trimIndent()) { result ->
            if (result != null && result != "null") {
                try {
                    // evaluateJavascript liefert den String in Anfuehrungszeichen
                    // und escaped innere Anfuehrungszeichen
                    val cleanResult = result
                        .trim()
                        .removeSurrounding("\"")
                        .replace("\\\"", "\"")
                        .replace("\\\\/", "/")

                    val json = org.json.JSONObject(cleanResult)
                    val title = json.optString("title", "Unbekanntes Video")
                    val thumbnail = json.optString("thumbnail", "")
                    val videoId = webView.url?.hashCode()?.toString() ?: System.currentTimeMillis().toString()

                    favoritesManager.addFavoriteVideo(videoId, title, thumbnail)
                    Toast.makeText(this, "Zu Favoriten hinzugefuegt!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Fehler beim Speichern: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun isCurrentVideoFavorite(): Boolean {
        val videoId = webView.url?.hashCode()?.toString() ?: return false
        return favoritesManager.isVideoFavorite(videoId)
    }

    fun removeCurrentVideoFromFavorites() {
        val videoId = webView.url?.hashCode()?.toString() ?: return
        favoritesManager.removeFavoriteVideo(videoId)
        Toast.makeText(this, "Aus Favoriten entfernt", Toast.LENGTH_SHORT).show()
    }

    // ==================== MOUSE MODE FUER FIRETV ====================

    private fun enableMouseMode() {
        val mouseScript = """
            (function() {
                if (window._btbfMouseMode) return;
                window._btbfMouseMode = true;

                var style = document.createElement('style');
                style.textContent =
                    '* { cursor: pointer !important; }' +
                    ':focus { outline: 3px solid #FFD700 !important; outline-offset: 3px !important; ' +
                    'box-shadow: 0 0 10px rgba(255, 215, 0, 0.5) !important; }';
                document.head.appendChild(style);

                document.querySelectorAll('a, button, input, select, [onclick], [role="button"], [tabindex]').forEach(function(el) {
                    if (!el.getAttribute('tabindex')) {
                        el.setAttribute('tabindex', '0');
                    }
                });
            })();
        """.trimIndent()

        webView.evaluateJavascript(mouseScript, null)
    }

    private fun scrollWebView(direction: String, amount: Int = 300) {
        val scrollScript = when (direction) {
            "up" -> "window.scrollBy(0, -$amount);"
            "down" -> "window.scrollBy(0, $amount);"
            "left" -> "window.scrollBy(-$amount, 0);"
            "right" -> "window.scrollBy($amount, 0);"
            else -> ""
        }
        webView.evaluateJavascript(scrollScript, null)
    }

    private fun navigateToNextElement() {
        webView.evaluateJavascript("""
            (function() {
                var elements = Array.from(document.querySelectorAll('a, button, [onclick], [role="button"], [tabindex]'));
                var current = document.activeElement;
                var index = elements.indexOf(current);

                if (index >= 0 && index < elements.length - 1) {
                    elements[index + 1].focus();
                    elements[index + 1].scrollIntoView({behavior: 'smooth', block: 'center'});
                } else if (elements.length > 0) {
                    elements[0].focus();
                    elements[0].scrollIntoView({behavior: 'smooth', block: 'center'});
                }
            })();
        """.trimIndent(), null)
    }

    private fun navigateToPreviousElement() {
        webView.evaluateJavascript("""
            (function() {
                var elements = Array.from(document.querySelectorAll('a, button, [onclick], [role="button"], [tabindex]'));
                var current = document.activeElement;
                var index = elements.indexOf(current);

                if (index > 0) {
                    elements[index - 1].focus();
                    elements[index - 1].scrollIntoView({behavior: 'smooth', block: 'center'});
                }
            })();
        """.trimIndent(), null)
    }

    private fun clickFocusedElement() {
        webView.evaluateJavascript("""
            (function() {
                if (document.activeElement) {
                    document.activeElement.click();
                }
            })();
        """.trimIndent(), null)
    }
}
