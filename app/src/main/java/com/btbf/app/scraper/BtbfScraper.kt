package com.btbf.app.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.regex.Pattern

/**
 * Spezifischer Scraper für BTBF (de.borntobefuck.com)
 * 
 * Wichtige Erkenntnisse zur Seitenstruktur:
 * - Alle Videos laufen über /videos (kein /new, /top, etc.)
 * - Sorting wird über Query-Parameter gelöst: ?sort=newest, ?sort=top, etc.
 * - Video-Items: div.video.ranked > a.cardVideo-top-link
 * - Titel: h3.video-title
 * - Model/Channel: p.video-channel > a[href*=/models/]
 * - Duration: div.time[data-duration] (in Sekunden!)
 * - Thumbnail: img.thumbnail
 * - Pagination: /videos?page=2
 * - Video-Player: iframe zu /videos/{id}/player?lang=de
 * - Video-URL: video#hls-video[data-hls-url] = HLS m3u8
 */
class BtbfScraper : SiteScraper {
    override val siteName = "BTBF"
    override val baseUrl = "https://de.borntobefuck.com/"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val timeout = 20000

    // Sort-Parameter für BTBF
    private val sortParams = mapOf(
        SortOrder.NEWEST to "newest",
        SortOrder.TOP to "popular",
        SortOrder.LONGEST to "longest",
        SortOrder.RANDOM to "random"
    )

    private suspend fun fetchDocument(url: String): Document? = withContext(Dispatchers.IO) {
        try {
            Jsoup.connect(url)
                .userAgent(userAgent)
                .timeout(timeout)
                .followRedirects(true)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "de,en-US;q=0.7,en;q=0.3")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .get()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun getHomePage(): PaginatedResult {
        return getVideosPage(page = 1, sort = null)
    }

    override suspend fun getCategory(categoryUrl: String): PaginatedResult {
        return getPage(categoryUrl)
    }

    override suspend fun getPage(pageUrl: String): PaginatedResult {
        val doc = fetchDocument(pageUrl) ?: return PaginatedResult(emptyList(), false)
        return parseVideoList(doc, pageUrl)
    }

    override suspend fun search(query: String, page: Int): PaginatedResult {
        // BTBF Search: /suche/ergebnisse?search=QUERY&page=2
        val searchUrl = buildString {
            append("${baseUrl}suche/ergebnisse")
            append("?search=${query.replace(" ", "+")}")
            if (page > 1) append("&page=$page")
        }
        val doc = fetchDocument(searchUrl) ?: return PaginatedResult(emptyList(), false)
        return parseVideoList(doc, searchUrl)
    }

    override suspend fun getSorted(sort: SortOrder, page: Int): PaginatedResult {
        return getVideosPage(page = page, sort = sort)
    }

    /**
     * Lädt die /videos Seite mit optionalem Sort-Parameter
     * Beispiele:
     * - /videos
     * - /videos?sort=newest&page=2
     * - /videos?sort=popular
     * - /videos?sort=longest
     * - /videos?sort=random
     */
    private suspend fun getVideosPage(page: Int, sort: SortOrder?): PaginatedResult {
        val url = buildString {
            append("${baseUrl}videos")
            if (sort != null) {
                val sortParam = sortParams[sort] ?: "newest"
                append("?sort=$sortParam")
                if (page > 1) append("&page=$page")
            } else {
                if (page > 1) append("?page=$page")
            }
        }
        val doc = fetchDocument(url) ?: return PaginatedResult(emptyList(), false)
        return parseVideoList(doc, url)
    }

    override suspend fun getCategories(): List<CategoryItem> {
        val doc = fetchDocument("${baseUrl}kategorien") ?: return emptyList()
        val categories = mutableListOf<CategoryItem>()

        // Kategorien-Struktur auf BTBF analysieren
        // Typisch: div.category-item oder a.category-link
        val selectors = listOf(
            "div.category-item",
            "div.categories-list > div",
            "a.category-link",
            "li.category",
            ".category-grid-item",
            ".mozaique .thumb-block"
        )

        for (selector in selectors) {
            val elements = doc.select(selector)
            if (elements.isNotEmpty()) {
                for (el in elements) {
                    val link = el.selectFirst("a[href]") ?: el.takeIf { it.tagName() == "a" } ?: continue
                    val href = link.attr("abs:href").takeIf { it.isNotEmpty() } ?: continue
                    
                    // Nur Kategorien-Links filtern
                    if (!href.contains("/kategorien/") && !href.contains("/category/")) continue

                    val name = el.selectFirst("h3, h4, .category-title, .title")?.text()?.trim()
                        ?: link.attr("title").takeIf { it.isNotEmpty() }
                        ?: link.text().trim()
                    
                    if (name.isEmpty()) continue

                    val thumb = el.selectFirst("img")?.let { getImgSrc(it) } ?: ""
                    val count = el.selectFirst(".count, .videos-count, .category-count")?.text()?.trim() ?: ""

                    categories.add(CategoryItem(name, href, thumb, count))
                }
                if (categories.isNotEmpty()) break
            }
        }

        // Fallback: Alle Links die wie Kategorien aussehen
        if (categories.isEmpty()) {
            doc.select("a[href]").forEach { link ->
                val href = link.attr("abs:href")
                if (href.contains("/kategorien/") || href.contains("/category/")) {
                    val name = link.text().trim()
                    val thumb = link.selectFirst("img")?.let { getImgSrc(it) } ?: ""
                    if (name.isNotEmpty()) {
                        categories.add(CategoryItem(name, href, thumb))
                    }
                }
            }
        }

        return categories.distinctBy { it.url }
    }

    override suspend fun getActors(page: Int): List<ActorItem> {
        val url = buildString {
            append("${baseUrl}models")
            if (page > 1) append("?page=$page")
        }
        val doc = fetchDocument(url) ?: return emptyList()
        val actors = mutableListOf<ActorItem>()

        // Models-Struktur: div.model-item, div.model-card, etc.
        val selectors = listOf(
            "div.model-item",
            "div.model-card",
            "div.models-list > div",
            "a.model-link",
            ".pornstar-item",
            ".performer-item"
        )

        for (selector in selectors) {
            val elements = doc.select(selector)
            if (elements.isNotEmpty()) {
                for (el in elements) {
                    val link = el.selectFirst("a[href]") ?: el.takeIf { it.tagName() == "a" } ?: continue
                    val href = link.attr("abs:href").takeIf { it.isNotEmpty() } ?: continue
                    
                    // Nur Models-Links
                    if (!href.contains("/models/") && !href.contains("/model/") && 
                        !href.contains("/pornstar/") && !href.contains("/actor/")) continue

                    val name = el.selectFirst(".name, .model-name, .title, h3, h4")?.text()?.trim()
                        ?: link.attr("title").takeIf { it.isNotEmpty() }
                        ?: link.text().trim()
                    
                    if (name.isEmpty()) continue

                    val thumb = el.selectFirst("img")?.let { getImgSrc(it) } ?: ""
                    val count = el.selectFirst(".count, .videos-count, .model-count")?.text()?.trim() ?: ""

                    actors.add(ActorItem(name, href, thumb, count))
                }
                if (actors.isNotEmpty()) break
            }
        }

        // Fallback
        if (actors.isEmpty()) {
            doc.select("a[href]").forEach { link ->
                val href = link.attr("abs:href")
                if (href.contains("/models/") || href.contains("/model/") || 
                    href.contains("/pornstar/") || href.contains("/actor/")) {
                    val name = link.text().trim()
                    val thumb = link.selectFirst("img")?.let { getImgSrc(it) } ?: ""
                    if (name.isNotEmpty()) {
                        actors.add(ActorItem(name, href, thumb))
                    }
                }
            }
        }

        return actors.distinctBy { it.url }
    }

    override suspend fun getTags(): List<TagItem> {
        val doc = fetchDocument("${baseUrl}tags") ?: return emptyList()
        val tags = mutableListOf<TagItem>()

        val selectors = listOf(
            "div.tag-item",
            "a.tag-link",
            "li.tag",
            ".tag-cloud a",
            ".tags-list a"
        )

        for (selector in selectors) {
            val elements = doc.select(selector)
            if (elements.size >= 3) {
                for (el in elements) {
                    val link = el.selectFirst("a[href]") ?: el.takeIf { it.tagName() == "a" } ?: continue
                    val href = link.attr("abs:href").takeIf { it.isNotEmpty() } ?: continue
                    
                    // Nur Tag-Links
                    if (!href.contains("/tags/") && !href.contains("/tag/")) continue

                    val name = el.selectFirst(".tag-name, .title")?.text()?.trim()
                        ?: link.attr("title").takeIf { it.isNotEmpty() }
                        ?: link.text().trim()
                    
                    if (name.isEmpty()) continue

                    val count = el.selectFirst(".count, .tag-count")?.text()?.trim() ?: ""
                    tags.add(TagItem(name, href, count))
                }
                if (tags.isNotEmpty()) break
            }
        }

        // Fallback
        if (tags.isEmpty()) {
            doc.select("a[href]").forEach { link ->
                val href = link.attr("abs:href")
                if (href.contains("/tags/") || href.contains("/tag/")) {
                    val name = link.text().trim()
                    if (name.isNotEmpty()) {
                        tags.add(TagItem(name, href))
                    }
                }
            }
        }

        return tags.distinctBy { it.name }
    }

    override suspend fun getVideoDetail(pageUrl: String): VideoDetail? {
        // Bei BTBF muss der Player-Iframe geladen werden
        // Video-Seite: https://de.borntobefuck.com/videos/24233
        // Player: https://de.borntobefuck.com/videos/24233/player?lang=de
        
        val doc = fetchDocument(pageUrl) ?: return null
        
        // 1. Versuch: Video-URL direkt von der Seite extrahieren
        val sources = extractVideoSources(doc)
        
        // 2. Versuch: Wenn keine URL gefunden, Player-Iframe laden
        if (sources.isEmpty()) {
            val playerUrl = buildPlayerUrl(pageUrl)
            val playerDoc = fetchDocument(playerUrl)
            if (playerDoc != null) {
                val playerSources = extractVideoSources(playerDoc)
                sources.addAll(playerSources)
            }
        }
        
        // Titel extrahieren
        val title = doc.selectFirst("h1.video-title, h1.title, h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.title().replace(" - BTBF", "").replace(" | BTBF", "").trim()
        
        // Thumbnail
        val thumbnail = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("video")?.attr("poster")
            ?: doc.selectFirst("img.thumbnail, img.thumb")?.let { getImgSrc(it) }
            ?: ""
        
        // Beschreibung
        val description = doc.selectFirst(".video-description, .description, .synopsis")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: ""
        
        // Tags
        val tags = doc.select(".tags a, .tag-list a, a[href*=tag]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        
        // Models/Darsteller
        val actors = mutableListOf<ActorItem>()
        doc.select("a[href*=/models/], a[href*=/model/], a[href*=/pornstar/]").forEach { link ->
            val name = link.text().trim()
            val href = link.attr("abs:href")
            val thumb = link.selectFirst("img")?.let { getImgSrc(it) } ?: ""
            if (name.isNotEmpty()) {
                actors.add(ActorItem(name, href, thumb))
            }
        }
        
        // Related Videos
        val relatedVideos = parseVideoItems(doc, ".related-videos, .related, .similar, .recommended")

        return VideoDetail(
            title = title,
            videoSources = sources,
            thumbnailUrl = thumbnail,
            description = description,
            tags = tags,
            actors = actors.map { it.name },
            relatedVideos = relatedVideos
        )
    }
    
    /**
     * Extrahiert Video-Quellen aus einem Dokument
     */
    private fun extractVideoSources(doc: Document): List<VideoSource> {
        val sources = mutableListOf<VideoSource>()
        
        // 1. HLS URL aus data-hls-url Attribut (BTBF spezifisch)
        doc.selectFirst("video#hls-video, video[data-hls-url]")?.let { video ->
            val hlsUrl = video.attr("data-hls-url")
            if (hlsUrl.isNotEmpty()) {
                sources.add(VideoSource(hlsUrl, "", VideoSourceType.HLS))
            }
        }
        
        // 2. Normale video source Tags
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
        
        // 3. Video src Attribut
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
        
        // 4. JavaScript nach Video-URLs durchsuchen
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
            
            // data-Attribute Pattern
            val dataPattern = Regex("""(?:data-(?:src|video|hls|stream|url))\s*[:=]?\s*['"](https?://[^'"]+)['"]""")
            dataPattern.findAll(text).forEach { match ->
                val url = match.groupValues[1]
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
        
        // 5. data-Attribute im DOM
        doc.select("[data-src], [data-video], [data-hls], [data-stream], [data-url]").forEach { el ->
            listOf("data-src", "data-video", "data-hls", "data-stream", "data-url").forEach { attr ->
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
        
        // 6. og:video Meta-Tag
        doc.selectFirst("meta[property=og:video]")?.attr("content")?.let { src ->
            if (src.isNotEmpty() && sources.none { it.url == src }) {
                val type = when {
                    src.contains(".m3u8", true) -> VideoSourceType.HLS
                    else -> VideoSourceType.DIRECT
                }
                sources.add(VideoSource(src, "", type))
            }
        }
        
        // HLS bevorzugen, dann nach Qualität sortieren
        return sources.sortedWith(
            compareBy<VideoSource> {
                when (it.type) {
                    VideoSourceType.HLS -> 0
                    VideoSourceType.DIRECT -> 1
                    VideoSourceType.DASH -> 2
                }
            }.thenByDescending { it.quality }
        )
    }
    
    /**
     * Baut die Player-URL aus der Video-Seiten-URL
     * Beispiel: /videos/24233 → /videos/24233/player?lang=de
     */
    private fun buildPlayerUrl(pageUrl: String): String {
        // Video-ID extrahieren
        val idPattern = Pattern.compile("/videos/(\\d+)")
        val matcher = idPattern.matcher(pageUrl)
        return if (matcher.find()) {
            val videoId = matcher.group(1)
            "${baseUrl}videos/${videoId}/player?lang=de"
        } else {
            // Fallback: URL direkt verwenden
            pageUrl
        }
    }

    /**
     * Parst die Video-Liste von einer Seite
     */
    private fun parseVideoList(doc: Document, currentUrl: String): PaginatedResult {
        val videos = mutableListOf<VideoItem>()

        // BTBF spezifische Selektoren
        // Hauptstruktur: div.video.ranked > a.cardVideo-top-link
        val primarySelector = "div.video.ranked"
        val items = doc.select(primarySelector)
        
        if (items.isNotEmpty()) {
            for (item in items) {
                parseBtbfVideoItem(item)?.let { videos.add(it) }
            }
        }
        
        // Fallback: Andere Selektoren versuchen
        if (videos.isEmpty()) {
            val fallbackSelectors = listOf(
                "div.video-item",
                "div.video-card",
                "div.thumb-block",
                ".videos-list > div",
                ".video-list > div",
                "article.video"
            )
            
            for (selector in fallbackSelectors) {
                val fallbackItems = doc.select(selector)
                if (fallbackItems.size >= 2) {
                    for (item in fallbackItems) {
                        parseVideoFromElement(item)?.let { videos.add(it) }
                    }
                    if (videos.isNotEmpty()) break
                }
            }
        }
        
        // Pagination erkennen
        val hasNext = doc.selectFirst("a.next, a[rel=next], .pagination .next a, li.next a, .pagination a:contains(Weiter)") != null
        val nextPageUrl = doc.selectFirst("a.next, a[rel=next], .pagination .next a, li.next a")
            ?.attr("abs:href")
            ?.takeIf { it.isNotEmpty() }
        
        // Aktuelle Seite erkennen
        val currentPage = doc.selectFirst(".pagination .active, .pagination .current, .pagination > strong")
            ?.text()?.trim()?.toIntOrNull() ?: 1
        
        return PaginatedResult(
            videos = videos.distinctBy { it.pageUrl },
            hasNextPage = hasNext,
            nextPageUrl = nextPageUrl,
            currentPage = currentPage
        )
    }
    
    /**
     * Parst ein BTBF-spezifisches Video-Item
     * Struktur:
     * <div class="video ranked">
     *   <a class="cardVideo-top-link" href="/videos/24233">
     *     <img class="thumbnail" src="..." alt="...">
     *   </a>
     *   <h3 class="video-title">Titel</h3>
     *   <p class="video-channel"><a href="/models/...">Model</a></p>
     *   <div class="time" data-duration="1234">20:34</div>
     * </div>
     */
    private fun parseBtbfVideoItem(element: Element): VideoItem? {
        // Link finden
        val link = element.selectFirst("a.cardVideo-top-link, a[href^=/videos/]") ?: return null
        val href = link.attr("abs:href").takeIf { it.isNotEmpty() } ?: return null
        
        // Thumbnail
        val img = link.selectFirst("img.thumbnail, img.thumb, img")
        val thumbUrl = img?.let { getImgSrc(it) } ?: ""
        
        // Titel
        val title = element.selectFirst("h3.video-title, h3.title, .video-title, .title")?.text()?.trim()
            ?: img?.attr("alt")?.trim()
            ?: img?.attr("title")?.trim()
            ?: link.attr("title").trim()
        
        if (title.isEmpty()) return null
        
        // Duration (in Sekunden als data-duration, angezeigt als MM:SS)
        val durationElement = element.selectFirst("div.time, .duration, .video-duration")
        val duration = durationElement?.attr("data-duration")?.let { secondsToDuration(it.toLongOrNull()) }
            ?: durationElement?.text()?.trim()
            ?: ""
        
        // Views
        val views = element.selectFirst(".views, .video-views, .view-count")?.text()?.trim() ?: ""
        
        // Quality
        val quality = element.selectFirst(".quality, .hd, .video-quality")?.text()?.trim() ?: ""
        
        // Date
        val date = element.selectFirst(".date, .added, .video-date, time")?.text()?.trim() ?: ""
        
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

    /**
     * Parst ein generisches Video-Item (Fallback)
     */
    private fun parseVideoFromElement(element: Element): VideoItem? {
        val link = element.selectFirst("a[href]") ?: return null
        val href = link.attr("abs:href").takeIf { it.isNotEmpty() } ?: return null
        if (href == baseUrl) return null

        val img = element.selectFirst("img")
        val thumbUrl = img?.let { getImgSrc(it) } ?: ""

        val title = element.selectFirst(".title, .video-title, h3, h4, .name")?.text()?.trim()
            ?: img?.attr("alt")?.trim()
            ?: link.attr("title").trim()
            ?: link.text().trim()
        
        if (title.isEmpty()) return null

        val duration = element.selectFirst(".duration, .time, .length, .video-duration")
            ?.text()?.trim() ?: ""

        val views = element.selectFirst(".views, .video-views, .view-count")?.text()?.trim() ?: ""
        val quality = element.selectFirst(".quality, .hd, .video-quality")?.text()?.trim() ?: ""
        val date = element.selectFirst(".date, .added, .video-date, time")?.text()?.trim() ?: ""

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

    /**
     * Parst Related Videos aus einem Container
     */
    private fun parseVideoItems(doc: Document, containerSelector: String): List<VideoItem> {
        val videos = mutableListOf<VideoItem>()
        
        doc.select(containerSelector).firstOrNull()?.let { container ->
            container.select("div.video.ranked").forEach { item ->
                parseBtbfVideoItem(item)?.let { videos.add(it) }
            }
            
            // Fallback
            if (videos.isEmpty()) {
                container.select("a[href]").forEach { link ->
                    val img = link.selectFirst("img") ?: return@forEach
                    val href = link.attr("abs:href")
                    val title = img.attr("alt").ifEmpty { link.text().trim() }
                    val thumb = getImgSrc(img)
                    if (title.isNotEmpty() && thumb.isNotEmpty()) {
                        videos.add(VideoItem(href.hashCode().toString(), title, thumb, href))
                    }
                }
            }
        }
        
        return videos
    }

    /**
     * Holt das Bild-src Attribut (unterstützt lazy-loading)
     */
    private fun getImgSrc(img: Element): String {
        // Verschiedene lazy-loading Patterns prüfen
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
    
    /**
     * Konvertiert Sekunden in MM:SS Format
     */
    private fun secondsToDuration(seconds: Long?): String {
        if (seconds == null || seconds <= 0) return ""
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", minutes, secs)
    }
}
