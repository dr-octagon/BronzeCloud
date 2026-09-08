package com.keyiflerolsun

import android.content.SharedPreferences
import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONException
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

class Vavoo(
    private val countries: Map<String, Boolean>,
    language: String,
    private val sharedPreferences: SharedPreferences,
) : MainAPI() {
    override var mainUrl = "https://vavoo.to"
    override var name = "Vavoo"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = language
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = countries
        .filterValues { it }
        .keys
        .sortedWith(compareBy<String> { it != TURKEY }.thenBy { it })
        .map { MainPageData(it, it) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (turkeyLogoMap.isEmpty()) {
            loadChannelMetadata()
        }

        if (request.data == TURKEY) {
            if (page > 1) return newHomePageResponse(emptyList())

            val (channels, trendingChannels) = coroutineScope {
                val channelsRequest = async { getAllTurkeyChannels() }
                val trendingRequest = async {
                    runCatching { getTrendingTurkeyChannels() }.getOrDefault(emptyList())
                }
                channelsRequest.await() to trendingRequest.await()
            }
            val categoryLists = TURKEY_CATEGORIES.mapNotNull { category ->
                val resultsSequence = channels
                    .asSequence()
                    .filter { categoryFor(it.name) == category }
                    .map(::channelToSearchResponse)

                val results = if (category == TurkeyCategory.OTHER) {
                    resultsSequence.take(150).toList()
                } else {
                    resultsSequence.toList()
                }

                results.takeIf { it.isNotEmpty() }
                    ?.let { HomePageList(category.title, it, true) }
            }
            val trendingList = trendingChannels
                .takeIf { it.isNotEmpty() }
                ?.let { trending ->
                    HomePageList(
                        TRENDING_TITLE,
                        trending.map(::channelToSearchResponse),
                        true,
                    )
                }
            return newHomePageResponse(listOfNotNull(trendingList) + categoryLists)
        }

        val (channels, hasNext) = getCatalog(request.data, page)
        return newHomePageResponse(
            request.name,
            channels.map(::channelToSearchResponse),
            hasNext,
        )
    }

    private suspend fun getAllTurkeyChannels(): List<Channel> {
        val now = System.currentTimeMillis()
        cachedTurkeyChannels?.let { (timestamp, cachedList) ->
            if (now - timestamp < CACHE_TTL_MS && cachedList.isNotEmpty()) {
                return cachedList
            }
        }

        val page1Result = getCatalog(TURKEY, 1)
        val channels = mutableListOf<Channel>()
        channels.addAll(page1Result.first)

        if (page1Result.second) {
            val remaining = coroutineScope {
                (2..6).map { page ->
                    async {
                        runCatching { getCatalog(TURKEY, page) }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
            for (res in remaining) {
                channels.addAll(res.first)
            }
        }

        val distinctChannels = channels.distinctBy { it.url }
        if (distinctChannels.isNotEmpty()) {
            cachedTurkeyChannels = now to distinctChannels
        }
        return distinctChannels
    }

    private suspend fun getTrendingTurkeyChannels(): List<Channel> {
        val now = System.currentTimeMillis()
        cachedTrendingChannels?.let { (timestamp, cachedList) ->
            if (now - timestamp < CACHE_TTL_MS && cachedList.isNotEmpty()) {
                return cachedList
            }
        }

        val trending = getCatalog(
            country = TURKEY,
            page = 1,
            sort = TRENDING_SORT,
        ).first
            .distinctBy { it.url }
            .take(TRENDING_LIMIT)

        if (trending.isNotEmpty()) {
            cachedTrendingChannels = now to trending
        }
        return trending
    }

    private suspend fun getCatalog(
        country: String,
        page: Int,
        searchQuery: String = "",
        sort: String = NAME_SORT,
    ): Pair<List<Channel>, Boolean> {
        val payload = mapOf(
            "language" to "en",
            "region" to "UK",
            "catalogId" to "iptv",
            "id" to "iptv",
            "adult" to true,
            "search" to searchQuery,
            "sort" to sort,
            "filter" to mapOf("group" to country),
            "cursor" to ((page - 1) * PAGE_SIZE),
            "clientVersion" to "3.0.2",
        )
        val response = app.post(
            "$mainUrl/mediahubmx-catalog.json",
            headers = mapOf("content-type" to "application/json; charset=utf-8"),
            json = payload,
        ).body.string()

        if (response.contains("Validation error")) throw ValidationError(response)

        val json = JSONObject(response)
        val hasNext = try {
            json.getInt("nextCursor")
            true
        } catch (_: JSONException) {
            false
        }
        return parseJson<List<Channel>>(json.getString("items")) to hasNext
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val enabledCountries = countries.filterValues { it }
        val country = enabledCountries.keys.singleOrNull().orEmpty()
        val (channels, hasNext) = getCatalog(country, page, query)
        return newSearchResponseList(channels.map(::channelToSearchResponse), hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        getAuthSign() ?: return null

        val response = app.post(
            "$mainUrl/mediahubmx-resolve.json",
            headers = mapOf(
                "user-agent" to RESOLVE_USER_AGENT,
                "accept" to "application/json",
                "content-type" to "application/json; charset=utf-8",
                "referer" to "$mainUrl/",
                "origin" to mainUrl,
            ),
            json = mapOf(
                "language" to "en",
                "region" to "UK",
                "url" to url,
                "clientVersion" to "3.0.2",
            ),
        ).body.string()

        if (response.contains("MediaHubMX signature timed out")) {
            authSign = null
            throw SignatureTimedOutException()
        }

        val channel = parseJson<List<ChannelData>>(response).firstOrNull() ?: return null
        val logo = findMetadataLogo(channel.name)
        return newLiveStreamLoadResponse(channel.name, url, channel.url) {
            this.posterUrl = POSTER_URL
            logo?.let { this.logoUrl = fixUrl(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = data.replace("https://", "http://"),
                type = ExtractorLinkType.M3U8,
            ) {
                referer = "$mainUrl/"
                headers = mapOf(
                    "user-agent" to RESOLVE_USER_AGENT,
                    "origin" to mainUrl,
                )
            },
        )
        return true
    }

    private fun channelToSearchResponse(channel: Channel): LiveSearchResponse {
        val logo = findMetadataLogo(channel.name) ?: channel.logo.takeIf { it.isNotBlank() }
        return newLiveSearchResponse(channel.name, channel.url) {
            posterUrl = logo?.let(::fixUrl) ?: POSTER_URL
        }
    }

    private fun findMetadataLogo(channelName: String): String? {
        val comparableName = normalize(channelName.substringBeforeLast(" .", channelName))
        return turkeyLogoMap[comparableName]
    }

    private suspend fun loadChannelMetadata() {
        if (turkeyLogoMap.isNotEmpty()) return

        val cached = sharedPreferences.getString(CHANNELS_CACHE_KEY, null)
        if (!cached.isNullOrBlank()) {
            turkeyLogoMap = runCatching { parseJson<Map<String, String>>(cached) }
                .getOrDefault(emptyMap())
        }
        if (turkeyLogoMap.isNotEmpty()) return

        runCatching {
            val response = app.get(
                "$mainUrl/live2/index?output=json",
                headers = mapOf(
                    "Content-Type" to "application/json; charset=utf-8",
                    "User-Agent" to BROWSER_USER_AGENT,
                ),
            ).body.string()
            val allChannels = parseJson<List<ChannelMetadata>>(response)
            val map = mutableMapOf<String, String>()
            for (ch in allChannels) {
                if (ch.logo.isNotBlank() && (ch.group.equals("Turkey", ignoreCase = true) || ch.tvg_id.endsWith(".tr"))) {
                    map[normalize(ch.name)] = ch.logo
                    map[normalize(ch.name.substringBeforeLast(" .", ch.name))] = ch.logo
                }
            }
            map
        }.onSuccess { map ->
            if (map.isNotEmpty()) {
                turkeyLogoMap = map
                sharedPreferences.edit().putString(CHANNELS_CACHE_KEY, map.toJson()).apply()
            }
        }.onFailure {
            Log.w(TAG, "Kanal logoları alınamadı: ${it.message}")
        }
    }

    private suspend fun getAuthSign(): AuthSign? {
        authSign?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it }

        val payload = mapOf(
            "token" to "",
            "reason" to "boot",
            "locale" to "tr",
            "theme" to "dark",
            "metadata" to mapOf(
                "device" to mapOf("type" to "desktop", "uniqueId" to ""),
                "os" to mapOf(
                    "name" to "win32",
                    "version" to "Windows 10",
                    "abis" to listOf("x64"),
                    "host" to "DESKTOP",
                ),
                "app" to mapOf("platform" to "electron"),
                "version" to mapOf(
                    "package" to "app.lokke.main",
                    "binary" to "1.0.19",
                    "js" to "1.0.19",
                ),
            ),
            "appFocusTime" to 173,
            "playerActive" to false,
            "playDuration" to 0,
            "devMode" to true,
            "hasAddon" to true,
            "castConnected" to false,
            "package" to "app.lokke.main",
            "version" to "1.0.19",
            "process" to "app",
            "firstAppStart" to System.currentTimeMillis(),
            "lastAppStart" to System.currentTimeMillis(),
            "ipLocation" to 0,
            "adblockEnabled" to true,
            "proxy" to mapOf(
                "supported" to listOf("ss"),
                "engine" to "cu",
                "enabled" to false,
                "autoServer" to true,
                "id" to 0,
            ),
            "iap" to mapOf("supported" to false),
        )
        val response = app.post(
            "https://www.lokke.app/api/app/ping",
            headers = mapOf(
                "accept" to "application/json",
                "user-agent" to AUTH_USER_AGENT,
                "content-type" to "application/json; charset=utf-8",
            ),
            json = payload,
        ).body.string()
        val signature = JSONObject(response).optString("addonSig").takeIf { it.isNotBlank() }
            ?: return null
        return AuthSign(signature, System.currentTimeMillis() + AUTH_CACHE_MILLIS)
            .also { authSign = it }
    }

    private fun categoryFor(channelName: String): TurkeyCategory {
        val name = normalize(channelName)
        return TURKEY_CATEGORIES.firstOrNull { category ->
            category.keywords.any { keyword -> containsKeyword(name, keyword) }
        } ?: TurkeyCategory.OTHER
    }

    private fun containsKeyword(normalizedName: String, keyword: String): Boolean {
        return if (keyword.any { it == ' ' || it.isDigit() }) {
            normalizedName.contains(keyword)
        } else {
            Regex("(?:^| )${Regex.escape(keyword)}(?: |$)").containsMatchIn(normalizedName)
        }
    }

    private fun normalize(value: String): String {
        val turkishLowercase = value.lowercase(Locale.forLanguageTag("tr"))
            .replace('ı', 'i')
        return Normalizer.normalize(turkishLowercase, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    data class AuthSign(val signature: String, val expiresAt: Long)

    data class Channel(
        val type: String,
        val ids: Ids,
        val url: String,
        val name: String,
        val group: String,
        val logo: String,
    )

    data class ChannelData(val id: String, val name: String, val url: String)

    data class ChannelMetadata(
        val url: String,
        val name: String,
        val group: String,
        val logo: String,
        val tvg_id: String,
    )

    data class Ids(val id: String)

    class ValidationError(message: String) : Exception("$message\n")
    class SignatureTimedOutException : Exception("Signature timed out\n")

    private enum class TurkeyCategory(
        val title: String,
        val keywords: Set<String>,
    ) {
        BEIN_SPORTS(
            "BeIN Sports",
            setOf("bein sport", "bein sports", "bein spots"),
        ),
        NATIONAL(
            "Ulusal Kanallar",
            setOf(
                "trt 1", "atv", "show tv", "kanal d", "star tv", "tv8", "tv 8",
                "now tv", "fox tv", "kanal 7", "teve2", "teve 2", "beyaz tv", "a2",
                "360 tv", "tv 360",
            ),
        ),
        SPORTS(
            "Spor",
            setOf(
                "spor", "sport", "sports", "bein sport", "bein sports", "s sport", "ssport", "tivibu spor",
                "tabii spor", "smart spor", "eurosport", "nba", "nfl", "f1", "formula 1",
                "fight", "futbol", "football", "soccer", "racing", "galatasaray tv",
                "fenerbahce tv", "fb tv", "bjk tv", "trabzonspor", "tjk", "a spor",
            ),
        ),
        CINEMA_SERIES(
            "Sinema & Dizi",
            setOf(
                "sinema", "film", "films", "movie", "movies", "dizi", "series", "drama",
                "action", "aksiyon", "komedi", "comedy", "romantik", "romance", "western",
                "thriller", "korku", "yesilcam", "turkmax", "fx", "paramount", "filbox",
                "epic drama", "viasat kino", "bein box office", "bein family", "bein action",
            ),
        ),
        DOCUMENTARY(
            "Belgesel & Yaşam",
            setOf(
                "belgesel", "documentary", "discovery", "national geographic", "nat geo",
                "history", "animal planet", "wild", "science", "investigation", "travel",
                "da vinci", "dmax", "tlc", "food", "yemek", "24 kitchen", "fashion",
                "outdoor", "nature", "yasam", "life", "lovel nature", "v history",
            ),
        ),
        NEWS(
            "Haber",
            setOf(
                "haber", "news", "cnn turk", "cnnturk", "ntv", "haberturk", "a haber",
                "trt haber", "tgrt", "tele 1", "tele1", "halk tv", "sozcu tv", "tv100",
                "tv 100", "ulke tv", "bloomberg ht", "ekoturk", "krt", "flash haber",
            ),
        ),
        KIDS(
            "Çocuk",
            setOf(
                "cocuk", "kids", "kid", "cartoon", "toon", "minika", "disney", "nick",
                "nickelodeon", "baby tv", "babytv", "trt cocuk", "smart cocuk", "boomerang",
                "cartoonito", "duck tv", "ducktv", "moonbug", "cbeebies", "da vinci kids",
            ),
        ),
        MUSIC(
            "Müzik",
            setOf(
                "muzik", "music", "kral pop", "kral tv", "power turk", "powerturk", "dream turk",
                "dreamturk", "number one", "nr1", "mtv", "trace", "mezzo", "mcm", "clubbing",
                "slow karadeniz", "taksim", "turku", "arabesk",
            ),
        ),
        OTHER("Yerel & Diğer", emptySet()),
    }

    private companion object {
        const val TAG = "Vavoo"
        const val TURKEY = "Turkey"
        const val PAGE_SIZE = 300
        const val MAX_TURKEY_PAGES = 20
        const val TRENDING_LIMIT = 30
        const val TRENDING_TITLE = "Trend Kanallar"
        const val TRENDING_SORT = "trending"
        const val NAME_SORT = "name"
        const val AUTH_CACHE_MILLIS = 55_000L
        const val CHANNELS_CACHE_KEY = "channels"
        const val RESOLVE_USER_AGENT = "MediaHubMX/2"
        const val AUTH_USER_AGENT = "okhttp/4.11.0"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
        const val POSTER_URL =
            "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/master/Vavoo/Vavoo.jpg"

        val TURKEY_CATEGORIES = listOf(
            TurkeyCategory.BEIN_SPORTS,
            TurkeyCategory.SPORTS,
            TurkeyCategory.NATIONAL,
            TurkeyCategory.CINEMA_SERIES,
            TurkeyCategory.DOCUMENTARY,
            TurkeyCategory.NEWS,
            TurkeyCategory.KIDS,
            TurkeyCategory.MUSIC,
            TurkeyCategory.OTHER,
        )
        const val CACHE_TTL_MS = 15 * 60 * 1000L
        var authSign: AuthSign? = null
        var cachedTurkeyChannels: Pair<Long, List<Channel>>? = null
        var cachedTrendingChannels: Pair<Long, List<Channel>>? = null
        var turkeyLogoMap: Map<String, String> = emptyMap()
    }
}
