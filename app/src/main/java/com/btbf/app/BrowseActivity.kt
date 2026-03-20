package com.btbf.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.btbf.app.databinding.ActivityBrowseBinding
import com.btbf.app.scraper.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class BrowseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowseBinding
    private lateinit var videoAdapter: VideoGridAdapter
    private lateinit var categoryAdapter: CategoryGridAdapter
    private lateinit var actorAdapter: ActorGridAdapter
    private lateinit var scraper: SiteScraper
    private lateinit var favoritesManager: FavoritesManager
    private lateinit var videoDownloadHelper: VideoDownloadHelper
    private lateinit var siteUrl: String

    private var currentJob: Job? = null
    private var nextPageUrl: String? = null
    private var isLoadingMore = false

    // Aktueller Ansichtsmodus
    private enum class ViewMode { VIDEOS, CATEGORIES, ACTORS }
    private var currentMode = ViewMode.VIDEOS

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
        siteUrl = intent.getStringExtra(EXTRA_SITE_URL) ?: "https://de.borntobefuck.com/"

        favoritesManager = FavoritesManager(this)
        videoDownloadHelper = VideoDownloadHelper(this)

        val innerScraper: SiteScraper = if (siteName == "BTBF") {
            BtbfScraper()
        } else {
            GenericScraper(siteName, siteUrl)
        }
        scraper = HybridSiteScraper(this, innerScraper)

        binding.toolbar.title = siteName
        binding.toolbar.subtitle = try {
            android.net.Uri.parse(siteUrl).host
        } catch (_: Exception) {
            null
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.navigationIcon?.setTint(
            ContextCompat.getColor(this, R.color.text_primary)
        )
        binding.toolbar.inflateMenu(R.menu.browse_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_open_site_home -> {
                    openInWebView(siteUrl)
                    true
                }
                R.id.action_clear_cookies -> {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    Toast.makeText(this, R.string.cookies_cleared, Toast.LENGTH_LONG).show()
                    true
                }
                else -> false
            }
        }

        setupAdapters()
        setupGrid()
        setupBrowseChips()
        setupSearch()
        loadHomePage()
    }

    private fun setupAdapters() {
        videoAdapter = VideoGridAdapter(
            onVideoClick = { video -> openVideo(video) },
            onVideoLongClick = { video -> showVideoOptions(video) }
        )

        categoryAdapter = CategoryGridAdapter { category ->
            switchToVideoMode()
            loadPage(category.url)
        }

        actorAdapter = ActorGridAdapter { actor ->
            switchToVideoMode()
            loadPage(actor.url)
        }
    }

    private fun setupGrid() {
        val spanCount = if (resources.configuration.smallestScreenWidthDp >= 600) 4 else 3
        val layoutManager = GridLayoutManager(this, spanCount)

        binding.videoGrid.layoutManager = layoutManager
        binding.videoGrid.adapter = videoAdapter

        // Infinite Scroll
        binding.videoGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !isLoadingMore && nextPageUrl != null && currentMode == ViewMode.VIDEOS) {
                    val totalItems = layoutManager.itemCount
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    if (lastVisible >= totalItems - 6) {
                        loadMoreVideos()
                    }
                }
            }
        })

        binding.btnRetry.setOnClickListener { loadHomePage() }
        binding.rvBrowseChips.post {
            binding.rvBrowseChips.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun setupBrowseChips() {
        binding.rvBrowseChips.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvBrowseChips.adapter = BrowseChipAdapter { action ->
            when (action) {
                BrowseNavAction.HOME -> {
                    switchToVideoMode()
                    loadHomePage()
                }
                BrowseNavAction.NEWEST -> {
                    switchToVideoMode()
                    loadSorted(SortOrder.NEWEST)
                }
                BrowseNavAction.TOP -> {
                    switchToVideoMode()
                    loadSorted(SortOrder.TOP)
                }
                BrowseNavAction.RANDOM -> {
                    switchToVideoMode()
                    loadSorted(SortOrder.RANDOM)
                }
                BrowseNavAction.LONGEST -> {
                    switchToVideoMode()
                    loadSorted(SortOrder.LONGEST)
                }
                BrowseNavAction.CATEGORIES -> showCategoriesGrid()
                BrowseNavAction.ACTORS -> showActorsGrid()
                BrowseNavAction.TAGS -> showTagsDialog()
                BrowseNavAction.FAVORITES -> showFavorites()
            }
        }
    }

    private fun setupSearch() {
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.etSearch.text.toString().trim()
                if (query.isNotEmpty()) {
                    switchToVideoMode()
                    searchVideos(query)
                }
                true
            } else false
        }
    }

    // ==================== VIEW MODE SWITCHING ====================

    private fun switchToVideoMode() {
        if (currentMode != ViewMode.VIDEOS) {
            currentMode = ViewMode.VIDEOS
            binding.videoGrid.adapter = videoAdapter
        }
    }

    private fun showCategoriesGrid() {
        currentMode = ViewMode.CATEGORIES
        binding.videoGrid.adapter = categoryAdapter
        currentJob?.cancel()
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            showLoading()
            val categories = scraper.getCategories()
            binding.loadingSpinner.visibility = View.GONE
            if (categories.isEmpty()) {
                binding.emptyState.visibility = View.VISIBLE
                binding.videoGrid.visibility = View.GONE
            } else {
                binding.emptyState.visibility = View.GONE
                binding.videoGrid.visibility = View.VISIBLE
                categoryAdapter.setItems(categories)
                binding.videoGrid.scrollToPosition(0)
            }
        }
    }

    private fun showActorsGrid() {
        currentMode = ViewMode.ACTORS
        binding.videoGrid.adapter = actorAdapter
        currentJob?.cancel()
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            showLoading()
            val actors = scraper.getActors()
            binding.loadingSpinner.visibility = View.GONE
            if (actors.isEmpty()) {
                binding.emptyState.visibility = View.VISIBLE
                binding.videoGrid.visibility = View.GONE
            } else {
                binding.emptyState.visibility = View.GONE
                binding.videoGrid.visibility = View.VISIBLE
                actorAdapter.setItems(actors)
                binding.videoGrid.scrollToPosition(0)
            }
        }
    }

    private fun showTagsDialog() {
        Toast.makeText(this, "Lade Tags...", Toast.LENGTH_SHORT).show()
        currentJob?.cancel()
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            val tags = scraper.getTags()
            if (tags.isEmpty()) {
                Toast.makeText(this@BrowseActivity, "Keine Tags gefunden", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = tags.map { tag ->
                if (tag.count.isNotEmpty()) "${tag.name} (${tag.count})" else tag.name
            }.toTypedArray()
            AlertDialog.Builder(this@BrowseActivity)
                .setTitle("Tags (${tags.size})")
                .setItems(names) { _, which ->
                    switchToVideoMode()
                    loadPage(tags[which].url)
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    private fun showFavorites() {
        switchToVideoMode()
        val favVideos = favoritesManager.getFavoriteVideos()
        if (favVideos.isEmpty()) {
            Toast.makeText(this, "Keine Favoriten gespeichert", Toast.LENGTH_SHORT).show()
            return
        }
        val videoItems = favVideos.map { fav ->
            VideoItem(
                id = fav.id,
                title = fav.title,
                thumbnailUrl = fav.thumbnailUrl,
                pageUrl = fav.id,
                duration = fav.duration
            )
        }
        nextPageUrl = null
        binding.loadingSpinner.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        binding.videoGrid.visibility = View.VISIBLE
        videoAdapter.setVideos(videoItems)
        binding.videoGrid.scrollToPosition(0)
    }

    // ==================== LOADING ====================

    private fun loadHomePage() {
        currentJob?.cancel()
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            showLoading()
            val result = scraper.getHomePage()
            showVideoResults(result)
        }
    }

    private fun loadPage(url: String) {
        currentJob?.cancel()
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            showLoading()
            val result = scraper.getPage(url)
            showVideoResults(result)
        }
    }

    private fun loadSorted(sort: SortOrder) {
        currentJob?.cancel()
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            showLoading()
            val result = scraper.getSorted(sort)
            showVideoResults(result)
        }
    }

    private fun searchVideos(query: String) {
        currentJob?.cancel()
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            showLoading()
            val result = scraper.search(query)
            showVideoResults(result)
        }
    }

    private fun loadMoreVideos() {
        val url = nextPageUrl ?: return
        if (isLoadingMore) return
        isLoadingMore = true
        binding.loadMoreSpinner.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.Main).launch {
            val result = scraper.getPage(url)
            videoAdapter.addVideos(result.videos)
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

    private fun showVideoResults(result: PaginatedResult) {
        binding.loadingSpinner.visibility = View.GONE
        nextPageUrl = result.nextPageUrl

        if (result.videos.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.videoGrid.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.videoGrid.visibility = View.VISIBLE
            videoAdapter.setVideos(result.videos)
            binding.videoGrid.scrollToPosition(0)
        }
    }

    // ==================== VIDEO ACTIONS ====================

    private fun openVideo(video: VideoItem) {
        Toast.makeText(this, "Lade: ${video.title}", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            val detail = scraper.getVideoDetail(video.pageUrl)
            if (detail != null && detail.videoSources.isNotEmpty()) {
                // Mehrere Quellen? Dialog zeigen
                if (detail.videoSources.size > 1) {
                    showSourcePicker(detail, video.pageUrl)
                } else {
                    launchPlayer(detail.videoSources.first().url, video.pageUrl, detail.title)
                }
            } else if (detail != null && detail.videoSources.isEmpty()) {
                Toast.makeText(this@BrowseActivity,
                    "Keine direkte Video-URL gefunden. Oeffne im Browser...",
                    Toast.LENGTH_SHORT).show()
                openInWebView(video.pageUrl)
            } else {
                Toast.makeText(this@BrowseActivity, "Fehler beim Laden", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSourcePicker(detail: VideoDetail, referer: String) {
        val labels = detail.videoSources.mapIndexed { i, src ->
            val type = when (src.type) {
                VideoSourceType.HLS -> "HLS"
                VideoSourceType.DASH -> "DASH"
                VideoSourceType.DIRECT -> when {
                    src.url.contains(".mp4", true) -> "MP4"
                    src.url.contains(".webm", true) -> "WEBM"
                    else -> "Video"
                }
            }
            val quality = if (src.quality.isNotEmpty()) " ${src.quality}" else ""
            "[$type$quality] Quelle ${i + 1}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Qualitaet waehlen")
            .setItems(labels) { _, which ->
                launchPlayer(detail.videoSources[which].url, referer, detail.title)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun launchPlayer(videoUrl: String, referer: String, title: String) {
        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
            putExtra(VideoPlayerActivity.EXTRA_REFERER, referer)
            putExtra(VideoPlayerActivity.EXTRA_USER_AGENT, WebViewListingExtractor.DEFAULT_USER_AGENT)
            putExtra(VideoPlayerActivity.EXTRA_TITLE, title)
        }
        startActivity(intent)
    }

    private fun openInWebView(url: String) {
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
            if (detail == null || detail.videoSources.isEmpty()) {
                Toast.makeText(this@BrowseActivity, R.string.download_no_sources, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val sources = detail.videoSources
            if (sources.size == 1) {
                startDownloadWithSource(sources.first(), video)
            } else {
                showDownloadSourcePicker(sources, video)
            }
        }
    }

    private fun showDownloadSourcePicker(sources: List<VideoSource>, video: VideoItem) {
        val labels = sources.mapIndexed { i, src ->
            val type = when (src.type) {
                VideoSourceType.HLS -> "HLS"
                VideoSourceType.DASH -> "DASH"
                VideoSourceType.DIRECT -> when {
                    src.url.contains(".mp4", true) -> "MP4"
                    src.url.contains(".webm", true) -> "WEBM"
                    else -> "Video"
                }
            }
            val quality = if (src.quality.isNotEmpty()) " ${src.quality}" else ""
            val dashOnly = src.type == VideoSourceType.DASH || videoDownloadHelper.isDashUrl(src.url)
            val suffix = if (dashOnly) getString(R.string.download_label_dash_suffix) else ""
            "[$type$quality] ${i + 1}$suffix"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.download_pick_source)
            .setItems(labels) { _, which ->
                startDownloadWithSource(sources[which], video)
            }
            .setNegativeButton(R.string.nav_back, null)
            .show()
    }

    private fun startDownloadWithSource(source: VideoSource, video: VideoItem) {
        if (source.type == VideoSourceType.DASH || videoDownloadHelper.isDashUrl(source.url)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dash_download_title)
                .setMessage(R.string.dash_download_message)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        when (source.type) {
            VideoSourceType.HLS -> {
                Toast.makeText(this, "HLS Download gestartet...", Toast.LENGTH_SHORT).show()
                CoroutineScope(Dispatchers.Main).launch {
                    videoDownloadHelper.downloadHlsStream(
                        m3u8Url = source.url,
                        referer = video.pageUrl,
                        userAgent = WebViewListingExtractor.DEFAULT_USER_AGENT,
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
            }
            else -> {
                videoDownloadHelper.downloadDirect(
                    source.url,
                    video.pageUrl,
                    WebViewListingExtractor.DEFAULT_USER_AGENT
                )
                Toast.makeText(this, "Download gestartet!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToFavorites(video: VideoItem) {
        favoritesManager.addFavoriteVideo(video.pageUrl, video.title, video.thumbnailUrl, video.duration)
        Toast.makeText(this, "Favorit gespeichert!", Toast.LENGTH_SHORT).show()
    }

    // ==================== FIRETV NAVIGATION ====================

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    binding.toolbar.showOverflowMenu()
                } else {
                    showFallbackToolbarMenu()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showFallbackToolbarMenu() {
        val items = arrayOf(
            getString(R.string.menu_open_site_home),
            getString(R.string.menu_clear_cookies)
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openInWebView(siteUrl)
                    1 -> {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        Toast.makeText(this, R.string.cookies_cleared, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
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
