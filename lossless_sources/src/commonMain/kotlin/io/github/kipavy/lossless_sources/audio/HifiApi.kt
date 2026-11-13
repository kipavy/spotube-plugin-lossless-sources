package io.github.kipavy.lossless_sources.audio

import io.github.kipavy.lossless_sources.core.Net
import io.github.kipavy.lossless_sources.core.arr
import io.github.kipavy.lossless_sources.core.encode
import io.github.kipavy.lossless_sources.core.get
import io.github.kipavy.lossless_sources.core.id
import io.github.kipavy.lossless_sources.core.num
import io.github.kipavy.lossless_sources.core.str
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Tidal through any hifi-api compatible instance.
 *
 * Instances are interchangeable and each proxies Tidal with its own account, so
 * a blocked one only costs a fallback to the next. Catalogue reads need no
 * account, which is why an instance can answer searches perfectly while serving
 * no audio at all -- only /track/ proves it works.
 *
 * - `GET /search/?s=&limit=` -> `{ data: { items: [{ id, title, duration (s), isrc, artist, artists, album }] } }`
 * - `GET /track/?id=&quality=` -> `{ data: { manifestMimeType, manifest (base64), sampleRate, bitDepth } }`
 */
class HifiApi(
    private val net: Net,
    private val retryDelaysMs: List<Long> = listOf(2_000, 4_000, 8_000),
) : LosslessSource {
    override val type = "hifi-api"

    override suspend fun search(query: String, bases: List<String>): List<Match> {
        for (base in bases) {
            val items = net.getJson("$base/search/?s=${encode(query)}&limit=20")["data"]["items"].arr()
                ?: continue
            val found = items.mapNotNull { item ->
                val id = item["id"].id() ?: return@mapNotNull null
                val album = item["album"]
                Match(
                    source = type,
                    ref = id,
                    title = item["title"].str().orEmpty(),
                    artists = creditsOf(item),
                    album = album["title"].str(),
                    // Seconds here, milliseconds everywhere else.
                    durationMs = (item["duration"].num() ?: 0L) * 1000,
                    isrc = item["isrc"].str(),
                    artwork = coverUrl(album["cover"].str()),
                    externalUri = "https://tidal.com/browse/track/$id",
                )
            }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /**
     * Walks the instances, asking each for every quality, and keeps the first
     * instance that serves anything. An instance on a lower Tidal tier silently
     * answers with the best it has, so asking for Hi-Res first costs nothing,
     * and the same URL coming back for several qualities is deduplicated.
     */
    override suspend fun streams(ref: String, durationMs: Long, bases: List<String>): List<AudioStream> {
        for (base in bases) {
            val found = mutableListOf<AudioStream>()
            for (quality in QUALITIES) {
                val stream = fetch(base, ref, quality) ?: continue
                if (found.none { it.url == stream.url }) found.add(stream)
            }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /**
     * One quality from one instance. 202 means every playback account on that
     * instance is busy right now, which is worth waiting out -- unlike 403 or
     * 503, where the account is blocked and retrying only wastes time.
     */
    private suspend fun fetch(base: String, ref: String, quality: String): AudioStream? {
        var attempt = 0
        while (true) {
            val res = net.get("$base/track/?id=${encode(ref)}&quality=$quality") ?: return null
            if (res.statusCode == 202 && attempt < retryDelaysMs.size) {
                delay(retryDelaysMs[attempt])
                attempt++
                continue
            }
            if (res.statusCode != 200) return null
            val payload = net.parse(res.body) ?: return null
            if (payload["detail"] != null) return null
            return decodeStream(payload["data"])
        }
    }

    companion object {
        val QUALITIES = listOf("HI_RES_LOSSLESS", "LOSSLESS", "HIGH")

        /** Tidal cover fields are bare UUIDs rather than URLs. */
        fun coverUrl(cover: String?): String? {
            if (cover.isNullOrBlank()) return null
            return "https://resources.tidal.com/images/${cover.replace('-', '/')}/640x640.jpg"
        }

        /** Every artist a search result credits, main artist first. */
        fun creditsOf(item: JsonElement): List<String> {
            val names = mutableListOf<String>()
            item["artist"]["name"].str()?.let { names.add(it) }
            item["artists"].arr()?.forEach { artist ->
                val name = artist["name"].str()
                if (!name.isNullOrBlank() && name !in names) names.add(name)
            }
            return names
        }

        /**
         * hifi-api hands back a base64 manifest instead of a URL. The BTS variant
         * decodes to JSON holding a direct CDN link; the Hi-Res variant is an
         * inline DASH manifest with no URL of its own to hand the player, so it
         * is skipped.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun decodeStream(payload: JsonElement?): AudioStream? {
            if (payload["manifestMimeType"].str() != "application/vnd.tidal.bts") return null
            val raw = payload["manifest"].str() ?: return null

            val text = try {
                Base64.decode(raw).decodeToString()
            } catch (error: Exception) {
                return null
            }
            val manifest = Net.parseLenient(text) ?: return null
            val url = manifest["urls"].arr()?.firstOrNull().str() ?: return null
            val codec = manifest["codecs"].str().orEmpty().lowercase()

            if (codec.contains("flac")) {
                return AudioStream.Lossless(
                    url = url,
                    codec = "flac",
                    container = "flac",
                    sampleRate = payload["sampleRate"].num()?.toInt() ?: 44_100,
                    channels = 2,
                )
            }
            // A lower-tier account returns AAC even when lossless is asked for.
            return AudioStream.Lossy(
                url = url,
                codec = "aac",
                container = "mp4",
                bitrate = 320_000,
            )
        }
    }
}
