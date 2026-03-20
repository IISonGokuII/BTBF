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
 * Universeller Scraper für typische Tube-/CMS-Layouts inkl. BTBF-ähnlicher Struktur
 * (.chunk-videos, .cardVideo-top-link, #load-more + ?page=).
 */
class GenericScraper(
    override val siteName: String,
    override val baseUrl: String
) : SiteScraper {

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val timeout = 20000
    private val baseRoot: String get() = baseUrl.trimEnd('/')

    private suspend fun fetchDocument(url: String): Document? = withContext(Dispatchers.IO) {
        try {
            Jsoup.connect(url)
                .userAgent(userAgent)
                .timeout(timeout)
                .followRedirects(true)
                .header("Accept-Language", "en-US,en;q=0.9,de;q=0.8")
                .referrer(baseUrl)
                .get()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getHomePage(): PaginatedResult {
        val candidates = listOf(
            baseUrl,
            "$baseRoot/",
            "$baseRoot/videos",
            "$baseRoot/videos/",
            "$baseRoot/?sort=trending",
            "$baseRoot/latest/",
            "$baseRoot/new/",
            "$baseRoot/most-recent/"
        )
        for (url in candidates.distinct()) {
            val doc = fetchDocument(url) ?: continue
            val result = parseVideoList(doc, url)
            if (result.videos.isNotEmpty()) return result
        }
        return PaginatedResult(emptyList(), false)
    }

    override suspend fun getCategory(categoryUrl: String): PaginatedResult = getPage(categoryUrl)

    override suspend fun getPage(pageUrl: String): PaginatedResult {
        val doc = fetchDocument(pageUrl) ?: return PaginatedResult(emptyList(), false)
        return parseVideoList(doc, pageUrl)
    }

    override suspend fun getSorted(sort: SortOrder, page: Int): PaginatedResult {
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

        for (url in urls.distinct()) {
            val doc = fetchDocument(url) ?: continue
            val result = parseVideoList(doc, url)
            if (result.videos.isNotEmpty()) return result
        }
        return PaginatedResult(emptyList(), false)
    }

    override suspend fun getActors(page: Int): List<ActorItem> {
        val pageParam = if (page > 1) "?page=$page" else ""
        val basePaths = listOf(
            "$baseRoot/models$pageParam",
            "$baseRoot/actors$pageParam",
            "$baseRoot/pornstars$pageParam",
            "$baseRoot/girls$pageParam",
            "$baseRoot/performers$pageParam",
            "$baseRoot/model$pageParam"
        )

        for (pageUrl in basePaths.distinct()) {
            val doc = fetchDocument(pageUrl) ?: continue
            val fromCards = parseActorCards(doc)
            if (fromCards.isNotEmpty()) return fromCards
            val fromLegacy = parseActorsLegacy(doc)
            if (fromLegacy.isNotEmpty()) return fromLegacy
        }
        return emptyList()
    }

    private fun parseActorCards(doc: Document): List<ActorItem> {
        val out = mutableListOf<ActorItem>()
        val links = doc.select(".chunk-cards li.card a.card-link[href], li.card a.card-link[href*=/models/]")
        for (link in links) {
            val href = resolveUrl(link.attr("href"))
            if (!href.contains("/models/") && !href.contains("/model/")) continue
            val name = link.selectFirst("h3.card-title")?.text()?.trim()
                ?: link.selectFirst("img.model-image, img")?.attr("alt")?.substringBefore(" ")?.trim()
                ?: link.text().trim()
            if (name.isEmpty()) continue
            val thumb = link.selectFirst("img.model-image, img")?.let { getImgSrc(it) } ?: ""
            val rank = link.selectFirst(".avatar-rank")?.text()?.trim() ?: ""
            out.add(ActorItem(name, href, thumb, rank))
        }
        return out.distinctBy { it.url }
    }

    private fun parseActorsLegacy(doc: Document): List<ActorItem> {
        val actors = mutableListOf<ActorItem>()
        val selectors = listOf(
            ".model-list .model-item", ".models-list .model",
            ".pornstar-list .pornstar", ".thumbs .thumb",
            ".list-models .item", ".model-block"
        )
        for (selector in selectors) {
            val elements = doc.select(selector)
            if (elements.isEmpty()) continue
            for (el in elements) {
                val link = el.selectFirst("a[href]") ?: continue
                val name = el.selectFirst(".name, .title, h3, h4")?.text()?.trim()
                    ?: el.selectFirst("img")?.attr("alt")?.trim() ?: link.text().trim()
                if (name.isEmpty()) continue
                val href = resolveUrl(link.attr("href"))
                val thumb = el.selectFirst("img")?.let { getImgSrc(it) } ?: ""
                val count = el.selectFirst(".count, .videos-count")?.text()?.trim() ?: ""
                actors.add(ActorItem(name, href, thumb, count))
            }
            if (actors.isNotEmpty()) return actors
        }
        doc.select("a[href]").forEach { link ->
            val href = link.attr("href")
            if (href.contains("/model/") || href.contains("/actor/") || href.contains("/pornstar/") ||
                href.contains("/models/")
            ) {
                val img = link.selectFirst("img")
                val name = img?.attr("alt")?.trim() ?: link.text().trim()
                val thumb = img?.let { getImgSrc(it) } ?: ""
                if (name.isNotEmpty()) actors.add(ActorItem(name, resolveUrl(href), thumb))
            }
        }
        return actors.distinctBy { it.url }
    }

    override suspend fun getTags(): List<TagItem> {
        val urls = listOf(
            "$baseRoot/tags", "$baseRoot/tags/",
            "$baseRoot/tag/", "$baseRoot/categories/tags/"
        )
        for (url in urls.distinct()) {
            val doc = fetchDocument(url) ?: continue
            val tags = mutableListOf<TagItem>()
            for (el in doc.select("a.active-video-tag[href*=/tags/], a.active-video-tag[href*=/tag/]")) {
                val name = el.text().trim().removePrefix("#").trim()
                val href = resolveUrl(el.attr("href"))
                if (name.isNotEmpty()) tags.add(TagItem(name, href))
            }
            if (tags.size >= 3) return tags.distinctBy { it.url }

            val selectors = listOf(
                ".tag-list a", ".tags a", ".tag-cloud a",
                "a[href*=/tag/]", "a[href*=/tags/]"
            )
            for (selector in selectors) {
                val elements = doc.select(selector)
                if (elements.size < 3) continue
                for (el in elements) {
                    val name = el.text().trim().removePrefix("#").trim()
                    val href = resolveUrl(el.attr("href"))
                    val count = el.selectFirst(".count, .num, span.badge")?.text()?.trim() ?: ""
                    if (name.isNotEmpty() && href.isNotEmpty() && !href.endsWith("/tags") && !href.endsWith("/tags/")) {
                        tags.add(TagItem(name, href, count))
                    }
                }
                if (tags.size >= 3) return tags.distinctBy { it.url }
            }
        }
        return emptyList()
    }

    override suspend fun search(query: String, page: Int): PaginatedResult {
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

        for (searchUrl in urls.distinct()) {
            val doc = fetchDocument(searchUrl) ?: continue
            val result = parseVideoList(doc, searchUrl)
            if (result.videos.isNotEmpty()) return result
        }
        return PaginatedResult(emptyList(), false)
    }

    override suspend fun getCategories(): List<CategoryItem> {
        val urls = listOf(
            "$baseRoot/kategorien", "$baseRoot/kategorien/",
            "$baseRoot/categories/", "$baseRoot/categories",
            "$baseRoot/category/", "$baseRoot/cats/"
        )
        for (url in urls.distinct()) {
            val doc = fetchDocument(url) ?: continue
            val categories = mutableListOf<CategoryItem>()

            for (card in doc.select("li.card.category")) {
                val link = card.selectFirst("a.card-link[href]") ?: continue
                val name = card.selectFirst("h3.card-title")?.text()?.trim()
                    ?: link.text().trim()
                val href = resolveUrl(link.attr("href"))
                val thumb = link.selectFirst("img")?.let { getImgSrc(it) } ?: ""
                if (name.isNotEmpty() && href.isNotEmpty()) {
                    categories.add(CategoryItem(name, href, thumb))
                }
            }
            if (categories.isNotEmpty()) return categories

            val selectors = listOf(
                ".category-list a", ".categories a", ".category-item a",
                ".cat-item a", "a[href*=category]", ".tag-list a"
            )
            for (selector in selectors) {
                val elements = doc.select(selector)
                if (elements.isEmpty()) continue
                for (el in elements) {
                    val name = el.text().trim()
                    val href = resolveUrl(el.attr("href"))
                    val thumb = el.selectFirst("img")?.let { getImgSrc(it) } ?: ""
                    if (name.isNotEmpty() && href.isNotEmpty()) {
                        categories.add(CategoryItem(name, href, thumb))
                    }
                }
                if (categories.isNotEmpty()) return categories
            }
        }
        return emptyList()
    }

    override suspend fun getVideoDetail(pageUrl: String): VideoDetail? {
        val doc = fetchDocument(pageUrl) ?: return null
        val sources = mutableListOf<VideoSource>()

        doc.select("video source").forEach { source ->
            val src = source.attr("src")
            if (src.isNotEmpty()) {
                val type = when {
                    src.contains(".m3u8", true) -> VideoSourceType.HLS
                    src.contains(".mpd", true) -> VideoSourceType.DASH
                    else -> VideoSourceType.DIRECT
                }
                sources.add(VideoSource(resolveUrl(src), source.attr("label"), type))
            }
        }

        doc.selectFirst("video")?.attr("src")?.let { src ->
            if (src.isNotEmpty() && !src.startsWith("blob:")) {
                val type = when {
                    src.contains(".m3u8", true) -> VideoSourceType.HLS
                    else -> VideoSourceType.DIRECT
                }
                sources.add(VideoSource(resolveUrl(src), "", type))
            }
        }

        doc.select("script").forEach { script ->
            val text = script.data()
            listOf(
                Regex("""https?://[^\s'"<>]+\.m3u8[^\s'"<>]*"""),
                Regex("""https?://[^\s'"<>]+\.mp4[^\s'"<>]*"""),
                Regex("""https?://[^\s'"<>]+\.webm[^\s'"<>]*""")
            ).forEach { pattern ->
                pattern.findAll(text).forEach { match ->
                    val url = match.value.replace("\\", "")
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

        doc.selectFirst("meta[property=og:video]")?.attr("content")?.let { src ->
            if (src.isNotEmpty() && sources.none { it.url == src }) {
                sources.add(VideoSource(src, "", VideoSourceType.DIRECT))
            }
        }

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.title()

        val thumbnail = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("video")?.attr("poster") ?: ""

        val tags = doc.select(".tags a, .tag-list a, a.active-video-tag, a[href*=/tag/]")
            .map { it.text().trim().removePrefix("#").trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val related = mutableListOf<VideoItem>()
        doc.select(".few-videos .chunk-videos > .video, .related-videos .video, .related .video").forEach { el ->
            parseVideoFromElement(el)?.let { related.add(it) }
        }

        return VideoDetail(
            title = title,
            videoSources = sources,
            thumbnailUrl = thumbnail,
            tags = tags,
            relatedVideos = related.distinctBy { it.pageUrl }
        )
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
            ".thumb-list .thumb",
            ".list-videos .item",
            ".videos .video",
            ".video-block",
            ".video-card",
            ".mozaique .thumb-block",
            "article.post",
            ".post-item",
            ".content-item"
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
            doc.select("a[href]").forEach { link ->
                val img = link.selectFirst("img") ?: return@forEach
                val href = link.attr("href")
                val imgSrc = getImgSrc(img)
                if (imgSrc.isEmpty() || !imgSrc.contains("http")) return@forEach
                if (looksLikeVideoPagePath(href)) {
                    val title = img.attr("alt").ifEmpty {
                        link.attr("title").ifEmpty { link.text().trim() }
                    }
                    if (title.isNotEmpty()) {
                        val url = resolveUrl(href)
                        videos.add(
                            VideoItem(
                                id = url,
                                title = title,
                                thumbnailUrl = imgSrc,
                                pageUrl = url,
                                duration = link.selectFirst(".duration, .time")?.text()?.trim() ?: ""
                            )
                        )
                    }
                }
            }
        }

        val distinct = videos.distinctBy { it.pageUrl }

        val nextLink = doc.selectFirst(
            "a.next, a[rel=next], .pagination .next a, li.next a, " +
                ".pagination a:contains(Next), .pagination a:contains(»), .pagination a:contains(Weiter)"
        )
        val loadMore = doc.selectFirst("#load-more, button.load-btn")

        val hasNext = when {
            loadMore != null && distinct.isNotEmpty() -> true
            nextLink != null -> true
            else -> false
        }

        val nextUrl = when {
            loadMore != null && distinct.isNotEmpty() -> bumpPageQuery(currentUrl)
            nextLink != null -> resolveUrl(nextLink.attr("href"))
            else -> null
        }

        val currentPage = Regex("""[?&]page=(\d+)""").find(currentUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        return PaginatedResult(
            videos = distinct,
            hasNextPage = hasNext,
            nextPageUrl = nextUrl,
            currentPage = currentPage
        )
    }

    private fun looksLikeVideoPagePath(href: String): Boolean {
        val h = href.lowercase()
        return h.contains("/video/") || h.contains("/videos/") || h.contains("/watch/") ||
            h.contains("/v/") || h.contains("/movie/") || h.contains("/scene/")
    }

    private fun bumpPageQuery(url: String): String {
        val regex = Regex("""([?&])page=\d+""")
        return when {
            regex.containsMatchIn(url) -> regex.replace(url) { m ->
                val sep = m.groupValues[1]
                val cur = Regex("""page=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                "${sep}page=${cur + 1}"
            }
            url.contains("?") -> "$url&page=2"
            else -> "$url?page=2"
        }
    }

    private fun parseVideoFromElement(element: Element): VideoItem? {
        val link = element.selectFirst("a.cardVideo-top-link[href], a[href*=/videos/], a[href*=/video/]")
            ?: element.selectFirst("a[href]") ?: return null
        var href = resolveUrl(link.attr("href"))
        if (href.isEmpty()) return null
        if (!looksLikeVideoPagePath(href)) return null
        if (href == baseUrl || href == baseRoot || href == "$baseRoot/") return null

        val img = element.selectFirst("img.thumbnail, img")
        val thumbUrl = img?.let { getImgSrc(it) } ?: ""

        val title = element.selectFirst("h3.video-title, .title, .video-title, h3, h4, .name")?.text()?.trim()
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

        val quality = element.selectFirst(".quality, .hd, .video-quality")?.text()?.trim() ?: ""

        val date = element.selectFirst("p.date[data-published]")
            ?.attr("data-published")
            ?.toLongOrNull()
            ?.let { formatEpochDay(it) }
            ?: element.selectFirst(".date, .added, .video-date, time")?.text()?.trim() ?: ""

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
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun formatEpochDay(epochSeconds: Long): String {
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochSeconds * 1000))
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
