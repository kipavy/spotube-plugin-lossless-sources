package io.github.kipavy.lossless_sources.audio

import io.github.kipavy.lossless_sources.core.Net
import io.github.kipavy.lossless_sources.core.arr
import io.github.kipavy.lossless_sources.core.get
import io.github.kipavy.lossless_sources.core.num
import io.github.kipavy.lossless_sources.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.spotube.plugin_interfaces.host_apis.HttpMethod
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioStream

/**
 * Lossy fallback for tracks no lossless source has, straight from YouTube's
 * InnerTube API through Spotube's HTTP client.
 *
 * Spotube 5 lent hetu plugins its YouTube engine; Spotube 6 lends Kotlin
 * plugins nothing of the kind, so the plugin talks to YouTube itself:
 *
 * - `POST music.youtube.com/youtubei/v1/search` as the YouTube Music web client,
 *   filtered to songs, so results are the official audio with proper credits
 *   rather than lyric videos and covers.
 * - `POST www.youtube.com/youtubei/v1/player` as a client YouTube still hands
 *   plain stream URLs to, with no signature to decipher and no PO token.
 *
 * YouTube changes which clients get plain URLs every few months, so [streams]
 * tries several and the first that serves a stream wins. When a client stops
 * working, this list is the one thing to update.
 */
class YouTube(
    private val net: Net,
    private val players: List<PlayerClient> = PLAYER_CLIENTS,
) : TrackSource {
    override val type = "youtube"

    override suspend fun search(query: String, bases: List<String>): List<Match> {
        val body = buildJsonObject {
            put("context", context(SEARCH_CLIENT.context))
            put("query", query)
            put("params", SONGS_FILTER)
        }
        val payload = net.postJson("$MUSIC/youtubei/v1/search?prettyPrint=false", body.toString(), SEARCH_CLIENT.headers())
            ?: return emptyList()
        return songsIn(payload).mapNotNull(::toMatch)
    }

    /**
     * Audio-only formats with a plain URL, best bitrate first. A client that
     * answers but whose URL the CDN refuses is skipped like one that answers
     * nothing: YouTube refusing a client shows up there first.
     */
    override suspend fun streams(ref: String, durationMs: Long, bases: List<String>): List<AudioStream> {
        for (client in players) {
            val body = buildJsonObject {
                put("context", context(client.context))
                put("videoId", ref)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }
            val payload = net.postJson("$WWW/youtubei/v1/player?prettyPrint=false", body.toString(), client.headers())
                ?: continue
            if (payload["playabilityStatus"]["status"].str() != "OK") continue

            val streams = audioStreams(payload)
            val best = streams.firstOrNull() ?: continue
            if (!answers(best.url)) continue
            return streams
        }
        return emptyList()
    }

    /** A one-byte range read: cheap, and it fails exactly when the player would. */
    private suspend fun answers(url: String): Boolean {
        val res = net.request(HttpMethod.Get, url, mapOf("Range" to "bytes=0-0")) ?: return false
        return res.statusCode == 200 || res.statusCode == 206
    }

    /** One InnerTube client: what it says it is in the request context, and in the headers. */
    class PlayerClient(
        val name: String,
        val id: String,
        val version: String,
        val userAgent: String,
        val context: Map<String, String>,
    ) {
        fun headers() = mapOf(
            "Content-Type" to "application/json",
            "User-Agent" to userAgent,
            "X-YouTube-Client-Name" to id,
            "X-YouTube-Client-Version" to version,
            "Origin" to if (name == "WEB_REMIX") MUSIC else WWW,
        )
    }

    companion object {
        const val MUSIC = "https://music.youtube.com"
        const val WWW = "https://www.youtube.com"

        /** YouTube Music's "Songs" search filter. */
        const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"

        private const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        val SEARCH_CLIENT = PlayerClient(
            name = "WEB_REMIX",
            id = "67",
            version = "1.20260213.01.00",
            userAgent = WEB_UA,
            context = mapOf("clientName" to "WEB_REMIX", "clientVersion" to "1.20260213.01.00"),
        )

        private fun vr(version: String, cronet: String) = PlayerClient(
            name = "ANDROID_VR",
            id = "28",
            version = version,
            userAgent = "com.google.android.apps.youtube.vr.oculus/$version (Linux; U; Android 12; en_US; " +
                "Quest 3; Build/SQ3A.220605.009.A1; Cronet/$cronet)",
            context = mapOf(
                "clientName" to "ANDROID_VR",
                "clientVersion" to version,
                "osName" to "Android",
                "osVersion" to "12",
                "deviceMake" to "Oculus",
                "deviceModel" to "Quest 3",
                "androidSdkVersion" to "32",
            ),
        )

        /**
         * Logged-out clients that get plain URLs, in the order yt-dlp and other
         * players currently rely on them. ANDROID_VR 1.43 serves non-adaptive
         * audio, which avoids stutter some players hit on adaptive formats.
         */
        val PLAYER_CLIENTS = listOf(
            vr("1.43.32", "107.0.5284.2"),
            vr("1.61.48", "132.0.6808.3"),
            PlayerClient(
                name = "IOS",
                id = "5",
                version = "21.03.1",
                userAgent = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)",
                context = mapOf(
                    "clientName" to "IOS",
                    "clientVersion" to "21.03.1",
                    "deviceMake" to "Apple",
                    "deviceModel" to "iPhone16,2",
                    "osName" to "iPhone",
                    "osVersion" to "18.2.22C152",
                ),
            ),
        )

        private fun context(client: Map<String, String>) = buildJsonObject {
            putJsonObject("client") {
                for ((key, value) in client) {
                    // androidSdkVersion is a number in every real client's request.
                    if (key == "androidSdkVersion") put(key, value.toInt()) else put(key, value)
                }
                put("hl", "en")
                put("gl", "US")
            }
        }

        /** Every list row of a search answer, whichever shelf it sits in. */
        fun songsIn(payload: JsonElement): List<JsonElement> {
            val found = mutableListOf<JsonElement>()
            fun walk(node: JsonElement?) {
                when (node) {
                    is JsonObject -> {
                        node["musicResponsiveListItemRenderer"]?.let { found.add(it) }
                        node.values.forEach(::walk)
                    }
                    is JsonArray -> node.forEach(::walk)
                    else -> {}
                }
            }
            walk(payload)
            return found
        }

        private fun runs(row: JsonElement, column: Int): List<JsonElement> =
            row["flexColumns"].arr()?.getOrNull(column)["musicResponsiveListItemFlexColumnRenderer"]["text"]["runs"]
                .arr().orEmpty()

        private fun pageType(run: JsonElement): String? =
            run["navigationEndpoint"]["browseEndpoint"]["browseEndpointContextSupportedConfigs"]["browseEndpointContextMusicConfig"]["pageType"].str()

        private const val SEPARATOR = " • "
        private val DURATION = Regex("""^\d{1,2}(:\d{2}){1,2}$""")

        /** "3:45" or "1:02:03" in milliseconds, null for anything else. */
        fun parseDuration(text: String?): Long? {
            val value = text?.trim() ?: return null
            if (!DURATION.matches(value)) return null
            return value.split(':').fold(0L) { total, part -> total * 60 + part.toLong() } * 1000
        }

        /**
         * A song row: title in the first column; artists, album and length in
         * the second, separated by " • ". Rows without a video id (artists,
         * albums, playlists) are not songs.
         */
        fun toMatch(row: JsonElement): Match? {
            val titleRun = runs(row, 0).firstOrNull()
            val videoId = row["playlistItemData"]["videoId"].str()
                ?: titleRun["navigationEndpoint"]["watchEndpoint"]["videoId"].str()
                ?: return null
            val title = titleRun["text"].str()?.takeIf { it.isNotBlank() } ?: return null

            val details = runs(row, 1)
            val sections = mutableListOf(mutableListOf<JsonElement>())
            for (run in details) {
                if (run["text"].str() == SEPARATOR) sections.add(mutableListOf()) else sections.last().add(run)
            }
            val linked = details.filter { pageType(it) == "MUSIC_PAGE_TYPE_ARTIST" }.mapNotNull { it["text"].str() }
            val artists = linked.ifEmpty {
                sections.first().mapNotNull { it["text"].str()?.trim() }
                    .filter { it.isNotEmpty() && it != "," && it != "&" }
            }
            val album = details.firstOrNull { pageType(it) == "MUSIC_PAGE_TYPE_ALBUM" }["text"].str()
            val duration = sections.lastOrNull()?.firstOrNull()?.get("text").str()

            return Match(
                source = "youtube",
                ref = videoId,
                title = title,
                artists = artists,
                album = album,
                durationMs = parseDuration(duration) ?: 0L,
                isrc = null,
                artwork = row["thumbnail"]["musicThumbnailRenderer"]["thumbnail"]["thumbnails"].arr()
                    ?.lastOrNull()["url"].str(),
                externalUri = "$MUSIC/watch?v=$videoId",
            )
        }

        /**
         * Audio-only formats that carry a plain URL, best first. A format with
         * only a `signatureCipher` needs YouTube's player JavaScript to decode,
         * which is exactly what the clients above avoid, so it is skipped.
         */
        fun audioStreams(payload: JsonElement): List<AudioStream.Lossy> {
            val formats = payload["streamingData"]["adaptiveFormats"].arr().orEmpty()
            return formats.mapNotNull { format ->
                val mime = format["mimeType"].str() ?: return@mapNotNull null
                if (!mime.startsWith("audio/")) return@mapNotNull null
                val url = format["url"].str() ?: return@mapNotNull null
                val bitrate = (format["averageBitrate"].num() ?: format["bitrate"].num() ?: return@mapNotNull null).toInt()
                val subtype = mime.substringAfter("audio/").substringBefore(';').trim()
                val codecs = mime.substringAfter("codecs=\"", "").substringBefore('"')
                when {
                    subtype == "webm" && codecs.startsWith("opus") ->
                        AudioStream.Lossy(url = url, codec = "opus", container = "webm", bitrate = bitrate)
                    subtype == "mp4" && codecs.startsWith("mp4a") ->
                        AudioStream.Lossy(url = url, codec = "aac", container = "mp4", bitrate = bitrate)
                    else -> null
                }
            }.sortedByDescending { it.bitrate }
        }
    }
}
