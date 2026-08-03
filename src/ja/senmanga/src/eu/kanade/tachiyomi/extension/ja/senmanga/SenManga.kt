package eu.kanade.tachiyomi.extension.ja.senmanga

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.time.Instant

@Source
abstract class SenManga : KeiSource() {

    override val supportsLatest = true

    // ================== Popular / Browse ==================
    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.get("$baseUrl/api/popular?page=$page")
        val apiResponse = response.parseAs<SenMangaListResponse>()
        val mangas = apiResponse.getMangas(baseUrl)
        
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    // ================== Latest ==================
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val limit = 20 * page
        val response = client.get("$baseUrl/api/recentAdded?limit=$limit")
        val apiResponse = response.parseAs<SenMangaListResponse>()
        val mangas = apiResponse.getMangas(baseUrl)
        
        return MangasPage(mangas, mangas.size >= limit)
    }

    // ================== Search ==================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/api/directory".toHttpUrl().newBuilder()
            .addQueryParameter("title", query)
            .addQueryParameter("page", page.toString())
            .build()

        val response = client.get(url)
        val apiResponse = response.parseAs<SenMangaListResponse>()
        val mangas = apiResponse.getMangas(baseUrl)
        
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    // ================== Manga Details & Chapters ==================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get("$baseUrl/api/series/${manga.url}")
        val detailsDto = response.parseAs<SenMangaDetails>()

        val updatedManga = if (fetchDetails) detailsDto.toSManga(baseUrl).apply { url = manga.url } else manga
        val updatedChapters = if (fetchChapters) detailsDto.getChaptersList() else chapters

        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    // ================== Page List (Images) ==================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get("$baseUrl/api/chapter/${chapter.url}")
        val pagesDto = response.parseAs<SenMangaPagesResponse>()
        return pagesDto.getPages(baseUrl)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}"
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter/${chapter.url}"
}

// ================== DTO Models ==================

@Serializable
class SenMangaListResponse(
    private val data: List<SenMangaItem> = emptyList()
) {
    fun getMangas(baseUrl: String): List<SManga> = data.map { it.toSManga(baseUrl) }
}

@Serializable
class SenMangaItem(
    private val id: String? = null,
    private val title: String? = null,
    private val cover: String? = null,
    @SerialName("cover_256") private val cover256: String? = null,
    private val series: SenMangaSeries? = null 
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        // Prioritize the nested series ID if it exists
        url = series?.id ?: this@SenMangaItem.id ?: ""
        title = series?.title ?: this@SenMangaItem.title ?: ""
        
        // Wrap images in SenManga's proxy to bypass MangaDex hotlinking protection
        val rawCover = series?.cover256 ?: this@SenMangaItem.cover256 ?: series?.cover ?: this@SenMangaItem.cover ?: ""
        thumbnail_url = if (rawCover.isNotEmpty()) "$baseUrl/api/proxy?imageUrl=$rawCover" else ""
    }
}

@Serializable
class SenMangaSeries(
    val id: String = "",
    val title: String = "",
    val cover: String = "",
    @SerialName("cover_256") val cover256: String = ""
)

@Serializable
class SenMangaDetails(
    private val title: String = "",
    private val description: String = "",
    private val status: String = "",
    private val author: String = "",
    private val cover: String = "",
    @SerialName("cover_256") private val cover256: String = "",
    private val chapters: List<SenMangaChapter> = emptyList()
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        this.title = this@SenMangaDetails.title
        this.description = this@SenMangaDetails.description
        this.author = this@SenMangaDetails.author
        
        val rawCover = cover256.ifEmpty { cover }
        this.thumbnail_url = if (rawCover.isNotEmpty()) "$baseUrl/api/proxy?imageUrl=$rawCover" else ""
        
        this.status = when (this@SenMangaDetails.status.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    fun getChaptersList(): List<SChapter> = chapters.map { it.toSChapter() }.sortedByDescending { it.date_upload }
}

@Serializable
class SenMangaChapter(
    private val id: String = "",
    private val chapter: String = "",
    private val title: String? = null,
    @SerialName("createdAt") private val createdAt: String = ""
) {
    fun toSChapter() = SChapter.create().apply {
        this.url = this@SenMangaChapter.id
        this.name = "Chapter $chapter" + (title?.let { " - $it" } ?: "")
        
        this.date_upload = runCatching {
            Instant.parseOrNull(createdAt)?.toEpochMilliseconds() ?: 0L
        }.getOrDefault(0L)
    }
}

@Serializable
class SenMangaPagesResponse(
    private val data: SenMangaChapterData? = null
) {
    fun getPages(baseUrl: String): List<Page> {
        return data?.pages?.mapIndexed { index, url ->
            Page(index, imageUrl = "$baseUrl/api/proxy?imageUrl=$url")
        } ?: emptyList()
    }
}

@Serializable
class SenMangaChapterData(
    val pages: List<String> = emptyList()
)
