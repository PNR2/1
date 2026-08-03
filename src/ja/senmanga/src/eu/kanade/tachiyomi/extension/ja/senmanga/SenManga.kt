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
import keiyoushi.utils.extractNextJs
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
        // We already have the title and cover from the home page. 
        // We only need to fetch the actual chapter list from the API you found.
        val updatedChapters = if (fetchChapters) {
            val response = client.get("$baseUrl/api/title/${manga.url}/chapters")
            response.parseAs<SenMangaChapterListResponse>().getChaptersList()
        } else {
            chapters
        }

        return SMangaUpdate(manga = manga, chapters = updatedChapters)
    }

    // ================== Page List (Images) ==================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // We use the built-in Next.js extractor to grab the JSON directly from the reader page!
        val response = client.get("$baseUrl/read/${chapter.url}")
        val nextData = response.extractNextJs<SenMangaNextData>()
        
        // Safely traverse the nullable Next.js structure
        val urls = nextData?.pageProps?.chapter?.pageList?.url ?: emptyList()
        
        return urls.mapIndexed { index, imgUrl ->
            Page(index, imageUrl = "$baseUrl/api/proxy?imageUrl=$imgUrl")
        }
    }

    // ================== WebView URLs ==================
    override fun getMangaUrl(manga: SManga): String = "$baseUrl/title/${manga.url}"
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/read/${chapter.url}"
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
        url = series?.id ?: this@SenMangaItem.id ?: ""
        title = series?.title ?: this@SenMangaItem.title ?: ""
        
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
class SenMangaChapterListResponse(
    private val data: List<SenMangaChapter> = emptyList()
) {
    fun getChaptersList(): List<SChapter> = data.map { it.toSChapter() }.sortedByDescending { it.date_upload }
}

@Serializable
class SenMangaChapter(
    private val id: String = "",
    private val chapter: String = "",
    private val title: String? = null,
    @SerialName("createdAt") private val createdAt: String = "",
    private val language: SenMangaLanguage? = null
) {
    fun toSChapter() = SChapter.create().apply {
        this.url = this@SenMangaChapter.id
        
        // Some chapters are in different languages, so we prepend the language code
        val langPrefix = language?.code?.let { "[$it] " } ?: ""
        this.name = langPrefix + "Chapter $chapter" + (title?.let { " - $it" } ?: "")
        
        this.date_upload = runCatching {
            Instant.parseOrNull(createdAt)?.toEpochMilliseconds() ?: 0L
        }.getOrDefault(0L)
    }
}

@Serializable
class SenMangaLanguage(
    val code: String = ""
)

// === Next.js Extractor DTOs ===
@Serializable
class SenMangaNextData(
    val pageProps: SenMangaPageProps? = null
)

@Serializable
class SenMangaPageProps(
    val chapter: SenMangaNextChapter? = null
)

@Serializable
class SenMangaNextChapter(
    val pageList: SenMangaPageList? = null
)

@Serializable
class SenMangaPageList(
    val url: List<String> = emptyList()
)
