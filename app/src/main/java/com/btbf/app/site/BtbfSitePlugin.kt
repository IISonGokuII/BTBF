package com.btbf.app.site

import com.btbf.app.R
import com.btbf.app.scraper.BtbfScraper
import com.btbf.app.scraper.SiteScraper

/**
 * de.borntobefuck.com — eigener Scraper (Laravel, deutsche Pfade, Infinite-Scroll).
 */
object BtbfSitePlugin : SitePlugin {

    override val id = "btbf"
    override val displayName = "BTBF"
    override val baseUrl = "https://de.borntobefuck.com/"
    override val domainMatch = "borntobefuck"
    override val subtitleRes = R.string.site_btbf_sub

    override fun createInnerScraper(): SiteScraper = BtbfScraper()
}
