package com.btbf.app.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Generischer Scraper für alle Seiten außer BTBF
 * 
 * Unterstützte Seiten:
 * - CamCaps (camcaps.tv)
 * - FyxXR (fyxxr.com)
 * - SheeshFans (sheeshfans.com)
 * - LeakPorner (leakporner.com)
 * 
 * Dieser Scraper verwendet multiple Selektoren und Fallbacks,
 * um mit verschiedenen Seitenstrukturen umzugehen.
 */
class GenericScraper(
    override val siteName: String,
    override val baseUrl: String
) : SiteScraper {

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val timeout = 20000

    private suspend fun fetchDocument(url: String): Document? = withContext(Dispatchers.IO) {
        try {
            Jsoup.connect(url)
                .userAgent(userAgent)
                .timeout(timeout)
                .followRedirects(true)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "de,en-US;q=0.7,en;q=0.3")
                .header("Connection", "keep-alive")
                .get()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun getHomePage(): PaginatedResult = getPage(baseUrl)

    override suspend fun getCategory(categoryUrl: String): PaginatedResult = getPage(categoryUrl)

    override suspend fun getPage(pageUrl: String): PaginatedResult {
        val doc = fetchDocument(pageUrl) ?: return PaginatedResult(emptyList(), false)
        return parseVideoList(doc)
    }

    override suspend fun getSorted(sort: SortOrder, page: Int): PaginatedResult {
        // Verschiedene URL-Patterns für Sortierung
        val urls = listOf(
            "${baseUrl}${sort.path}/${if (page > 1) "?page=$page" else ""}",
            "${baseUrl}?sort=${sort.path}&page=${if (page > 1) page else ""}",
            "${baseUrl}videos/?sort=${sort.path}${if (page > 1) "&page=$page" else ""}",
            "${baseUrl}watch/?sort=${sort.path}${if (page > 1) "&page=$page" else ""}"
        )
        
        for (url in urls) {
            val doc = fetchDocument(url)
            if (doc != null) {
                val result = parseVideoList(doc)
                if (result.videos.isNotEmpty()) return result
            }
        }
        return PaginatedResult(emptyList(), false)
    }

    override suspend fun getActors(page: Int): List<ActorItem> {
        val urls = listOf(
            "${baseUrl}models/",
            "${baseUrl}actors/",
            "${baseUrl}pornstars/",
            "${baseUrl}girls/",
            "${baseUrl}performers/",
            "${baseUrl}stars/"
        )
        
        for (url in urls) {
            val pageUrl = if (page > 1) "${url}?page=$page" else url
            val doc = fetchDocument(pageUrl) ?: continue

            val actors = mutableListOf<ActorItem>()
            
            // Multiple Selektoren für Model/Actor-Listen
            val selectors = listOf(
                ".model-list .model-item",
                ".models-list .model",
                ".pornstar-list .pornstar",
                ".actor-list .actor",
                ".performer-list .performer",
                ".stars-list .star",
                ".list-models .item",
                ".model-block",
                ".model-card",
                ".actor-card",
                ".thumb-block.model",
                ".grid-item.model"
            )
            
            for (selector in selectors) {
                val elements = doc.select(selector)
                if (elements.isNotEmpty()) {
                    for (el in elements) {
                        val link = el.selectFirst("a[href]") ?: continue
                        val name = el.selectFirst(".name, .title, .model-name, .actor-name, h3, h4")?.text()?.trim()
                            ?: el.selectFirst("img")?.attr("alt")?.trim()
                            ?: link.attr("title").trim()
                            ?: link.text().trim()
                        if (name.isEmpty()) continue

                        val href = resolveUrl(link.attr("href"))
                        val thumb = el.selectFirst("img")?.let { getImgSrc(it) } ?: ""
                        val count = el.selectFirst(".count, .videos-count, .num, .video-count")?.text()?.trim() ?: ""

                        actors.add(ActorItem(name, href, thumb, count))
                    }
                    return actors
                }
            }

            // Fallback: Alle Links die wie Model-Seiten aussehen
            doc.select("a[href]").forEach { link ->
                val href = link.attr("href")
                if (href.contains("/model/") || href.contains("/actor/") || 
                    href.contains("/pornstar/") || href.contains("/girl/") ||
                    href.contains("/performer/") || href.contains("/star/")) {
                    val img = link.selectFirst("img")
                    val name = img?.attr("alt")?.trim() ?: link.text().trim()
                    val thumb = img?.let { getImgSrc(it) } ?: ""
                    if (name.isNotEmpty()) {
                        actors.add(ActorItem(name, resolveUrl(href), thumb))
                    }
                }
            }
            
            if (actors.isNotEmpty()) return actors
        }
        return emptyList()
    }

    override suspend fun getTags(): List<TagItem> {
        val urls = listOf(
            "${baseUrl}tags/",
            "${baseUrl}tag/",
            "${baseUrl}categories/tags/"
        )
        
        for (url in urls) {
            val doc = fetchDocument(url) ?: continue

            val tags = mutableListOf<TagItem>()
            
            val selectors = listOf(
                ".tag-list a",
                ".tags a",
                ".tag-cloud a",
                ".tags-list a",
                "a[href*=tag]",
                "li.tag a",
                ".tag-item a"
            )
            
            for (selector in selectors) {
                val elements = doc.select(selector)
                if (elements.size >= 3) {
                    for (el in elements) {
                        val name = el.text().trim()
                        val href = resolveUrl(el.attr("href"))
                        val count = el.selectFirst(".count, .num, span.badge")?.text()?.trim() ?: ""
                        if (name.isNotEmpty() && href.isNotEmpty()) {
                            tags.add(TagItem(name, href, count))
                        }
                    }
                    return tags.distinctBy { it.name }
                }
            }
        }
        return emptyList()
    }

    override suspend fun search(query: String, page: Int): PaginatedResult {
        // Verschiedene Search-URL Patterns
        val searchUrls = listOf(
            "${baseUrl}search/${query.replace(" ", "+")}/${if (page > 1) "?page=$page" else ""}",
            "${baseUrl}?s=${query.replace(" ", "+")}${if (page > 1) "&page=$page" else ""}",
            "${baseUrl}search/?q=${query.replace(" ", "+")}${if (page > 1) "&page=$page" else ""}",
            "${baseUrl}search/?query=${query.replace(" ", "+")}${if (page > 1) "&page=$page" else ""}",
            "${baseUrl}videos/?search=${query.replace(" ", "+")}${if (page > 1) "&page=$page" else ""}"
        )

        for (searchUrl in searchUrls) {
            val doc = fetchDocument(searchUrl)
            if (doc != null) {
                val result = parseVideoList(doc)
                if (result.videos.isNotEmpty()) return result
            }
        }
        return PaginatedResult(emptyList(), false)
    }

    override suspend fun getCategories(): List<CategoryItem> {
        val urls = listOf(
            "${baseUrl}categories/",
            "${baseUrl}categories",
            "${baseUrl}categories/tags/",
            "${baseUrl}channels/"
        )
        
        for (url in urls) {
            val doc = fetchDocument(url) ?: continue

            val categories = mutableListOf<CategoryItem>()
            
            val selectors = listOf(
                ".category-list a",
                ".categories a",
                ".category-item a",
                ".cat-item a",
                "a[href*=category]",
                ".channel-list a",
                ".channels a",
                ".channel-item a",
                ".tag-list a",
                ".tags a"
            )

            for (selector in selectors) {
                val elements = doc.select(selector)
                if (elements.isNotEmpty()) {
                    for (el in elements) {
                        val name = el.text().trim()
                        val href = resolveUrl(el.attr("href"))
                        val thumb = el.selectFirst("img")?.let { getImgSrc(it) } ?: ""
                        val count = el.selectFirst(".count, .num, span")?.text()?.trim() ?: ""
                        if (name.isNotEmpty() && href.isNotEmpty()) {
                            categories.add(CategoryItem(name, href, thumb, count))
                        }
                    }
                    return categories.distinctBy { it.url }
                }
            }
        }
        return emptyList()
    }

    override suspend fun getVideoDetail(pageUrl: String): VideoDetail? {
        val doc = fetchDocument(pageUrl) ?: return null

        val sources = mutableListOf<VideoSource>()

        // 1. Video source Tags
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
                sources.add(VideoSource(src, quality, type))
            }
        }

        // 2. Video src Attribut
        doc.selectFirst("video")?.let { video ->
            val src = video.attr("src")
            if (src.isNotEmpty() && !src.startsWith("blob:")) {
                val type = when {
                    src.contains(".m3u8", true) -> VideoSourceType.HLS
                    src.contains(".mpd", true) -> VideoSourceType.DASH
                    else -> VideoSourceType.DIRECT
                }
                sources.add(VideoSource(src, "", type))
            }
        }

        // 3. JavaScript nach Video-URLs durchsuchen
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

            // MP4/WebM URLs
            val mp4Pattern = Regex("""https?://[^\s'"<>]+\.mp4[^\s'"<>]*""")
            mp4Pattern.findAll(text).forEach { match ->
                val url = match.value.replace("\\", "")
                if (sources.none { it.url == url }) {
                    sources.add(VideoSource(url, "", VideoSourceType.DIRECT))
                }
            }
            
            val webmPattern = Regex("""https?://[^\s'"<>]+\.webm[^\s'"<>]*""")
            webmPattern.findAll(text).forEach { match ->
                val url = match.value.replace("\\", "")
                if (sources.none { it.url == url }) {
                    sources.add(VideoSource(url, "", VideoSourceType.DIRECT))
                }
            }

            // Player Patterns
            val playerPatterns = listOf(
                Regex("""(?:file|src|source|video_url|videoUrl)\s*[:=]\s*['"](https?://[^'"]+)['"]"""),
                Regex("""(?:file|src)\s*:\s*['"](https?://[^'"]+\.(?:mp4|m3u8|webm))['"]"""),
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

        // 4. data-Attribute
        doc.select("[data-src], [data-video], [data-hls], [data-stream]").forEach { el ->
            listOf("data-src", "data-video", "data-hls", "data-stream").forEach { attr ->
                val value = el.attr(attr)
                if (value.matches(Regex(""".*\.(mp4|m3u8|webm|mpd).*"""))) {
                    val type = when {
                        value.contains(".m3u8", true) -> VideoSourceType.HLS
                        value.contains(".mpd", true) -> VideoSourceType.DASH
                        else -> VideoSourceType.DIRECT
                    }
                    if (sources.none { it.url == value }) {
                        sources.add(VideoSource(value, "", type))
                    }
                }
            }
        }

        // 5. og:video Meta-Tag
        doc.selectFirst("meta[property=og:video]")?.attr("content")?.let { src ->
            if (src.isNotEmpty() && sources.none { it.url == src }) {
                val type = when {
                    src.contains(".m3u8", true) -> VideoSourceType.HLS
                    else -> VideoSourceType.DIRECT
                }
                sources.add(VideoSource(src, "", type))
            }
        }

        // Metadaten extrahieren
        val title = doc.selectFirst("h1, .video-title, .title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.title()
        
        val thumbnail = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("video")?.attr("poster")
            ?: ""
        
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".description, .video-description, .synopsis")?.text()?.trim()
            ?: ""
        
        val tags = doc.select(".tags a, .tag-list a, a[href*=tag]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // Sortieren: HLS zuerst, dann nach Qualität
        val sortedSources = sources.sortedWith(
            compareBy<VideoSource> {
                when (it.type) {
                    VideoSourceType.HLS -> 0
                    VideoSourceType.DIRECT -> 1
                    VideoSourceType.DASH -> 2
                }
            }.thenByDescending { it.quality }
        )

        return VideoDetail(
            title = title,
            videoSources = sortedSources,
            thumbnailUrl = thumbnail,
            description = description,
            tags = tags
        )
    }

    /**
     * Parst eine Video-Liste von einer Seite
     */
    private fun parseVideoList(doc: Document): PaginatedResult {
        val videos = mutableListOf<VideoItem>()

        // Site-spezifische Selektoren
        val siteSelectors = getSiteSpecificSelectors()
        
        // Generische Selektoren als Fallback
        val genericSelectors = listOf(
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
            "article.post",
            ".post-item",
            ".content-item",
            ".grid-item.video",
            ".video-grid-item"
        )
        
        val allSelectors = siteSelectors + genericSelectors

        for (selector in allSelectors) {
            val items = doc.select(selector)
            if (items.size >= 1) {
                for (item in items) {
                    parseVideoFromElement(item)?.let { videos.add(it) }
                }
                if (videos.isNotEmpty()) break
            }
        }

        // Fallback: Alle Links mit Thumbnails suchen
        if (videos.isEmpty()) {
            doc.select("a[href]").forEach { link ->
                val img = link.selectFirst("img") ?: return@forEach
                val href = link.attr("href")
                val imgSrc = getImgSrc(img)
                
                if (imgSrc.isEmpty() || !imgSrc.contains("http")) return@forEach

                // Nur Links die wie Video-Seiten aussehen
                if (href.contains("/video/") || href.contains("/watch/") || 
                    href.contains("/v/") || href.contains("/play/") ||
                    href.matches(Regex(""".+/[a-z0-9-]+/?$"""))) {
                    val title = img.attr("alt").ifEmpty { 
                        link.attr("title").ifEmpty { 
                            link.text().trim() 
                        }
                    }
                    if (title.isNotEmpty()) {
                        val url = resolveUrl(href)
                        val duration = link.selectFirst(".duration, .time, .length, .video-duration")
                            ?.text()?.trim() ?: ""
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
            ".pagination a:contains(next), .pagination a:contains(»), li.next a, " +
            ".pagination .active + a, button:contains(Next), button:contains(Load More)"
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
    
    /**
     * Gibt site-spezifische Selektoren zurück
     */
    private fun getSiteSpecificSelectors(): List<String> {
        return when {
            // FyxXR spezifische Selektoren
            siteName == "FyxXR" -> listOf(
                "div.grid > div.w-full",
                "a.block[href^=\"/video/\"]",
                "div.overflow-hidden.rounded-lg"
            )
            // CamCaps spezifische Selektoren
            siteName == "CamCaps" -> listOf(
                ".video-item",
                ".thumb-block",
                ".video-card"
            )
            // SheeshFans spezifische Selektoren
            siteName == "SheeshFans" -> listOf(
                ".video-item",
                ".video-card",
                ".thumb-block"
            )
            // LeakPorner spezifische Selektoren
            siteName == "LeakPorner" -> listOf(
                ".video-item",
                ".video-card",
                ".thumb-block",
                ".post-item"
            )
            else -> emptyList()
        }
    }

    /**
     * Parst ein Video-Item aus einem Element
     */
    private fun parseVideoFromElement(element: Element): VideoItem? {
        // Link finden
        val link = element.selectFirst("a[href]") ?: return null
        val href = resolveUrl(link.attr("href"))
        if (href.isEmpty() || href == baseUrl) return null

        // Thumbnail
        val img = element.selectFirst("img")
        val thumbUrl = if (img != null) getImgSrc(img) else ""

        // Titel
        val title = element.selectFirst(".title, .video-title, h3, h4, .name, p.line-clamp-2")?.text()?.trim()
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
        val quality = element.selectFirst(".quality, .hd, .video-quality, .badge")
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

    private fun getImgSrc(img: Element): String {
        // Verschiedene lazy-loading Patterns
        return img.attr("data-src").ifEmpty {
            img.attr("data-lazy-src").ifEmpty {
                img.attr("data-original").ifEmpty {
                    img.attr("data-thumb").ifEmpty {
                        img.attr("data-srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull().ifEmpty {
                            img.attr("src")
                        }
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
