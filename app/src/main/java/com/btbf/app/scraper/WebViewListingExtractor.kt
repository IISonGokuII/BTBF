package com.btbf.app.scraper

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Lädt eine Seite in einer versteckten WebView und extrahiert Video-Karten per JavaScript.
 * Fallback wenn Jsoup nur leeres HTML (SPA / nachgeladenes DOM / Challenge) liefert.
 */
class WebViewListingExtractor(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * Liefert { items: [{pageUrl,title,thumbnailUrl}], next: string|null, nextMode: "href"|"load_more"|null }
         */
        private const val EXTRACT_SCRIPT = """
            (function(){
              try {
                var host = location.hostname;
                var items = [];
                var seen = {};
                var hints = ['/video/','/videos/','/watch','/watch/','/v/','/movie/','/scene/','/tube/','/post/','/play/','/embed/'];
                function abs(href) {
                  try { return new URL(href, location.href).href; } catch(e) { return ''; }
                }
                function sameHost(u) {
                  try { return new URL(u).hostname === host; } catch(e) { return false; }
                }
                function looksVideoPath(u) {
                  try {
                    var p = new URL(u).pathname.toLowerCase();
                    if (p.length < 4) return false;
                    for (var i = 0; i < hints.length; i++) {
                      if (p.indexOf(hints[i]) >= 0) return true;
                    }
                    return false;
                  } catch(e) { return false; }
                }
                function skipHref(h) {
                  if (!h || h.indexOf('#') === 0 || h.indexOf('javascript:') === 0) return true;
                  var l = h.toLowerCase();
                  if (l.indexOf('/login') >= 0 || l.indexOf('/signin') >= 0 || l.indexOf('/register') >= 0) return true;
                  if (l.indexOf('/user/') >= 0 || l.indexOf('/account') >= 0) return true;
                  return false;
                }
                var nodes = document.querySelectorAll('a[href]');
                for (var i = 0; i < nodes.length; i++) {
                  var a = nodes[i];
                  var href = a.getAttribute('href');
                  if (skipHref(href)) continue;
                  var url = abs(href);
                  if (!url || !sameHost(url) || !looksVideoPath(url)) continue;
                  if (seen[url]) continue;
                  var img = a.querySelector('img');
                  var thumb = '';
                  if (img) {
                    thumb = img.currentSrc || img.src || img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || '';
                  }
                  var title = '';
                  if (img && img.alt) title = String(img.alt).trim();
                  if (!title) title = (a.getAttribute('title') || a.textContent || '').trim();
                  title = title.replace(/\s+/g, ' ');
                  if (title.length < 2) continue;
                  if (title.length > 300) title = title.substring(0, 300);
                  seen[url] = true;
                  items.push({ pageUrl: url, title: title, thumbnailUrl: thumb });
                  if (items.length >= 120) break;
                }
                var next = null;
                var nextMode = null;
                var rel = document.querySelector('a[rel=next]');
                if (rel && rel.href) { next = rel.href; nextMode = 'href'; }
                else {
                  var nx = document.querySelector('a.next, .pagination li.next a, .pagination a.next');
                  if (nx && nx.href) { next = nx.href; nextMode = 'href'; }
                }
                if (!next && document.querySelector('#load-more, button.load-btn, .load-more, [data-load-more]')) {
                  next = location.href;
                  nextMode = 'load_more';
                }
                return JSON.stringify({ items: items, next: next, nextMode: nextMode });
              } catch (e) {
                return JSON.stringify({ items: [], next: null, nextMode: null, error: String(e) });
              }
            })();
        """

        private const val EXTRACT_NAV_SCRIPT = """
            (function(kind){
              try {
                var host = location.hostname;
                function abs(href) {
                  try { return new URL(href, location.href).href; } catch(e) { return ''; }
                }
                function sameHost(u) {
                  try { return new URL(u).hostname === host; } catch(e) { return false; }
                }
                var items = [];
                var seen = {};
                var nodes = document.querySelectorAll('a[href]');
                for (var i = 0; i < nodes.length; i++) {
                  var a = nodes[i];
                  var href = a.getAttribute('href');
                  if (!href || href.indexOf('#') === 0) continue;
                  var url = abs(href);
                  if (!url || !sameHost(url)) continue;
                  var p = '';
                  try { p = new URL(url).pathname.toLowerCase(); } catch(e) { continue; }
                  var ok = false;
                  if (kind === 'category') {
                    ok = (p.indexOf('/categor') >= 0 || p.indexOf('/cat/') >= 0 || p.indexOf('/channel/') >= 0) &&
                         p.indexOf('/video') < 0 && p.indexOf('/watch') < 0;
                  } else if (kind === 'actor') {
                    ok = p.indexOf('/model') >= 0 || p.indexOf('/actor') >= 0 || p.indexOf('/pornstar') >= 0 || p.indexOf('/performer') >= 0;
                  } else if (kind === 'tag') {
                    ok = p.indexOf('/tag/') >= 0 || p.indexOf('/tags/') >= 0;
                  }
                  if (!ok) continue;
                  var name = (a.textContent || '').trim().replace(/\s+/g, ' ');
                  if (name.length < 1 || name.length > 80) continue;
                  if (seen[url]) continue;
                  seen[url] = true;
                  var img = a.querySelector('img');
                  var thumb = '';
                  if (img) thumb = img.currentSrc || img.src || '';
                  items.push({ name: name, url: url, thumb: thumb });
                  if (items.length >= 200) break;
                }
                return JSON.stringify({ items: items });
              } catch (e) {
                return JSON.stringify({ items: [], error: String(e) });
              }
            })
        """
    }

    suspend fun extractVideoListing(pageUrl: String): PaginatedResult =
        withContext(Dispatchers.Main) {
            loadAndExtract(pageUrl) { json -> parseVideoPaginated(json, pageUrl) }
        }

    private enum class NavKind { CATEGORY, ACTOR, TAG }

    private suspend fun <T> loadAndExtract(url: String, parse: (String) -> T): T =
        suspendCancellableCoroutine { cont ->
            val container = FrameLayout(activity)
            container.layoutParams = FrameLayout.LayoutParams(1, 1)
            val wv = WebView(activity)
            wv.layoutParams = FrameLayout.LayoutParams(1, 1)
            wv.alpha = 0f
            WebView.setWebContentsDebuggingEnabled(false)

            val root = activity.window.decorView as ViewGroup
            root.addView(container)
            container.addView(wv)

            fun removeAndDestroy() {
                try {
                    container.removeView(wv)
                    root.removeView(container)
                    wv.stopLoading()
                    wv.destroy()
                } catch (_: Exception) {
                }
            }

            cont.invokeOnCancellation { handler.post { removeAndDestroy() } }

            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = DEFAULT_USER_AGENT
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            wv.webChromeClient = WebChromeClient()

            var loadGeneration = 0
            var completed = false

            fun finish(value: T) {
                if (completed) return
                completed = true
                removeAndDestroy()
                if (cont.isActive) cont.resume(value)
            }

            val bridge = object {
                @JavascriptInterface
                fun done(payload: String) {
                    handler.post {
                        if (!cont.isActive) {
                            removeAndDestroy()
                            return@post
                        }
                        try {
                            finish(parse(payload))
                        } catch (_: Exception) {
                            finish(parse("{\"items\":[]}"))
                        }
                    }
                }
            }
            wv.addJavascriptInterface(bridge, "BtbfExtract")

            wv.webViewClient = object : WebViewClient() {
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return false
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    val gen = ++loadGeneration
                    handler.postDelayed({
                        if (gen != loadGeneration || completed) return@postDelayed
                        val script = "BtbfExtract.done($EXTRACT_SCRIPT);"
                        view.evaluateJavascript(script, null)
                    }, 1400)
                }
            }

            handler.postDelayed({
                if (!completed && cont.isActive) {
                    try {
                        val empty = parse("{\"items\":[]}")
                        finish(empty)
                    } catch (_: Exception) {
                        removeAndDestroy()
                        if (cont.isActive) cont.resumeWith(Result.failure(Exception("webview timeout")))
                    }
                }
            }, 28000)

            wv.loadUrl(url)
        }

    suspend fun extractCategories(pageUrl: String): List<CategoryItem> =
        extractNavList(pageUrl, NavKind.CATEGORY) { arr ->
            val out = mutableListOf<CategoryItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "")
                val u = o.optString("url", "")
                if (name.isNotEmpty() && u.isNotEmpty()) {
                    out.add(CategoryItem(name, u, o.optString("thumb", "")))
                }
            }
            out
        }

    suspend fun extractActors(pageUrl: String): List<ActorItem> =
        extractNavList(pageUrl, NavKind.ACTOR) { arr ->
            val out = mutableListOf<ActorItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "")
                val u = o.optString("url", "")
                if (name.isNotEmpty() && u.isNotEmpty()) {
                    out.add(ActorItem(name, u, o.optString("thumb", "")))
                }
            }
            out
        }

    suspend fun extractTags(pageUrl: String): List<TagItem> =
        extractNavList(pageUrl, NavKind.TAG) { arr ->
            val out = mutableListOf<TagItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").removePrefix("#").trim()
                val u = o.optString("url", "")
                if (name.isNotEmpty() && u.isNotEmpty()) {
                    out.add(TagItem(name, u))
                }
            }
            out
        }

    private suspend fun <T> extractNavList(
        pageUrl: String,
        kind: NavKind,
        map: (JSONArray) -> T
    ): T = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val container = FrameLayout(activity)
            val wv = WebView(activity)
            wv.layoutParams = FrameLayout.LayoutParams(1, 1)
            wv.alpha = 0f
            val root = activity.window.decorView as ViewGroup
            root.addView(container)
            container.addView(wv)

            fun removeAndDestroy() {
                try {
                    container.removeView(wv)
                    root.removeView(container)
                    wv.stopLoading()
                    wv.destroy()
                } catch (_: Exception) {
                }
            }

            cont.invokeOnCancellation { handler.post { removeAndDestroy() } }

            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = DEFAULT_USER_AGENT
            }
            wv.webChromeClient = WebChromeClient()

            var loadGeneration = 0
            var completed = false

            fun finish(value: T) {
                if (completed) return
                completed = true
                removeAndDestroy()
                if (cont.isActive) cont.resume(value)
            }

            val kindStr = when (kind) {
                NavKind.CATEGORY -> "category"
                NavKind.ACTOR -> "actor"
                NavKind.TAG -> "tag"
            }

            val bridge = object {
                @JavascriptInterface
                fun done(payload: String) {
                    handler.post {
                        if (!cont.isActive) {
                            removeAndDestroy()
                            return@post
                        }
                        try {
                            val arr = JSONObject(payload).optJSONArray("items") ?: JSONArray()
                            finish(map(arr))
                        } catch (_: Exception) {
                            finish(map(JSONArray()))
                        }
                    }
                }
            }
            wv.addJavascriptInterface(bridge, "BtbfNavExtract")

            wv.webViewClient = object : WebViewClient() {
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    false

                override fun onPageFinished(view: WebView, url: String?) {
                    val gen = ++loadGeneration
                    handler.postDelayed({
                        if (gen != loadGeneration || completed) return@postDelayed
                        val call = "BtbfNavExtract.done($EXTRACT_NAV_SCRIPT('$kindStr'));"
                        view.evaluateJavascript(call, null)
                    }, 1400)
                }
            }

            handler.postDelayed({
                if (!completed && cont.isActive) {
                    finish(map(JSONArray()))
                }
            }, 26000)

            wv.loadUrl(pageUrl)
        }
    }

    private fun parseVideoPaginated(json: String, currentUrl: String): PaginatedResult {
        val root = JSONObject(json)
        val arr = root.optJSONArray("items") ?: return PaginatedResult(emptyList(), false)
        val videos = mutableListOf<VideoItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pageUrl = o.optString("pageUrl", "")
            val title = o.optString("title", "")
            if (pageUrl.isEmpty() || title.isEmpty()) continue
            val thumb = o.optString("thumbnailUrl", "")
            videos.add(
                VideoItem(
                    id = pageUrl,
                    title = title,
                    thumbnailUrl = thumb,
                    pageUrl = pageUrl
                )
            )
        }
        val distinct = videos.distinctBy { it.pageUrl }
        val nextMode = root.optString("nextMode", "")
        val nextRaw = root.opt("next")
        val nextStr = when (nextRaw) {
            is String -> nextRaw
            else -> null
        }
        val nextUrl = when {
            distinct.isEmpty() -> null
            nextMode == "href" && !nextStr.isNullOrEmpty() -> nextStr
            nextMode == "load_more" -> bumpPageQuery(currentUrl)
            else -> null
        }
        val hasNext = nextUrl != null
        val currentPage = Regex("""[?&]page=(\d+)""").find(currentUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return PaginatedResult(
            videos = distinct,
            hasNextPage = hasNext,
            nextPageUrl = nextUrl,
            currentPage = currentPage
        )
    }

    private fun bumpPageQuery(url: String): String {
        val regex = Regex("""([?&])page=\d+""")
        return when {
            regex.containsMatchIn(url) -> regex.replace(url) { m ->
                val sep = m.groupValues[1]
                val cur = Regex("""page=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                "${sep}page=${cur + 1}"
            }
            url.contains("?") -> "$url&page=2"
            else -> "$url?page=2"
        }
    }
}
