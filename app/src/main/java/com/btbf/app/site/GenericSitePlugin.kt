package com.btbf.app.site

import com.btbf.app.scraper.GenericScraper
import com.btbf.app.scraper.SiteScraper

/**
 * Standard-Tube/CMS-Layout über [GenericScraper].
 * Für eine neue Seite: Instanz in [SitePluginRegistry] eintragen oder eigene Klasse von [SitePlugin] bauen.
 */
open class GenericSitePlugin(
    override val id: String,
    override val displayName: String,
    override val baseUrl: String,
    override val domainMatch: String,
    override val subtitleRes: Int
) : SitePlugin {

    override fun createInnerScraper(): SiteScraper =
        GenericScraper(displayName, baseUrl)
}
