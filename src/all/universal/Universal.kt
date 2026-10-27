package eu.kanade.tachiyomi.extension.all.universal

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Universal : KeiSource() {

    private enum class Theme {
        Madara,
        Themesia,
    }

    private var cachedTheme: Pair<String, Theme>? = null

    private suspend fun theme(): Theme {
        cachedTheme?.takeIf { it.first == baseUrl }?.let { return it.second }
        val html = client.get(baseUrl).asJsoup().html()
        val detected = when {
            html.contains("wp-manga", true) || html.contains("madara", true) -> Theme.Madara
            html.contains("ts_reader", true) || html.contains("listupd", true) -> Theme.Themesia
            else -> error("Unsupported site theme")
        }
        cachedTheme = baseUrl to detected
        return detected
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = when (theme()) {
            Theme.Madara -> "$baseUrl/manga/page/$page/?m_orderby=views"
            Theme.Themesia -> "$baseUrl/manga/?page=$page&order=popular"
        }
        return parseList(client.get(url).asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = when (theme()) {
            Theme.Madara -> "$baseUrl/manga/page/$page/?m_orderby=latest"
            Theme.Themesia -> "$baseUrl/manga/?page=$page&order=update"
        }
        return parseList(client.get(url).asJsoup())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val isMadara = theme() == Theme.Madara
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) addPathSegments("page/$page/")
            addQueryParameter("s", query)
            if (isMadara) addQueryParameter("post_type", "wp-manga")
        }.build()
        return parseList(client.get(url).asJsoup())
    }

    private fun parseList(document: Document): MangasPage {
        val items = document.select(
            "div.page-item-detail, div.c-tabs-item__content, .listupd .bs .bsx, .listupd .utao .uta",
        )
        val mangas = items.mapNotNull { element ->
            val link = element.selectFirst("h3 a, h5 a, .post-title a, a[title], a[href]")
                ?: return@mapNotNull null
            val href = link.attr("abs:href").takeIf(String::isNotBlank) ?: return@mapNotNull null
            SManga.create().apply {
                url = href.toHttpUrl().encodedPath
                title = link.attr("title").ifBlank { link.text() }
                    .ifBlank { element.selectFirst("img")?.attr("alt").orEmpty() }
                thumbnail_url = element.selectFirst("img")?.let(::imageUrl)
            }
        }.distinctBy { it.url }
        val hasNext = document.selectFirst("a.next, a.nextpostslink, .hpage a.r") != null
        return MangasPage(mangas, hasNext)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        val details = if (fetchDetails) parseDetails(document, manga.url) else manga
        val chapterList = if (fetchChapters) parseChapters(document, manga.url) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private fun parseDetails(document: Document, mangaUrl: String): SManga = SManga.create().apply {
        url = mangaUrl
        title = document.selectFirst(".post-title h1, .post-title h3, h1.entry-title")?.text().orEmpty()
        thumbnail_url = document.selectFirst(".summary_image img, .thumb img")?.let(::imageUrl)
        description = document.selectFirst(
            ".description-summary .summary__content, .summary__content, .entry-content[itemprop=description]",
        )?.text()
        genre = document.select(".genres-content a, .mgen a").joinToString { it.text() }.ifBlank { null }
        val statusText = document.selectFirst(".post-status .summary-content, .imptdt i")?.text().orEmpty()
        status = when {
            statusText.contains("ongoing", true) || statusText.contains("مستمر") -> SManga.ONGOING
            statusText.contains("completed", true) || statusText.contains("مكتمل") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    private suspend fun parseChapters(document: Document, mangaUrl: String): List<SChapter> {
        var items = document.select(CHAPTER_SELECTOR)
        if (items.isEmpty() && theme() == Theme.Madara) {
            val ajax = client.post(
                "$baseUrl${mangaUrl.trimEnd('/')}/ajax/chapters/",
                headersBuilder().set("X-Requested-With", "XMLHttpRequest").build(),
                FormBody.Builder().build(),
            ).asJsoup()
            items = ajax.select(CHAPTER_SELECTOR)
        }
        return items.mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("abs:href").takeIf(String::isNotBlank) ?: return@mapNotNull null
            SChapter.create().apply {
                url = href.toHttpUrl().encodedPath
                name = element.selectFirst(".chapternum")?.text() ?: link.text()
            }
        }
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

        val script = document.select("script").map { it.data() }.firstOrNull { "ts_reader" in it }
        val array = script?.let {
            Regex("\"images\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(it)?.groupValues?.get(1)
        }
        if (array != null) {
            return Regex("\"(https?:[^\"]+)\"").findAll(array).mapIndexed { index, match ->
                Page(index, document.location(), match.groupValues[1].replace("\\/", "/"))
            }.toList()
        }

        return document.select(".reading-content img, #readerarea img, .page-break img")
            .mapIndexedNotNull { index, element ->
                imageUrl(element).takeIf { it.isNotBlank() }?.let { Page(index, document.location(), it) }
            }
    }

    override fun getFilterList(data: JsonElement?) = FilterList()

    private fun imageUrl(element: Element): String = element.absUrl("data-src")
        .ifEmpty { element.absUrl("data-lazy-src") }
        .ifEmpty { element.absUrl("src") }

    companion object {
        private const val CHAPTER_SELECTOR = "li.wp-manga-chapter, #chapterlist li"
    }
}
