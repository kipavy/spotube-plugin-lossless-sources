package io.github.kipavy.lossless_sources.audio

import io.github.kipavy.lossless_sources.core.Net
import io.github.kipavy.lossless_sources.core.arr
import io.github.kipavy.lossless_sources.core.bool
import io.github.kipavy.lossless_sources.core.encode
import io.github.kipavy.lossless_sources.core.get
import io.github.kipavy.lossless_sources.core.header
import io.github.kipavy.lossless_sources.core.id
import io.github.kipavy.lossless_sources.core.num
import io.github.kipavy.lossless_sources.core.str
import kotlinx.coroutines.delay
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioStream

/**
 * Monochrome's own catalogue: search plus direct FLAC files.
 *
 * Monochrome moved off hifi-api in April 2026 and serves the files from its own
 * servers, so it does not depend on a shared Tidal account that can be banned.
 * The stream is the file itself: no manifest to decode, no token to refresh,
 * and a URL that does not expire.
 *
 * - `GET /search/tracks?q=&limit=` -> `{ tracks: [{ id, title, artistNames, duration (ms), artwork, isrc, playable }] }`
 * - `GET /track/<id>` -> the FLAC file
 */
class Monochrome(
    private val net: Net,
    private val retryDelaysMs: List<Long> = listOf(1_000, 2_000, 4_000),
) : LosslessSource {
    override val type = "monochrome"

    override suspend fun search(query: String, bases: List<String>): List<Match> {
        for (base in bases) {
            val items = net.getJson("$base/search/tracks?q=${encode(query)}&limit=20")["tracks"].arr()
                ?: continue
            val found = items.mapNotNull { item ->
                // `playable: false` is a catalogue entry with no file behind it.
                if (item["playable"].bool() == false) return@mapNotNull null
                val id = item["id"].id() ?: return@mapNotNull null
                Match(
                    source = type,
                    ref = id,
                    title = item["title"].str().orEmpty(),
                    artists = item["artistNames"].arr()?.mapNotNull { it.str() }?.filter { it.isNotBlank() }.orEmpty(),
                    album = null,
                    // Already milliseconds, unlike hifi-api.
                    durationMs = item["duration"].num() ?: 0L,
                    isrc = item["isrc"].str(),
                    artwork = item["artwork"].str()?.takeIf { it.isNotBlank() },
                    externalUri = "$base/track/$id",
                )
            }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /**
     * Checks the file answers before handing it to the player, which streams the
     * URL itself: a transient 521 there would be a track that silently does not
     * play. A HEAD costs no audio. The host sits behind Cloudflare and a 5xx on
     * one request is routinely gone on the next, so 5xx is retried with backoff;
     * 4xx means this host does not have the file.
     */
    override suspend fun streams(ref: String, durationMs: Long, bases: List<String>): List<AudioStream> {
        for (base in bases) {
            val url = "$base/track/$ref"
            var attempt = 0
            while (true) {
                val res = net.head(url)
                val code = res?.statusCode ?: 0
                if (res != null && (code == 200 || code == 206)) {
                    val size = res.header("content-length")?.trim()?.toLongOrNull() ?: 0L
                    return listOf(flacStream(url, size, durationMs))
                }
                if ((code == 0 || code >= 500) && attempt < retryDelaysMs.size) {
                    delay(retryDelaysMs[attempt])
                    attempt++
                    continue
                }
                break
            }
        }
        return emptyList()
    }

    companion object {
        /** Uncompressed 16-bit/44.1 kHz stereo PCM. FLAC never averages above the PCM it encodes. */
        const val CD_PCM_BITRATE = 1_411_200L

        /** Uncompressed 24-bit/44.1 kHz stereo PCM: a file averaging above it cannot be 44.1 kHz. */
        const val HIRES_PCM_BITRATE = 2_116_800L

        /**
         * The host sends no quality fields, so the sample rate is read off the
         * file's real average bitrate, as the least resolution that bitrate
         * implies. That never overstates.
         */
        fun flacStream(url: String, sizeBytes: Long, durationMs: Long): AudioStream.Lossless {
            val bitrate = if (sizeBytes > 0 && durationMs > 0) sizeBytes * 8 * 1000 / durationMs else CD_PCM_BITRATE
            return AudioStream.Lossless(
                url = url,
                codec = "flac",
                container = "flac",
                sampleRate = if (bitrate > HIRES_PCM_BITRATE) 96_000 else 44_100,
                channels = 2,
            )
        }
    }
}
