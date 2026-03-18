package com.btbf.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.btbf.app.databinding.ActivityBrowseBinding
import com.btbf.app.scraper.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowseBinding
    private lateinit var adapter: VideoGridAdapter
    private lateinit var scraper: SiteScraper
    private lateinit var favoritesManager: FavoritesManager
    private lateinit var videoDownloadHelper: VideoDownloadHelper

    private var currentJob: Job? = null
    private var nextPageUrl: String? = null
    private var isLoadingMore = false

    companion object {
        const val EXTRA_SITE_NAME = "site_name"
        const val EXTRA_SITE_URL = "site_url"

        fun launch(context: Context, siteName: String, siteUrl: String) {
            context.startActivity(Intent(context, BrowseActivity::class.java).apply {
                putExtra(EXTRA_SITE_NAME, siteName)
                putExtra(EXTRA_SITE_URL, siteUrl)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        binding = ActivityBrowseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUI()

        val siteName = intent.getStringExtra(EXTRA_SITE_NAME) ?: "BTBF"
        val siteUrl = intent.getStringExtra(EXTRA_SITE_URL) ?: "https://de.borntobefuck.com/"

        binding.tvSiteName.text = siteName
        favoritesManager = FavoritesManager(this)
        videoDownloadHelper = VideoDownloadHelper(this)

        scraper = if (siteName == "BTBF") {
            BtbfScraper()
        } else {
            GenericScraper(siteName, siteUrl)
        }

        setupGrid()
        setupChips(siteUrl)
        setupSearch()
        loadHomePage()
    }

    private fun setupGrid() {
        // 3 Spalten auf TV/Tablet, 2 auf Handy
        val spanCount = if (resources.configuration.smallestScreenWidthDp >= 600) 4 else 3
        val layoutManager = GridLayoutManager(this, spanCount)

        adapter = VideoGridAdapter(
            onVideoClick = { video -> openVideo(video) },
            onVideoLongClick = { video -> showVideoOptions(video) }
        )

        binding.videoGrid.layoutManager = layoutManager
        binding.videoGrid.adapter = adapter

        // Infinite Scroll
        binding.videoGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !isLoadingMore && nextPageUrl != null) {
                    val totalItems = layoutManager.itemCount
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    if (lastVisible >= totalItems - 6) {
                        loadMoreVideos()
                    }
                }
            }
        })

        binding.btnRetry.setOnClickListener { loadHomePage() }

        // Fokus für FireTV
        binding.chipHome.requestFocus()
    }

    private fun setupChips(siteUrl: String) {
        binding.chipHome.setOnClickListener { loadPage(siteUrl) }
        binding.chipNew.setOnClickListener { loadPage("${siteUrl}new") }
        binding.chipTop.setOnClickListener { loadPage("${siteUrl}top") }
        binding.chipRandom.setOnClickListener { loadPage("${siteUrl}random") }
        binding.chipCategories.setOnClickListener { showCategoriesDialog() }
    }

    private fun setupSearch() {
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.etSearch.text.toString().trim()
                if (query.isNotEmpty()) {
                    searchVideos(query)
                }
                true
            } else false
        }
    }

    private fun loadHomePage() {
        currentJob?.cancel()
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            showLoading()
            val result = scraper.getHomePage()
            showResults(result)
        }
    }

    private fun loadPage(url: String) {
        currentJob?.cancel()
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            showLoading()
            val result = scraper.getPage(url)
            showResults(result)
        }
    }

    private fun searchVideos(query: String) {
        currentJob?.cancel()
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            showLoading()
            val result = scraper.search(query)
            showResults(result)
        }
    }

    private fun loadMoreVideos() {
        val url = nextPageUrl ?: return
        if (isLoadingMore) return
        isLoadingMore = true
        binding.loadMoreSpinner.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.Main).launch {
            val result = scraper.getPage(url)
            adapter.addVideos(result.videos)
            nextPageUrl = result.nextPageUrl
            isLoadingMore = false
            binding.loadMoreSpinner.visibility = View.GONE
        }
    }

    private fun showLoading() {
        binding.loadingSpinner.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        binding.videoGrid.visibility = View.GONE
    }

    private fun showResults(result: PaginatedResult) {
        binding.loadingSpinner.visibility = View.GONE
        nextPageUrl = result.nextPageUrl

        if (result.videos.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.videoGrid.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.videoGrid.visibility = View.VISIBLE
            adapter.setVideos(result.videos)
            binding.videoGrid.scrollToPosition(0)
        }
    }

    private fun openVideo(video: VideoItem) {
        Toast.makeText(this, "Lade: ${video.title}", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            val detail = scraper.getVideoDetail(video.pageUrl)
            if (detail != null && detail.videoSources.isNotEmpty()) {
                val source = detail.videoSources.first()
                val intent = Intent(this@BrowseActivity, VideoPlayerActivity::class.java).apply {
                    putExtra(VideoPlayerActivity.EXTRA_VIDEO_URL, source.url)
                    putExtra(VideoPlayerActivity.EXTRA_REFERER, video.pageUrl)
                    putExtra(VideoPlayerActivity.EXTRA_TITLE, detail.title)
                }
                startActivity(intent)
            } else if (detail != null && detail.videoSources.isEmpty()) {
                // Fallback: Seite im WebView öffnen
                Toast.makeText(this@BrowseActivity,
                    "Keine direkte Video-URL gefunden. Oeffne im Browser...",
                    Toast.LENGTH_SHORT).show()
                openInWebView(video.pageUrl)
            } else {
                Toast.makeText(this@BrowseActivity,
                    "Fehler beim Laden", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openInWebView(url: String) {
        // Zurück zur MainActivity mit WebView-Fallback
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("fallback_url", url)
        }
        startActivity(intent)
    }

    private fun showVideoOptions(video: VideoItem) {
        val options = arrayOf("Abspielen", "Download", "Favorit speichern")
        AlertDialog.Builder(this)
            .setTitle(video.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openVideo(video)
                    1 -> downloadVideo(video)
                    2 -> saveToFavorites(video)
                }
            }
            .show()
    }

    private fun downloadVideo(video: VideoItem) {
        Toast.makeText(this, "Suche Download-URL...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            val detail = scraper.getVideoDetail(video.pageUrl)
            if (detail != null && detail.videoSources.isNotEmpty()) {
                val source = detail.videoSources.first()
                when (source.type) {
                    VideoSourceType.HLS -> {
                        Toast.makeText(this@BrowseActivity, "HLS Download gestartet...", Toast.LENGTH_SHORT).show()
                        videoDownloadHelper.downloadHlsStream(
                            m3u8Url = source.url,
                            referer = video.pageUrl,
                            userAgent = null,
                            onProgress = { progress, message ->
                                if (progress % 20 == 0) {
                                    Toast.makeText(this@BrowseActivity, "$message ($progress%)", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onComplete = { file ->
                                val msg = if (file != null) "Download fertig: ${file.name}" else "Download fehlgeschlagen"
                                Toast.makeText(this@BrowseActivity, msg, Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                    else -> {
                        videoDownloadHelper.downloadDirect(source.url, video.pageUrl, null)
                        Toast.makeText(this@BrowseActivity, "Download gestartet!", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this@BrowseActivity, "Keine Download-URL gefunden", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToFavorites(video: VideoItem) {
        favoritesManager.addFavoriteVideo(video.id, video.title, video.thumbnailUrl, video.duration)
        Toast.makeText(this, "Favorit gespeichert!", Toast.LENGTH_SHORT).show()
    }

    private fun showCategoriesDialog() {
        Toast.makeText(this, "Lade Kategorien...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            val categories = scraper.getCategories()
            if (categories.isEmpty()) {
                Toast.makeText(this@BrowseActivity, "Keine Kategorien gefunden", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = categories.map { it.name }.toTypedArray()
            AlertDialog.Builder(this@BrowseActivity)
                .setTitle("Kategorien")
                .setItems(names) { _, which ->
                    loadPage(categories[which].url)
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    // ==================== FIRETV NAVIGATION ====================

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                // Menu: Zurück zur Seitenauswahl
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
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
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentJob?.cancel()
    }
}
