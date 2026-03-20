package com.btbf.app.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Scraper für de.borntobefuck.com (Laravel + Infinite-Scroll, deutsche URL-Pfade).
 * HTML-Struktur: window.routes + div.video / .chunk-videos, Suche per GET name=search.
 */
class BtbfScraper : SiteScraper {
    override val siteName = "BTBF"
    override val baseUrl = "https://de.borntobefuck.com/"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val timeout = 15000

    private val baseRoot: String get() = baseUrl.trimEnd('/')

    private suspend fun fetchDocument(url: String): Document? = withContext(Dispatchers.IO) {
        try {
            Jsoup.connect(url)
                .userAgent(userAgent)
                .timeout(timeout)
                .followRedirects(true)
                .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
                .referrer(baseUrl)
                .get()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getHomePage(): PaginatedResult {
        val url = "$baseRoot/?sort=trending&page=1"
        val doc = fetchDocument(url) ?: return PaginatedResult(emptyList(), false)
        return parseVideoList(doc, url)
    }

    override suspend fun getCategory(categoryUrl: String): PaginatedResult = getPage(categoryUrl)

    override suspend fun getPage(pageUrl: String): PaginatedResult {
        val doc = fetchDocument(pageUrl) ?: return PaginatedResult(emptyList(), false)
        return parseVideoList(doc, pageUrl)
    }

    override suspend fun search(query: String, page: Int): PaginatedResult {
        val enc = URLEncoder.encode(query, Charsets.UTF_8.name())
        val pageParam = if (page > 1) "&page=$page" else ""
        val searchUrl = "${baseRoot}/suche/ergebnisse?search=$enc$pageParam"
        val doc = fetchDocument(searchUrl) ?: return PaginatedResult(emptyList(), false)
        return parseVideoList(doc, searchUrl)
    }

    override suspend fun getSorted(sort: SortOrder, page: Int): PaginatedResult {
        val sortParam = when (sort) {
            SortOrder.NEWEST -> "latest"
            SortOrder.TOP -> "mostLiked"
            SortOrder.LONGEST -> "longest"
            SortOrder.RANDOM -> "random"
        }
        val pageParam = if (page > 1) "&page=$page" else ""
        val url = "${baseRoot}/videos?sort=$sortParam$pageParam"
        val doc = fetchDocument(url) ?: return PaginatedResult(emptyList(), false)
        return parseVideoList(doc, url)
    }

    override suspend fun getActors(page: Int): List<ActorItem> {
        val pageParam = if (page > 1) "?page=$page" else ""
        val url = "${baseRoot}/models$pageParam"
        val doc = fetchDocument(url) ?: return emptyList()
        val actors = mutableListOf<ActorItem>()

        val cards = doc.select(".chunk-cards li.card a.card-link[href*=/models/]")
        if (cards.isNotEmpty()) {
            for (link in cards) {
                val href = resolveUrl(link.attr("href"))
                val name = link.selectFirst("h3.card-title")?.text()?.trim()
                    ?: link.selectFirst("img.model-image")?.attr("alt")?.substringBefore(" Borntobefuck")?.trim()
                    ?: link.selectFirst("img")?.attr("alt")?.trim()
                    ?: continue
                if (name.isEmpty()) continue
                val thumb = link.selectFirst("img.model-image")?.let { getImgSrc(it) }
                    ?: link.selectFirst("img")?.let { getImgSrc(it) } ?: ""
                val rank = link.selectFirst(".avatar-rank")?.text()?.trim() ?: ""
                actors.add(ActorItem(name, href, thumb, rank))
            }
            return actors.distinctBy { it.url }
        }

        for (link in doc.select("a.card-link[href*=/models/]")) {
            val href = resolveUrl(link.attr("href"))
            val img = link.selectFirst("img.model-image, img")
            val name = link.selectFirst("h3.card-title")?.text()?.trim()
                ?: img?.attr("alt")?.substringBefore(" Borntobefuck")?.trim()
                ?: img?.attr("alt")?.trim()
                ?: link.text().trim()
            if (name.isEmpty()) continue
            val thumb = img?.let { getImgSrc(it) } ?: ""
            actors.add(ActorItem(name, href, thumb))
        }
        return actors.distinctBy { it.url }
    }

    override suspend fun getTags(): List<TagItem> {
        val doc = fetchDocument("${baseRoot}/tags") ?: return emptyList()
        val tags = mutableListOf<TagItem>()
        for (el in doc.select("a.active-video-tag[href*=/tags/]")) {
            val name = el.text().trim().removePrefix("#").trim()
            val href = resolveUrl(el.attr("href"))
            if (name.isNotEmpty() && href.isNotEmpty()) {
                tags.add(TagItem(name, href))
            }
        }
        return tags.distinctBy { it.url }
    }

    override suspend fun getCategories(): List<CategoryItem> {
        val doc = fetchDocument("${baseRoot}/kategorien") ?: return emptyList()
        val categories = mutableListOf<CategoryItem>()
        for (card in doc.select("li.card.category")) {
            val link = card.selectFirst("a.card-link[href]") ?: continue
            val name = card.selectFirst("h3.card-title")?.text()?.trim()
                ?: link.text().trim()
            val url = resolveUrl(link.attr("href"))
            val thumb = link.selectFirst("img")?.let { getImgSrc(it) } ?: ""
            if (name.isNotEmpty() && url.isNotEmpty()) {
                categories.add(CategoryItem(name, url, thumb))
            }
        }
        return categories
    }

    override suspend fun getVideoDetail(pageUrl: String): VideoDetail? {
        val doc = fetchDocument(pageUrl) ?: return null
        val sources = mutableListOf<VideoSource>()

        doc.select("video source").forEach { source ->
            val src = source.attr("src")
            if (src.isNotEmpty()) {
                val quality = source.attr("label").ifEmpty {
                    source.attr("title").ifEmpty {
                        source.attr("res").ifEmpty { "" }
                    }
                }
                val type = when {
                    src.contains(".m3u8", true) -> VideoSourceType.HLS
                    src.contains(".mpd", true) -> VideoSourceType.DASH
                    else -> VideoSourceType.DIRECT
                }
                sources.add(VideoSource(resolveUrl(src), quality, type))
            }
        }

        doc.selectFirst("video")?.let { video ->
            val src = video.attr("src")
            if (src.isNotEmpty() && !src.startsWith("blob:")) {
                val type = when {
                    src.contains(".m3u8", true) -> VideoSourceType.HLS
                    src.contains(".mpd", true) -> VideoSourceType.DASH
                    else -> VideoSourceType.DIRECT
                }
                sources.add(VideoSource(resolveUrl(src), "", type))
            }
        }

        doc.select("script").forEach { script ->
            val text = script.data()
            val hlsPattern = Regex("""https?://[^\s'"<>]+\.m3u8[^\s'"<>]*""")
            hlsPattern.findAll(text).forEach { match ->
                val url = match.value.replace("\\", "")
                if (sources.none { it.url == url }) {
                    sources.add(VideoSource(url, "", VideoSourceType.HLS))
                }
            }
            val mp4Pattern = Regex("""https?://[^\s'"<>]+\.mp4[^\s'"<>]*""")
            mp4Pattern.findAll(text).forEach { match ->
                val url = match.value.replace("\\", "")
                if (sources.none { it.url == url }) {
                    sources.add(VideoSource(url, "", VideoSourceType.DIRECT))
                }
            }
            val playerPatterns = listOf(
                Regex("""(?:file|src|source|video_url|videoUrl|stream_url)\s*[:=]\s*['"](https?://[^'"]+)['"]\s*"""),
                Regex("""(?:file|src|source)\s*:\s*['"](https?://[^'"]+\.(?:mp4|m3u8|webm))['"]\s*"""),
                Regex("""html5player\.set(?:VideoUrl|VideoHLS)\s*\(\s*['"](https?://[^'"]+)['"]\s*\)""")
            )
            for (pattern in playerPatterns) {
                pattern.findAll(text).forEach { match ->
                    val url = match.groupValues[1].replace("\\", "")
                    if (sources.none { it.url == url }) {
                        val type = when {
                            url.contains(".m3u8", true) -> VideoSourceType.HLS
                            url.contains(".mpd", true) -> VideoSourceType.DASH
                            else -> VideoSourceType.DIRECT
                        }
                        sources.add(VideoSource(url, "", type))
                    }
                }
            }
        }

        doc.select("[data-src], [data-video], [data-hls], [data-stream]").forEach { el ->
            listOf("data-src", "data-video", "data-hls", "data-stream").forEach { attr ->
                val value = el.attr(attr)
                if (value.matches(Regex(""".*\.(mp4|m3u8|webm|mpd).*"""))) {
                    val type = when {
                        value.contains(".m3u8", true) -> VideoSourceType.HLS
                        value.contains(".mpd", true) -> VideoSourceType.DASH
                        else -> VideoSourceType.DIRECT
                    }
                    val url = resolveUrl(value)
                    if (sources.none { it.url == url }) {
                        sources.add(VideoSource(url, "", type))
                    }
                }
            }
        }

        doc.selectFirst("meta[property=og:video]")?.attr("content")?.let { src ->
            if (src.isNotEmpty() && sources.none { it.url == src }) {
                val type = when {
                    src.contains(".m3u8", true) -> VideoSourceType.HLS
                    else -> VideoSourceType.DIRECT
                }
                sources.add(VideoSource(src, "", type))
            }
        }

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.title()

        val thumbnail = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("video")?.attr("poster") ?: ""

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".description, .video-description")?.text() ?: ""

        val tags = doc.select("a.active-video-tag, .tags a, a[href*=/tags/]")
            .map { it.text().trim().removePrefix("#").trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val actors = mutableListOf<ActorItem>()
        for (link in doc.select("a[href*=/models/].channel_avatar_video_card, a.profil[href*=/models/], .bottom a[href*=/models/]")) {
            val name = link.selectFirst("img")?.attr("alt")?.substringBefore(" Borntobefuck")?.trim()
                ?: link.selectFirst(".video-channel")?.text()?.trim()
                ?: continue
            val href = resolveUrl(link.attr("href"))
            val thumb = link.selectFirst("img.avatar, img")?.let { getImgSrc(it) } ?: ""
            if (name.isNotEmpty()) actors.add(ActorItem(name, href, thumb))
        }

        val relatedVideos = parseRelatedVideosOnDetailPage(doc)

        val sortedSources = sources.sortedWith(compareBy<VideoSource> {
            when (it.type) {
                VideoSourceType.HLS -> 0
                VideoSourceType.DIRECT -> 1
                VideoSourceType.DASH -> 2
            }
        }.thenByDescending { it.quality })

        return VideoDetail(
            title = title,
            videoSources = sortedSources,
            thumbnailUrl = thumbnail,
            description = description,
            tags = tags,
            actors = actors.distinctBy { it.url },
            relatedVideos = relatedVideos
        )
    }

    private fun parseRelatedVideosOnDetailPage(doc: Document): List<VideoItem> {
        val videos = mutableListOf<VideoItem>()
        val blocks = doc.select(".few-videos .chunk-videos > .video")
        for (item in blocks) {
            parseVideoFromElement(item)?.let { videos.add(it) }
        }
        return videos.distinctBy { it.pageUrl }
    }

    private fun parseVideoList(doc: Document, currentUrl: String): PaginatedResult {
        val videos = mutableListOf<VideoItem>()

        val containerSelectors = listOf(
            ".chunk-videos > .video",
            ".chunk-videos .video.ranked",
            ".chunk-videos .video",
            ".home-chunk-videos .video",
            ".videos-list .video-item",
            ".video-list .video-item",
            ".thumbs .thumb",
            ".list-videos .item",
            ".videos .video",
            ".video-block",
            ".video-card",
            ".mozaique .thumb-block"
        )

        for (selector in containerSelectors) {
            val items = doc.select(selector)
            if (items.size >= 1) {
                for (item in items) {
                    parseVideoFromElement(item)?.let { videos.add(it) }
                }
                if (videos.isNotEmpty()) break
            }
        }

        if (videos.isEmpty()) {
            val allLinks = doc.select("a[href]")
            for (link in allLinks) {
                val img = link.selectFirst("img") ?: continue
                val href = link.attr("href")
                val imgSrc = getImgSrc(img)
                if (imgSrc.isEmpty()) continue
                if (href.contains("/videos/") || href.contains("/video/") || href.contains("/watch/") ||
                    href.contains("/v/")
                ) {
                    val title = img.attr("alt").ifEmpty {
                        link.attr("title").ifEmpty { link.text().trim() }
                    }
                    if (title.isNotEmpty() && imgSrc.contains("http")) {
                        val url = resolveUrl(href)
                        val duration = link.selectFirst(".duration, .time, .length, .video-duration")?.text()?.trim() ?: ""
                        videos.add(
                            VideoItem(
                                id = url,
                                title = title,
                                thumbnailUrl = imgSrc,
                                pageUrl = url,
                                duration = duration
                            )
                        )
                    }
                }
            }
        }

        val distinct = videos.distinctBy { it.pageUrl }
        val loadMore = doc.selectFirst("#load-more, button.load-btn")
        val hasNext = loadMore != null && distinct.isNotEmpty()
        val nextUrl = if (hasNext) bumpPageQuery(currentUrl) else null

        val currentPage = Regex("""[?&]page=(\d+)""").find(currentUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        return PaginatedResult(
            videos = distinct,
            hasNextPage = hasNext,
            nextPageUrl = nextUrl,
            currentPage = currentPage
        )
    }

    private fun bumpPageQuery(url: String): String {
        val regex = Regex("""([?&])page=\d+""")
        return when {
            regex.containsMatchIn(url) -> regex.replace(url) { m ->
                val sep = m.groupValues[1]
                val next = Regex("""page=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()?.plus(1) ?: 2
                "${sep}page=$next"
            }
            url.contains("?") -> "$url&page=2"
            else -> "$url?page=2"
        }
    }

    private fun parseVideoFromElement(element: Element): VideoItem? {
        val link = element.selectFirst("a.cardVideo-top-link[href], a[href*=/videos/]")
            ?: element.selectFirst("a[href]")
            ?: return null
        val href = resolveUrl(link.attr("href"))
        if (href.isEmpty() || (!href.contains("/videos/") && !href.contains("/video/"))) return null
        if (href == baseUrl || href == baseRoot) return null

        val img = element.selectFirst("img.thumbnail, img")
        val thumbUrl = img?.let { getImgSrc(it) } ?: ""

        val title = element.selectFirst("h3.video-title")?.text()?.trim()
            ?: img?.attr("alt")?.substringBefore(" Borntobefuck")?.trim()
            ?: img?.attr("alt")?.trim()
            ?: link.attr("title").trim()
            ?: link.text().trim()

        if (title.isEmpty()) return null

        val duration = element.selectFirst("div.time[data-duration], .time[data-duration]")
            ?.attr("data-duration")
            ?.toLongOrNull()
            ?.let { formatDurationSeconds(it) }
            ?: element.selectFirst(".duration, .time, .length, .video-duration, .thumb-duration")
                ?.text()?.trim() ?: ""

        val views = element.selectFirst("p.views[data-views]")
            ?.attr("data-views")?.trim()
            ?: element.selectFirst(".views, .video-views, .view-count")?.text()?.trim() ?: ""

        val quality = element.selectFirst(".quality, .hd, .video-quality")
            ?.text()?.trim() ?: ""

        val date = element.selectFirst("p.date[data-published]")
            ?.attr("data-published")
            ?.toLongOrNull()
            ?.let { formatEpochDay(it) }
            ?: element.selectFirst(".date, .added, .video-date, time")
                ?.text()?.trim() ?: ""

        return VideoItem(
            id = href,
            title = title,
            thumbnailUrl = thumbUrl,
            pageUrl = href,
            duration = duration,
            views = views,
            quality = quality,
            date = date
        )
    }

    private fun formatDurationSeconds(total: Long): String {
        if (total <= 0) return ""
        val m = TimeUnit.SECONDS.toMinutes(total)
        val s = total - TimeUnit.MINUTES.toSeconds(m)
        return String.format("%d:%02d", m, s)
    }

    private fun formatEpochDay(epochSeconds: Long): String {
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date(epochSeconds * 1000))
        } catch (_: Exception) {
            ""
        }
    }

    private fun getImgSrc(img: Element): String {
        return img.attr("data-src").ifEmpty {
            img.attr("data-lazy-src").ifEmpty {
                img.attr("data-original").ifEmpty {
                    img.attr("data-thumb").ifEmpty {
                        img.attr("src")
                    }
                }
            }
        }
    }

    private fun resolveUrl(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return baseRoot + url
        return "$baseRoot/$url"
    }
}
