package com.btbf.app.scraper

interface SiteScraper {
    val siteName: String
    val baseUrl: String

    suspend fun getHomePage(): PaginatedResult
    suspend fun getCategory(categoryUrl: String): PaginatedResult
    suspend fun getPage(pageUrl: String): PaginatedResult
    suspend fun getVideoDetail(pageUrl: String): VideoDetail?
    suspend fun getCategories(): List<CategoryItem>
    suspend fun getActors(page: Int = 1): List<ActorItem>
    suspend fun getTags(): List<TagItem>
    suspend fun search(query: String, page: Int = 1): PaginatedResult
    suspend fun getSorted(sort: SortOrder, page: Int = 1): PaginatedResult
}
