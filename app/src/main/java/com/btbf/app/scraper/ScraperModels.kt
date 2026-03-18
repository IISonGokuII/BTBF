package com.btbf.app.scraper

/**
 * Datenmodelle für den Scraper
 */

/**
 * Ein Video in der Übersicht
 */
data class VideoItem(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val pageUrl: String,
    val duration: String = "",
    val views: String = "",
    val quality: String = "",
    val date: String = ""
)

/**
 * Details zu einem Video mit Video-Quellen
 */
data class VideoDetail(
    val title: String,
    val videoSources: List<VideoSource>,
    val thumbnailUrl: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val relatedVideos: List<VideoItem> = emptyList()
)

/**
 * Eine Video-Quelle mit URL und Typ
 */
data class VideoSource(
    val url: String,
    val quality: String = "",
    val type: VideoSourceType = VideoSourceType.DIRECT
)

/**
 * Typ der Video-Quelle
 */
enum class VideoSourceType {
    DIRECT,     // Direkte MP4/WebM Datei
    HLS,        // HLS Stream (.m3u8)
    DASH        // DASH Stream (.mpd)
}

/**
 * Eine Kategorie
 */
data class CategoryItem(
    val name: String,
    val url: String,
    val thumbnailUrl: String = "",
    val count: String = ""
)

/**
 * Ein Model/Darsteller
 */
data class ActorItem(
    val name: String,
    val url: String,
    val thumbnailUrl: String = "",
    val videoCount: String = ""
)

/**
 * Ein Tag
 */
data class TagItem(
    val name: String,
    val url: String,
    val count: String = ""
)

/**
 * Sortieroptionen
 */
enum class SortOrder(val label: String, val path: String) {
    NEWEST("Neueste", "new"),
    TOP("Beliebteste", "top"),
    LONGEST("Längste", "longest"),
    RANDOM("Zufall", "random")
}

/**
 * Paginiertes Ergebnis mit Videos
 */
data class PaginatedResult(
    val videos: List<VideoItem>,
    val hasNextPage: Boolean,
    val nextPageUrl: String? = null,
    val currentPage: Int = 1,
    val totalPages: Int = 0
)
