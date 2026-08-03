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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.injectLazy
import kotlin.time.Instant

@Source
abstract class SenManga : KeiSource() {

    override val supportsLatest = true
    private val json: Json by injectLazy()

    // Spoof headers to bypass HTTP 500 errors
    private val apiHeaders by lazy {
        headers.newBuilder()
            .add("Referer", "$baseUrl/")
            .add("Accept", "application/json, text/plain, */*")
            .build()
    }

    // ================== Popular / Browse ==================
    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.get("$baseUrl/api/popular?page=$page", apiHeaders)
        val apiResponse = response.parseAs<SenMangaListResponse>()
        val mangas = apiResponse.getMangas()
        
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    // ================== Latest ==================
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val limit = 20 * page
        val response = client.get("$baseUrl/api/recentAdded?limit=$limit", apiHeaders)
        val apiResponse = response.parseAs<SenMangaListResponse>()
        val mangas = apiResponse.getMangas()
        
        return MangasPage(mangas, mangas.size >= limit)
    }

    // ================== Search ==================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val (searchUrl, hasNextPage) = if (query.isNotBlank()) {
            if (page > 1) return MangasPage(emptyList(), false) 
            
            val url = "$baseUrl/api/ajaxsearch".toHttpUrl().newBuilder()
                .addQueryParameter("title", query)
                .build()
            Pair(url, false)
        } else {
            val offset = (page - 1) * 60
            val url = "$baseUrl/api/adv_search".toHttpUrl().newBuilder()
                .addQueryParameter("limit", "60")
                .addQueryParameter("offset", offset.toString())
                .build()
            Pair(url, true)
        }

        val response = client.get(searchUrl, apiHeaders)
        val responseData = response.body.string()
        
        val parsedJson = json.parseToJsonElement(responseData)
        val jsonArray = try {
            parsedJson.jsonArray
        } catch (e: Exception) {
            parsedJson.jsonObject["data"]?.jsonArray ?: return MangasPage(emptyList(), false)
        }
        
        val mangas = jsonArray.map { element ->
            json.decodeFromJsonElement<SenMangaItem>(element).toSManga()
        }
        
        return MangasPage(mangas, hasNextPage && mangas.isNotEmpty())
    }

    // ================== Manga Details & Chapters ==================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedChapters = if (fetchChapters) {
            val response = client.get("$baseUrl/api/title/${manga.url}/chapters", apiHeaders)
            response.parseAs<SenMangaChapterListResponse>().getChaptersList()
        } else {
            chapters
        }

        return SMangaUpdate(manga = manga, chapters = updatedChapters)
    }

    // ================== Page List (Images) ==================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get("$baseUrl/read/${chapter.url}", apiHeaders)
        val document = response.asJsoup()
        
        val scriptData = document.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Could not find image data on page")
            
        val parsed = json.parseToJsonElement(scriptData).jsonObject
        val pageProps = parsed["props"]?.jsonObject?.get("pageProps")?.jsonObject ?: parsed["pageProps"]?.jsonObject
        val urlArray = pageProps?.get("chapter")?.jsonObject?.get("pageList")?.jsonObject?.get("url")?.jsonArray
            ?: throw Exception("Failed to extract image URLs from JSON")
            
        return urlArray.mapIndexed { index, element ->
            val imgUrl = element.jsonPrimitive.content
            // BYPASS THE PROXY! Load straight from the source for instant speed!
            Page(index, imageUrl = imgUrl)
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
    fun getMangas(): List<SManga> = data.map { it.toSManga() }
}

@Serializable
class SenMangaItem(
    private val id: String? = null,
    private val title: String? = null,
    private val cover: String? = null,
    @SerialName("cover_256") private val cover256: String? = null,
    private val series: SenMangaSeries? = null,
) {
    fun toSManga() = SManga.create().apply {
        this.url = series?.id ?: this@SenMangaItem.id ?: ""
        this.title = series?.title ?: this@SenMangaItem.title ?: ""
        
        // Remove the slow proxy here as well!
        val rawCover = series?.cover256 ?: this@SenMangaItem.cover256 ?: series?.cover ?: this@SenMangaItem.cover ?: ""
        this.thumbnail_url = rawCover
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
    fun getChaptersList(): List<SChapter> = data
        .filter { it.pages > 0 && it.externalUrl == null }
        .map { it.toSChapter() }
        .sortedByDescending { it.date_upload }
}

@Serializable
class SenMangaChapter(
    private val id: String = "",
    private val chapter: String = "",
    private val title: String? = null,
    @SerialName("createdAt") private val createdAt: String = "",
    private val group: JsonElement? = null,
    private val language: JsonElement? = null,
    val pages: Int = 0,
    val externalUrl: String? = null,
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
