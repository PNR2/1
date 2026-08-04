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

    private val apiHeaders by lazy {
        headers.newBuilder()
            .add("Referer", "$baseUrl/")
            .add("Accept", "application/json, text/plain, */*")
            .build()
    }

    // ================== Popular / Browse ==================
    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        val response = client.get("$baseUrl/api/popular", apiHeaders)
        val apiResponse = response.parseAs<SenMangaListResponse>()
        val mangas = apiResponse.getMangas(baseUrl)

        return MangasPage(mangas, false)
    }

    // ================== Latest ==================
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val limit = 60
        val offset = (page - 1) * limit
        val response = client.get("$baseUrl/api/recentAdded?limit=$limit&offset=$offset", apiHeaders)
        val apiResponse = response.parseAs<SenMangaListResponse>()
        val mangas = apiResponse.getMangas(baseUrl)

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
            json.decodeFromJsonElement<SenMangaItem>(element).toSManga(baseUrl)
        }

        return MangasPage(mangas, hasNextPage && mangas.isNotEmpty())
    }

    // ================== Chapter List ==================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            val response = client.get("$baseUrl/api/title/${manga.url}", apiHeaders)
            val parsedJson = json.parseToJsonElement(response.body.string()).jsonObject
            val dataObj = parsedJson["data"]?.jsonObject ?: parsedJson
            json.decodeFromJsonElement<SenMangaItem>(dataObj).toSManga(baseUrl).apply {
                this.url = manga.url
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            val response = client.get("$baseUrl/api/title/${manga.url}/chapters", apiHeaders)
            response.parseAs<SenMangaChapterListResponse>().getChaptersList()
        } else {
            chapters
        }

        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
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
class SenMangaName(
    val name: String = "",
)

@Serializable
class SenMangaItem(
    private val id: String? = null,
    private val title: String? = null,
    private val cover: String? = null,
    @SerialName("cover_256") private val cover256: String? = null,
    private val series: SenMangaSeries? = null,
    private val author: JsonElement? = null,
    private val artist: JsonElement? = null,
    private val description: String? = null,
    private val genres: JsonElement? = null,
    private val tags: JsonElement? = null,
    private val status: String? = null,
) {
    private fun extractNames(element: JsonElement?): String? {
        if (element == null) return null
        return try {
            element.jsonArray.mapNotNull {
                it.jsonObject["name"]?.jsonPrimitive?.content
            }.joinToString(", ").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    fun toSManga(baseUrl: String) = SManga.create().apply {
        this.url = series?.id ?: this@SenMangaItem.id ?: ""
        this.title = series?.title ?: this@SenMangaItem.title ?: ""

        val rawCover = series?.cover256 ?: this@SenMangaItem.cover256 ?: series?.cover ?: this@SenMangaItem.cover ?: ""
        this.thumbnail_url = if (rawCover.isNotEmpty()) "$baseUrl/api/proxy?imageUrl=$rawCover" else ""

        this.author = extractNames(this@SenMangaItem.author)
        this.artist = extractNames(this@SenMangaItem.artist)
        this.description = this@SenMangaItem.description

        val genreList = listOfNotNull(
            extractNames(this@SenMangaItem.genres),
            extractNames(this@SenMangaItem.tags),
        ).filter { it.isNotBlank() }

        if (genreList.isNotEmpty()) {
            this.genre = genreList.joinToString(", ")
        }

        this.status = when (this@SenMangaItem.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "cancelled" -> SManga.CANCELLED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
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
