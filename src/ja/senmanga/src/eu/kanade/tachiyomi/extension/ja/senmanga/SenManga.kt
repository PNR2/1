package eu.kanade.tachiyomi.extension.ja.senmanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class SenManga : ParsedHttpSource() {

    override val name = "SenManga"
    override val baseUrl = "https://senmanga.com"
    override val lang = "ja"

    override val supportsLatest = true

    // ================== Popular / Browse ==================
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/directory/popular?page=$page", headers)

    override fun popularMangaSelector() = "div.item"

    override fun popularMangaFromElement(element: Element): SManga =
        SManga.create().apply {
            val titleElement = element.selectFirst("a.series-title")!!
            title = titleElement.text().trim()
            setUrlWithoutDomain(titleElement.attr("href"))
            thumbnail_url = element.selectFirst("img")?.let { img ->
                img.attr("src").ifEmpty { img.attr("data-src") }
            }
        }

    override fun popularMangaNextPageSelector() = "ul.pagination li:last-child:not(.disabled) a"

    // ================== Latest ==================
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/directory/last_update?page=$page", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ================== Search ==================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ================== Manga Details ==================
    override fun mangaDetailsParse(document: Document): SManga =
        SManga.create().apply {
            title = document.selectFirst("h1.series-title")?.text()?.trim() ?: ""
            thumbnail_url = document.selectFirst("div.cover img")?.attr("src")
            author = document.select("ul.series-info li:contains(Author) a").text()
            artist = document.select("ul.series-info li:contains(Artist) a").text()
            genre = document.select("ul.series-info li:contains(Genre) a").joinToString(", ") { it.text() }
            description = document.selectFirst("div.summary")?.text()?.trim()

            val statusText = document.select("ul.series-info li:contains(Status)").text()
            status = when {
                statusText.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                statusText.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }

    // ================== Chapters ==================
    override fun chapterListSelector() = "ul.chapter-list li"

    override fun chapterFromElement(element: Element): SChapter =
        SChapter.create().apply {
            val link = element.selectFirst("a")!!
            name = link.text().trim()
            setUrlWithoutDomain(link.attr("href"))

            val dateText = element.selectFirst("time")?.text() ?: ""
            date_upload = parseDate(dateText)
        }

    private fun parseDate(dateStr: String): Long =
        try {
            SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH).parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }

    // ================== Page List (Images) ==================
    override fun pageListParse(document: Document): List<Page> =
        document.select("div.reader-page img").mapIndexed { index, img ->
            val imageUrl = img.attr("src").ifEmpty { img.attr("data-src") }
            Page(index, "", imageUrl)
        }

    override fun imageUrlParse(document: Document): String = ""
}
