package com.btbf.app.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class GenericScraper(
    override val siteName: String,
    override val baseUrl: String
) : SiteScraper {

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

    override suspend fun getHomePage(): PaginatedResult = getPage(baseUrl)

    override suspend fun getCategory(categoryUrl: String): PaginatedResult = getPage(categoryUrl)

    override suspend fun getPage(pageUrl: String): PaginatedResult {
        val doc = fetchDocument(pageUrl) ?: return PaginatedResult(emptyList(), false)
        return parseVideoList(doc)
    }

    override suspend fun search(query: String, page: Int): PaginatedResult {
        // Verschiedene Search-URL Patterns versuchen
        val searchUrls = listOf(
            "${baseUrl}search/${query.replace(" ", "+")}/${if (page > 1) "?page=$page" else ""}",
            "${baseUrl}?s=${query.replace(" ", "+")}${if (page > 1) "&page=$page" else ""}",
            "${baseUrl}search/?q=${query.replace(" ", "+")}${if (page > 1) "&page=$page" else ""}"
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
        val urls = listOf("${baseUrl}categories/", "${baseUrl}categories", "${baseUrl}tags/")
        for (url in urls) {
            val doc = fetchDocument(url) ?: continue
            val categories = mutableListOf<CategoryItem>()
            val selectors = listOf(
                ".category-list a", ".categories a", ".category-item a",
                ".cat-item a", "a[href*=category]", ".tag-list a", ".tags a"
            )
            for (selector in selectors) {
                val elements = doc.select(selector)
                if (elements.isNotEmpty()) {
                    for (el in elements) {
                        val name = el.text().trim()
                        val href = resolveUrl(el.attr("href"))
                        val thumb = el.selectFirst("img")?.let { getImgSrc(it) } ?: ""
                        if (name.isNotEmpty() && href.isNotEmpty()) {
                            categories.add(CategoryItem(name, href, thumb))
                        }
                    }
                    return categories
                }
            }
        }
        return emptyList()
    }

    override suspend fun getVideoDetail(pageUrl: String): VideoDetail? {
        val doc = fetchDocument(pageUrl) ?: return null
        val sources = mutableListOf<VideoSource>()

        // Video sources aus HTML
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

        // JavaScript nach Video-URLs durchsuchen
        doc.select("script").forEach { script ->
            val text = script.data()
            val patterns = listOf(
                Regex("""https?://[^\s'"<>]+\.m3u8[^\s'"<>]*"""),
                Regex("""https?://[^\s'"<>]+\.mp4[^\s'"<>]*"""),
                Regex("""https?://[^\s'"<>]+\.webm[^\s'"<>]*""")
            )
            for (pattern in patterns) {
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

        val tags = doc.select(".tags a, .tag-list a, a[href*=tag]")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

        return VideoDetail(
            title = title,
            videoSources = sources,
            thumbnailUrl = thumbnail,
            tags = tags
        )
    }

    private fun parseVideoList(doc: Document): PaginatedResult {
        val videos = mutableListOf<VideoItem>()

        val containerSelectors = listOf(
            ".videos-list .video-item", ".video-list .video-item",
            ".thumbs .thumb", ".thumb-list .thumb",
            ".list-videos .item", ".videos .video",
            ".video-block", ".video-card",
            ".mozaique .thumb-block",
            "article.post", ".post-item", ".content-item"
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

        // Fallback
        if (videos.isEmpty()) {
            doc.select("a[href]").forEach { link ->
                val img = link.selectFirst("img") ?: return@forEach
                val href = link.attr("href")
                val imgSrc = getImgSrc(img)
                if (imgSrc.isEmpty() || !imgSrc.contains("http")) return@forEach
                if (href.contains("/video/") || href.contains("/watch/") || href.contains("/v/")) {
                    val title = img.attr("alt").ifEmpty { link.text().trim() }
                    if (title.isNotEmpty()) {
                        val url = resolveUrl(href)
                        videos.add(VideoItem(url.hashCode().toString(), title, imgSrc, url))
                    }
                }
            }
        }

        val nextPage = doc.selectFirst(
            "a.next, a[rel=next], .pagination .next a, .pagination a:contains(Next), .pagination a:contains(»)"
        )

        return PaginatedResult(
            videos = videos.distinctBy { it.pageUrl },
            hasNextPage = nextPage != null,
            nextPageUrl = nextPage?.attr("href")?.let { resolveUrl(it) }
        )
    }

    private fun parseVideoFromElement(element: Element): VideoItem? {
        val link = element.selectFirst("a[href]") ?: return null
        val href = resolveUrl(link.attr("href"))
        if (href.isEmpty() || href == baseUrl) return null
        val img = element.selectFirst("img")
        val thumbUrl = if (img != null) getImgSrc(img) else ""
        val title = element.selectFirst(".title, .video-title, h3, h4, .name")?.text()?.trim()
            ?: img?.attr("alt")?.trim()
            ?: link.attr("title").trim()
        if (title.isEmpty()) return null
        val duration = element.selectFirst(".duration, .time, .length")?.text()?.trim() ?: ""
        return VideoItem(href.hashCode().toString(), title, thumbUrl, href, duration)
    }

    private fun getImgSrc(img: Element): String {
        return img.attr("data-src").ifEmpty {
            img.attr("data-lazy-src").ifEmpty {
                img.attr("data-original").ifEmpty {
                    img.attr("data-thumb").ifEmpty { img.attr("src") }
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
