package eu.kanade.tachiyomi.extension.ja.senmanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

@Source
class SenManga(
    override val name: String = "SenManga",
    override val baseUrl: String = "https://senmanga.com",
    override val lang: String = "ja",
    override val id: Long = 8527391823471923L,
) : KeiSource() {

    override val supportsLatest = true

    // ================== Popular / Browse ==================
    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = GET("$baseUrl/directory/popular?page=$page", headers)
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body.string(), baseUrl)
        return parseMangasPage(document)
    }

    // ================== Latest ==================
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = GET("$baseUrl/directory/last_update?page=$page", headers)
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body.string(), baseUrl)
        return parseMangasPage(document)
    }

    // ================== Search ==================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()
        val request = GET(url, headers)
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body.string(), baseUrl)
        return parseMangasPage(document)
    }

    private fun parseMangasPage(document: Document): MangasPage {
        val mangas = document.select("div.item").map { element ->
            SManga.create().apply {
                val titleElement = element.selectFirst("a.series-title")!!
                title = titleElement.text().trim()
                setUrlWithoutDomain(titleElement.attr("href"))
                thumbnail_url = element.selectFirst("img")?.let { img ->
                    img.attr("src").ifEmpty { img.attr("data-src") }
                }
            }
        }
        val hasNextPage = document.selectFirst("ul.pagination li:last-child:not(.disabled) a") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ================== Manga Details & Chapters ==================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val request = GET(baseUrl + manga.url, headers)
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body.string(), baseUrl)

        val updatedManga = if (fetchDetails) {
            SManga.create().apply {
                url = manga.url
                title = document.selectFirst("h1.series-title")?.text()?.trim() ?: manga.title
                thumbnail_url = document.selectFirst("div.cover img")?.attr("src") ?: manga.thumbnail_url
                author = document.select("ul.series-info li:contains(Author) a").text().ifEmpty { manga.author }
                artist = document.select("ul.series-info li:contains(Artist) a").text().ifEmpty { manga.artist }
                genre = document.select("ul.series-info li:contains(Genre) a").joinToString(", ") { it.text() }.ifEmpty { manga.genre }
                description = document.selectFirst("div.summary")?.text()?.trim() ?: manga.description

                val statusText = document.select("ul.series-info li:contains(Status)").text()
                status = when {
                    statusText.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                    statusText.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                    else -> manga.status
                }
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            document.select("ul.chapter-list li").map { element ->
                SChapter.create().apply {
                    val link = element.selectFirst("a")!!
                    name = link.text().trim()
                    setUrlWithoutDomain(link.attr("href"))

                    val dateText = element.selectFirst("time")?.text() ?: ""
                    date_upload = parseDate(dateText)
                }
            }
        } else {
            chapters
        }

        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    private fun parseDate(dateStr: String): Long = try {
        SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH).parse(dateStr)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    // ================== Page List (Images) ==================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val request = GET(baseUrl + chapter.url, headers)
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body.string(), baseUrl)

        return document.select("div.reader-page img").mapIndexed { index, img ->
            val imageUrl = img.attr("src").ifEmpty { img.attr("data-src") }
            Page(index, "", imageUrl)
        }
    }
}
