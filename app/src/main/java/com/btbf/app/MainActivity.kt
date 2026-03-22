package com.btbf.app

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.btbf.app.databinding.ActivityMainBinding
import com.btbf.app.databinding.DialogFavoritesBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private var customView: View? = null
    private var customViewCallback: (() -> Unit)? = null
    private var isFullScreen = false

    private val websiteUrl = "https://de.borntobefuck.com/"

    private val storagePermissionCode = 100

    // Favoriten Manager
    private lateinit var favoritesManager: FavoritesManager

    // Gesture Detector für Swipe-Steuerung
    private lateinit var gestureDetector: GestureDetector
    private var lastYPosition = 0f

    // Maus-Modus Zustand
    private var isMouseModeActive = false
    private val mouseStepSize = 30 // Pixel pro D-Pad Druck

    // Bottom-Bar Zustand
    private var isBottomBarFocused = false

    // Scroll-Position Speicher (URL -> ScrollY), max 50 Einträge
    private val scrollPositionMap = object : LinkedHashMap<String, Int>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 50
    }

    // Kachelgröße (Prozent: 0=Standard/aus, 20-100 = Spaltenbreite)
    private var tileScalePercent = 0

    // Einstellungen
    private lateinit var prefs: SharedPreferences

    // Erlaubte Domains - nur diese Seiten werden im Verlauf behalten
    private val allowedDomains = listOf(
        "borntobefuck.com",
        "borntobefucked.com",
        "btbf.com"
    )

    // Navigations-Verlauf (nur echte Seiten, keine Redirects)
    private val pageHistory = mutableListOf<String>()
    private var isNavigatingBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Statusbar für immersive Erfahrung ausblenden
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Einstellungen laden
        prefs = getSharedPreferences("btbf_settings", Context.MODE_PRIVATE)
        tileScalePercent = prefs.getInt("tile_scale", 0)

        // Favoriten Manager initialisieren
        favoritesManager = FavoritesManager(this)

        // Gesture Detector initialisieren
        gestureDetector = GestureDetector(this, GestureListener())

        setupWebView()
        setupButtons()
        setupWebViewContextMenu()

        // Berechtigungen anfordern
        requestPermissions()
    }
    
    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO
                    ),
                    storagePermissionCode
                )
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ),
                    storagePermissionCode
                )
            }
        }
    }

    private fun setupWebView() {
        webView = binding.webView

        // WebView Einstellungen - Optimiert für beste Erfahrung
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
            // Wichtig für bessere Navigation
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
        }

        // WebViewClient für Seiten-Navigation
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                val host = request.url.host ?: ""

                // Fremde Domains blockieren (Redirects, Tracker, Werbung)
                val isSiteDomain = allowedDomains.any { host.contains(it, ignoreCase = true) }
                if (!isSiteDomain && host.isNotEmpty()) {
                    // Externe URL blockieren - nicht im WebView öffnen
                    return true
                }

                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE

                // Echte Seiten-URL zum eigenen Verlauf hinzufügen
                url?.let {
                    if (!isNavigatingBack) {
                        val host = Uri.parse(it).host ?: ""
                        if (allowedDomains.any { d -> host.contains(d, ignoreCase = true) }) {
                            if (pageHistory.isEmpty() || pageHistory.last() != it) {
                                pageHistory.add(it)
                            }
                        }
                    }
                    isNavigatingBack = false
                }

                // Verbesserter AdBlocker + Komfort-Features
                injectComfortScripts()

                // Kachelgröße anwenden
                applyTileScale()

                // Maus-Cursor injizieren (immer bereit, aber nur sichtbar wenn aktiv)
                injectVirtualCursor()
                // Wenn Maus-Modus aktiv, Cursor sofort zeigen nach Seitennavigation
                if (isMouseModeActive) {
                    showCursorAtPosition()
                }

                // Scroll-Position wiederherstellen wenn vorhanden
                url?.let { restoreScrollPosition(it) }
            }
        }
        
        // WebChromeClient für Video-Player und JavaScript Alerts
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(
                view: View,
                callback: android.webkit.WebChromeClient.CustomViewCallback
            ) {
                customView = view
                customViewCallback = { callback.onCustomViewHidden() }
                isFullScreen = true

                // System UI ausblenden für Fullscreen
                hideSystemUI()

                // Custom View zum Container hinzufügen
                binding.fullscreenContainer.apply {
                    visibility = View.VISIBLE
                    addView(view)
                }

                // WebView ausblenden
                binding.webViewContainer.visibility = View.GONE
                binding.buttonContainer.visibility = View.GONE
            }

            override fun onHideCustomView() {
                customView?.let {
                    binding.fullscreenContainer.removeView(it)
                    customView = null
                }
                customViewCallback?.invoke()
                customViewCallback = null
                isFullScreen = false

                // System UI wieder anzeigen
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
        
        // Download Listener für Videos
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            downloadVideo(url, contentDisposition, mimetype)
        }
        
        // JavaScript Interface für Download-Funktion
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidInterface")
        
        // Website laden
        webView.loadUrl(websiteUrl)
    }
    
    private fun injectComfortScripts() {
        // Umfassender AdBlocker + Komfort-Verbesserungen
        val comfortScript = """
            (function() {
                console.log('BTBF Comfort Mode aktiv');
                
                // === WERBEBLOCKER ===
                const adSelectors = [
                    // Allgemeine Werbung
                    'iframe[src*="ad"]',
                    'iframe[src*="ads"]',
                    'iframe[src*="banner"]',
                    'div[class*="ad-"]',
                    'div[class*="ad_"]',
                    'div[id*="ad-"]',
                    'div[id*="ad_"]',
                    '.advertisement',
                    '.advertisement-wrapper',
                    '.ads',
                    '.ads-wrapper',
                    '#ads',
                    '#ads-wrapper',
                    '[class*="sponsor"]',
                    '[id*="sponsor"]',
                    // Popups und Overlays
                    '.popup',
                    '.modal-ad',
                    '.ad-overlay',
                    // Video Ads
                    'div[class*="video-ad"]',
                    'div[id*="video-ad"]',
                    '.pre-roll-ad',
                    '.mid-roll-ad'
                ];
                
                adSelectors.forEach(selector => {
                    try {
                        document.querySelectorAll(selector).forEach(el => {
                            el.style.display = 'none';
                            el.style.visibility = 'hidden';
                            el.remove();
                        });
                    } catch(e) {}
                });
                
                // Popups blockieren
                window.open = function() { return null; };
                
                // === KOMFORT-FUNKTIONEN ===
                
                // Auto-Vollbild für Videos verbessern
                document.querySelectorAll('video').forEach(video => {
                    video.setAttribute('playsinline', '');
                    video.setAttribute('webkit-playsinline', '');
                    
                    // Video-Controls immer sichtbar bei Berührung
                    video.addEventListener('click', function() {
                        if (video.paused) {
                            video.play();
                        } else {
                            video.pause();
                        }
                    });
                });
                
                // Touch-Optimierung für bessere Bedienung
                const style = document.createElement('style');
                style.textContent = `
                    /* Größere Touch-Ziele für bessere Bedienung */
                    a, button, input, select {
                        min-height: 44px;
                        min-width: 44px;
                    }
                    
                    /* Smooth Scrolling */
                    html {
                        scroll-behavior: smooth;
                    }
                    
                    /* Bessere Lesbarkeit */
                    body {
                        -webkit-text-size-adjust: 100%;
                    }
                    
                    /* Verstecke störende Elemente */
                    .cookie-banner,
                    .cookie-consent,
                    .newsletter-popup {
                        display: none !important;
                    }
                `;
                document.head.appendChild(style);
                
                console.log('Comfort Mode: Alle Optimierungen aktiv');
            })();
        """.trimIndent()

        webView.evaluateJavascript(comfortScript, null)
    }
    
    private fun setupButtons() {
        // Bottom-Bar Focus-Tracking: Wenn Fokus die Bar verlässt, zurücksetzen
        val barButtons = listOf(
            binding.btnHome, binding.btnBack, binding.btnRefresh, binding.btnSearch,
            binding.btnDownload, binding.btnFullscreen, binding.btnFavorites, binding.btnSettings
        )
        barButtons.forEach { btn ->
            btn.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    isBottomBarFocused = true
                }
            }
        }

        // === HAUPT-NAVIGATION ===

        // Home Button
        binding.btnHome.setOnClickListener {
            isBottomBarFocused = false
            webView.loadUrl(websiteUrl)
        }

        // Refresh Button
        binding.btnRefresh.setOnClickListener {
            webView.reload()
        }

        // Download Button
        binding.btnDownload.setOnClickListener {
            getCurrentVideoUrl { videoUrl ->
                if (videoUrl != null) {
                    downloadVideo(videoUrl, null, "video/mp4")
                    Toast.makeText(this, "Download gestartet", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Kein Video gefunden", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Fullscreen Toggle
        binding.btnFullscreen.setOnClickListener {
            toggleFullscreen()
        }

        // Back Button im Button Container
        binding.btnBack.setOnClickListener {
            isBottomBarFocused = false
            navigateBack()
        }
        
        // Favorites Button
        binding.btnFavorites.setOnClickListener {
            showFavoritesDialog()
        }

        // Settings Button
        binding.btnSettings.setOnClickListener {
            showSettingsMenu()
        }

        // Search Button
        binding.btnSearch.setOnClickListener {
            showSearchDialog()
        }

        // === KATEGORIE SCHNELLZUGRIFF ===
        
        // Home Kategorie
        binding.btnCatHome.setOnClickListener {
            webView.loadUrl(websiteUrl)
            hideCategoryBar()
        }
        
        // Neueste Videos
        binding.btnCatNew.setOnClickListener {
            webView.loadUrl("${websiteUrl}new")
            hideCategoryBar()
        }
        
        // Top bewertete Videos
        binding.btnCatTop.setOnClickListener {
            webView.loadUrl("${websiteUrl}top")
            hideCategoryBar()
        }
        
        // Zufälliges Video
        binding.btnCatRandom.setOnClickListener {
            webView.loadUrl("${websiteUrl}random")
            hideCategoryBar()
        }

        // Suche in Kategorien-Leiste
        binding.btnCatSearch.setOnClickListener {
            hideCategoryBar()
            showSearchDialog()
        }

        // Kategorien-Leiste nach 3 Sekunden ausblenden
        binding.categoryScroll.postDelayed({
            hideCategoryBar()
        }, 3000)
    }
    
    private fun focusBottomBar() {
        isBottomBarFocused = true
        binding.buttonContainer.visibility = View.VISIBLE
        // Ersten Button fokussieren
        binding.btnHome.requestFocus()
        Toast.makeText(this, "Leiste aktiv - Hoch um zurueck", Toast.LENGTH_SHORT).show()
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
    
    private fun getCurrentVideoUrl(callback: (String?) -> Unit) {
        webView.evaluateJavascript("""
            (function() {
                const videos = document.querySelectorAll('video');
                if (videos.length > 0) {
                    return videos[0].currentSrc || videos[0].src;
                }
                const sources = document.querySelectorAll('source[src*="video"]');
                if (sources.length > 0) {
                    return sources[0].src;
                }
                return null;
            })();
        """.trimIndent()) { result ->
            callback(result?.replace("\"", "")?.takeIf { it != "null" })
        }
    }
    
    private fun downloadVideo(
        url: String,
        contentDisposition: String?,
        mimetype: String?
    ) {
        try {
            val fileName = "BTBF_Video_${System.currentTimeMillis()}.mp4"
            
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("BTBF Video Download")
                .setDescription("Lade Video herunter...")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setMimeType(mimetype ?: "video/mp4")
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName
                )
            
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
    
    private fun toggleFullscreen() {
        if (isFullScreen) {
            webView.evaluateJavascript("document.exitFullscreen();", null)
        } else {
            webView.evaluateJavascript(
                "document.documentElement.requestFullscreen();",
                null
            )
        }
    }
    
    private fun hideSystemUI() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showSystemUI() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // FireTV Fernbedienung Navigation
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isMouseModeActive) {
                    moveMouseCursor(0, -mouseStepSize)
                    return true
                }
                if (isBottomBarFocused) {
                    // Aus Bottom-Bar zurück zum WebView
                    isBottomBarFocused = false
                    webView.requestFocus()
                    return true
                }
                if (binding.categoryScroll.visibility == View.GONE) {
                    navigateToPreviousElement()
                } else {
                    showCategoryBar()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isMouseModeActive) {
                    moveMouseCursor(0, mouseStepSize)
                    return true
                }
                if (isBottomBarFocused) {
                    // In Bottom-Bar: native Fokus-Navigation zulassen
                    return super.onKeyDown(keyCode, event)
                }
                navigateToNextElement()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isMouseModeActive) {
                    moveMouseCursor(-mouseStepSize, 0)
                    return true
                }
                if (isBottomBarFocused) {
                    // In Bottom-Bar: native Fokus-Navigation zulassen
                    return super.onKeyDown(keyCode, event)
                }
                scrollWebView("left", 200)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isMouseModeActive) {
                    moveMouseCursor(mouseStepSize, 0)
                    return true
                }
                if (isBottomBarFocused) {
                    // In Bottom-Bar: native Fokus-Navigation zulassen
                    return super.onKeyDown(keyCode, event)
                }
                scrollWebView("right", 200)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (isMouseModeActive) {
                    clickAtMouseCursor()
                    return true
                }
                if (isBottomBarFocused) {
                    // In Bottom-Bar: native Klick zulassen
                    return super.onKeyDown(keyCode, event)
                }
                clickFocusedElement()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isMouseModeActive) {
                    toggleMouseMode()
                    return true
                }
                if (isFullScreen) {
                    webView.evaluateJavascript("document.exitFullscreen();", null)
                    return true
                }
                if (navigateBack()) {
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event?.repeatCount == 0) {
                    binding.root.postDelayed({
                        if (!isMenuLongPress) {
                            showSettingsMenu()
                        }
                        isMenuLongPress = false
                    }, 500)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (isFullScreen) {
                    webView.evaluateJavascript(
                        "document.querySelector('video')?.paused ? document.querySelector('video')?.play() : document.querySelector('video')?.pause()",
                        null
                    )
                    return true
                }
                // Nicht im Fullscreen: Bottom-Bar fokussieren
                focusBottomBar()
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
            KeyEvent.KEYCODE_M -> {
                // M-Taste = Maus-Modus umschalten
                toggleMouseMode()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    private var isMenuLongPress = false
    
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                // Long-Press - Video zu Favoriten hinzufügen
                isMenuLongPress = true
                addCurrentVideoToFavorites()
                return true
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onBackPressed() {
        if (isMouseModeActive) {
            toggleMouseMode()
            return
        }
        if (isFullScreen) {
            webView.evaluateJavascript("document.exitFullscreen();", null)
            return
        }
        if (!navigateBack()) {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (customView != null) {
            customViewCallback?.invoke()
            customView = null
            customViewCallback = null
        }
        webView.destroy()
    }
    
    // JavaScript Interface für Website-Kommunikation
    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun downloadVideo(url: String) {
            lifecycleScope.launch {
                downloadVideo(url, null, "video/mp4")
            }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            lifecycleScope.launch {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // === KONTEXTMENÜ (LONG-PRESS) ===

    private fun setupWebViewContextMenu() {
        webView.setOnLongClickListener {
            val hitResult = webView.hitTestResult
            val url = hitResult.extra

            when (hitResult.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                WebView.HitTestResult.IMAGE_TYPE -> {
                    showVideoContextMenu(url)
                    true
                }
                else -> {
                    // Prüfe ob ein Video-Link unter dem Cursor ist
                    if (isMouseModeActive) {
                        showMouseCursorContextMenu()
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    private fun showVideoContextMenu(url: String?) {
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // Play/Öffnen
        items.add("Abspielen")
        actions.add {
            if (url != null) {
                webView.loadUrl(url)
            }
        }

        // In neuem Kontext öffnen
        if (url != null) {
            items.add("Link öffnen")
            actions.add { webView.loadUrl(url) }
        }

        // Zu Favoriten
        items.add("Zu Favoriten hinzufügen")
        actions.add { addCurrentVideoToFavorites() }

        // Download
        items.add("Video herunterladen")
        actions.add {
            getCurrentVideoUrl { videoUrl ->
                if (videoUrl != null) {
                    downloadVideo(videoUrl, null, "video/mp4")
                } else if (url != null) {
                    downloadVideo(url, null, "video/mp4")
                } else {
                    Toast.makeText(this, "Kein Video gefunden", Toast.LENGTH_SHORT).show()
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Video")
            .setItems(items.toTypedArray()) { _, which ->
                actions[which]()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showMouseCursorContextMenu() {
        // Element unter Cursor finden und Kontextmenü zeigen
        webView.evaluateJavascript("""
            (function() {
                var el = document.elementFromPoint(${cssCursorX}, ${cssCursorY});
                if (el) {
                    var link = el.closest('a');
                    if (link) return link.href;
                }
                return null;
            })();
        """.trimIndent()) { result ->
            val linkUrl = result?.replace("\"", "")?.takeIf { it != "null" }
            showVideoContextMenu(linkUrl)
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
            
            // Swipe nach unten - Kategorien-Leiste anzeigen
            if (diffY > 0 && diffYAbs > SWIPE_THRESHOLD && velocityYAbs > SWIPE_VELOCITY_THRESHOLD) {
                if (binding.categoryScroll.visibility == View.GONE && !isFullScreen) {
                    showCategoryBar()
                    return true
                }
            }
            
            // Swipe nach oben - Kategorien-Leiste ausblenden
            if (diffY < 0 && diffYAbs > SWIPE_THRESHOLD && velocityYAbs > SWIPE_VELOCITY_THRESHOLD) {
                if (binding.categoryScroll.visibility == View.VISIBLE) {
                    hideCategoryBar()
                    return true
                }
            }
            
            return false
        }
        
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Bottom-Bar bleibt immer sichtbar (außer im Fullscreen)
            return super.onSingleTapUp(e)
        }
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }
    
    // ==================== FAVORITEN FUNKTIONEN ====================
    
    /**
     * Favoriten-Dialog anzeigen
     */
    private fun showFavoritesDialog() {
        val dialogBinding = DialogFavoritesBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()
        
        // Close Button
        dialogBinding.btnCloseFavorites.setOnClickListener {
            dialog.dismiss()
        }
        
        // Tab Switcher
        dialogBinding.favoritesTabLayout.addOnTabSelectedListener(
            object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                    when (tab?.position) {
                        0 -> {
                            dialogBinding.videosTabContent.visibility = View.VISIBLE
                            dialogBinding.actorsTabContent.visibility = View.GONE
                            loadFavoriteVideos(dialogBinding.recyclerFavoriteVideos, dialogBinding.emptyVideosState, dialog)
                        }
                        1 -> {
                            dialogBinding.videosTabContent.visibility = View.GONE
                            dialogBinding.actorsTabContent.visibility = View.VISIBLE
                            loadFavoriteActors(dialogBinding.recyclerFavoriteActors, dialogBinding.emptyActorsState, dialog)
                        }
                    }
                }
                override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
                override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            }
        )
        
        // Export Button
        dialogBinding.btnExportFavorites.setOnClickListener {
            exportFavorites()
            Toast.makeText(this, "Favoriten exportiert", Toast.LENGTH_SHORT).show()
        }
        
        // Clear All Button
        dialogBinding.btnClearFavorites.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Alle Favoriten löschen?")
                .setMessage("Möchtest du wirklich alle Favoriten löschen?")
                .setPositiveButton("Löschen") { _, _ ->
                    favoritesManager.clearAllFavorites()
                    Toast.makeText(this, "Alle Favoriten gelöscht", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
        
        // Ersten Tab laden
        dialogBinding.videosTabContent.visibility = View.VISIBLE
        dialogBinding.actorsTabContent.visibility = View.GONE
        loadFavoriteVideos(dialogBinding.recyclerFavoriteVideos, dialogBinding.emptyVideosState, dialog)

        dialog.show()
    }
    
    /**
     * Favoriten-Videos laden
     */
    private fun loadFavoriteVideos(
        recyclerView: androidx.recyclerview.widget.RecyclerView,
        emptyView: View,
        dialog: AlertDialog
    ) {
        val videos = favoritesManager.getFavoriteVideos()

        if (videos.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE

            if (recyclerView.layoutManager == null) {
                recyclerView.layoutManager = LinearLayoutManager(this)
            }

            val adapter = recyclerView.adapter as? FavoriteVideoAdapter
            if (adapter != null) {
                adapter.updateVideos(videos)
            } else {
                recyclerView.adapter = FavoriteVideoAdapter(
                    videos,
                    onPlay = { video ->
                        val url = video.id
                        if (url.startsWith("http")) {
                            dialog.dismiss()
                            webView.loadUrl(url)
                        }
                    },
                    onRemove = { video ->
                        favoritesManager.removeFavoriteVideo(video.id)
                        loadFavoriteVideos(recyclerView, emptyView, dialog)
                    }
                )
            }
        }
    }

    private fun loadFavoriteActors(
        recyclerView: androidx.recyclerview.widget.RecyclerView,
        emptyView: View,
        dialog: AlertDialog
    ) {
        val actors = favoritesManager.getFavoriteActors()

        if (actors.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE

            if (recyclerView.layoutManager == null) {
                recyclerView.layoutManager = LinearLayoutManager(this)
            }

            val adapter = recyclerView.adapter as? FavoriteActorAdapter
            if (adapter != null) {
                adapter.updateActors(actors)
            } else {
                recyclerView.adapter = FavoriteActorAdapter(
                    actors,
                    onClick = { actor ->
                        dialog.dismiss()
                    },
                    onRemove = { actor ->
                        favoritesManager.removeFavoriteActor(actor.id)
                        loadFavoriteActors(recyclerView, emptyView, dialog)
                    }
                )
            }
        }
    }
    
    /**
     * Favoriten exportieren (JSON)
     */
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
        
        // In Clipboard kopieren
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("BTBF Favorites", json)
        clipboard.setPrimaryClip(clip)
    }
    
    /**
     * Aktuelles Video zu Favoriten hinzufügen
     */
    fun addCurrentVideoToFavorites() {
        webView.evaluateJavascript("""
            (function() {
                const title = document.querySelector('h1')?.textContent || document.title;
                const thumbnail = document.querySelector('video')?.poster ||
                                document.querySelector('meta[property="og:image"]')?.content || '';
                return JSON.stringify({title: title, thumbnail: thumbnail});
            })();
        """.trimIndent()) { result ->
            if (result != null && result != "null") {
                try {
                    val cleaned = result.trim().let {
                        if (it.startsWith("\"") && it.endsWith("\""))
                            it.substring(1, it.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                        else it
                    }
                    val json = org.json.JSONObject(cleaned)
                    val title = json.optString("title", "Unbekanntes Video")
                    val thumbnail = json.optString("thumbnail", "")
                    val videoUrl = webView.url ?: return@evaluateJavascript

                    favoritesManager.addFavoriteVideo(videoUrl, title, thumbnail)
                    Toast.makeText(this, "Zu Favoriten hinzugefuegt", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * Prüfen ob aktuelles Video Favorit ist
     */
    fun isCurrentVideoFavorite(): Boolean {
        val videoId = webView.url ?: return false
        return favoritesManager.isVideoFavorite(videoId)
    }
    
    /**
     * Video aus Favoriten entfernen
     */
    fun removeCurrentVideoFromFavorites() {
        val videoId = webView.url ?: return
        favoritesManager.removeFavoriteVideo(videoId)
        Toast.makeText(this, "Aus Favoriten entfernt", Toast.LENGTH_SHORT).show()
    }
    
    // ==================== VIRTUAL MOUSE CURSOR ====================

    private fun injectVirtualCursor() {
        val cursorScript = """
            (function() {
                // Alten Cursor entfernen falls vorhanden (nach Seitennavigation)
                var old = document.getElementById('btbf-cursor');
                if (old) old.remove();

                const cursor = document.createElement('div');
                cursor.id = 'btbf-cursor';
                cursor.innerHTML = '<div style="position:absolute;top:50%;left:50%;width:8px;height:8px;background:#fff;border-radius:50%;transform:translate(-50%,-50%);"></div>';
                cursor.style.cssText = `
                    position: fixed;
                    width: 40px;
                    height: 40px;
                    border-radius: 50%;
                    background: rgba(229, 9, 20, 0.9);
                    border: 4px solid #FFD700;
                    box-shadow: 0 0 20px rgba(255, 215, 0, 0.8), 0 0 40px rgba(229, 9, 20, 0.6), inset 0 0 10px rgba(255,255,255,0.3);
                    z-index: 2147483647;
                    pointer-events: none;
                    display: none;
                    transform: translate(-50%, -50%);
                    transition: left 0.05s linear, top 0.05s linear;
                `;
                document.body.appendChild(cursor);

                // Fokus-Styling für Element-Navigation
                var oldStyle = document.getElementById('btbf-nav-style');
                if (oldStyle) oldStyle.remove();
                const style = document.createElement('style');
                style.id = 'btbf-nav-style';
                style.textContent = `
                    .btbf-cursor-hover {
                        outline: 3px solid #FFD700 !important;
                        outline-offset: 2px !important;
                        box-shadow: 0 0 10px rgba(255, 215, 0, 0.5) !important;
                    }
                    :focus {
                        outline: 3px solid #FFD700 !important;
                        outline-offset: 3px !important;
                        box-shadow: 0 0 10px rgba(255, 215, 0, 0.5) !important;
                    }
                `;
                document.head.appendChild(style);

                // Alle klickbaren Elemente fokussierbar machen
                document.querySelectorAll('a, button, input, select, [onclick], [role="button"]').forEach(el => {
                    if (!el.getAttribute('tabindex')) {
                        el.setAttribute('tabindex', '0');
                    }
                });
            })();
        """.trimIndent()
        webView.evaluateJavascript(cursorScript, null)
    }

    // CSS-Pixel Koordinaten des Cursors (nicht Android-Pixel!)
    private var cssCursorX = 0f
    private var cssCursorY = 0f

    private fun toggleMouseMode() {
        isMouseModeActive = !isMouseModeActive
        if (isMouseModeActive) {
            injectVirtualCursor()
            // CSS-Viewport-Mitte berechnen
            webView.evaluateJavascript("JSON.stringify({w: window.innerWidth, h: window.innerHeight})") { result ->
                try {
                    val cleaned = result?.replace("\\\"", "\"")?.trim('"') ?: ""
                    val json = org.json.JSONObject(cleaned)
                    cssCursorX = json.getInt("w") / 2f
                    cssCursorY = json.getInt("h") / 2f
                } catch (_: Exception) {
                    cssCursorX = 400f
                    cssCursorY = 300f
                }
                showCursorAtPosition()
            }
            Toast.makeText(this, "Maus-Modus AN - D-Pad bewegt Cursor", Toast.LENGTH_SHORT).show()
        } else {
            webView.evaluateJavascript("""
                (function() {
                    var c = document.getElementById('btbf-cursor');
                    if (c) c.style.display = 'none';
                    document.querySelectorAll('.btbf-cursor-hover').forEach(el => el.classList.remove('btbf-cursor-hover'));
                })();
            """.trimIndent(), null)
            Toast.makeText(this, "Maus-Modus AUS", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCursorAtPosition() {
        val x = cssCursorX
        val y = cssCursorY
        webView.evaluateJavascript("""
            (function() {
                var c = document.getElementById('btbf-cursor');
                if (c) {
                    c.style.display = 'block';
                    c.style.left = '${x}px';
                    c.style.top = '${y}px';
                }
            })();
        """.trimIndent(), null)
    }

    private fun moveMouseCursor(dx: Int, dy: Int) {
        // Bewege in CSS-Pixel (step = 15 CSS-Pixel)
        val cssStep = 15f
        cssCursorX = (cssCursorX + dx / mouseStepSize.toFloat() * cssStep).coerceAtLeast(0f)
        cssCursorY = (cssCursorY + dy / mouseStepSize.toFloat() * cssStep).coerceAtLeast(0f)

        updateMouseCursorPositionAndScroll(dx, dy)
    }

    private fun updateMouseCursorPositionAndScroll(dx: Int, dy: Int) {
        val x = cssCursorX
        val y = cssCursorY
        val scrollMargin = 40
        val scrollAmount = 100
        val script = """
            (function() {
                var c = document.getElementById('btbf-cursor');
                if (!c) return;
                var maxX = window.innerWidth - 10;
                var maxY = window.innerHeight - 10;
                var cx = Math.min(${x}, maxX);
                var cy = Math.min(${y}, maxY);
                c.style.left = cx + 'px';
                c.style.top = cy + 'px';

                // Am Rand scrollen
                if (cy <= ${scrollMargin} && ${dy} < 0) window.scrollBy(0, -${scrollAmount});
                if (cy >= maxY - ${scrollMargin} && ${dy} > 0) window.scrollBy(0, ${scrollAmount});
                if (cx <= ${scrollMargin} && ${dx} < 0) window.scrollBy(-${scrollAmount}, 0);
                if (cx >= maxX - ${scrollMargin} && ${dx} > 0) window.scrollBy(${scrollAmount}, 0);

                // Highlight Element unter Cursor
                document.querySelectorAll('.btbf-cursor-hover').forEach(el => el.classList.remove('btbf-cursor-hover'));
                var el = document.elementFromPoint(cx, cy);
                if (el) {
                    var clickable = el.closest('a, button, [onclick], [role="button"], input, select, [tabindex]');
                    if (clickable) clickable.classList.add('btbf-cursor-hover');
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun clickAtMouseCursor() {
        val x = cssCursorX
        val y = cssCursorY
        val script = """
            (function() {
                var maxX = window.innerWidth - 10;
                var maxY = window.innerHeight - 10;
                var cx = Math.min(${x}, maxX);
                var cy = Math.min(${y}, maxY);
                var el = document.elementFromPoint(cx, cy);
                if (el) {
                    var clickable = el.closest('a, button, [onclick], [role="button"], input, select, [tabindex]');
                    if (clickable) {
                        clickable.click();
                    } else {
                        el.click();
                    }
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    // ==================== SMART BACK NAVIGATION ====================

    /**
     * Navigiert zurück zur vorherigen echten Seite.
     * Benutzt eigenen Verlauf um Redirect-Schleifen zu umgehen.
     * @return true wenn Navigation stattfand
     */
    private fun navigateBack(): Boolean {
        saveScrollPosition()

        // Eigenen Verlauf nutzen wenn vorhanden
        if (pageHistory.size >= 2) {
            // Aktuelle Seite entfernen
            pageHistory.removeLastOrNull()
            // Vorherige Seite laden
            val previousUrl = pageHistory.lastOrNull()
            if (previousUrl != null) {
                isNavigatingBack = true
                webView.loadUrl(previousUrl)
                return true
            }
        }

        // Fallback: WebView-eigenen Verlauf nutzen
        if (webView.canGoBack()) {
            isNavigatingBack = true
            // Mehrere Schritte zurück springen um Redirects zu überspringen
            val backList = webView.copyBackForwardList()
            val currentIndex = backList.currentIndex
            for (i in currentIndex - 1 downTo 0) {
                val item = backList.getItemAtIndex(i)
                val url = item.url
                val host = Uri.parse(url).host ?: ""
                if (allowedDomains.any { host.contains(it, ignoreCase = true) }) {
                    webView.goBackOrForward(i - currentIndex)
                    return true
                }
            }
            // Kein erlaubter Eintrag gefunden, einfach zurück
            webView.goBack()
            return true
        }

        return false
    }

    // ==================== SCROLL-POSITION SPEICHER ====================

    private fun saveScrollPosition() {
        val url = webView.url ?: return
        webView.evaluateJavascript("window.scrollY") { result ->
            try {
                val scrollY = result.trim().replace("\"", "").toIntOrNull() ?: 0
                if (scrollY > 0) {
                    scrollPositionMap[url] = scrollY
                }
            } catch (_: Exception) {}
        }
    }

    private fun restoreScrollPosition(url: String) {
        val savedY = scrollPositionMap[url] ?: return
        // Kurz warten bis Seite gerendert ist
        webView.postDelayed({
            webView.evaluateJavascript("window.scrollTo(0, $savedY);", null)
        }, 300)
        scrollPositionMap.remove(url)
    }

    // ==================== KACHELGRÖSSE ====================

    private fun applyTileScale() {
        val widthPercent = tileScalePercent
        val script = """
            (function() {
                var style = document.getElementById('btbf-tile-scale');
                if (style) style.remove();
                if (${widthPercent} === 0) return; // Standard - keine Änderung nötig

                style = document.createElement('style');
                style.id = 'btbf-tile-scale';

                // Berechne Spaltenanzahl basierend auf Prozent
                // Kleiner = mehr Spalten, Größer = weniger Spalten
                var colWidth = ${widthPercent};

                style.textContent = `
                    /* Video-Kacheln Größe über Breite steuern */
                    .thumb-list__item,
                    .video-item,
                    .thumb-item,
                    .mozaique .thumb-block,
                    .mozaique > div,
                    .videos-list > div,
                    .thumbs-list > div,
                    .list-videos .video,
                    ul.videos li,
                    .video-list-item,
                    [class*="video-card"],
                    [class*="video-item"],
                    [class*="video_item"] {
                        width: ${'$'}{colWidth}% !important;
                        max-width: ${'$'}{colWidth}% !important;
                        flex-basis: ${'$'}{colWidth}% !important;
                        box-sizing: border-box !important;
                    }

                    /* Container auf Flex-Wrap umstellen */
                    .thumb-list,
                    .mozaique,
                    .videos-list,
                    .thumbs-list,
                    .list-videos,
                    ul.videos {
                        display: flex !important;
                        flex-wrap: wrap !important;
                    }

                    /* Bilder und Vorschau an Kachel anpassen */
                    .thumb-list__item img,
                    .video-item img,
                    .thumb-item img,
                    .mozaique .thumb-block img,
                    [class*="video-card"] img,
                    [class*="video-item"] img,
                    [class*="thumb"] img {
                        width: 100% !important;
                        height: auto !important;
                    }
                `;
                document.head.appendChild(style);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    // ==================== EINSTELLUNGEN MENÜ ====================

    private fun showSettingsMenu() {
        val items = arrayOf(
            if (isMouseModeActive) "Maus-Modus AUS" else "Maus-Modus AN",
            "Kachelgröße ändern",
            "Suche",
            "Favoriten",
            "Verlauf löschen",
            "Seite neu laden",
            "Zur Startseite"
        )

        AlertDialog.Builder(this)
            .setTitle("Einstellungen")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleMouseMode()
                    1 -> showTileSizeDialog()
                    2 -> showSearchDialog()
                    3 -> showFavoritesDialog()
                    4 -> clearHistory()
                    5 -> webView.reload()
                    6 -> webView.loadUrl(websiteUrl)
                }
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun showSearchDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Suchbegriff eingeben..."
            setPadding(48, 24, 48, 24)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }

        AlertDialog.Builder(this)
            .setTitle("Suche")
            .setView(input)
            .setPositiveButton("Suchen") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) {
                    val searchUrl = "${websiteUrl}search?q=${Uri.encode(query)}"
                    webView.loadUrl(searchUrl)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()

        // Tastatur sofort anzeigen
        input.requestFocus()
        input.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun clearHistory() {
        AlertDialog.Builder(this)
            .setTitle("Verlauf löschen")
            .setMessage("WebView-Verlauf und gespeicherte Daten löschen?")
            .setPositiveButton("Löschen") { _, _ ->
                webView.clearHistory()
                webView.clearCache(true)
                pageHistory.clear()
                scrollPositionMap.clear()
                Toast.makeText(this, "Verlauf gelöscht", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showTileSizeDialog() {
        // Voreinstellungen: 20% (5 Spalten), 25% (4), 33% (3), 50% (2), 100% (1)
        val sizes = arrayOf("Klein (5 pro Reihe)", "Mittel-Klein (4 pro Reihe)", "Mittel (3 pro Reihe)", "Groß (2 pro Reihe)", "Sehr groß (1 pro Reihe)", "Standard (Website-Layout)")
        val values = intArrayOf(20, 25, 33, 50, 100, 0)

        // Aktuell ausgewählt finden
        val currentIndex = when (tileScalePercent) {
            20 -> 0
            25 -> 1
            33 -> 2
            50 -> 3
            100 -> 4
            else -> 5 // Standard
        }

        AlertDialog.Builder(this)
            .setTitle("Kachelgröße")
            .setSingleChoiceItems(sizes, currentIndex) { dialog, which ->
                tileScalePercent = values[which]
                prefs.edit().putInt("tile_scale", tileScalePercent).apply()
                applyTileScale()
                Toast.makeText(this, sizes[which], Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ==================== NAVIGATION FUNKTIONEN ====================

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
        val navigateScript = """
            (function() {
                const elements = Array.from(document.querySelectorAll('a, button, [onclick], [role="button"], [tabindex]'));
                let current = document.activeElement;
                let index = elements.indexOf(current);

                if (index >= 0 && index < elements.length - 1) {
                    elements[index + 1].focus();
                    elements[index + 1].scrollIntoView({behavior: 'smooth', block: 'center'});
                } else if (elements.length > 0) {
                    elements[0].focus();
                    elements[0].scrollIntoView({behavior: 'smooth', block: 'center'});
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(navigateScript, null)
    }

    private fun navigateToPreviousElement() {
        val navigateScript = """
            (function() {
                const elements = Array.from(document.querySelectorAll('a, button, [onclick], [role="button"], [tabindex]'));
                let current = document.activeElement;
                let index = elements.indexOf(current);

                if (index > 0) {
                    elements[index - 1].focus();
                    elements[index - 1].scrollIntoView({behavior: 'smooth', block: 'center'});
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(navigateScript, null)
    }

    private fun clickFocusedElement() {
        val clickScript = """
            (function() {
                if (document.activeElement) {
                    document.activeElement.click();
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(clickScript, null)
    }
}
