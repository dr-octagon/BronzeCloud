package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class InatBoxBC : MainAPI() {
    override var name = "İnatBox BC"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)
    override var sequentialMainPage = false
    override val getMainPageTimeoutMs = 30_000L

    private val urlToSearchResponse = mutableMapOf<String, SearchResponse>()

    companion object {
        private const val HMK = "x7kkk0qmqz63kj68tla5i7u26192v7zqnnddhjgm"
        private const val SDK_URL = "https://ironsdk.net/api/sdk/"
        private const val FALLBACK_CONFIG_URL = "https://speedrestapi.com/rest/api/"
        private const val FALLBACK_CONTENT_URL = "https://dizilabmedia.click/CDN/001/002/dizilab/v2/ct.php"
        private val SECURE_RANDOM = SecureRandom()
        private val HEX_CHARS = "0123456789abcdef".toCharArray()

        fun generateRandomKey(length: Int = 16): String {
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val sb = StringBuilder(length)
            for (i in 0 until length) {
                sb.append(chars[SECURE_RANDOM.nextInt(chars.length)])
            }
            return sb.toString()
        }

        fun bytesToHex(bytes: ByteArray): String {
            val hex = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val b = bytes[i].toInt() and 0xFF
                hex[i * 2] = HEX_CHARS[b ushr 4]
                hex[i * 2 + 1] = HEX_CHARS[b and 0x0F]
            }
            return String(hex)
        }

        fun sha256Hex(data: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            return bytesToHex(md.digest(data.toByteArray(StandardCharsets.UTF_8)))
        }

        fun hmacSha256(data: String, key: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            return bytesToHex(mac.doFinal(data.toByteArray(StandardCharsets.UTF_8)))
        }

        fun signRequest(method: String, url: String, body: String): Map<String, String> {
            val uriPath = try {
                val rawPath = URI(url).rawPath
                if (rawPath.isNullOrEmpty()) "/" else rawPath
            } catch (_: Exception) {
                "/"
            }

            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val nonceBytes = ByteArray(16)
            SECURE_RANDOM.nextBytes(nonceBytes)
            val nonce = bytesToHex(nonceBytes)

            val bodyHash = sha256Hex(body)
            val signString = "${method.uppercase(Locale.ROOT)}\n$uriPath\n$timestamp\n$nonce\n$bodyHash"
            val signature = hmacSha256(signString, HMK)

            return mapOf(
                "X-Ts" to timestamp,
                "X-Nc" to nonce,
                "X-Sg" to signature
            )
        }

        fun decryptAesLayer(encryptedTextWithIv: String, key: String): String? {
            return runCatching {
                val parts = encryptedTextWithIv.split(":")
                if (parts.size < 2) return null
                val cipherBytes = Base64.decode(parts[0].trim(), Base64.DEFAULT)
                val ivBytes = Base64.decode(parts[1].trim(), Base64.DEFAULT)

                var keyStr = key
                if (keyStr.length < 16) {
                    keyStr = keyStr.padEnd(16, '0')
                } else if (keyStr.length > 16) {
                    keyStr = keyStr.substring(0, 16)
                }

                val keyBytes = keyStr.toByteArray(StandardCharsets.ISO_8859_1)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
                String(cipher.doFinal(cipherBytes), StandardCharsets.ISO_8859_1)
            }.getOrNull()
        }

        fun decryptDoubleAes(encryptedResponse: String, key: String): String? {
            return runCatching {
                val layer1 = decryptAesLayer(encryptedResponse, key) ?: return null
                val layer2 = decryptAesLayer(layer1, key) ?: return null
                String(layer2.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
            }.getOrNull()
        }

        fun verifyAndStripHmacSuffix(text: String): String? {
            if (text.length <= 64) return null
            val payload = text.substring(0, text.length - 64)
            val signature = text.substring(text.length - 64)
            val expected = hmacSha256(payload, HMK)
            return if (signature.equals(expected, ignoreCase = true)) {
                payload
            } else {
                payload
            }
        }
    }

    private val contentUrl by lazy { resolveContentUrl() }

    override val mainPage = mainPageOf(FALLBACK_CONTENT_URL to "Ana Sayfa")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val activeUrl = try { contentUrl } catch (_: Exception) { FALLBACK_CONTENT_URL }
        val lists = loadCategoryLists(activeUrl)
        return if (lists.isEmpty()) {
            newHomePageResponse(request.name, emptyList())
        } else {
            newHomePageResponse(lists)
        }
    }

    private suspend fun loadCategoryLists(indexUrl: String): List<HomePageList> {
        val decryptedIndex = makeInatPostRequest(indexUrl) ?: return emptyList()
        val categories = runCatching { JSONArray(decryptedIndex) }.getOrNull() ?: return emptyList()

        val orderedCategories = (0 until categories.length())
            .mapNotNull { categories.optJSONObject(it) }
            .filter(::categoryAllowed)
            .sortedBy { categoryPriority(it.optString("catName")) }

        return coroutineScope {
            orderedCategories.map { category ->
                async(Dispatchers.IO) {
                    val categoryUrl = category.optString("catUrl")
                    if (categoryUrl.isBlank()) return@async null

                    val response = makeInatPostRequest(categoryUrl) ?: return@async null
                    val results = getSearchResponseList(response)
                    if (results.isEmpty()) return@async null

                    synchronized(urlToSearchResponse) {
                        results.forEach { urlToSearchResponse.putIfAbsent(it.url, it) }
                    }

                    val categoryName = category.optString("catName", "İsimsiz")
                    val categoryType = category.optString("catType").lowercase(Locale.ROOT)
                    val isHorizontal = categoryType.contains("live") ||
                        categoryType.contains("iptv") ||
                        categoryType.contains("tv") ||
                        categoryName.lowercase(Locale.ROOT).contains("canlı") ||
                        categoryName.lowercase(Locale.ROOT).contains("spor")

                    HomePageList(categoryName, results, isHorizontal)
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun categoryPriority(name: String): Int {
        val normalizedName = name.lowercase(Locale.forLanguageTag("tr"))
        return when {
            normalizedName.contains("spor") -> 0
            normalizedName.contains("ulusal") -> 1
            normalizedName.contains("sinema") -> 2
            else -> 3
        }
    }

    private fun categoryAllowed(category: JSONObject): Boolean {
        val name = category.optString("catName")
        val type = category.optString("catType")
        val url = category.optString("catUrl")
        val lowerName = name.lowercase(Locale.forLanguageTag("tr"))
        return type != "link" && type != "link_mode" && type != "destek" &&
            type != "destek_mode" && name != "Hata Bildir" && name != "Derbiler" &&
            !lowerName.contains("liste 1") && !url.contains("list1.php") &&
            !lowerName.contains("| web") && !url.contains("catalog-web") &&
            !url.contains("4k-film-exo.php") && !url.contains("destek_mode") &&
            !url.contains("inattv") && !url.contains("x.com/") && !name.startsWith("@")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (urlToSearchResponse.isEmpty()) {
            val activeUrl = try { contentUrl } catch (_: Exception) { FALLBACK_CONTENT_URL }
            loadCategoryLists(activeUrl)
        }

        val matchingResults = mutableListOf<SearchResponse>()
        val regex = try {
            Regex(query, RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        }

        for ((_, searchResponse) in urlToSearchResponse) {
            if (regex.containsMatchIn(searchResponse.name)) {
                matchingResults.add(searchResponse)
            }
        }

        return matchingResults.distinctBy { it.name }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val item = JSONObject(url)
        if (!inatContentAllowed(item)) {
            return null
        }

        return if (item.has("diziType")) {
            val type = item.getString("diziType")
            when {
                type.contains("dizi") -> parseTvSeriesResponse(item)
                type.contains("film") -> parseMovieResponse(item)
                else -> null
            }
        } else if (item.has("chName") && item.has("chUrl") && item.has("chImg")) {
            val chType = item.getString("chType")
            when {
                chType.contains("SsprDrm", ignoreCase = true) -> parseSSportResponse(item)
                chType.contains("live") || chType.contains("cable") -> parseLiveStreamLoadResponse(item)
                chType.contains("tekli") -> parseLiveSportsStreamLoadResponse(item)
                else -> parseMovieResponse(item)
            }
        } else {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            if (data.startsWith("[")) {
                val chContentJsonArray = JSONArray(data)
                for (i in 0 until chContentJsonArray.length()) {
                    val chContentJsonObject = chContentJsonArray.getJSONObject(i)
                    val chContent = parseToChContent(chContentJsonObject)
                    loadChContentLinks(chContent, subtitleCallback, callback)
                }
            } else {
                val chContentJsonObject = JSONObject(data)
                val chContent = parseToChContent(chContentJsonObject)
                loadChContentLinks(chContent, subtitleCallback, callback)
            }
            true
        } catch (e: Exception) {
            Log.e("InatBoxBC", "Error on loadLinks: ${e.message}")
            false
        }
    }

    private suspend fun parseTvSeriesResponse(
        item: JSONObject,
        tvType: TvType = TvType.TvSeries
    ): LoadResponse? {
        val episodes = mutableMapOf<DubStatus, MutableList<Episode>>()
        val seasonDataList = mutableListOf<SeasonData>()

        val name = item.getString("diziName")
        val url = item.getString("diziUrl")
        val plot = item.optString("diziDetay", "")

        val jsonResponse = makeInatPostRequest(url) ?: return null
        val jsonArray = runCatching { JSONArray(jsonResponse) }.getOrNull() ?: return null

        try {
            for (i in 0 until jsonArray.length()) {
                val seasonItem = jsonArray.getJSONObject(i)
                val seasonName = seasonItem.getString("diziName")
                seasonDataList.add(SeasonData(season = (i + 1), name = seasonName))

                val seasonUrl = seasonItem.getString("diziUrl")
                val episodeResponse = makeInatPostRequest(seasonUrl) ?: continue
                val episodeArray = runCatching { JSONArray(episodeResponse) }.getOrNull() ?: continue

                for (j in 0 until episodeArray.length()) {
                    try {
                        val episodeItem = episodeArray.getJSONObject(j)
                        val episodeName = episodeItem.getString("chName")
                        val episodePoster = episodeItem.optString("chImg", "")
                        episodes.getOrPut(DubStatus.None) { mutableListOf() }.add(
                            newEpisode(episodeItem.toString()) {
                                this.name = episodeName
                                this.posterUrl = episodePoster
                                this.season = i + 1
                                this.episode = j + 1
                            }
                        )
                    } catch (_: JSONException) {
                        continue
                    }
                }
            }

            val posterUrl = if (jsonArray.length() > 0) {
                jsonArray.getJSONObject(0).optString("diziImg", item.optString("diziImg", ""))
            } else {
                item.optString("diziImg", "")
            }

            return newAnimeLoadResponse(
                name = name,
                url = item.toString(),
                type = tvType,
                comingSoonIfNone = false
            ) {
                this.episodes = episodes.mapValues { it.value.toList() }.toMutableMap()
                this.posterUrl = posterUrl
                this.plot = plot
                this.seasonNames = seasonDataList
            }
        } catch (e: Exception) {
            Log.e("InatBoxBC", "Failed to parse TV series response: ${e.message}")
            return null
        }
    }

    private suspend fun parseSSportResponse(item: JSONObject): LoadResponse? {
        try {
            val name = item.optString("chName", "S Sport Plus")
            val posterUrl = item.optString("chImg", "")

            val rawResponse = try {
                app.get(
                    "https://sprspr.help/CDN/SSP/bir-p-no-cron.php",
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Safari/605.1.15",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "https://google.com/"
                    )
                ).body.string()
            } catch (_: Exception) {
                return null
            }

            val jsonResponse = JSONObject(rawResponse)
            val categories = jsonResponse.optJSONArray("Categories") ?: return null
            val firstCategory = categories.optJSONObject(0) ?: return null
            val contents = firstCategory.optJSONArray("Contents") ?: return null

            val episodes = mutableListOf<Episode>()
            for (i in 0 until contents.length()) {
                val content = contents.optJSONObject(i) ?: continue
                val epName = content.optString("Title", "")
                val description = content.optString("Description", "")
                val medias = content.optJSONArray("Medias")
                val mediaUrl = if (medias != null && medias.length() > 0) {
                    medias.optJSONObject(0)?.optString("URL", "") ?: ""
                } else ""

                if (mediaUrl.isNotEmpty()) {
                    episodes.add(
                        newEpisode(mediaUrl) {
                            this.name = epName
                            this.description = description
                            this.episode = i + 1
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }

            return newAnimeLoadResponse(
                name = name,
                url = item.toString(),
                type = TvType.TvSeries
            ) {
                this.episodes = mutableMapOf(DubStatus.None to episodes)
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("InatBoxBC", "Failed to parse SSport response: ${e.message}")
            return null
        }
    }

    private suspend fun parseMovieResponse(item: JSONObject): LoadResponse? {
        return try {
            if (item.has("diziType")) {
                val name = item.getString("diziName")
                val url = item.getString("diziUrl")
                val posterUrl = item.optString("diziImg", "")
                val plot = item.optString("diziDetay", "")

                val jsonResponse = makeInatPostRequest(url) ?: return null
                val jsonArray = JSONArray(jsonResponse)

                newMovieLoadResponse(
                    name = name,
                    url = item.toString(),
                    type = TvType.Movie,
                    dataUrl = jsonArray.toString()
                ) {
                    this.posterUrl = posterUrl
                    this.plot = plot
                }
            } else {
                val name = item.getString("chName")
                val posterUrl = item.optString("chImg", "")
                newMovieLoadResponse(name, item.toString(), TvType.Movie, item.toString()) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            Log.e("InatBoxBC", "Failed to parse movie response: ${e.message}")
            null
        }
    }

    private suspend fun parseLiveSportsStreamLoadResponse(item: JSONObject): LiveStreamLoadResponse? {
        return try {
            val chContent = parseToChContent(item)
            newLiveStreamLoadResponse(chContent.chName, item.toString(), item.toString()) {
                this.posterUrl = chContent.chImg
            }
        } catch (e: Exception) {
            Log.e("InatBoxBC", "Failed to parse sports live stream response: ${e.message}")
            null
        }
    }

    private suspend fun parseLiveStreamLoadResponse(item: JSONObject): LiveStreamLoadResponse? {
        return try {
            val chContent = parseToChContent(item)
            newLiveStreamLoadResponse(chContent.chName, item.toString(), item.toString()) {
                this.posterUrl = chContent.chImg
            }
        } catch (e: Exception) {
            Log.e("InatBoxBC", "Failed to parse live stream response: ${e.message}")
            null
        }
    }

    private fun inatContentAllowed(item: JSONObject): Boolean {
        val type = if (item.has("diziType")) item.optString("diziType") else item.optString("chType")
        return when (type) {
            "link", "web", "link_mode", "web_mode", "destek", "destek_mode" -> false
            else -> true
        }
    }

    private fun String.vkSourceFix(): String {
        return if (this.startsWith("act")) {
            "https://vk.com/al_video.php?$this"
        } else {
            this
        }
    }

    private fun parseToChContent(item: JSONObject): ChContent {
        return ChContent(
            chName = item.optString("chName"),
            chUrl = item.optString("chUrl").vkSourceFix(),
            chImg = item.optString("chImg"),
            chHeaders = item.opt("chHeaders")?.toString() ?: "null",
            chReg = item.opt("chReg")?.toString() ?: "null",
            chType = item.optString("chType")
        )
    }

    private suspend fun loadChContentLinks(
        chContent: ChContent,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val resolvedChContent = if (chContent.chUrl.startsWith("NONE/")) {
            val id = chContent.chUrl.substringAfter("NONE/")
            chContent.copy(chUrl = "https://sspplus.redzones.icu/CDN/SSP/txt/$id.m3u8")
        } else {
            chContent
        }

        val chType = resolvedChContent.chType
        var sourceUrl = resolvedChContent.chUrl

        val headers = mutableMapOf<String, String>()
        var regex1: String? = null
        var regex2: String? = null
        var regex2p: String? = null

        try {
            val chHeaders = resolvedChContent.chHeaders
            if (chHeaders != "null" && chHeaders.isNotBlank()) {
                val jsonHeaders = JSONArray(chHeaders).getJSONObject(0)
                for (key in jsonHeaders.keys()) {
                    val keyName = when (key) {
                        "UserAgent" -> "User-Agent"
                        "XRequestedWith" -> "X-Requested-With"
                        else -> key
                    }
                    headers[keyName] = jsonHeaders.getString(key)
                }
            }
        } catch (_: Exception) {}

        try {
            val chReg = resolvedChContent.chReg
            if (chReg != "null" && chReg.isNotBlank()) {
                val jsonReg = JSONArray(chReg).getJSONObject(0)
                regex1 = jsonReg.optString("Regex1", null)
                regex2 = jsonReg.optString("Regex2", null)
                regex2p = jsonReg.optString("Regex2p", null)
                if (jsonReg.has("playSH2")) {
                    headers["Cookie"] = jsonReg.getString("playSH2")
                }
            }
        } catch (_: Exception) {}

        if (!headers.containsKey("Referer")) {
            headers["Referer"] = "https://google.com/"
        }
        if (!headers.containsKey("User-Agent")) {
            headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0"
        }

        // Resolving dynamic streams
        if (chType.contains("tekli_regex") && !isDirectStream(sourceUrl) && !regex1.isNullOrEmpty()) {
            val resolved = resolveTekliRegexStream(sourceUrl, headers, regex1, regex2, regex2p)
            if (!resolved.isNullOrBlank()) {
                sourceUrl = resolved
            }
        }

        if (sourceUrl.contains("filmizleeeee") && !isDirectStream(sourceUrl)) {
            val resolved = resolveFilmizleStream(sourceUrl, headers)
            if (!resolved.isNullOrBlank()) {
                sourceUrl = resolved
            }
        }

        if (isDirectStream(sourceUrl)) {
            val linkType = when {
                sourceUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                sourceUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = resolvedChContent.chName,
                    url = sourceUrl,
                    type = linkType
                ) {
                    this.referer = headers["Referer"].orEmpty()
                    this.headers = headers
                    this.quality = Qualities.Unknown.value
                }
            )
        } else {
            loadExtractor(
                sourceUrl,
                headers["Referer"].orEmpty(),
                subtitleCallback,
                callback
            )
        }
    }

    private suspend fun resolveTekliRegexStream(
        url: String,
        headers: Map<String, String>,
        regex1: String,
        regex2: String?,
        regex2p: String?
    ): String? {
        return try {
            val reqHeaders = headers.toMutableMap()
            reqHeaders.putAll(signRequest("GET", url, ""))
            reqHeaders["Cache-Control"] = "no-cache"

            val response = app.get(url, headers = reqHeaders)
            if (!response.isSuccessful) return null

            val raw = response.body.string().trim()
            val p1 = decryptAesLayer(raw, regex1) ?: return null

            val key2 = regex2?.takeIf { it.isNotBlank() } ?: regex1
            var p2 = decryptAesLayer(p1, key2)
            if (p2 == null && !regex2p.isNullOrBlank()) {
                p2 = decryptAesLayer(p1, regex2p)
            }
            if (p2 == null) return null

            val stripped = verifyAndStripHmacSuffix(p2) ?: p2
            val json = JSONObject(stripped)
            json.optString("chUrl", null)
        } catch (e: Exception) {
            Log.e("InatBoxBC", "Failed to resolve stream for $url: ${e.message}")
            null
        }
    }

    private fun isDirectStream(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true) ||
            url.contains(".mpd", ignoreCase = true) ||
            url.contains(".mp4", ignoreCase = true) ||
            url.contains(".webm", ignoreCase = true)

    private suspend fun resolveFilmizleStream(
        url: String,
        headers: Map<String, String>
    ): String? {
        var response: String = try {
            app.get(url, headers = headers, referer = headers["Referer"]).body.string()
        } catch (_: Exception) {
            return null
        }

        repeat(3) {
            val separator = response.lastIndexOf(':')
            if (separator < 1) return@repeat

            val encrypted = response.substring(0, separator).trim()
            val encodedKey = response.substring(separator + 1).trim()
            val key = try {
                String(Base64.decode(encodedKey, Base64.DEFAULT))
            } catch (_: Exception) {
                return@repeat
            }
            response = decryptAesLayer(encrypted, key) ?: return@repeat

            val json = try {
                JSONObject(response.trim())
            } catch (_: Exception) {
                null
            }
            if (json != null && json.has("chUrl")) return json.optString("chUrl")
        }
        return null
    }

    private suspend fun makeInatPostRequest(url: String, retryCount: Int = 2): String? {
        val hostName = try {
            URI(url).host ?: "speedrestapi.com"
        } catch (_: Exception) {
            "speedrestapi.com"
        }

        repeat(retryCount) { attempt ->
            try {
                val dynamicKey = generateRandomKey(16)
                val requestBody = "1=$dynamicKey&0=$dynamicKey"
                val signatureHeaders = signRequest("POST", url, requestBody)

                val headers = mutableMapOf(
                    "User-Agent" to "speedrestapi",
                    "X-Requested-With" to "com.bp.box",
                    "Referer" to "https://speedrestapi.com/",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Cache-Control" to "no-cache",
                    "Host" to hostName
                )
                headers.putAll(signatureHeaders)

                val response = app.post(
                    url = url,
                    headers = headers,
                    requestBody = requestBody.toRequestBody(
                        "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
                    )
                )

                if (response.isSuccessful) {
                    val rawBody = response.body.string()
                    val decrypted = decryptDoubleAes(rawBody, dynamicKey)
                    if (!decrypted.isNullOrBlank()) {
                        return decrypted
                    }
                }
            } catch (e: Exception) {
                if (attempt == retryCount - 1) {
                    Log.e("InatBoxBC", "Post request failed for $url: ${e.message}")
                }
            }
        }
        return null
    }

    private fun resolveContentUrl(): String = runBlocking {
        try {
            val sdkResponse = makeInatPostRequest(SDK_URL)
            val configUrl = if (!sdkResponse.isNullOrBlank()) {
                JSONObject(sdkResponse).optString("DC1", FALLBACK_CONFIG_URL)
            } else {
                FALLBACK_CONFIG_URL
            }

            val configResponse = makeInatPostRequest(configUrl)
            if (!configResponse.isNullOrBlank()) {
                val json = JSONObject(configResponse)
                json.optString("DC2", FALLBACK_CONTENT_URL)
            } else {
                FALLBACK_CONTENT_URL
            }
        } catch (e: Exception) {
            Log.w("InatBoxBC", "Dynamic domain lookup failed, using fallback: ${e.message}")
            FALLBACK_CONTENT_URL
        }
    }

    private fun getSearchResponseList(jsonResponse: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        try {
            val jsonArray = JSONArray(jsonResponse)

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                if (!inatContentAllowed(item)) continue

                if (item.has("diziType")) {
                    val name = item.getString("diziName")
                    val type = item.getString("diziType")
                    val posterUrl = item.optString("diziImg", "")

                    val searchResponse = when {
                        type.contains("dizi") -> newTvSeriesSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }
                        type.contains("film") -> newMovieSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }
                        else -> null
                    }
                    searchResponse?.let { searchResults.add(it) }
                } else if (item.has("chName") && item.has("chUrl") && item.has("chImg")) {
                    val name = item.getString("chName")
                    val posterUrl = item.optString("chImg", "")
                    val chType = item.optString("chType")

                    val searchResponse = when {
                        chType.contains("live") || chType.contains("tekli") -> newLiveSearchResponse(
                            name,
                            item.toString(),
                            TvType.Live
                        ) {
                            this.posterUrl = posterUrl
                        }
                        else -> newMovieSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }
                    }
                    searchResults.add(searchResponse)
                }
            }
        } catch (e: Exception) {
            Log.e("InatBoxBC", "Failed to parse JSON response: ${e.message}")
        }
        return searchResults
    }
}
