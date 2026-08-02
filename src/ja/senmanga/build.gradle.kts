import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SenManga"
    versionCode = 1
    libVersion = "1.6"
    contentWarning = ContentWarning.SAFE

    source {
        baseUrl = "https://senmanga.com"
        lang = "ja"
    }
}
