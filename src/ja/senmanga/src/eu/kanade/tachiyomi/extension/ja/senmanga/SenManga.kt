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
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
class SenManga(
    override val name: String = "SenManga",
    override val baseUrl: String = "https://senmanga.com",
    override val lang: String = "ja",
    override val id: Long = 8527391823471923L,
) : KeiSource() {

    override val supportsLatest = true

    private val apiUrl = "$baseUrl/api"

    // ================== Popular / Browse ==================
    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = GET("$apiUrl/directory?order=Popular&page=$page", headers)
        val response = client.newCall(request).execute()
        val data = response.parseAs<DirectoryResponse>()
        val mangas = data.series.map { it.toSManga() }
        val hasNext = (data.currentPage ?: 1) < (data.totalPages ?: 1)

        return MangasPage(mangas, hasNext)
    }

    // ================== Latest ==================
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = GET("$apiUrl/home?page=$page", headers)
        val response = client.newCall(request).execute()
        val data = response.parseAs<HomeResponse>()
        val mangas = data.series.map { it.toSManga() }

        return MangasPage(mangas, mangas.isNotEmpty())
    }

    // ================== Search ==================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/directory".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("s", query)
        }

        val request = GET(url.build(), headers)
        val response = client.newCall(request).execute()
        val data = response.parseAs<DirectoryResponse>()
        val mangas = data.series.map { it.toSManga() }
        val hasNext = (data.currentPage ?: 1) < (data.totalPages ?: 1)

        return MangasPage(mangas, hasNext)
    }

    // ================== Manga Details & Chapters ==================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val request = GET("$apiUrl/manga/${manga.url}", headers)
        val response = client.newCall(request).execute()
        val data = response.parseAs<SeriesDto>()

        val updatedManga = if (fetchDetails) {
            data.toSManga()
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            val mangaSlug = data.slug
            data.chapterList?.map { chapter ->
                SChapter.create().apply {
                    url = "$mangaSlug/${chapter.url}"
                    name = chapter.title
                    date_upload = dateFormat.tryParse(chapter.datetime)
                }
            } ?: emptyList()
        } else {
            chapters
        }

        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    // ================== Page List (Images) ==================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val request = GET("$apiUrl/read/${chapter.url}", headers)
        val response = client.newCall(request).execute()
        val data = response.parseAs<ReadResponse>()
        return data.pages.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/manga/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        val mangaSlug = chapter.url.substringBefore("/")
        val chapterSlug = chapter.url.substringAfter("/")
        return "$baseUrl/manga/$mangaSlug/chapter-$chapterSlug/"
    }
}

private val dateFormat by lazy {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}

// ================== JSON Data Transfer Objects ==================

@Serializable
private class DirectoryResponse(
    val series: List<SeriesDto> = emptyList(),
    @SerialName("current_page") val currentPage: Int? = null,
    @SerialName("total_pages") val totalPages: Int? = null,
)

@Serializable
private class HomeResponse(
    val series: List<SeriesDto> = emptyList(),
)

@Serializable
private class ReadResponse(
    val pages: List<String> = emptyList(),
)

@Serializable
private class SeriesDto(
    val title: String = "",
    val slug: String = "",
    val cover: String = "",
    val author: String? = null,
    val artist: String? = null,
    val genre: List<String>? = null,
    val summary: String? = null,
    val status: String? = null,
    @SerialName("chapter_list") val chapterList: List<ChapterDto>? = null,
) {
    fun toSManga() = SManga.create().apply {
        title = this@SeriesDto.title
        url = slug
        thumbnail_url = cover
        author = this@SeriesDto.author
        artist = this@SeriesDto.artist
        genre = this@SeriesDto.genre?.joinToString(", ")
        description = summary
        status = when (this@SeriesDto.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
private class ChapterDto(
    val title: String = "",
    val url: String = "",
    val datetime: String? = null,
)
