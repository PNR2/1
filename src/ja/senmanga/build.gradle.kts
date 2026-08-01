plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SenManga"
    isNsfw = false
    
    source {
        baseUrl = "https://senmanga.com"
        lang = "ja"
    }
}
