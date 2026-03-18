package com.btbf.app.scraper

/**
 * Basis-Interface für alle Site-Scraper
 */
interface SiteScraper {
    val siteName: String
    val baseUrl: String

    /**
     * Lädt die Startseite mit Videos
     */
    suspend fun getHomePage(): PaginatedResult

    /**
     * Lädt Videos von einer Kategorie-Seite
     */
    suspend fun getCategory(categoryUrl: String): PaginatedResult

    /**
     * Lädt Videos von einer beliebigen Seite
     */
    suspend fun getPage(pageUrl: String): PaginatedResult

    /**
     * Lädt Details zu einem Video inkl. Video-URLs
     */
    suspend fun getVideoDetail(pageUrl: String): VideoDetail?

    /**
     * Lädt alle Kategorien
     */
    suspend fun getCategories(): List<CategoryItem>

    /**
     * Lädt Models/Darsteller
     */
    suspend fun getActors(page: Int): List<ActorItem>

    /**
     * Lädt alle Tags
     */
    suspend fun getTags(): List<TagItem>

    /**
     * Sucht nach Videos
     */
    suspend fun search(query: String, page: Int): PaginatedResult

    /**
     * Lädt sortierte Videos (neu, top, etc.)
     */
    suspend fun getSorted(sort: SortOrder, page: Int): PaginatedResult
}
