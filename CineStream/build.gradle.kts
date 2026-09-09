import java.util.Properties

version = 483

android {
    namespace = "com.megix"

    defaultConfig {
        val properties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { properties.load(it) }
        }

        buildFeatures.buildConfig = true

        fun getProp(key: String): String {
            return System.getenv(key) ?: properties.getProperty(key) ?: ""
        }

        val defaultTmdb = "500330721680edb6d5f7f12ba7cd9023"
        buildConfigField("String", "SIMKL_API", "\"${getProp("SIMKL_API")}\"")
        buildConfigField("String", "TMDB_KEY", "\"${getProp("TMDB_KEY").ifEmpty { defaultTmdb }}\"")
        buildConfigField("String", "CC_COOKIE", "\"${getProp("CC_COOKIE")}\"")
        buildConfigField("String", "CASTLE_KEY", "\"${getProp("CASTLE_KEY")}\"")
        buildConfigField("String", "MOVIEBLAST_TOKEN", "\"${getProp("MOVIEBLAST_TOKEN")}\"")
        buildConfigField("String", "MOVIEBLAST_API", "\"${getProp("MOVIEBLAST_API")}\"")
        buildConfigField("String", "MOVIEBLAST_KEY", "\"${getProp("MOVIEBLAST_KEY")}\"")
        buildConfigField("String", "NETMIRROR_TOKEN", "\"${getProp("NETMIRROR_TOKEN")}\"")
    }
}

cloudstream {
    language    = "en"
    description = "One stop solution for Movies, Series, Anime, AsianDrama and Torrents"
    authors     = listOf("SaurabhKaperwan", "megix")
    status      = 1
    tvTypes     = listOf(
        "TvSeries",
        "Movie",
        "AsianDrama",
        "Anime",
        "Torrent"
    )

    iconUrl = "https://raw.githubusercontent.com/dr-octagon/Cloudstream-BronzeCloud/master/CineStream/icon.png"
}
