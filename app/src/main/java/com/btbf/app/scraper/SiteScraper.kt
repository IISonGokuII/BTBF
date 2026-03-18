package com.btbf.app.scraper

interface SiteScraper {
    val siteName: String
    val baseUrl: String

    suspend fun getHomePage(): PaginatedResult
    suspend fun getCategory(categoryUrl: String): PaginatedResult
    suspend fun getPage(pageUrl: String): PaginatedResult
    suspend fun getVideoDetail(pageUrl: String): VideoDetail?
    suspend fun getCategories(): List<CategoryItem>
    suspend fun search(query: String, page: Int = 1): PaginatedResult
}
