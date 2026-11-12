package com.xiao.feng.features.items.chat

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * QQ 音乐搜索与播放地址解析。移植自 Hchat 的 QQMusicClient。
 */
class QqMusicClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun search(keyword: String): QqMusicSearchResult {
        val detail = searchDetail(keyword) ?: return QqMusicSearchResult.NotFound
        val albumMid = detail.optJSONObject("album")?.optString("pmid").orEmpty()
        val mediaMid = detail.optJSONObject("file")?.optString("media_mid").orEmpty()
        val mid = detail.optString("mid")
        if (mid.isBlank()) return QqMusicSearchResult.NotFound
        val title = detail.optString("name").ifBlank { keyword }
        val singer = detail.optJSONArray("singer")
            ?.optJSONObject(0)
            ?.optString("name")
            .orEmpty()
        val songId = detail.optLong("id", 0L)
        val playUrl = requestPlayUrl(songId, mediaMid, mid) ?: return QqMusicSearchResult.Unavailable
        return QqMusicSearchResult.Success(
            QqMusicTrack(
                title = title,
                singer = singer,
                mid = mid,
                playUrl = playUrl,
                coverUrl = albumMid.takeIf { it.isNotBlank() }
                    ?.let { "https://y.gtimg.cn/music/photo_new/T002R500x500M000$it.jpg" }
                    .orEmpty(),
                landingUrl = "https://y.qq.com/n/ryqq/songDetail/$mid",
            )
        )
    }

    private fun searchDetail(keyword: String): JSONObject? {
        val headers = qqHeaders()
        runCatching {
            val request = JSONObject().apply {
                put("comm", JSONObject().put("ct", "19").put("cv", "1882"))
                put("req", JSONObject().apply {
                    put("method", "DoSearchForQQMusicDesktop")
                    put("module", "music.search.SearchCgiService")
                    put("param", JSONObject().apply {
                        put("num_per_page", 10)
                        put("page_num", 1)
                        put("query", keyword)
                        put("search_type", 0)
                    })
                })
            }
            val encoded = URLEncoder.encode(request.toString(), Charsets.UTF_8.name())
            parseSearchSong(get("$MUSICU_URL?data=$encoded", headers), keyword)
        }.getOrNull()?.let { return it }
        return searchBySmartbox(keyword, headers)
    }

    private fun parseSearchSong(response: String?, keyword: String): JSONObject? {
        if (response.isNullOrBlank()) return null
        val root = JSONObject(response)
        val search = root.optJSONObject("req") ?: root.optJSONObject("searchMusic") ?: return null
        val songs = search.optJSONObject("data")
            ?.optJSONObject("body")
            ?.optJSONObject("song")
            ?.optJSONArray("list")
            ?: return null
        val candidates = (0 until songs.length()).mapNotNull { songs.optJSONObject(it) }
        val index = QqMusicSongMatcher.selectIndex(keyword, candidates.map { it.songTitle() })
        return candidates.getOrNull(index)
    }

    private fun searchBySmartbox(keyword: String, headers: Map<String, String>): JSONObject? {
        return runCatching {
            val encoded = URLEncoder.encode(keyword, Charsets.UTF_8.name())
            val response = get(
                "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg" +
                        "?format=json&inCharset=utf8&outCharset=utf-8&key=$encoded",
                headers,
            ) ?: return@runCatching null
            val songs = JSONObject(response)
                .optJSONObject("data")
                ?.optJSONObject("song")
                ?.optJSONArray("itemlist")
                ?: return@runCatching null
            val candidates = (0 until songs.length()).mapNotNull { songs.optJSONObject(it) }
            val index = QqMusicSongMatcher.selectIndex(keyword, candidates.map { it.songTitle() })
            val mid = candidates.getOrNull(index)?.optString("mid").orEmpty()
            if (mid.isBlank()) return@runCatching null
            val detailRequest = JSONObject().apply {
                put("comm", JSONObject().put("ct", "19").put("cv", "1882"))
                put("req", JSONObject().apply {
                    put("module", "music.pf_song_detail_svr")
                    put("method", "get_song_detail")
                    put("param", JSONObject().put("song_mid", mid))
                })
            }
            val detailEncoded = URLEncoder.encode(detailRequest.toString(), Charsets.UTF_8.name())
            get("$MUSICU_URL?data=$detailEncoded", headers)
                ?.let { JSONObject(it) }
                ?.optJSONObject("req")
                ?.optJSONObject("data")
                ?.optJSONObject("track_info")
        }.getOrNull()
    }

    private fun JSONObject.songTitle(): String = sequenceOf("name", "title", "songname", "song_name")
        .map { optString(it).trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()

    private fun requestPlayUrl(songId: Long, mediaMid: String, songMid: String): String? {
        if (songId > 0L) {
            val obtainRequest = JSONObject().apply {
                put("comm", liteComm())
                put("request", JSONObject().apply {
                    put("module", "music.qqmusiclite.MtLimitFreeSvr")
                    put("method", "Obtain")
                    put("param", JSONObject().put("songid", JSONArray().put(songId)).put("need_ppurl", true))
                })
            }
            val ppurl = runCatching {
                post(MUSICU_URL, obtainRequest.toString())
                    ?.let { JSONObject(it) }
                    ?.optJSONObject("request")
                    ?.optJSONObject("data")
                    ?.optJSONArray("tracks")
                    ?.optJSONObject(0)
                    ?.optJSONObject("control")
                    ?.optString("ppurl")
                    .orEmpty()
            }.getOrDefault("")
            if (ppurl.isNotBlank()) {
                val tempRequest = JSONObject().put("request", JSONObject().apply {
                    put("module", "music.vkey.GetVkey")
                    put("method", "CgiGetTempVkey")
                    put("param", JSONObject().apply {
                        put("guid", "Yun")
                        put("songlist", JSONArray().put(JSONObject().apply {
                            put("mediamid", "Yun")
                            put("tempVkey", ppurl)
                            put("songMID", songMid)
                        }))
                    })
                })
                val url = runCatching {
                    post(MUSICU_URL, tempRequest.toString())
                        ?.let { JSONObject(it) }
                        ?.optJSONObject("request")
                        ?.optJSONObject("data")
                        ?.optJSONObject("data")
                        ?.optJSONObject("Yun")
                        ?.optString("purl")
                        .orEmpty()
                }.getOrDefault("")
                if (url.isNotBlank()) return url
            }
        }
        if (mediaMid.isBlank()) return null
        val request = JSONObject().apply {
            put("comm", liteComm())
            put("request", JSONObject().apply {
                put("module", "music.vkey.GetVkey")
                put("method", "UrlGetVkey")
                put("param", JSONObject().apply {
                    put("guid", "Yun")
                    put("songmid", JSONArray().put(songMid))
                    put("filename", JSONArray().put("M500$mediaMid.mp3"))
                })
            })
        }
        val flowUrl = runCatching {
            post(MUSICU_URL, request.toString())
                ?.let { JSONObject(it) }
                ?.optJSONObject("request")
                ?.optJSONObject("data")
                ?.optJSONArray("midurlinfo")
                ?.optJSONObject(0)
                ?.optString("flowurl")
                .orEmpty()
        }.getOrDefault("")
        return flowUrl.takeIf { it.isNotBlank() }?.let { "https://sjy.stream.qqmusic.qq.com/$it" }
    }

    private fun liteComm(): JSONObject = JSONObject().apply {
        put("ct", "11")
        put("cv", "22060004")
        put("tmeAppID", "ztelite")
        put("OpenUDID", "nouid")
        put("uid", "3449496653")
    }

    private fun qqHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36",
        "Referer" to "https://y.qq.com/",
        "Origin" to "https://y.qq.com",
        "Accept" to "application/json, text/plain, */*",
    )

    private fun get(url: String, headers: Map<String, String>): String? {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        return execute(builder.build())
    }

    private fun post(url: String, body: String): String? {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request)
    }

    private fun execute(request: Request): String? {
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.string()
        }
    }

    companion object {
        private const val MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

data class QqMusicTrack(
    val title: String,
    val singer: String,
    val mid: String,
    val playUrl: String,
    val coverUrl: String,
    val landingUrl: String,
)

sealed interface QqMusicSearchResult {
    data class Success(val track: QqMusicTrack) : QqMusicSearchResult
    data object NotFound : QqMusicSearchResult
    data object Unavailable : QqMusicSearchResult
}

/** Selects a QQ Music result while keeping QQ's order for equally ranked matches. */
object QqMusicSongMatcher {
    fun selectIndex(query: String, titles: List<String>): Int {
        if (titles.isEmpty()) return -1
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return 0

        return titles.withIndex()
            .mapNotNull { (index, title) -> rank(index, normalizedQuery, title) }
            .minWithOrNull(compareBy<Ranked> { it.tier }.thenByDescending { it.similarity }.thenBy { it.index })
            ?.index ?: 0
    }

    private fun rank(index: Int, query: String, rawTitle: String): Ranked? {
        val title = normalize(rawTitle)
        if (title.isEmpty()) return null
        val baseTitle = normalize(stripVersionSuffix(rawTitle))
        val tier = when {
            title == query -> 0
            baseTitle == query -> 1
            title.startsWith(query) -> 2
            title.contains(query) -> 3
            else -> {
                val similarity = similarity(query, title)
                if (minOf(query.length, title.length) < MIN_FUZZY_LENGTH ||
                    similarity < MIN_FUZZY_SIMILARITY
                ) {
                    return null
                }
                return Ranked(index, 4, similarity)
            }
        }
        return Ranked(index, tier, 1.0)
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .filterNot(Char::isWhitespace)
        .filterNot { it in NORMALIZATION_PUNCTUATION }

    private fun stripVersionSuffix(value: String): String {
        var result = value.trim()
        while (true) {
            val match = VERSION_SUFFIX.find(result) ?: break
            result = result.substring(0, match.range.first).trimEnd()
        }
        return result
    }

    private fun similarity(left: String, right: String): Double {
        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)
        for (leftIndex in left.indices) {
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                val substitution = if (left[leftIndex] == right[rightIndex]) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1],
                    previous[rightIndex] + substitution,
                )
            }
            current.copyInto(previous)
        }
        return 1.0 - previous[right.length].toDouble() / maxOf(left.length, right.length)
    }

    private data class Ranked(val index: Int, val tier: Int, val similarity: Double)

    private const val MIN_FUZZY_LENGTH = 5
    private const val MIN_FUZZY_SIMILARITY = 0.82
    private const val NORMALIZATION_PUNCTUATION = " \t\n\r　·・,，.。!！?？:：;；'\"“”‘’-—_()（）[]【】"
    private val VERSION_SUFFIX = Regex(
        "(?:\\s*[-—_]?\\s*(?:[（(](?:Live(?:\\s*版)?|现场版?|演唱会版|翻唱|Cover|伴奏|Instrumental|" +
                "Remix(?:\\s*版)?|混音版?|DJ版|纯音乐版|合唱版)[）)]|\\[(?:Live|现场|翻唱|伴奏|Remix|Cover)]|" +
                "【(?:Live|现场|翻唱|伴奏|Remix|Cover)】))$|(?:\\s+[-—_]?\\s*)(?:Live(?:\\s*版)?|现场版?|" +
                "演唱会版|翻唱|Cover|伴奏|Instrumental|Remix(?:\\s*版)?|混音版?|DJ版|纯音乐版|合唱版)$",
        RegexOption.IGNORE_CASE,
    )
}
