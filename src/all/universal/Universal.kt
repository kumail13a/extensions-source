package eu.kanade.tachiyomi.extension.all.universal

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Universal source: the user enters a site URL in the extension settings,
 * the source detects the site's theme (Madara / MangaThemesia) and adapts.
 */
class Universal : ConfigurableSource, HttpSource() {

    override val name = "Universal"
    override val lang = "all"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // The site address typed by the user
    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, "")!!.trim().trimEnd('/')

    private enum class Theme { MADARA, THEMESIA }

    // Detected once per base URL, then cached
    private var cachedTheme: Pair<String, Theme>? = null

    private fun theme(): Theme {
        val base = baseUrl
        if (base.isBlank()) throw Exception("افتح إعدادات المصدر وأدخل رابط الموقع")
        cachedTheme?.let { if (it.first == base) return it.second }

        val html = client.newCall(GET(base, headers)).execute().use { it.body.string() }
        val detected = when {
            html.contains("wp-manga", true) || html.contains("madara", true) -> Theme.MADARA
            html.contains("ts_reader", true) || html.contains("listupd", true) ||
                html.contains("mangastream", true) -> Theme.THEMESIA
            else -> throw Exception("قالب الموقع غير مدعوم حالياً")
        }
        cachedTheme = base to detected
        return detected
    }

    // ---------- Settings ----------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "رابط الموقع"
            summary = "مثال: https://example.com"
            dialogTitle = "رابط الموقع"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val v = (newValue as String).trim()
                // Validate before saving
                runCatching { v.toHttpUrl() }.isSuccess || v.isEmpty()
            }
        }.also(screen::addPreference)
    }

    // ---------- Popular ----------

    override fun popularMangaRequest(page: Int): Request = when (theme()) {
        Theme.MADARA -> GET("$baseUrl/manga/page/$page/?m_orderby=views", headers)
        Theme.THEMESIA -> GET("$baseUrl/manga/?page=$page&order=popular", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    // ---------- Latest ----------

    override fun latestUpdatesRequest(page: Int): Request = when (theme()) {
        Theme.MADARA -> GET("$baseUrl/manga/page/$page/?m_orderby=latest", headers)
        Theme.THEMESIA -> GET("$baseUrl/manga/?page=$page&order=update", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    // ---------- Search ----------

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) addPathSegments("page/$page/")
            addQueryParameter("s", query)
            if (theme() == Theme.MADARA) addQueryParameter("post_type", "wp-manga")
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    // ---------- List parsing (shared) ----------

    private fun parseList(response: Response): MangasPage {
        val doc = response.asJsoup()
        val items = doc.select(
            "div.page-item-detail, div.c-tabs-item__content, " + // Madara
                ".listupd .bs .bsx, .listupd .utao .uta", // MangaThemesia
        )
        val mangas = items.mapNotNull { el ->
            val link = el.selectFirst("h3 a, h5 a, .post-title a, a[title], a[href]") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.attr("title").ifBlank { link.text() }
                    .ifBlank { el.selectFirst("img")?.attr("alt").orEmpty() }
                thumbnail_url = el.selectFirst("img")?.let(::imgUrl)
            }
        }.distinctBy { it.url }
        val hasNext = doc.selectFirst("a.next, .nav-previous a, .hpage a.r, a.nextpostslink") != null
        return MangasPage(mangas, hasNext)
    }

    // ---------- Details ----------

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst(".post-title h1, h1.entry-title")?.text().orEmpty()
            thumbnail_url = doc.selectFirst(".summary_image img, .thumb img")?.let(::imgUrl)
            description = doc.selectFirst(
                ".description-summary .summary__content, .summary__content, .entry-content[itemprop=description]",
            )?.text()
            genre = doc.select(".genres-content a, .mgen a").joinToString { it.text() }
            val statusText = doc.selectFirst(".post-status .summary-content, .imptdt i")?.text().orEmpty()
            status = when {
                statusText.contains("ongoing", true) || statusText.contains("مستمر") -> SManga.ONGOING
                statusText.contains("completed", true) || statusText.contains("مكتمل") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ---------- Chapters ----------

    override fun chapterListParse(response: Response): List<SChapter> {
        var doc = response.asJsoup()
        var items = doc.select(CHAPTER_SELECTOR)

        // Newer Madara sites load the chapter list through ajax
        if (items.isEmpty() && theme() == Theme.MADARA) {
            val ajaxUrl = response.request.url.toString().trimEnd('/') + "/ajax/chapters/"
            doc = client.newCall(POST(ajaxUrl, headers)).execute().asJsoup()
            items = doc.select(CHAPTER_SELECTOR)
        }

        return items.map { el ->
            val link = el.selectFirst("a")!!
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = el.selectFirst(".chapternum")?.text() ?: link.text()
                date_upload = 0L // TODO: parse the site's date format
            }
        }
    }

    // ---------- Pages ----------

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()

        // MangaThemesia: images live inside the ts_reader.run({...}) script
        val script = doc.select("script").map { it.data() }.firstOrNull { "ts_reader" in it }
        if (script != null) {
            val arr = Regex("\"images\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
                .find(script)?.groupValues?.get(1)
            if (arr != null) {
                return Regex("\"(https?:[^\"]+)\"").findAll(arr)
                    .mapIndexed { i, m -> Page(i, imageUrl = m.groupValues[1].replace("\\/", "/")) }
                    .toList()
            }
        }

        // Madara / fallback: plain <img> tags
        return doc.select(".reading-content img, #readerarea img, .page-break img")
            .mapIndexedNotNull { i, img ->
                imgUrl(img).takeIf { it.isNotBlank() }?.let { Page(i, imageUrl = it) }
            }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    // ---------- Helpers ----------

    private fun imgUrl(img: Element): String =
        img.absUrl("data-src")
            .ifEmpty { img.absUrl("data-lazy-src") }
            .ifEmpty { img.absUrl("src") }

    companion object {
        private const val BASE_URL_PREF = "base_url"
        private const val CHAPTER_SELECTOR = "li.wp-manga-chapter, #chapterlist li"
    }
}
