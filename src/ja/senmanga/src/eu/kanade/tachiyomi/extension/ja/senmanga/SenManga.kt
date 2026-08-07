package eu.kanade.tachiyomi.extension.ja.senmanga

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
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
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.time.Instant

@Source
abstract class SenManga :
    KeiSource(),
    ConfigurableSource {

    override val supportsLatest = true
    private val json: Json by injectLazy()

    private val apiHeaders by lazy {
        headers.newBuilder()
            .add("Referer", "$baseUrl/")
            .add("Accept", "application/json, text/plain, */*")
            .build()
    }

    // ================== Preferences (Language Filter) ==================
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val langPref = ListPreference(screen.context).apply {
            key = "PREF_LANG"
            title = "Preferred Language"
            entries = arrayOf(
                "All Languages",
                "English (en)",
                "Spanish (es / es-419)",
                "Portuguese (pt / pt-BR)",
                "Russian (ru)",
                "Indonesian (id)",
                "Vietnamese (vi)",
                "French (fr)",
                "Italian (it)",
                "German (de)",
                "Thai (th)",
                "Polish (pl)",
            )
            entryValues = arrayOf("all", "en", "es", "pt", "ru", "id", "vi", "fr", "it", "de", "th", "pl")
            setDefaultValue("all")
            summary = "%s\nNote: You must 'Pull to Refresh' the chapter list to apply changes."
        }
        screen.addPreference(langPref)
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

    // ================== Filters & Search ==================
    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Search Terms"),
        TitleFilter(),
        ScanlationFilter(),
        Filter.Separator(),
        Filter.Header("Advanced Filters"),
        StatusFilter(),
        DemographicFilter(),
        FormatFilter(),
        Filter.Separator(),
        Filter.Header("Genres: Click once to Include (✓), twice to Exclude (×)"),
        GenreFilter(),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        var actualQuery = query
        var scanlationGroup = ""

        filters.filterIsInstance<Filter.Text>().forEach { filter ->
            when (filter) {
                is TitleFilter -> if (filter.state.isNotEmpty() && actualQuery.isBlank()) actualQuery = filter.state
                is ScanlationFilter -> scanlationGroup = filter.state
            }
        }

        val (searchUrl, hasNextPage) = if (actualQuery.isNotBlank() && filters.isEmpty()) {
            if (page > 1) return MangasPage(emptyList(), false)
            val url = "$baseUrl/api/ajaxsearch".toHttpUrl().newBuilder()
                .addQueryParameter("title", actualQuery)
                .build()
            Pair(url, false)
        } else {
            val offset = (page - 1) * 60
            val urlBuilder = "$baseUrl/api/adv_search".toHttpUrl().newBuilder()
                .addQueryParameter("limit", "60")
                .addQueryParameter("offset", offset.toString())

            if (actualQuery.isNotBlank()) urlBuilder.addQueryParameter("title", actualQuery)
            if (scanlationGroup.isNotBlank()) urlBuilder.addQueryParameter("group", scanlationGroup)

            filters.forEach { filter ->
                when (filter) {
                    is StatusFilter -> {
                        if (filter.toUriPart().isNotEmpty()) {
                            urlBuilder.addQueryParameter("status", filter.toUriPart())
                        }
                    }
                    is DemographicFilter -> {
                        if (filter.toUriPart().isNotEmpty()) {
                            urlBuilder.addQueryParameter("demographic", filter.toUriPart())
                        }
                    }
                    is FormatFilter -> {
                        if (filter.toUriPart().isNotEmpty()) {
                            urlBuilder.addQueryParameter("format", filter.toUriPart())
                        }
                    }
                    is GenreFilter -> {
                        filter.state.forEach { genre ->
                            when (genre.state) {
                                Filter.TriState.STATE_INCLUDE -> urlBuilder.addQueryParameter("genre[]", genre.valID)
                                Filter.TriState.STATE_EXCLUDE -> urlBuilder.addQueryParameter("exclude_genre[]", genre.valID)
                                else -> {}
                            }
                        }
                    }
                    else -> {}
                }
            }

            Pair(urlBuilder.build(), true)
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
            val allChapters = mutableListOf<SChapter>()
            var offset = 0
            val limit = 500

            try {
                while (true) {
                    val response = client.get("$baseUrl/api/title/${manga.url}/chapters?limit=$limit&offset=$offset", apiHeaders)
                    if (!response.isSuccessful) break

                    val pageData = response.parseAs<SenMangaChapterListResponse>()
                    if (pageData.data.isEmpty()) break

                    val pageChapters = pageData.data.map { it.toSChapter() }
                    allChapters.addAll(pageChapters)

                    if (pageData.data.size < limit) break
                    offset += limit
                    if (offset >= 5000) break
                    delay(400)
                }
            } catch (e: Exception) {
                if (allChapters.isEmpty()) throw e
            }

            val langCounts = allChapters.groupingBy { ch ->
                Regex("""\[(.*?)\]""").find(ch.name)?.groupValues?.get(1) ?: "unknown"
            }.eachCount()

            val breakdownText = langCounts.entries
                .sortedByDescending { it.value }
                .joinToString("\n") { "• [${it.key}]: ${it.value} chapters" }

            val cleanDescription = updatedManga.description?.substringBefore("\n\n=== Language Breakdown ===") ?: ""
            updatedManga.description = if (cleanDescription.isBlank()) {
                "=== Language Breakdown ===\n$breakdownText"
            } else {
                "$cleanDescription\n\n=== Language Breakdown ===\n$breakdownText"
            }

            val prefLang = preferences.getString("PREF_LANG", "all") ?: "all"
            val filteredChapters = if (prefLang == "all") {
                allChapters
            } else {
                allChapters.filter { ch ->
                    val code = Regex("""\[(.*?)\]""").find(ch.name)?.groupValues?.get(1) ?: ""
                    code.startsWith(prefLang, ignoreCase = true)
                }
            }

            filteredChapters.sortedWith(compareByDescending<SChapter> { it.chapter_number }.thenByDescending { it.date_upload })
        } else {
            chapters
        }

        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    // ================== Page List (Images) ==================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (chapter.url.startsWith("http")) {
            val placeholderUrl = buildString {
                append("https://placehold.co/1080x1920/292929/ffffff.png?text=")
                append("Use+Webview+to+Read+this+chapter.%5Cn%5Cn")
                append("Because+SenManga+Don%27t+Have+File+to+Load%2C+Its+an+External+link.%5Cn%5Cn")
                append("Which+is+Inaccessible+for+Mihon.")
            }
            return listOf(Page(0, imageUrl = placeholderUrl))
        }

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

    override fun getChapterUrl(chapter: SChapter): String = if (chapter.url.startsWith("http")) {
        chapter.url
    } else {
        "$baseUrl/read/${chapter.url}"
    }

    // ================== Filter Implementations ==================

    private class TitleFilter : Filter.Text("Title / Keyword")
    private class ScanlationFilter : Filter.Text("Scanlation Group (If supported)")

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("All", "Ongoing", "Completed", "Cancelled", "Hiatus")) {
        fun toUriPart() = when (state) {
            1 -> "ongoing"
            2 -> "completed"
            3 -> "cancelled"
            4 -> "hiatus"
            else -> ""
        }
    }

    private class DemographicFilter : Filter.Select<String>("Demographic", arrayOf("All", "Shounen", "Shoujo", "Seinen", "Josei")) {
        fun toUriPart() = when (state) {
            1 -> "Shounen"
            2 -> "Shoujo"
            3 -> "Seinen"
            4 -> "Josei"
            else -> ""
        }
    }

    private class FormatFilter : Filter.Select<String>("Format", arrayOf("All", "Manga", "Manhwa", "Manhua", "Webtoon", "One-Shot", "Doujinshi", "Adaptation")) {
        fun toUriPart() = when (state) {
            1 -> "Manga"
            2 -> "Manhwa"
            3 -> "Manhua"
            4 -> "Webtoon"
            5 -> "One-Shot"
            6 -> "Doujinshi"
            7 -> "Adaptation"
            else -> ""
        }
    }

    private class GenreTriState(name: String, val valID: String) : Filter.TriState(name)
    private class GenreFilter : Filter.Group<GenreTriState>("Genres", genres.map { GenreTriState(it.first, it.second) })

    companion object {
        private val genres = listOf(
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Comedy", "Comedy"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Harem", "Harem"),
            Pair("Historical", "Historical"),
            Pair("Horror", "Horror"),
            Pair("Isekai", "Isekai"),
            Pair("Josei", "Josei"),
            Pair("Martial Arts", "Martial_Arts"),
            Pair("Mature", "Mature"),
            Pair("Mecha", "Mecha"),
            Pair("Mystery", "Mystery"),
            Pair("Psychological", "Psychological"),
            Pair("Romance", "Romance"),
            Pair("School Life", "School_Life"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Seinen", "Seinen"),
            Pair("Shoujo", "Shoujo"),
            Pair("Shounen", "Shounen"),
            Pair("Slice of Life", "Slice_of_Life"),
            Pair("Sports", "Sports"),
            Pair("Supernatural", "Supernatural"),
            Pair("Tragedy", "Tragedy"),
        )
    }
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
    @SerialName("altTitles") private val altTitles: JsonElement? = null,
    @SerialName("alt_titles") private val altTitlesFallback: JsonElement? = null,
    private val format: JsonElement? = null,
    private val demographic: JsonElement? = null,
    private val released: JsonElement? = null,
    private val year: JsonElement? = null,
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

    private fun extractAltTitles(element: JsonElement?): String? {
        if (element == null) return null
        return try {
            element.jsonArray.mapNotNull {
                it.jsonObject["title"]?.jsonPrimitive?.content
                    ?: it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: it.jsonPrimitive.contentOrNull
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

        val rawAuthor = extractNames(this@SenMangaItem.author)
        this.author = rawAuthor
        
        val rawArtist = extractNames(this@SenMangaItem.artist)
        this.artist = rawArtist

        // Compile Metadata
        val metadataList = mutableListOf<String>()
        if (!rawAuthor.isNullOrBlank()) metadataList.add("Author: $rawAuthor")
        if (!rawArtist.isNullOrBlank()) metadataList.add("Artist: $rawArtist")

        val formatStr = extractNames(this@SenMangaItem.format) ?: this@SenMangaItem.format?.jsonPrimitive?.contentOrNull
        if (!formatStr.isNullOrBlank()) metadataList.add("Format: $formatStr")

        val demographicStr = extractNames(this@SenMangaItem.demographic) ?: this@SenMangaItem.demographic?.jsonPrimitive?.contentOrNull
        if (!demographicStr.isNullOrBlank()) metadataList.add("Demographic: $demographicStr")

        val releaseStr = this@SenMangaItem.released?.jsonPrimitive?.contentOrNull ?: this@SenMangaItem.year?.jsonPrimitive?.contentOrNull
        if (!releaseStr.isNullOrBlank()) metadataList.add("Released: $releaseStr")

        val rawGenres = extractNames(this@SenMangaItem.genres)
        if (!rawGenres.isNullOrBlank()) metadataList.add("Genres: $rawGenres")

        val rawTags = extractNames(this@SenMangaItem.tags)
        if (!rawTags.isNullOrBlank()) metadataList.add("Tags: $rawTags")

        // Build Description block
        val baseDesc = this@SenMangaItem.description?.trim() ?: ""
        val rawAltTitles = extractAltTitles(this@SenMangaItem.altTitles) ?: extractAltTitles(this@SenMangaItem.altTitlesFallback)

        this.description = buildString {
            if (baseDesc.isNotBlank()) {
                append(baseDesc)
                append("\n\n")
            }
            if (metadataList.isNotEmpty()) {
                append("=== Additional Info ===\n")
                append(metadataList.joinToString("\n"))
                append("\n\n")
            }
            if (!rawAltTitles.isNullOrBlank()) {
                append("=== Alternate Titles ===\n")
                append(rawAltTitles)
            }
        }.trim()

        val genreList = listOfNotNull(rawGenres, rawTags).filter { it.isNotBlank() }
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
    val data: List<SenMangaChapter> = emptyList(),
)

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
        this.url = this@SenMangaChapter.externalUrl ?: this@SenMangaChapter.id

        val langCode = try {
            language?.jsonObject?.get("code")?.jsonPrimitive?.content
        } catch (e: Exception) {
            language?.jsonPrimitive?.content
        }

        val langPrefix = langCode?.let { "[$it] " } ?: ""
        val externalTag = if (this@SenMangaChapter.externalUrl != null) " 🔗" else ""
        this.name = langPrefix + "Chapter $chapter" + (title?.let { " - $it" } ?: "") + externalTag

        val cleanChapter = chapter.trim()
        this.chapter_number = if (cleanChapter.isNotEmpty() && cleanChapter.toFloatOrNull() != null) {
            cleanChapter.toFloat()
        } else {
            val numRegex = Regex("""(?i)(?:chapter|ch\.?)\s*(\d+(?:\.\d+)?)""")
            val match = numRegex.find(cleanChapter) ?: numRegex.find(this.name)
            match?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
        }

        this.scanlator = try {
            group?.jsonObject?.get("title")?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }

        this.date_upload = runCatching {
            Instant.parseOrNull(createdAt)?.toEpochMilliseconds() ?: 0L
        }.getOrDefault(0L)
    }
}
