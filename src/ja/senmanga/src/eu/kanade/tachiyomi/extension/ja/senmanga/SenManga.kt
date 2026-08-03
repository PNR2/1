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
class SenManga : KeiSource() {

    override val supportsLatest = true

    // ================== Popular / Browse ==================
    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.get("$baseUrl/api/popular?page=$page")
        val apiResponse = response.parseAs<SenMangaListResponse>()
        val mangas = apiResponse.getMangas()
        
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    // ================== Latest ==================
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        // The API uses a limit. We increment the limit to fetch more pages.
        val limit = 20 * page
        val response = client.get("$baseUrl/api/recentAdded?limit=$limit")
        val apiResponse = response.parseAs<SenMangaListResponse>()
        val mangas = apiResponse.getMangas()
        
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
        val mangas = apiResponse.getMangas()
        
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    // ================== Manga Details & Chapters ==================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Fetching details directly from the API using the stored UUID
        val response = client.get("$baseUrl/api/series/${manga.url}")
        val detailsDto = response.parseAs<SenMangaDetails>()

        val updatedManga = if (fetchDetails) detailsDto.toSManga().apply { url = manga.url } else manga
        val updatedChapters = if (fetchChapters) detailsDto.getChaptersList() else chapters

        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    // ================== Page List (Images) ==================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get("$baseUrl/api/chapter/${chapter.url}")
        val pagesDto = response.parseAs<SenMangaPagesResponse>()
        return pagesDto.getPages()
    }

    // Ensures "Open in WebView" takes the user to the actual website instead of the raw API JSON
    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}"
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter/${chapter.url}"
}

// ================== DTO Models ==================

@Serializable
class SenMangaListResponse(
    private val data: List<SenMangaItem> = emptyList()
) {
    fun getMangas(): List<SManga> = data.map { it.toSManga() }
}

@Serializable
class SenMangaItem(
    private val id: String? = null,
    private val title: String? = null,
    private val cover: String? = null,
    @SerialName("cover_256") private val cover256: String? = null,
    // Accommodates the nested "series" object found in the recentAdded payload
    private val series: SenMangaSeries? = null 
) {
    fun toSManga() = SManga.create().apply {
        // We store the raw UUID as the URL to easily query the API later
        url = this@SenMangaItem.id ?: series?.id ?: ""
        title = this@SenMangaItem.title ?: series?.title ?: ""
        thumbnail_url = this@SenMangaItem.cover256 ?: series?.cover256 ?: this@SenMangaItem.cover ?: series?.cover ?: ""
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
    fun toSManga() = SManga.create().apply {
        this.title = this@SenMangaDetails.title
        this.description = this@SenMangaDetails.description
        this.author = this@SenMangaDetails.author
        this.thumbnail_url = cover256.ifEmpty { cover }
        this.status = when (this@SenMangaDetails.status.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    // Chapters must be sorted descending according to the guide
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
        
        // Safely parses ISO-8601 dates and falls back to 0L if it fails
        this.date_upload = runCatching {
            Instant.parseOrNull(createdAt)?.toEpochMilliseconds() ?: 0L
        }.getOrDefault(0L)
    }
}

@Serializable
class SenMangaPagesResponse(
    private val data: SenMangaChapterData? = null
) {
    fun getPages(): List<Page> {
        return data?.pages?.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        } ?: emptyList()
    }
}

@Serializable
class SenMangaChapterData(
    val pages: List<String> = emptyList()
)
