package com.megix

import android.util.Log
import com.lagradost.cloudstream3.app
import org.json.JSONObject

// ── 1. Static APIs ──────────────────────────────────────────
const val malsyncAPI = "https://api.malsync.moe"
const val tokyoInsiderAPI = "https://www.tokyoinsider.com"
const val WYZIESubsAPI = "https://sub.wyzie.io"
const val MostraguardaAPI = "https://mostraguarda.stream"
const val CC_COOKIE = BuildConfig.CC_COOKIE
const val CASTLE_KEY = BuildConfig.CASTLE_KEY
const val MOVIEBLAST_TOKEN = BuildConfig.MOVIEBLAST_TOKEN
const val MOVIEBLAST_API = BuildConfig.MOVIEBLAST_API
const val MOVIEBLAST_KEY = BuildConfig.MOVIEBLAST_KEY
const val NETMIRROR_TOKEN = BuildConfig.NETMIRROR_TOKEN
const val animepaheAPI = "https://animepahe.pw"
const val allmovielandAPI = "https://allmovieland.one"
const val anizoneAPI = "https://anizone.to"
const val PrimeSrcApi = "https://primesrc.me"
const val asiaflixAPI = "https://asiaflix.net"
const val dahmerMoviesAPI = "https://a.111477.xyz"
const val hexaAPI = "https://theemoviedb.hexa.su"
const val videasyAPI = "https://api.speedracelight.com"
const val vidlinkAPI = "https://vidlink.pro"
const val multiDecryptAPI = "https://enc-dec.app/api"
const val animetoshoAPI = "https://feed.animetosho.xyz"
const val animetoshoBaseAPI = "https://animetosho.xyz"
const val anizipAPI = "https://api.ani.zip"
const val vidzeeApi = "https://player.vidzee.wtf"
const val kissKhAPI = "https://kisskh.nl"
const val vidupAPI = "https://vidup.to"
const val bollywoodAPI = "https://tga-hd.api.hashhackers.com"
const val bollywoodBaseAPI = "https://bollywood.eu.org"
const val xpassAPI = "https://play.xpass.top"
const val cinemacityAPI = "https://cinemacity.cc"
const val akwamAPI = "https://akwam.it"
const val levidiaAPI = "https://www.levidia.ch"
const val showboxAPI = "https://showbox.media"
const val febboxAPI  = "https://www.febbox.com"
const val anikotoAPI = "https://anikototv.to"
const val projectfreetvAPI = "https://projectfreetv.sx"
const val ctgMoviesBaseAPI = "https://ctgmovies.com"
const val vidrockAPI = "https://vidrock.ru"
const val animekizzAPI = "https://animekizz.live"
const val vidfastProApi = "https://vidfast.vc"
const val onetouchtvAPI = "https://api3.devcorp.me"
const val av1encodesAPI = "https://av1encodes.com"
const val peachifyBaseAPI = "https://peachify.top"
const val reanimeAPI = "https://reanime.to"
const val animesaltAPI = "https://animesalt.ac"
const val anidbAPI = "https://anidb.app"
const val vaPlayerAPI = "https://streamdata.vaplayer.ru"
const val fshareAPI = "https://fsharetv.cc"
const val castleAPI = "https://api.hlowb.com"
const val vidcoreAPI = "https://vidcore.io"
const val anikageAPI = "https://anikage.cc"
const val hdGharTvAPI = "https://hdghartv.cc"
const val aninekoAPI = "https://anineko.to"
const val torrentioAPI = "https://torrentio.strem.fun/limit=4"
const val torrentsdbAPI = "https://torrentsdb.com/eyJsaW1pdCI6IjMiLCJkZWJyaWRvcHRpb25zIjpbIm5vZG93bmxvYWRsaW5rcyJdfQ=="

// ── 2. Dynamic API Config ────────────────────────────────────
// Loaded once via init() called from CineStream.load()
private val DEFAULT_APIS = mapOf(
    "4khdhub" to "https://4khdhub.one",
    "bollyflix" to "https://bollyflix.free",
    "hdmovie2" to "https://hdmovie2a.cfd",
    "rtally" to "https://rtally.link",
    "hindmoviez" to "https://hindmovie.fit",
    "moviesdrive" to "https://new3.moviesdrive.christmas",
    "movies4u" to "https://new4.movies4u.clinic",
    "multimovies" to "https://multimovies.makeup",
    "nfmirror" to "https://tv.imgcdn.kim/newtv",
    "skymovies" to "https://skymovieshd.forex",
    "uhdmovies" to "https://uhdmovies.autos",
    "moviesmod" to "https://moviesmod.zone",
    "topmovies" to "https://moviesleech.art",
    "vegamovies" to "https://new2.vegamovies.futbol",
    "rogmovies" to "https://new2.rogmovies.click",
    "gdflix" to "https://new3.gdflix.io",
    "hubcloud" to "https://hubcloud.cx",
    "toonstream" to "https://toon-stream.site",
    "zinkmovies" to "https://zinkmovies.org",
    "vcloud" to "https://vcloud.fit",
    "dudefilms" to "https://dudefilms.garden",
    "m4ufree" to "https://ww4.m4ufree.lat",
    "animedao" to "https://anidao.to",
    "mlsbd" to "https://mlsbd.co",
    "fibwatch" to "https://fibwatch.art"
)

private var _apiConfig: JSONObject? = null

suspend fun init() {
    if (_apiConfig != null) return
    val urls = listOf(
        "https://raw.githubusercontent.com/Dr-Octagon/cloudstream-turkish/master/urls.json",
        "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"
    )
    for (url in urls) {
        val cfg = runCatching {
            JSONObject(app.get(url).text)
        }.getOrNull()
        if (cfg != null && cfg.length() > 0) {
            _apiConfig = cfg
            return
        }
    }
    _apiConfig = JSONObject()
}

private fun api(key: String): String {
    val dynamicUrl = _apiConfig?.optString(key)?.takeIf { it.isNotBlank() }
    return dynamicUrl ?: DEFAULT_APIS[key].orEmpty()
}

// ── 3. Dynamic APIs ──────────────────────────────────────────
val fourkhdhubAPI get() = api("4khdhub")
val multimoviesAPI get() = api("multimovies")
val bollyflixAPI get() = api("bollyflix")
val movies4uAPI get() = api("movies4u")
val skymoviesAPI get() = api("skymovies")
val hindMoviezAPI get() = api("hindmoviez")
val hdmovie2API get() = api("hdmovie2")
val nfmirrorAPI get() = api("nfmirror")
val moviesdriveAPI get() = api("moviesdrive")
val vegamoviesAPI get() = api("vegamovies")
val rogmoviesAPI get() = api("rogmovies")
val uhdmoviesAPI get() = api("uhdmovies")
val moviesmodAPI get() = api("moviesmod")
val topmoviesAPI get() = api("topmovies")
val toonStreamAPI get() = api("toonstream")
val rtallyAPI get() = api("rtally")
val dudefilmsAPI get() = api("dudefilms")
val m4ufreeAPI get() = api("m4ufree")
val zinkmoviesAPI get() = api("zinkmovies")
val animedaoAPI get() = api("animedao")
val mlsbdAPI get() = api("mlsbd")
val fibwatchBaseUrl get() = api("fibwatch")
