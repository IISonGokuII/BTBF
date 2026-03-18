package com.btbf.app.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class BtbfScraper : SiteScraper {
    override val siteName = "BTBF"
    override val baseUrl = "https://de.borntobefuck.com/"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val timeout = 15000

    private suspend fun fetchDocument(url: String): Document? = withContext(Dispatchers.IO) {
        try {
            Jsoup.connect(url)
                .userAgent(userAgent)
                .timeout(timeout)
                .followRedirects(true)
                .get()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getHomePage(): PaginatedResult {
        return getPage(baseUrl)
    }

    override suspend fun getCategory(categoryUrl: String): PaginatedResult {
        return getPage(categoryUrl)
    }

    override suspend fun getPage(pageUrl: String): PaginatedResult {
        val doc = fetchDocument(pageUrl) ?: return PaginatedResult(emptyList(), false)
        return parseVideoList(doc, pageUrl)
    }

    override suspend fun search(query: String, page: Int): PaginatedResult {
        val searchUrl = "${baseUrl}search/${query.replace(" ", "+")}/${if (page > 1) "?page=$page" else ""}"
        val doc = fetchDocument(searchUrl) ?: return PaginatedResult(emptyList(), false)
        return parseVideoList(doc, searchUrl)
    }

    override suspend fun getCategories(): List<CategoryItem> {
        val doc = fetchDocument("${baseUrl}categories/") ?: return emptyList()
        val categories = mutableListOf<CategoryItem>()

        // Versuche verschiedene Selektoren für Kategorien
        val categorySelectors = listOf(
            ".category-list a", ".categories a", ".category-item a",
            ".cat-item a", "a[href*=category]", "a[href*=categories]",
            ".tag-list a", ".tags a"
        )

        for (selector in categorySelectors) {
            val elements = doc.select(selector)
            if (elements.isNotEmpty()) {
                for (el in elements) {
                    val name = el.text().trim()
                    val url = resolveUrl(el.attr("href"))
                    val thumb = el.selectFirst("img")?.let { getImgSrc(it) } ?: ""
                    val count = el.selectFirst(".count, .num, span")?.text()?.trim() ?: ""
                    if (name.isNotEmpty() && url.isNotEmpty()) {
                        categories.add(CategoryItem(name, url, thumb, count))
                    }
                }
                break
            }
        }

        return categories
    }

    override suspend fun getVideoDetail(pageUrl: String): VideoDetail? {
        val doc = fetchDocument(pageUrl) ?: return null
        val sources = mutableListOf<VideoSource>()

        // 1. <video> Tags suchen
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

        // Video src direkt
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

        // 2. JavaScript nach Video-URLs durchsuchen
        doc.select("script").forEach { script ->
            val text = script.data()

            // HLS URLs
            val hlsPattern = Regex("""https?://[^\s'"<>]+\.m3u8[^\s'"<>]*""")
            hlsPattern.findAll(text).forEach { match ->
                val url = match.value.replace("\\", "")
                if (sources.none { it.url == url }) {
                    sources.add(VideoSource(url, "", VideoSourceType.HLS))
                }
            }

            // MP4 URLs
            val mp4Pattern = Regex("""https?://[^\s'"<>]+\.mp4[^\s'"<>]*""")
            mp4Pattern.findAll(text).forEach { match ->
                val url = match.value.replace("\\", "")
                if (sources.none { it.url == url }) {
                    sources.add(VideoSource(url, "", VideoSourceType.DIRECT))
                }
            }

            // Gängige JS-Patterns für Video-Player
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

        // 3. data-Attribute
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

        // 4. og:video Meta-Tag
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

        val tags = doc.select(".tags a, .tag-list a, a[href*=tag], a[href*=category]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // Related Videos
        val relatedVideos = parseVideoItems(doc, ".related-videos, .related, .similar")

        // HLS bevorzugen, dann nach Qualität sortieren
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
            relatedVideos = relatedVideos
        )
    }

    private fun parseVideoList(doc: Document, currentUrl: String): PaginatedResult {
        val videos = mutableListOf<VideoItem>()

        // Versuche verschiedene gängige Selektoren für Video-Grids
        val containerSelectors = listOf(
            ".videos-list .video-item",
            ".video-list .video-item",
            ".thumbs .thumb",
            ".thumb-list .thumb",
            ".video-listing .video-thumb",
            ".list-videos .item",
            ".videos .video",
            ".video-block",
            ".video-card",
            ".mozaique .thumb-block",
            ".well .well-sm",
            ".col-sm-6, .col-md-4, .col-lg-3",
            "article.post",
            ".post-item",
            ".content-item"
        )

        for (selector in containerSelectors) {
            val items = doc.select(selector)
            if (items.size >= 2) {
                for (item in items) {
                    parseVideoFromElement(item)?.let { videos.add(it) }
                }
                break
            }
        }

        // Fallback: Alle Links mit Thumbnails suchen
        if (videos.isEmpty()) {
            val allLinks = doc.select("a[href]")
            for (link in allLinks) {
                val img = link.selectFirst("img") ?: continue
                val href = link.attr("href")
                val imgSrc = getImgSrc(img)
                if (imgSrc.isEmpty()) continue

                // Nur Links die wie Video-Seiten aussehen
                if (href.contains("/video/") || href.contains("/watch/") ||
                    href.contains("/v/") || href.matches(Regex(""".+/[a-z0-9-]+/?$"""))
                ) {
                    val title = img.attr("alt").ifEmpty {
                        link.attr("title").ifEmpty {
                            link.text().trim()
                        }
                    }
                    if (title.isNotEmpty() && imgSrc.contains("http")) {
                        val url = resolveUrl(href)
                        val duration = link.selectFirst(".duration, .time, .length, .video-duration")?.text()?.trim() ?: ""
                        videos.add(
                            VideoItem(
                                id = url.hashCode().toString(),
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

        // Pagination erkennen
        val nextPage = doc.selectFirst(
            "a.next, a[rel=next], .pagination .next a, .pagination a:contains(Next), " +
            ".pagination a:contains(next), .pagination a:contains(»), li.next a"
        )
        val hasNext = nextPage != null
        val nextUrl = nextPage?.attr("href")?.let { resolveUrl(it) }

        // Aktuelle Seite erkennen
        val currentPage = doc.selectFirst(".pagination .active, .pagination .current")
            ?.text()?.trim()?.toIntOrNull() ?: 1

        return PaginatedResult(
            videos = videos.distinctBy { it.pageUrl },
            hasNextPage = hasNext,
            nextPageUrl = nextUrl,
            currentPage = currentPage
        )
    }

    private fun parseVideoFromElement(element: Element): VideoItem? {
        // Link finden
        val link = element.selectFirst("a[href]") ?: return null
        val href = resolveUrl(link.attr("href"))
        if (href.isEmpty() || href == baseUrl) return null

        // Thumbnail
        val img = element.selectFirst("img")
        val thumbUrl = if (img != null) getImgSrc(img) else ""

        // Title
        val title = element.selectFirst(".title, .video-title, h3, h4, .name")?.text()?.trim()
            ?: img?.attr("alt")?.trim()
            ?: link.attr("title").trim()
            ?: link.text().trim()

        if (title.isEmpty()) return null

        // Duration
        val duration = element.selectFirst(".duration, .time, .length, .video-duration, .thumb-duration")
            ?.text()?.trim() ?: ""

        // Views
        val views = element.selectFirst(".views, .video-views, .view-count")
            ?.text()?.trim() ?: ""

        // Quality
        val quality = element.selectFirst(".quality, .hd, .video-quality")
            ?.text()?.trim() ?: ""

        // Date
        val date = element.selectFirst(".date, .added, .video-date, time")
            ?.text()?.trim() ?: ""

        return VideoItem(
            id = href.hashCode().toString(),
            title = title,
            thumbnailUrl = thumbUrl,
            pageUrl = href,
            duration = duration,
            views = views,
            quality = quality,
            date = date
        )
    }

    private fun parseVideoItems(doc: Document, containerSelector: String): List<VideoItem> {
        val videos = mutableListOf<VideoItem>()
        doc.select(containerSelector).firstOrNull()?.let { container ->
            container.select("a[href]").forEach { link ->
                val img = link.selectFirst("img") ?: return@forEach
                val href = resolveUrl(link.attr("href"))
                val title = img.attr("alt").ifEmpty { link.text().trim() }
                val thumb = getImgSrc(img)
                if (title.isNotEmpty() && thumb.isNotEmpty()) {
                    videos.add(VideoItem(href.hashCode().toString(), title, thumb, href))
                }
            }
        }
        return videos
    }

    private fun getImgSrc(img: Element): String {
        // Verschiedene lazy-loading Patterns
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
        if (url.startsWith("/")) return baseUrl.trimEnd('/') + url
        return baseUrl.trimEnd('/') + "/" + url
    }
}
