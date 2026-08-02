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
import okhttp3.Headers
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

    // Disguise Mihon as a standard Chrome Desktop browser to bypass bot-checks
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "en-US,en;q=0.9")

    // ================== Popular / Browse ==================
    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = GET("$baseUrl/directory?Order=Popular&page=$page", headers)
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body.string(), baseUrl)
        return parseMangasPage(document)
    }

    // ================== Latest ==================
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = GET("$baseUrl/updates?page=$page", headers)
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body.string(), baseUrl)
        return parseMangasPage(document)
    }

    // ================== Search ==================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/directory".toHttpUrl().newBuilder()
            .addQueryParameter("title", query)
            .addQueryParameter("page", page.toString())
            .build()
        
        val request = GET(url, headers)
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body.string(), baseUrl)
        return parseMangasPage(document)
    }

    // ================== HTML Parser for Mangas ==================
    private fun parseMangasPage(document: Document): MangasPage {
        val mangaElements = document.select("div.manga-card")
        val mangas = mangaElements.mapNotNull { element ->
            val linkElement = element.selectFirst("a") ?: return@mapNotNull null
            val titleElement = element.selectFirst("div.title") ?: return@mapNotNull null

            SManga.create().apply {
                title = titleElement.text().trim()
                setUrlWithoutDomain(linkElement.attr("href"))
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }
        
        val hasNextPage = document.selectFirst("ul.pagination li a[rel=next]") != null
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
                title = document.selectFirst("h1.series-title, h1.title, h1, div.info h2")?.text()?.trim() ?: manga.title
                thumbnail_url = document.selectFirst("div.cover img, div.thumb img, img.cover, img.img-responsive")?.attr("abs:src") ?: manga.thumbnail_url
                author = document.select("ul.series-info li:contains(Author) a, div.author a").text().ifEmpty { manga.author }
                artist = document.select("ul.series-info li:contains(Artist) a, div.artist a").text().ifEmpty { manga.artist }
                genre = document.select("ul.series-info li:contains(Genre) a, div.genre a").joinToString(", ") { it.text() }.ifEmpty { manga.genre }
                description = document.selectFirst("div.summary, div.description, p.summary, div.synopsis")?.text()?.trim() ?: manga.description

                val statusText = document.select("ul.series-info li:contains(Status), div.status").text()
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
            document.select("ul.chapter-list li, div.chapter-list li, ul.chapters li, div.chapter-item, div.chapters-list ul li").mapNotNull { element ->
                val link = element.selectFirst("a") ?: return@mapNotNull null
                SChapter.create().apply {
                    name = link.text().trim()
                    setUrlWithoutDomain(link.attr("href"))

                    val dateText = element.selectFirst("time, span.date, div.date")?.text() ?: ""
                    date_upload = try {
                        SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH).parse(dateText)?.time ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                }
            }
        } else {
            chapters
        }

        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    // ================== Page List (Images) ==================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val request = GET(baseUrl + chapter.url, headers)
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body.string(), baseUrl)

        return document.select("div.reader-page img, div.page-image img, div#reader img, img.page").mapIndexed { index, img ->
            val imageUrl = img.attr("abs:src").ifEmpty { img.attr("abs:data-src") }
            Page(index, "", imageUrl)
        }
    }
}
