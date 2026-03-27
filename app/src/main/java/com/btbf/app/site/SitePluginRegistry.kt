package com.btbf.app.site

import android.net.Uri
import com.btbf.app.R

/**
 * Alle eingebauten Quellen. Reihenfolge = Reihenfolge im Site-Picker.
 * Neue Seite: Eintrag hinzufügen (idealerweise eigene [SitePlugin]-Klasse mit Sonderlogik).
 */
object SitePluginRegistry {

    val plugins: List<SitePlugin> = listOf(
        BtbfSitePlugin,
        GenericSitePlugin(
            id = "camcaps",
            displayName = "CamCaps",
            baseUrl = "https://camcaps.tv/",
            domainMatch = "camcaps.tv",
            subtitleRes = R.string.site_camcaps_sub
        ),
        GenericSitePlugin(
            id = "fyxxr",
            displayName = "FyxXR",
            baseUrl = "https://fyxxr.com/",
            domainMatch = "fyxxr.com",
            subtitleRes = R.string.site_fyxxr_sub
        ),
        GenericSitePlugin(
            id = "sheeshfans",
            displayName = "SheeshFans",
            baseUrl = "https://sheeshfans.com/",
            domainMatch = "sheeshfans.com",
            subtitleRes = R.string.site_sheesh_sub
        ),
        GenericSitePlugin(
            id = "leakporner",
            displayName = "LeakPorner",
            baseUrl = "https://leakporner.com/",
            domainMatch = "leakporner.com",
            subtitleRes = R.string.site_leakporner_sub
        )
    )

    fun byId(id: String): SitePlugin? =
        plugins.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun byDisplayName(name: String): SitePlugin? {
        val n = name.trim()
        return plugins.firstOrNull { it.displayName.equals(n, ignoreCase = true) }
    }

    fun byBaseUrl(url: String): SitePlugin? {
        val normalized = url.trimEnd('/').lowercase()
        return plugins.firstOrNull {
            it.baseUrl.trimEnd('/').lowercase() == normalized
        } ?: byHost(Uri.parse(url).host)
    }

    /** Host z. B. de.borntobefuck.com, www.camcaps.tv */
    fun byHost(host: String?): SitePlugin? {
        if (host.isNullOrBlank()) return null
        val h = host.lowercase()
        return plugins.firstOrNull { p ->
            h.contains(p.domainMatch.lowercase())
        }
    }

    /**
     * Fallback: unbekannte URL → generischer Scraper mit übergebenem Namen (z. B. Deep-Link).
     */
    fun genericFallback(displayName: String, baseUrl: String): SitePlugin {
        val host = try {
            Uri.parse(baseUrl).host?.lowercase()
        } catch (_: Exception) {
            null
        }
        return GenericSitePlugin(
            id = "custom_${displayName.hashCode()}",
            displayName = displayName,
            baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/",
            domainMatch = host ?: displayName.lowercase(),
            subtitleRes = R.string.site_picker_subtitle
        )
    }
}
