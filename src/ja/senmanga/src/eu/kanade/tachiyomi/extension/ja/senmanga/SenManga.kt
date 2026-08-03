package eu.kanade.tachiyomi.extension.ja.senmanga

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.injectLazy
import kotlin.time.Instant

@Source
abstract class SenManga : KeiSource() {

    override val supportsLatest = true
    private val json: Json by injectLazy()

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

    // ================== Search (HTML Scraper) ==================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val searchUrl = "$baseUrl/directory".toHttpUrl().newBuilder()
            .addQueryParameter("title", query)
            .addQueryParameter("page", page.toString())
            .build()

        val response = client.get(searchUrl)
        val document = response.asJsoup()
        
        val mangas = document.select("div.manga-card").mapNotNull { element ->
            val linkElement = element.selectFirst("a") ?: return@mapNotNull null
            val titleElement = element.selectFirst("div.title") ?: return@mapNotNull null

            SManga.create().apply {
                this.title = titleElement.text().trim()
                this.url = linkElement.attr("href").substringAfterLast("/") 
                
                val rawCover = element.selectFirst("img")?.attr("src") ?: ""
                this.thumbnail_url = if (rawCover.isNotEmpty()) "$baseUrl/api/proxy?imageUrl=$rawCover" else ""
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
        val response = client.get("$baseUrl/read/${chapter.url}")
        val document = response.asJsoup()
        
        // Manually parse the JSON to bypass the "Cannot infer a predicate" bug
        val scriptData = document.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Could not find image data on page")
            
        val nextData = json.decodeFromString<SenMangaNextData>(scriptData)
        val urls = nextData.pageProps?.chapter?.pageList?.url ?: emptyList()
        
        if (urls.isEmpty()) throw Exception("No pages found")
        
        return urls.mapIndexed { index, imgUrl ->
            Page(index, imageUrl = "$baseUrl/api/proxy?imageUrl=$imgUrl")
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/title/${manga.url}"
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/read/${chapter.url}"
}

// ================== DTO Models ==================

@Serializable
class SenMangaListResponse(
    private val data: List<SenMangaItem> = emptyList(),
) {
    fun getMangas(baseUrl: String): List<SManga> = data.map { it.toSManga(baseUrl) }
}

@Serializable
class SenMangaItem(
    private val id: String? = null,
    private val title: String? = null,
    private val cover: String? = null,
    @SerialName("cover_256") private val cover256: String? = null,
    private val series: SenMangaSeries? = null,
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        this.url = series?.id ?: this@SenMangaItem.id ?: ""
        this.title = series?.title ?: this@SenMangaItem.title ?: ""
        
        val rawCover = series?.cover256 ?: this@SenMangaItem.cover256 ?: series?.cover ?: this@SenMangaItem.cover ?: ""
        this.thumbnail_url = if (rawCover.isNotEmpty()) "$baseUrl/api/proxy?imageUrl=$rawCover" else ""
    }
}

@Serializable
class SenMangaSeries(
    val id: String = "",
    val title: String = "",
    val cover: String = "",
    @SerialName("cover_256") val cover256: String = "",
)

@Serializable
class SenMangaChapterListResponse(
    private val data: List<SenMangaChapter> = emptyList(),
) {
    fun getChaptersList(): List<SChapter> = data.map { it.toSChapter() }.sortedByDescending { it.date_upload }
}

@Serializable
class SenMangaChapter(
    private val id: String = "",
    private val chapter: String = "",
    private val title: String? = null,
    @SerialName("createdAt") private val createdAt: String = "",
    private val group: JsonElement? = null,
    private val language: JsonElement? = null,
) {
    fun toSChapter() = SChapter.create().apply {
        this.url = this@SenMangaChapter.id
        
        val langCode = try {
            language?.jsonObject?.get("code")?.jsonPrimitive?.content
        } catch (e: Exception) {
            language?.jsonPrimitive?.content
        }
        
        val langPrefix = langCode?.let { "[$it] " } ?: ""
        this.name = langPrefix + "Chapter $chapter" + (title?.let { " - $it" } ?: "")
        
        this.scanlator = try {
            group?.jsonObject?.get("title")?.jsonPrimitive?.content
        } catch (e: Exception) { null }
        
        this.date_upload = runCatching {
            Instant.parseOrNull(createdAt)?.toEpochMilliseconds() ?: 0L
        }.getOrDefault(0L)
    }
}

// === Next.js Extractor DTOs ===
@Serializable
class SenMangaNextData(
    val pageProps: SenMangaPageProps? = null,
)

@Serializable
class SenMangaPageProps(
    val chapter: SenMangaNextChapter? = null,
)

@Serializable
class SenMangaNextChapter(
    val pageList: SenMangaPageList? = null,
)

@Serializable
class SenMangaPageList(
    val url: List<String> = emptyList(),
)
