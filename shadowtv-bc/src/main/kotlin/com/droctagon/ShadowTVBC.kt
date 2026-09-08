package com.droctagon

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ShadowTVBC : MainAPI() {
    override var mainUrl              = "https://androidapi.site"
    override var name                 = "Shadow TV BC"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)

    companion object {
        private const val BOOTSTRAP_URL    = "https://raw.githubusercontent.com/asdsplus/gulum/refs/heads/main/ssl2.key"
        private const val DEFAULT_API_URL  = "https://androidapi.site/appMainGetData.php"
        private const val SIMGE            = "trskmrskslmzbzcnfstkcshpfstkcshp"
        private const val IMGE             = "trskmrskslmzbzcn"
        private const val TS_KEY           = "HdRyPcAqhpXf92kLmThp4sQzWv7nXeAs"
        private const val TS_IV            = "GbMJtUeB9hGzskmz"
        private const val DEFAULT_EMAIL    = "atarsercan2@gmail.com"
        private const val DEFAULT_PASSWORD = "Yarrakadam"
        private const val UUID             = "c9a8d7df-6401-4bbc-97b5-6630cc1470e1"
        private const val ASIZ_HASH        = "ZZzIUo5Wrw1T1WMwjybNwg=="
        private const val GLG1_KEY         = "0Ae0+Zxj5pITIE38f+LqpRGO1IQjj9NytSciDvSew+oNq7T6dGsHASlMl+O8DUxu"
        private const val ORMOX_ROKS       = "D8C42BC6CD20C00E85659003F62B1F4A7A882DCB"
        private const val USER_AGENT       = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:80.0) Gecko/20100101 Firefox/79.0"

        private const val VOD_FILM_API     = "https://abibigitya.xyz/api/film/filmmakinesi/filmapi.php"
        private const val VOD_FILM_SEARCH  = "https://abibigitya.xyz/api/film/filmmakinesi/search.php"
        private const val VOD_DIZI_API     = "https://abibigitya.xyz/api/dizi/dizipal/diziapi.php"
        private const val VOD_DIZI_SEARCH  = "https://abibigitya.xyz/api/dizi/dizipal/dizisearch.php"
    }

    private val channelMutex = Mutex()
    private var cachedApiUrl: String? = null
    private var cachedChannels: List<ShadowChannelItem>? = null
    private var lastFetchTime = 0L

    // ---- Cryptography Helpers ----
    private fun decryptAesCbc(cipherTextB64: String, key: ByteArray, iv: ByteArray): String {
        val cipherBytes = Base64.decode(cipherTextB64.trim(), Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
    }

    private fun generateTimestampToken(): String {
        val nowStr = System.currentTimeMillis().toString()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(TS_KEY.toByteArray(StandardCharsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(TS_IV.toByteArray(StandardCharsets.UTF_8))
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(nowStr.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun getApiUrl(): String = DEFAULT_API_URL

    // ---- Live TV & Playlist APIs ----
    private suspend fun fetchMainChannels(): List<ShadowChannelItem> {
        val now = System.currentTimeMillis()
        if (cachedChannels != null && (now - lastFetchTime) < 300_000) {
            return cachedChannels ?: emptyList()
        }

        return channelMutex.withLock {
            val recheckNow = System.currentTimeMillis()
            if (cachedChannels != null && (recheckNow - lastFetchTime) < 300_000) {
                return@withLock cachedChannels ?: emptyList()
            }

            try {
                val apiUrl = getApiUrl()
                val tsToken = generateTimestampToken()
                val simgeBytes = SIMGE.toByteArray(StandardCharsets.UTF_8)
                val imgeBytes = IMGE.toByteArray(StandardCharsets.UTF_8)

                val formMap = mapOf(
                    "ormoxRoks" to ORMOX_ROKS,
                    "ormxArmegedEryxc" to "",
                    "uuid" to UUID,
                    "asize" to ASIZ_HASH,
                    "serverurl" to BOOTSTRAP_URL,
                    "glg1Key" to GLG1_KEY,
                    "Authorization" to tsToken,
                    "email" to DEFAULT_EMAIL,
                    "password" to DEFAULT_PASSWORD
                )

                val headers = mapOf(
                    "X-Requested-With" to "com.golge.golgetv2",
                    "User-Agent" to USER_AGENT,
                    "Authorization" to "Bearer $tsToken",
                    "email" to DEFAULT_EMAIL,
                    "password" to DEFAULT_PASSWORD,
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                )

                val res = app.post(apiUrl, data = formMap, headers = headers)
                val decrypted = decryptAesCbc(res.text, simgeBytes, imgeBytes)
                val parsed = AppUtils.tryParseJson<ShadowChannelResponse>(decrypted)
                val list = parsed?.channels ?: emptyList()
                Log.d(name, "fetchMainChannels: Successfully loaded ${list.size} channels")
                cachedChannels = list
                lastFetchTime = System.currentTimeMillis()
                list
            } catch (e: Exception) {
                Log.e(name, "Failed to fetch main channels: ${e.message}", e)
                cachedChannels ?: emptyList()
            }
        }
    }

    private suspend fun fetchSubPlaylist(linkId: String): List<ShadowSubPlaylistItem> {
        return try {
            val apiUrl = getApiUrl()
            val tsToken = generateTimestampToken()
            val simgeBytes = SIMGE.toByteArray(StandardCharsets.UTF_8)
            val imgeBytes = IMGE.toByteArray(StandardCharsets.UTF_8)

            val formMap = mapOf(
                "ormoxRoks" to ORMOX_ROKS,
                "qOyOxSzVyL" to linkId,
                "tICFQdmhzR" to "",
                "Authorization" to tsToken,
                "email" to DEFAULT_EMAIL,
                "password" to DEFAULT_PASSWORD
            )

            val headers = mapOf(
                "X-Requested-With" to "com.golge.golgetv2",
                "User-Agent" to USER_AGENT,
                "Authorization" to "Bearer $tsToken",
                "email" to DEFAULT_EMAIL,
                "password" to DEFAULT_PASSWORD,
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            )

            val res = app.post(apiUrl, data = formMap, headers = headers)
            val decrypted = decryptAesCbc(res.text, simgeBytes, imgeBytes)
            val parsed = AppUtils.tryParseJson<ShadowSubPlaylistWrapper>(decrypted)
            val items = parsed?.list?.items ?: emptyList()
            Log.d(name, "fetchSubPlaylist: Loaded ${items.size} items for linkId $linkId")
            items
        } catch (e: Exception) {
            Log.e(name, "Failed to fetch sub-playlist for linkId $linkId: ${e.message}", e)
            emptyList()
        }
    }

    // ---- Main Page Configuration ----
    override val mainPage = mainPageOf(
        "spor_kanallari"       to "Spor Kanalları",
        "canli_maclar"         to "Canlı Maç Yayınları",
        "ulusal_tv"            to "Ulusal ve Canlı TV",
        "haber_kanallari"      to "Haber Kanalları",
        "sinema_kanallari"     to "Sinema Kanalları",
        "belgesel_kanallari"   to "Belgesel Kanalları",
        "cocuk_kanallari"      to "Çocuk ve Çizgi Dizi",
        "dunya_kanallari"      to "Dünya Kanalları (World)",
        "ozel_paneller"        to "Özel Paneller (D-Smart, Tabii, RC)",
        "son_filmler"          to "Son Eklenen Filmler",
        "son_diziler"          to "Son Eklenen Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val mapper = jacksonObjectMapper()

        if (request.data == "son_filmler") {
            val films = try {
                val url = if (page <= 1) VOD_FILM_API else "$VOD_FILM_API?page=$page"
                val res = app.get(url, headers = mapOf("User-Agent" to USER_AGENT))
                AppUtils.tryParseJson<List<ShadowVodItem>>(res.text) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            val list = films.mapNotNull { film ->
                val title = film.title ?: return@mapNotNull null
                val pass = ShadowPassData(type = "vod_film", vodItem = film)
                newMovieSearchResponse(title, mapper.writeValueAsString(pass), TvType.Movie) {
                    this.posterUrl = film.image
                    this.year = film.year?.toIntOrNull()
                }
            }
            return newHomePageResponse(request.name, list)
        }

        if (request.data == "son_diziler") {
            val diziler = try {
                val url = if (page <= 1) VOD_DIZI_API else "$VOD_DIZI_API?page=$page"
                val res = app.get(url, headers = mapOf("User-Agent" to USER_AGENT))
                AppUtils.tryParseJson<List<ShadowVodItem>>(res.text) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            val list = diziler.mapNotNull { dizi ->
                val title = dizi.title ?: return@mapNotNull null
                val pass = ShadowPassData(type = "vod_dizi", vodItem = dizi)
                newTvSeriesSearchResponse(title, mapper.writeValueAsString(pass), TvType.TvSeries) {
                    this.posterUrl = dizi.image
                    this.year = dizi.year?.toIntOrNull()
                }
            }
            return newHomePageResponse(request.name, list)
        }

        val allChannels = fetchMainChannels()
        val filtered = when (request.data) {
            "spor_kanallari" -> allChannels.filter {
                it.kategori.equals("SPOR", ignoreCase = true) && !it.player.equals("m3u", ignoreCase = true)
            }
            "canli_maclar" -> allChannels.filter {
                it.kategori.equals("SPOR", ignoreCase = true) && it.player.equals("m3u", ignoreCase = true)
            }
            "ulusal_tv" -> allChannels.filter {
                (it.kategori.equals("CANLI", ignoreCase = true) || it.kategori.equals("ULUSAL", ignoreCase = true)) &&
                        !it.player.equals("m3u", ignoreCase = true)
            }
            "haber_kanallari" -> allChannels.filter {
                it.kategori.equals("HABER", ignoreCase = true)
            }
            "sinema_kanallari" -> allChannels.filter {
                it.kategori.equals("SİNEMA", ignoreCase = true) || it.kategori.equals("SINEMA", ignoreCase = true)
            }
            "belgesel_kanallari" -> allChannels.filter {
                it.kategori.equals("BELGESEL", ignoreCase = true)
            }
            "cocuk_kanallari" -> allChannels.filter {
                it.kategori.equals("ÇOCUK", ignoreCase = true) || it.kategori.equals("COCUK", ignoreCase = true)
            }
            "dunya_kanallari" -> allChannels.filter {
                it.kategori.equals("WORLD", ignoreCase = true)
            }
            "ozel_paneller" -> allChannels.filter {
                it.kategori.equals("PANELLER", ignoreCase = true)
            }
            else -> allChannels
        }

        val homeItems = filtered.mapNotNull { ch ->
            val title = ch.isim ?: return@mapNotNull null
            val isSubPlaylist = ch.player.equals("m3u", ignoreCase = true)
            val pass = ShadowPassData(type = if (isSubPlaylist) "m3u" else "channel", channel = ch)
            val dataStr = mapper.writeValueAsString(pass)

            if (isSubPlaylist) {
                newTvSeriesSearchResponse(title, dataStr, TvType.Live) {
                    this.posterUrl = ch.resim
                }
            } else {
                newLiveSearchResponse(title, dataStr, TvType.Live) {
                    this.posterUrl = ch.resim
                }
            }
        }

        return newHomePageResponse(request.name, homeItems)
    }

    // ---- Search ----
    override suspend fun search(query: String): List<SearchResponse> {
        val mapper = jacksonObjectMapper()
        val results = mutableListOf<SearchResponse>()
        val cleanQuery = query.trim().lowercase()

        // 1. Live Channel Search
        try {
            val allChannels = fetchMainChannels()
            val matchedChannels = allChannels.filter { ch ->
                ch.isim?.lowercase()?.contains(cleanQuery) == true
            }
            matchedChannels.forEach { ch ->
                val title = ch.isim ?: return@forEach
                val isSub = ch.player.equals("m3u", ignoreCase = true)
                val pass = ShadowPassData(type = if (isSub) "m3u" else "channel", channel = ch)
                val dataStr = mapper.writeValueAsString(pass)
                if (isSub) {
                    results.add(newTvSeriesSearchResponse(title, dataStr, TvType.Live) {
                        this.posterUrl = ch.resim
                    })
                } else {
                    results.add(newLiveSearchResponse(title, dataStr, TvType.Live) {
                        this.posterUrl = ch.resim
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error searching channels: ${e.message}")
        }

        // 2. Film Search
        try {
            val res = app.get("$VOD_FILM_SEARCH?search=${Uri.encode(query)}", headers = mapOf("User-Agent" to USER_AGENT))
            val films = AppUtils.tryParseJson<List<ShadowVodItem>>(res.text) ?: emptyList()
            films.forEach { film ->
                val title = film.title ?: return@forEach
                val pass = ShadowPassData(type = "vod_film", vodItem = film)
                results.add(newMovieSearchResponse(title, mapper.writeValueAsString(pass), TvType.Movie) {
                    this.posterUrl = film.image
                    this.year = film.year?.toIntOrNull()
                } )
            }
        } catch (e: Exception) {
            Log.e(name, "Error searching films: ${e.message}")
        }

        // 3. Dizi Search
        try {
            val res = app.get("$VOD_DIZI_SEARCH?search=${Uri.encode(query)}", headers = mapOf("User-Agent" to USER_AGENT))
            val diziler = AppUtils.tryParseJson<List<ShadowVodItem>>(res.text) ?: emptyList()
            diziler.forEach { dizi ->
                val title = dizi.title ?: return@forEach
                val pass = ShadowPassData(type = "vod_dizi", vodItem = dizi)
                results.add(newTvSeriesSearchResponse(title, mapper.writeValueAsString(pass), TvType.TvSeries) {
                    this.posterUrl = dizi.image
                    this.year = dizi.year?.toIntOrNull()
                })
            }
        } catch (e: Exception) {
            Log.e(name, "Error searching diziler: ${e.message}")
        }

        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---- Load Details ----
    override suspend fun load(url: String): LoadResponse? {
        val mapper = jacksonObjectMapper()
        val passData = AppUtils.tryParseJson<ShadowPassData>(url)

        // 1. M3U Sub-Playlist (e.g. CANLI MAÇLAR 1-11, D-Smart, RC Panel)
        if (passData?.type == "m3u" || passData?.channel?.player.equals("m3u", ignoreCase = true)) {
            val ch = passData?.channel ?: return null
            val linkId = ch.link ?: ch.id ?: return null
            val items = fetchSubPlaylist(linkId)

            val episodes = items.mapIndexed { index, subItem ->
                val subPass = ShadowPassData(type = "sub_item", subItem = subItem)
                val subDataStr = mapper.writeValueAsString(subPass)
                newEpisode(subDataStr) {
                    this.name = subItem.title ?: "Yayın ${index + 1}"
                    this.episode = index + 1
                    this.posterUrl = subItem.thumb_square ?: ch.resim
                    this.description = subItem.group
                }
            }

            return newTvSeriesLoadResponse(ch.isim ?: "Oynatma Listesi", url, TvType.Live, episodes) {
                this.posterUrl = ch.resim
                this.plot = "${ch.isim} canlı yayın ve maç kanalları listesi."
                this.tags = listOfNotNull(ch.kategori, "Canlı Liste")
            }
        }

        // 2. Direct Live Channel
        if (passData?.type == "channel" || passData?.channel != null) {
            val ch = passData?.channel ?: return null
            return newLiveStreamLoadResponse(ch.isim ?: "Canlı Kanal", url, url) {
                this.posterUrl = ch.resim
                this.plot = "Kategori: ${ch.kategori ?: "Canlı"}"
                this.tags = listOfNotNull(ch.kategori, "Canlı TV")
            }
        }

        // 3. VOD Film
        if (passData?.type == "vod_film") {
            val film = passData.vodItem ?: return null
            val detailUrl = film.detailUrl
            val detail = if (!detailUrl.isNullOrEmpty()) {
                try {
                    val res = app.get(detailUrl, headers = mapOf("User-Agent" to USER_AGENT))
                    AppUtils.tryParseJson<ShadowVodDetail>(res.text)
                } catch (e: Exception) {
                    null
                }
            } else null

            val title = detail?.film_name ?: film.title ?: "Film"
            val plot = detail?.film_description
            val poster = detail?.film_img ?: film.image

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = film.year?.toIntOrNull()
                this.score = Score.from10((detail?.imdb_rating ?: film.imdb)?.trim())
                this.tags = listOfNotNull(detail?.tur, film.dil)
            }
        }

        // 4. VOD Dizi
        if (passData?.type == "vod_dizi") {
            val dizi = passData.vodItem ?: return null
            val detailUrl = dizi.detailUrl
            val detail = if (!detailUrl.isNullOrEmpty()) {
                try {
                    val res = app.get(detailUrl, headers = mapOf("User-Agent" to USER_AGENT))
                    AppUtils.tryParseJson<ShadowVodDetail>(res.text)
                } catch (e: Exception) {
                    null
                }
            } else null

            val title = detail?.dizi_adi ?: dizi.title ?: "Dizi"
            val episodes = mutableListOf<Episode>()
            val numberRegex = Regex("\\d+")

            detail?.dizi_bolumler?.forEachIndexed { index, bolum ->
                val bUrl = bolum.bolum_url ?: bolum.bolum ?: return@forEachIndexed
                val bName = bolum.bolum_adi ?: bolum.isim ?: "Bölüm ${index + 1}"
                val seasonNum = bolum.sezon?.toIntOrNull() ?: numberRegex.find(bName)?.value?.toIntOrNull() ?: 1
                val epNum = bolum.bolum?.toIntOrNull() ?: (index + 1)

                val epPass = ShadowPassData(type = "vod_ep", directUrl = bUrl)
                episodes.add(newEpisode(mapper.writeValueAsString(epPass)) {
                    this.name = bName
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = detail.dizi_resim ?: dizi.image
                })
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = detail?.dizi_resim ?: dizi.image
                this.plot = detail?.dizi_aciklama
                this.year = dizi.year?.toIntOrNull()
            }
        }


        return null
    }

    // ---- Stream Links Resolution ----
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val passData = AppUtils.tryParseJson<ShadowPassData>(data)

        // Case A: Sub-Playlist Item (e.g. specific live match)
        if (passData?.subItem != null) {
            val item = passData.subItem
            val rawStream = item.url ?: item.media_url ?: return false
            val headers = buildHeaders(
                userAgent = item.userAgent,
                h1K = item.h1Key, h1V = item.h1Val,
                h2K = item.h2Key, h2V = item.h2Val,
                h3K = item.h3Key, h3V = item.h3Val,
                h4K = item.h4Key, h4V = item.h4Val,
                h5K = item.h5Key, h5V = item.h5Val
            )
            return resolveAndEmitStream(item.title ?: this.name, rawStream, headers, subtitleCallback, callback)
        }

        // Case B: Direct Channel Item
        if (passData?.channel != null) {
            val ch = passData.channel
            val rawStream = ch.link ?: return false
            val headers = buildHeaders(
                userAgent = ch.userAgent,
                cookie = ch.cookie,
                h1K = ch.h1Key, h1V = ch.h1Val,
                h2K = ch.h2Key, h2V = ch.h2Val,
                h3K = ch.h3Key, h3V = ch.h3Val,
                h4K = ch.h4Key, h4V = ch.h4Val,
                h5K = ch.h5Key, h5V = ch.h5Val
            )
            return resolveAndEmitStream(ch.isim ?: this.name, rawStream, headers, subtitleCallback, callback)
        }

        // Case C: VOD Film Item
        if (passData?.vodItem != null) {
            val film = passData.vodItem
            val detailUrl = film.detailUrl ?: return false
            return try {
                val res = app.get(detailUrl, headers = mapOf("User-Agent" to USER_AGENT))
                val detail = AppUtils.tryParseJson<ShadowVodDetail>(res.text)
                val iframeUrl = detail?.iframe_url ?: return false

                val iframeRes = app.get(iframeUrl, headers = mapOf("User-Agent" to USER_AGENT))
                val streamResp = AppUtils.tryParseJson<ShadowStreamResponse>(iframeRes.text)
                val streamUrl = streamResp?.stream ?: iframeRes.text

                resolveAndEmitStream(film.title ?: this.name, streamUrl, emptyMap(), subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "Failed to resolve film stream: ${e.message}")
                false
            }
        }

        // Case D: VOD Episode
        if (passData?.type == "vod_ep" && !passData.directUrl.isNullOrEmpty()) {
            val epUrl = passData.directUrl
            return try {
                val res = app.get(epUrl, headers = mapOf("User-Agent" to USER_AGENT))
                val streamResp = AppUtils.tryParseJson<ShadowStreamResponse>(res.text)
                val streamUrl = streamResp?.stream ?: res.text
                resolveAndEmitStream(this.name, streamUrl, emptyMap(), subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "Failed to resolve episode stream: ${e.message}")
                false
            }
        }

        // Fallback: direct URL string
        if (data.startsWith("http://") || data.startsWith("https://") || data.startsWith("golge")) {
            return resolveAndEmitStream(this.name, data, emptyMap(), subtitleCallback, callback)
        }

        return false
    }

    private fun buildHeaders(
        userAgent: String?,
        cookie: String? = null,
        h1K: String? = null, h1V: String? = null,
        h2K: String? = null, h2V: String? = null,
        h3K: String? = null, h3V: String? = null,
        h4K: String? = null, h4V: String? = null,
        h5K: String? = null, h5V: String? = null
    ): Map<String, String> {
        val map = mutableMapOf<String, String>()
        map["User-Agent"] = if (!userAgent.isNullOrBlank()) userAgent else USER_AGENT

        if (!cookie.isNullOrBlank()) {
            map["Cookie"] = cookie
        }

        val pairs = listOf(h1K to h1V, h2K to h2V, h3K to h3V, h4K to h4V, h5K to h5V)
        for ((k, v) in pairs) {
            if (!k.isNullOrBlank() && k != "0" && !v.isNullOrBlank() && v != "0") {
                map[k] = v
            }
        }
        return map
    }

    private fun extractCloseloadStream(html: String): String? {
        return try {
            val fileVarMatcher = Pattern.compile("""sources:\s*\[\{file:\s*([a-zA-Z0-9_]+)""").matcher(html)
            if (!fileVarMatcher.find()) return null
            val targetVar = fileVarMatcher.group(1)

            val callMatcher = Pattern.compile("""var\s+${targetVar}\s*=\s*([a-zA-Z0-9_]+)\s*\(\s*(\[[^\]]+\])\s*\);""").matcher(html)
            if (!callMatcher.find()) return null
            val funcName = callMatcher.group(1)
            val arrArg = callMatcher.group(2)

            val fnMatcher = Pattern.compile("""function\s+${funcName}\s*\([^\)]*\)\s*\{[\s\S]*?return\s+[a-zA-Z0-9_]+;\s*\}""").matcher(html)
            if (!fnMatcher.find()) return null
            val fnBody = fnMatcher.group(0)

            val atobPoly = """
                function atob(s) {
                    var b = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    var o = '', c1, c2, c3, e1, e2, e3, e4, i = 0;
                    s = s.replace(/[^A-Za-z0-9+/=]/g, '');
                    while (i < s.length) {
                        e1 = b.indexOf(s.charAt(i++));
                        e2 = b.indexOf(s.charAt(i++));
                        e3 = b.indexOf(s.charAt(i++));
                        e4 = b.indexOf(s.charAt(i++));
                        c1 = (e1 << 2) | (e2 >> 4);
                        c2 = ((e2 & 15) << 4) | (e3 >> 2);
                        c3 = ((e3 & 3) << 6) | e4;
                        o += String.fromCharCode(c1);
                        if (e3 != 64) o += String.fromCharCode(c2);
                        if (e4 != 64) o += String.fromCharCode(c3);
                    }
                    return o;
                }
            """.trimIndent()

            val jsCode = "$atobPoly\n$fnBody\n$funcName($arrArg);"

            val rhino = Context.enter()
            rhino.optimizationLevel = -1
            val scope: Scriptable = rhino.initStandardObjects()
            val result = rhino.evaluateString(scope, jsCode, "closeload", 1, null)?.toString()
            Context.exit()

            if (result?.startsWith("http://") == true || result?.startsWith("https://") == true) {
                result
            } else null
        } catch (e: Exception) {
            Log.e(name, "Error extracting closeload stream: ${e.message}")
            try { Context.exit() } catch (_: Exception) {}
            null
        }
    }

    private suspend fun extractPlayerjsStream(
        targetUrl: String,
        referer: String,
        sourceTitle: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val embedHost = Uri.parse(targetUrl).host ?: return false
            val embedHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to if (referer.isNotEmpty()) referer else "https://www.dizipal.bid/"
            )
            val embedHtml = app.get(targetUrl, headers = embedHeaders).text

            val subMatcher = Pattern.compile(""""subtitle"\s*:\s*"([^"]+)"""").matcher(embedHtml)
            if (subMatcher.find()) {
                val subRaw = subMatcher.group(1)
                subRaw?.split(",")?.forEach { item ->
                    val trimmed = item.trim()
                    val lang = if (trimmed.startsWith("[")) trimmed.substringAfter("[").substringBefore("]") else "Türkçe"
                    val sUrl = if (trimmed.startsWith("[")) trimmed.substringAfter("]") else trimmed
                    if (sUrl.startsWith("http")) {
                        subtitleCallback.invoke(newSubtitleFile(lang, sUrl))
                    }
                }
            }

            val dlMatcher = Pattern.compile("""(/dl\?op=[^'"]+)""").matcher(embedHtml)
            if (dlMatcher.find()) {
                val dlPath = dlMatcher.group(1)
                val dlUrl = "https://$embedHost$dlPath"
                val dlHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to targetUrl,
                    "Origin" to "https://$embedHost",
                    "X-Requested-With" to "XMLHttpRequest"
                )
                val dlResp = app.get(dlUrl, headers = dlHeaders).text
                val dlJson = AppUtils.tryParseJson<Map<String, Any>>(dlResp)
                val streamUrl = (dlJson?.get("url") as? String)?.trim()

                if (!streamUrl.isNullOrEmpty()) {
                    val streamHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://$embedHost/"
                    )
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = sourceTitle,
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.headers = streamHeaders
                            this.referer = "https://$embedHost/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(name, "Error in extractPlayerjsStream: ${e.message}")
            false
        }
    }

    private suspend fun resolveAndEmitStream(
        sourceTitle: String,
        rawUrl: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var url = rawUrl.trim()

        // 1. Resolve `golge2://` (tvmarkaj / live stream obfuscation)
        if (url.startsWith("golge2://")) {
            try {
                var clean = url.substring(9)
                if (clean.startsWith("https//")) clean = clean.replaceFirst("https//", "https://")
                else if (clean.startsWith("http//")) clean = clean.replaceFirst("http//", "http://")

                val split = clean.split("%7C", "|")
                val requestUrl = split[0].trim()
                val pathyeni = if (split.size > 1 && split[1].isNotBlank()) split[1].trim() else "bc2b05d321cb80050c5d035a9daeb26d"
                val subdomain = if (split.size > 2 && split[2].isNotBlank()) split[2].trim() else "a"
                val host = if (split.size > 3 && split[3].isNotBlank()) split[3].trim() else null

                val reqHeaders = headers.toMutableMap()
                if (!reqHeaders.containsKey("User-Agent")) reqHeaders["User-Agent"] = USER_AGENT

                val resp = app.get(requestUrl, headers = reqHeaders).text
                val arr = AppUtils.tryParseJson<List<String>>(resp)
                if (arr != null && arr.size >= 6) {
                    val s0 = arr[0]
                    val s5 = arr[5]
                    val matcher = Pattern.compile("atob\\(\"([^\"]+)\"").matcher(s0)
                    if (matcher.find()) {
                        val decodedDomain = String(Base64.decode(matcher.group(1), Base64.DEFAULT), StandardCharsets.UTF_8)
                        val idParam = if (requestUrl.contains("id=")) requestUrl.substringAfter("id=").substringBefore("&") else ""
                        val finalHost = host ?: "$subdomain$decodedDomain"
                        val finalStreamUrl = "https://$finalHost/$pathyeni/-/$idParam/playlist.m3u8$s5"

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = sourceTitle,
                                url = finalStreamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.headers = reqHeaders
                                this.referer = reqHeaders["referer"] ?: "https://izlemac529.sbs/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(name, "Failed to resolve golge2 stream: ${e.message}")
            }
        }

        // 2. Resolve `golge1://` and `golge11://` (pipe separated URL|Referer|Origin|Subtitle)
        if (url.startsWith("golge1://") || url.startsWith("golge11://")) {
            val clean = url.substringAfter("://")
            val decoded = try {
                URLDecoder.decode(clean, "UTF-8")
            } catch (_: Exception) {
                clean
            }
            val parts = decoded.split("|")
            val targetUrl = parts.getOrNull(0)?.trim() ?: ""
            val referer = parts.getOrNull(1)?.trim() ?: ""
            val origin = parts.getOrNull(2)?.trim() ?: ""

            for (p in parts) {
                if (p.contains(".vtt", ignoreCase = true) || p.contains(".srt", ignoreCase = true)) {
                    val vttUrl = if (p.contains("idx=")) p.substringAfter("idx=").substringBefore("&") else p
                    if (vttUrl.startsWith("http")) {
                        subtitleCallback.invoke(newSubtitleFile("Türkçe", vttUrl.trim()))
                    }
                }
            }

            if (targetUrl.isNotEmpty()) {
                val reqHeaders = headers.toMutableMap()
                if (referer.isNotEmpty()) reqHeaders["Referer"] = referer
                if (origin.isNotEmpty()) reqHeaders["Origin"] = origin

                if (targetUrl.contains(".m3u8", ignoreCase = true) || targetUrl.contains(".mp4", ignoreCase = true)) {
                    val linkType = if (targetUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = sourceTitle,
                            url = targetUrl,
                            type = linkType
                        ) {
                            this.headers = reqHeaders
                            this.referer = referer
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return true
                }

                // A. Closeload resolver
                if (targetUrl.contains("closeload", ignoreCase = true)) {
                    val embedHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to if (referer.isNotEmpty()) referer else "https://filmmakinesi.to/"
                    )
                    try {
                        val embedHtml = app.get(targetUrl, headers = embedHeaders).text
                        val tracksMatcher = Pattern.compile("""tracks:\s*(\[[^\]]+\])""").matcher(embedHtml)
                        if (tracksMatcher.find()) {
                            val tracksJson = tracksMatcher.group(1)
                            val arr = AppUtils.tryParseJson<List<Map<String, Any>>>(tracksJson)
                            arr?.forEach { track ->
                                val f = track["file"]?.toString()
                                val label = track["label"]?.toString() ?: "Türkçe"
                                if (!f.isNullOrEmpty() && f.startsWith("http")) {
                                    subtitleCallback.invoke(newSubtitleFile(label, f))
                                }
                            }
                        }

                        val streamUrl = extractCloseloadStream(embedHtml)
                        if (!streamUrl.isNullOrEmpty()) {
                            val streamHeaders = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://closeload.filmmakinesi.to/"
                            )
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = sourceTitle,
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.headers = streamHeaders
                                    this.referer = "https://closeload.filmmakinesi.to/"
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            return true
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Error resolving closeload: ${e.message}")
                    }
                }

                // B. Ag2m4 / Playerjs dl stream resolver
                if (targetUrl.contains("ag2m4", ignoreCase = true) || targetUrl.contains("/embed-", ignoreCase = true)) {
                    val resolved = extractPlayerjsStream(targetUrl, referer, sourceTitle, subtitleCallback, callback)
                    if (resolved) return true
                }

                // C. Fallback to generic extractor
                return loadExtractor(targetUrl, referer, subtitleCallback, callback)
            }
        }

        // 3. Resolve `golge5://` (embed URL)
        if (url.startsWith("golge5://")) {
            url = url.substring(9)
        }

        // 4. If direct embed url passed (closeload or ag2m4)
        if (url.contains("closeload", ignoreCase = true)) {
            try {
                val embedHtml = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://filmmakinesi.to/")).text
                val streamUrl = extractCloseloadStream(embedHtml)
                if (!streamUrl.isNullOrEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = sourceTitle,
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://closeload.filmmakinesi.to/")
                            this.referer = "https://closeload.filmmakinesi.to/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return true
                }
            } catch (e: Exception) {
                Log.e(name, "Error in direct closeload resolve: ${e.message}")
            }
        }
        if (url.contains("ag2m4", ignoreCase = true) || url.contains("/embed-", ignoreCase = true)) {
            val resolved = extractPlayerjsStream(url, headers["Referer"] ?: "", sourceTitle, subtitleCallback, callback)
            if (resolved) return true
        }

        // 5. Standard Direct HLS or MP4 Stream
        if (url.startsWith("http://") || url.startsWith("https://")) {
            val linkType = if (url.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else if (url.contains(".mp4", ignoreCase = true)) {
                ExtractorLinkType.VIDEO
            } else {
                INFER_TYPE
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = sourceTitle,
                    url = url,
                    type = linkType
                ) {
                    this.headers = headers
                    this.referer = headers["Referer"] ?: headers["referer"] ?: "https://twitter.com/"
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        return false
    }
}
