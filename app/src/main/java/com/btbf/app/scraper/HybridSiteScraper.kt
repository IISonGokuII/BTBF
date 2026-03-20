package com.btbf.app.scraper

import android.app.Activity
import java.net.URLEncoder

/**
 * Ruft zuerst den eingebetteten Scraper (Jsoup) auf und nutzt bei leeren Ergebnissen
 * versteckte WebViews für Listen und Video-Details (Streams / Download-URLs).
 */
class HybridSiteScraper(
    private val activity: Activity,
    private val inner: SiteScraper
) : SiteScraper {

    private val listExtractor = WebViewListingExtractor(activity)
    private val detailExtractor = WebViewDetailExtractor(activity)
    private val baseRoot: String get() = inner.baseUrl.trimEnd('/')

    override val siteName: String get() = inner.siteName
    override val baseUrl: String get() = inner.baseUrl

    override suspend fun getHomePage(): PaginatedResult {
        val first = inner.getHomePage()
        if (first.videos.isNotEmpty()) return first
        val urls = homeUrlCandidates()
        for (u in urls) {
            val w = listExtractor.extractVideoListing(u)
            if (w.videos.isNotEmpty()) return w
        }
        return PaginatedResult(emptyList(), false)
    }

    override suspend fun getCategory(categoryUrl: String): PaginatedResult {
        val first = inner.getCategory(categoryUrl)
        if (first.videos.isNotEmpty()) return first
        return listExtractor.extractVideoListing(categoryUrl)
    }

    override suspend fun getPage(pageUrl: String): PaginatedResult {
        val first = inner.getPage(pageUrl)
        if (first.videos.isNotEmpty()) return first
        return listExtractor.extractVideoListing(pageUrl)
    }

    override suspend fun getSorted(sort: SortOrder, page: Int): PaginatedResult {
        val first = inner.getSorted(sort, page)
        if (first.videos.isNotEmpty()) return first
        val urls = sortedUrlCandidates(sort, page)
        for (u in urls) {
            val w = listExtractor.extractVideoListing(u)
            if (w.videos.isNotEmpty()) return w
        }
        return PaginatedResult(emptyList(), false)
    }

    override suspend fun getActors(page: Int): List<ActorItem> {
        val first = inner.getActors(page)
        if (first.isNotEmpty()) return first
        val pageParam = if (page > 1) "?page=$page" else ""
        val paths = listOf(
            "$baseRoot/models$pageParam",
            "$baseRoot/actors$pageParam",
            "$baseRoot/pornstars$pageParam",
            "$baseRoot/girls$pageParam",
            "$baseRoot/performers$pageParam",
            "$baseRoot/model$pageParam",
            "$baseRoot/creators$pageParam"
        )
        for (u in paths.distinct()) {
            val w = listExtractor.extractActors(u)
            if (w.isNotEmpty()) return w
        }
        return emptyList()
    }

    override suspend fun getTags(): List<TagItem> {
        val first = inner.getTags()
        if (first.isNotEmpty()) return first
        val paths = listOf(
            "$baseRoot/tags",
            "$baseRoot/tags/",
            "$baseRoot/tag/",
            "$baseRoot/categories/tags/"
        )
        for (u in paths.distinct()) {
            val w = listExtractor.extractTags(u)
            if (w.isNotEmpty()) return w
        }
        return emptyList()
    }

    override suspend fun search(query: String, page: Int): PaginatedResult {
        val first = inner.search(query, page)
        if (first.videos.isNotEmpty()) return first
        val enc = try {
            URLEncoder.encode(query, Charsets.UTF_8.name())
        } catch (_: Exception) {
            query.replace(" ", "+")
        }
        val pageAmp = if (page > 1) "&page=$page" else ""
        val pageQ = if (page > 1) "?page=$page" else ""
        val urls = listOf(
            "$baseRoot/suche/ergebnisse?search=$enc$pageAmp",
            "$baseRoot/search/?q=$enc$pageAmp",
            "$baseRoot/search?q=$enc$pageAmp",
            "$baseRoot/?s=$enc$pageAmp",
            "$baseRoot/?q=$enc$pageAmp",
            "$baseRoot/search/$enc$pageQ",
            "$baseRoot/search/${query.replace(" ", "-")}$pageQ",
            "$baseRoot/video/search?q=$enc$pageAmp"
        )
        for (u in urls.distinct()) {
            val w = listExtractor.extractVideoListing(u)
            if (w.videos.isNotEmpty()) return w
        }
        return PaginatedResult(emptyList(), false)
    }

    override suspend fun getCategories(): List<CategoryItem> {
        val first = inner.getCategories()
        if (first.isNotEmpty()) return first
        val paths = listOf(
            "$baseRoot/kategorien",
            "$baseRoot/kategorien/",
            "$baseRoot/categories/",
            "$baseRoot/categories",
            "$baseRoot/category/",
            "$baseRoot/cats/"
        )
        for (u in paths.distinct()) {
            val w = listExtractor.extractCategories(u)
            if (w.isNotEmpty()) return w
        }
        return emptyList()
    }

    override suspend fun getVideoDetail(pageUrl: String): VideoDetail? {
        val first = inner.getVideoDetail(pageUrl)
        if (first != null && first.videoSources.isNotEmpty()) return first

        val fromWeb = detailExtractor.extract(pageUrl)
        return when {
            fromWeb == null -> first
            first == null -> fromWeb
            else -> first.copy(
                videoSources = mergeVideoSources(first.videoSources, fromWeb.videoSources),
                thumbnailUrl = first.thumbnailUrl.ifEmpty { fromWeb.thumbnailUrl },
                title = first.title.ifBlank { fromWeb.title },
                description = first.description.ifEmpty { fromWeb.description },
                tags = if (first.tags.isNotEmpty()) first.tags else fromWeb.tags,
                actors = if (first.actors.isNotEmpty()) first.actors else fromWeb.actors,
                relatedVideos = if (first.relatedVideos.isNotEmpty()) first.relatedVideos else fromWeb.relatedVideos
            )
        }
    }

    private fun mergeVideoSources(a: List<VideoSource>, b: List<VideoSource>): List<VideoSource> {
        val merged = (a + b).distinctBy { it.url }
        return merged.sortedWith(
            compareBy<VideoSource> {
                when (it.type) {
                    VideoSourceType.HLS -> 0
                    VideoSourceType.DIRECT -> 1
                    VideoSourceType.DASH -> 2
                }
            }.thenByDescending { it.quality }
        )
    }

    private fun homeUrlCandidates(): List<String> {
        return listOf(
            baseUrl,
            "$baseRoot/",
            "$baseRoot/videos",
            "$baseRoot/videos/",
            "$baseRoot/?sort=trending",
            "$baseRoot/latest/",
            "$baseRoot/new/",
            "$baseRoot/most-recent/"
        ).distinct()
    }

    private fun sortedUrlCandidates(sort: SortOrder, page: Int): List<String> {
        val pageQ = if (page > 1) "&page=$page" else ""
        val pageQAlt = if (page > 1) "?page=$page" else ""
        val modernSort = when (sort) {
            SortOrder.NEWEST -> "latest"
            SortOrder.TOP -> "mostLiked"
            SortOrder.LONGEST -> "longest"
            SortOrder.RANDOM -> "random"
        }
        val urls = mutableListOf<String>()
        urls.add("$baseRoot/videos?sort=$modernSort$pageQ".replace("?&", "?"))
        if (page == 1) urls.add("$baseRoot/videos?sort=$modernSort")
        urls.add("$baseRoot${sort.path}/$pageQAlt")
        urls.add("$baseRoot${sort.path}$pageQAlt")
        urls.add("$baseRoot/videos/${sort.path}$pageQAlt")
        urls.add("$baseRoot/browse/${sort.path}$pageQAlt")
        return urls.distinct()
    }
}
