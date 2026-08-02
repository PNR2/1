import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
    alias(libs.plugins.kotlin.serialization)
}

keiyoushi {
    name = "SenManga"
    versionCode = 8
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "ja"
        baseUrl = "https://senmanga.com"
    }
}
```[cite: 1]

---

### File 2: `Filters.kt`
*(Place this in `src/ja/senmanga/src/eu/kanade/tachiyomi/extension/ja/senmanga/Filters.kt`)*

```kotlin
package eu.kanade.tachiyomi.extension.ja.senmanga

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}

class TypeFilter :
    UriPartFilter(
        "Type",
        arrayOf(
            Pair("All", ""),
            Pair("Manga", "Manga"),
            Pair("Manhua", "Manhua"),
            Pair("Manhwa", "Manhwa"),
        ),
    )

class StatusFilter :
    UriPartFilter(
        "Status",
        arrayOf(
            Pair("All", ""),
            Pair("Ongoing", "Ongoing"),
            Pair("Completed", "Completed"),
        ),
    )

class OrderFilter :
    UriPartFilter(
        "Order",
        arrayOf(
            Pair("Default", ""),
            Pair("A-Z", "A-Z"),
            Pair("Z-A", "Z-A"),
            Pair("Update", "Update"),
            Pair("Added", "Added"),
            Pair("Popular", "Popular"),
            Pair("Rating", "Rating"),
        ),
    )
```[cite: 1]

---

### File 3: `DTO.kt`
*(Place this in `src/ja/senmanga/src/eu/kanade/tachiyomi/extension/ja/senmanga/DTO.kt`)*

```kotlin
package eu.kanade.tachiyomi.extension.ja.senmanga

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable

@Serializable
class DirectoryResponse(
    val currentPage: Int? = null,
    val totalPages: Int? = null,
    val series: List<SeriesDto> = emptyList(),
)

@Serializable
class HomeResponse(
    val series: List<SeriesDto> = emptyList(),
)

@Serializable
class SeriesDto(
    private val title: String,
    val slug: String,
    private val cover: String? = null,
    private val status: String? = null,
    private val genre: String? = null,
    private val description: String? = null,
    val chapterList: List<ChapterDto>? = null,
) {
    fun toSManga() = SManga.create().apply {
        this.title = this@SeriesDto.title
        this.url = slug
        this.thumbnail_url = cover
        this.description = this@SeriesDto.description
        this.genre = this@SeriesDto.genre
        this.status = parseStatus(this@SeriesDto.status)
    }

    private fun parseStatus(statusString: String?): Int = when {
        statusString == null -> SManga.UNKNOWN
        statusString.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
        statusString.contains("complete", ignoreCase = true) -> SManga.COMPLETED
        statusString.contains("hiatus", ignoreCase = true) -> SManga.ON_HIATUS
        statusString.contains("dropped", ignoreCase = true) -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }
}

@Serializable
class ChapterDto(
    val title: String,
    val url: String,
    val datetime: String? = null,
)

@Serializable
class ReadResponse(
    val pages: List<String> = emptyList(),
)
```[cite: 1]

---

### File 4: `SenManga.kt`
*(Place this in `src/ja/senmanga/src/eu/kanade/tachiyomi/extension/ja/senmanga/SenManga.kt`)*

```kotlin
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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
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

    // ================== Search & Filters ==================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/directory".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("s", query)
        }

        filters.firstInstanceOrNull<TypeFilter>()?.let {
            url.addQueryParameter("type", it.toUriPart())
        }

        filters.firstInstanceOrNull<StatusFilter>()?.let {
            url.addQueryParameter("status", it.toUriPart())
        }

        filters.firstInstanceOrNull<OrderFilter>()?.let {
            url.addQueryParameter("order", it.toUriPart())
        }

        val request = GET(url.build(), headers)
        val response = client.newCall(request).execute()
        val data = response.parseAs<DirectoryResponse>()
        val mangas = data.series.map { it.toSManga() }
        val hasNext = (data.currentPage ?: 1) < (data.totalPages ?: 1)

        return MangasPage(mangas, hasNext)
    }

    override fun getFilterList() = FilterList(
        TypeFilter(),
        StatusFilter(),
        OrderFilter(),
    )

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
```[cite: 1]
