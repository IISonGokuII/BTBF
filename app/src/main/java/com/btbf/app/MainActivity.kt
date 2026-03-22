package com.btbf.app

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
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
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.SeekBar
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
    private var mouseCursorX = 0
    private var mouseCursorY = 0
    private val mouseStepSize = 30 // Pixel pro D-Pad Druck

    // Scroll-Position Speicher (URL -> ScrollY)
    private val scrollPositionMap = mutableMapOf<String, Int>()

    // Kachelgröße (Prozent: 50-150, Standard 100)
    private var tileScalePercent = 100

    // Einstellungen
    private lateinit var prefs: SharedPreferences

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
        tileScalePercent = prefs.getInt("tile_scale", 100)

        // Favoriten Manager initialisieren
        favoritesManager = FavoritesManager(this)

        // Gesture Detector initialisieren
        gestureDetector = GestureDetector(this, GestureListener())

        setupWebView()
        setupButtons()

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
                // Alle URLs im WebView öffnen
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE

                // Verbesserter AdBlocker + Komfort-Features
                injectComfortScripts()

                // Kachelgröße anwenden
                applyTileScale()

                // Maus-Cursor injizieren (immer bereit, aber nur sichtbar wenn aktiv)
                injectVirtualCursor()

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
        // === HAUPT-NAVIGATION ===
        
        // Home Button
        binding.btnHome.setOnClickListener {
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
            if (webView.canGoBack()) {
                saveScrollPosition()
                webView.goBack()
            }
        }
        
        // Favorites Button
        binding.btnFavorites.setOnClickListener {
            showFavoritesDialog()
        }

        // Settings Button
        binding.btnSettings.setOnClickListener {
            showSettingsMenu()
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
        
        // Kategorien-Leiste nach 3 Sekunden ausblenden
        binding.categoryScroll.postDelayed({
            hideCategoryBar()
        }, 3000)
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
        CoroutineScope(Dispatchers.Main).launch {
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
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }
    
    private fun showSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // FireTV Fernbedienung Navigation
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isMouseModeActive) {
                    moveMouseCursor(0, -mouseStepSize)
                } else if (binding.categoryScroll.visibility == View.GONE) {
                    navigateToPreviousElement()
                } else {
                    showCategoryBar()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isMouseModeActive) {
                    moveMouseCursor(0, mouseStepSize)
                } else {
                    navigateToNextElement()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isMouseModeActive) {
                    moveMouseCursor(-mouseStepSize, 0)
                } else {
                    scrollWebView("left", 200)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isMouseModeActive) {
                    moveMouseCursor(mouseStepSize, 0)
                } else {
                    scrollWebView("right", 200)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (isMouseModeActive) {
                    clickAtMouseCursor()
                } else {
                    clickFocusedElement()
                }
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
                if (webView.canGoBack()) {
                    saveScrollPosition()
                    webView.goBack()
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
        if (webView.canGoBack()) {
            saveScrollPosition()
            webView.goBack()
        } else {
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
            CoroutineScope(Dispatchers.Main).launch {
                downloadVideo(url, null, "video/mp4")
            }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
            // Einmaliges Tippen zeigt Buttons für 3 Sekunden
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
        loadFavoriteVideos(dialogBinding.recyclerFavoriteVideos, dialogBinding.emptyVideosState)
        
        dialog.show()
    }
    
    /**
     * Favoriten-Videos laden
     */
    private fun loadFavoriteVideos(recyclerView: androidx.recyclerview.widget.RecyclerView, emptyView: View) {
        val videos = favoritesManager.getFavoriteVideos()
        
        if (videos.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            // TODO: RecyclerView Adapter implementieren
        }
    }
    
    /**
     * Favoriten-Darsteller laden
     */
    private fun loadFavoriteActors(recyclerView: androidx.recyclerview.widget.RecyclerView, emptyView: View) {
        val actors = favoritesManager.getFavoriteActors()
        
        if (actors.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            // TODO: RecyclerView Adapter implementieren
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
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript("""
                    (function() {
                        const title = document.querySelector('h1')?.textContent || document.title;
                        const thumbnail = document.querySelector('video')?.poster ||
                                        document.querySelector('meta[property="og:image"]')?.content || '';
                        return JSON.stringify({title: title, thumbnail: thumbnail});
                    })();
                """.trimIndent()) { result ->
                    if (result != null) {
                        try {
                            val json = org.json.JSONObject(result)
                            val title = json.optString("title", "Unbekanntes Video")
                            val thumbnail = json.optString("thumbnail", "")
                            val videoId = webView.url.hashCode().toString()

                            favoritesManager.addFavoriteVideo(videoId, title, thumbnail)
                            Toast.makeText(this@MainActivity, "⭐ Zu Favoriten hinzugefügt", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Prüfen ob aktuelles Video Favorit ist
     */
    fun isCurrentVideoFavorite(): Boolean {
        val videoId = webView.url.hashCode().toString()
        return favoritesManager.isVideoFavorite(videoId)
    }
    
    /**
     * Video aus Favoriten entfernen
     */
    fun removeCurrentVideoFromFavorites() {
        val videoId = webView.url.hashCode().toString()
        favoritesManager.removeFavoriteVideo(videoId)
        Toast.makeText(this, "Aus Favoriten entfernt", Toast.LENGTH_SHORT).show()
    }
    
    // ==================== VIRTUAL MOUSE CURSOR ====================

    private fun injectVirtualCursor() {
        val cursorScript = """
            (function() {
                if (document.getElementById('btbf-cursor')) return;

                const cursor = document.createElement('div');
                cursor.id = 'btbf-cursor';
                cursor.style.cssText = `
                    position: fixed;
                    width: 24px;
                    height: 24px;
                    border-radius: 50%;
                    background: rgba(229, 9, 20, 0.8);
                    border: 3px solid #FFD700;
                    box-shadow: 0 0 15px rgba(255, 215, 0, 0.6);
                    z-index: 999999;
                    pointer-events: none;
                    display: none;
                    transform: translate(-50%, -50%);
                    transition: left 0.05s linear, top 0.05s linear;
                `;
                document.body.appendChild(cursor);

                // Fokus-Styling für Element-Navigation
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
                if (!document.getElementById('btbf-nav-style')) {
                    document.head.appendChild(style);
                }

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

    private fun toggleMouseMode() {
        isMouseModeActive = !isMouseModeActive
        if (isMouseModeActive) {
            // Cursor in Bildschirmmitte starten
            mouseCursorX = webView.width / 2
            mouseCursorY = webView.height / 2
            updateMouseCursorPosition()
            webView.evaluateJavascript(
                "document.getElementById('btbf-cursor').style.display = 'block';", null
            )
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

    private fun moveMouseCursor(dx: Int, dy: Int) {
        mouseCursorX = (mouseCursorX + dx).coerceIn(0, webView.width)
        mouseCursorY = (mouseCursorY + dy).coerceIn(0, webView.height)

        // Am Rand scrollen
        if (mouseCursorY < 50) scrollWebView("up", 100)
        if (mouseCursorY > webView.height - 50) scrollWebView("down", 100)
        if (mouseCursorX < 50) scrollWebView("left", 100)
        if (mouseCursorX > webView.width - 50) scrollWebView("right", 100)

        updateMouseCursorPosition()
    }

    private fun updateMouseCursorPosition() {
        val script = """
            (function() {
                var c = document.getElementById('btbf-cursor');
                if (!c) return;
                c.style.left = '${mouseCursorX}px';
                c.style.top = '${mouseCursorY}px';

                // Highlight Element unter Cursor
                document.querySelectorAll('.btbf-cursor-hover').forEach(el => el.classList.remove('btbf-cursor-hover'));
                var el = document.elementFromPoint(${mouseCursorX}, ${mouseCursorY});
                if (el) {
                    var clickable = el.closest('a, button, [onclick], [role="button"], input, select, [tabindex]');
                    if (clickable) clickable.classList.add('btbf-cursor-hover');
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun clickAtMouseCursor() {
        val script = """
            (function() {
                var el = document.elementFromPoint(${mouseCursorX}, ${mouseCursorY});
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
        val scale = tileScalePercent / 100.0
        val script = """
            (function() {
                var style = document.getElementById('btbf-tile-scale');
                if (style) style.remove();
                style = document.createElement('style');
                style.id = 'btbf-tile-scale';
                style.textContent = `
                    /* Video-Kacheln/Thumbnails skalieren */
                    .thumb-list__item,
                    .video-item,
                    .thumb-item,
                    [class*="thumb"],
                    [class*="video-card"],
                    [class*="video-item"],
                    [class*="video_item"],
                    .mozaique .thumb-block,
                    .mozaique > div,
                    .videos-list > div,
                    .thumbs-list > div,
                    .list-videos .video,
                    ul.videos li,
                    .video-list-item {
                        transform: scale(${scale}) !important;
                        transform-origin: top left !important;
                        margin-bottom: ${if (scale < 1.0) "-${((1.0 - scale) * 50).toInt()}px" else "0px"} !important;
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
            "Favoriten",
            "Seite neu laden",
            "Zur Startseite"
        )

        AlertDialog.Builder(this)
            .setTitle("Einstellungen")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleMouseMode()
                    1 -> showTileSizeDialog()
                    2 -> showFavoritesDialog()
                    3 -> webView.reload()
                    4 -> webView.loadUrl(websiteUrl)
                }
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun showTileSizeDialog() {
        val seekBar = SeekBar(this).apply {
            max = 100 // 50-150 -> offset by 50
            progress = tileScalePercent - 50
            setPadding(48, 24, 48, 24)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Kachelgröße: ${tileScalePercent}%")
            .setView(seekBar)
            .setPositiveButton("OK") { _, _ ->
                tileScalePercent = seekBar.progress + 50
                prefs.edit().putInt("tile_scale", tileScalePercent).apply()
                applyTileScale()
                Toast.makeText(this, "Kachelgröße: ${tileScalePercent}%", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Zurücksetzen") { _, _ ->
                tileScalePercent = 100
                prefs.edit().putInt("tile_scale", 100).apply()
                applyTileScale()
                Toast.makeText(this, "Kachelgröße zurückgesetzt", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .create()

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                dialog.setTitle("Kachelgröße: ${progress + 50}%")
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        dialog.show()
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
