package com.btbf.app.site

import android.app.Activity
import android.webkit.WebView
import androidx.annotation.StringRes
import com.btbf.app.scraper.HybridSiteScraper
import com.btbf.app.scraper.SiteScraper

/**
 * Eine eingebaute oder konfigurierbare Quelle: eigener Scraper-Pfad und optionale WebView-Anpassungen.
 * Pro Seite eine Implementierung — hier können später gezielt Pfade, UA, JS-Hooks ergänzt werden.
 */
interface SitePlugin {

    /** Stabiler Schlüssel (z. B. "btbf", "camcaps"). */
    val id: String

    val displayName: String

    val baseUrl: String

    /**
     * Teilstring des Hosts für [android.net.Uri.host] / URL-Policy (wie bisher currentSiteDomain).
     * z. B. "borntobefuck", "camcaps.tv"
     */
    val domainMatch: String

    @get:StringRes
    val subtitleRes: Int

    /** Jsoup-/Spezial-Scraper vor Hybrid-WebView-Fallback. */
    fun createInnerScraper(): SiteScraper

    /** BrowseActivity: Hybrid + inner. */
    fun createBrowsingScraper(activity: Activity): SiteScraper =
        HybridSiteScraper(activity, createInnerScraper())

    /**
     * WebView User-Agent (Basis ohne "; wv" — MainActivity setzt das vorher).
     * Überschreiben, falls eine Site ohne TV-WebView-UA blockiert.
     */
    fun webViewUserAgent(defaultChromeLike: String): String = defaultChromeLike

    /**
     * Optional: Zusätzliches JS nach [com.btbf.app.MainActivity] Standard-Injects.
     * Nur aufrufen, wenn die geladene URL zu dieser Quelle gehört.
     */
    fun onPageReadyInjectJs(webView: WebView, pageUrl: String): String? = null
}
