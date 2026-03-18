package com.btbf.app.scraper

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

data class VideoDetail(
    val title: String,
    val videoSources: List<VideoSource>,
    val thumbnailUrl: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val actors: List<ActorItem> = emptyList(),
    val relatedVideos: List<VideoItem> = emptyList()
)

data class VideoSource(
    val url: String,
    val quality: String = "",
    val type: VideoSourceType = VideoSourceType.DIRECT
)

enum class VideoSourceType {
    DIRECT, HLS, DASH
}

data class CategoryItem(
    val name: String,
    val url: String,
    val thumbnailUrl: String = "",
    val count: String = ""
)

data class ActorItem(
    val name: String,
    val url: String,
    val thumbnailUrl: String = "",
    val videoCount: String = ""
)

data class TagItem(
    val name: String,
    val url: String,
    val count: String = ""
)

enum class SortOrder(val label: String, val path: String) {
    NEWEST("Neueste", "new"),
    TOP("Beliebteste", "top"),
    LONGEST("Laengste", "longest"),
    RANDOM("Zufall", "random")
}

data class PaginatedResult(
    val videos: List<VideoItem>,
    val hasNextPage: Boolean,
    val nextPageUrl: String? = null,
    val currentPage: Int = 1,
    val totalPages: Int = 0
)
