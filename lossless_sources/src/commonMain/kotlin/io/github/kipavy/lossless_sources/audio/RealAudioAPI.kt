package io.github.kipavy.lossless_sources.audio

import io.github.kipavy.lossless_sources.core.Net
import io.github.kipavy.lossless_sources.core.SourceStore
import org.spotube.plugin_interfaces.extras.logger.Logger
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioAPI
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioFormat
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioQuality
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioSource
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioStream
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.Thumbnail
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

/** Match ids are `<source type>:<reference>:<duration ms>`, so a match can go back to the source that found it. */
private const val ID_SEPARATOR = ":"

/** How many fitting matches of one source are tried for a stream before moving on to the next source. */
private const val STREAM_ATTEMPTS = 3

/** How many matches are handed to Spotube in all, including alternatives it can offer. */
private const val MAX_RESULTS = 5

/**
 * Routes a track to whichever lossless source can actually play it.
 *
 * Sources are tried in the order the published list gives them and the first
 * one that both has the recording and serves a stream for it wins, so the list
 * is also the preference order. A source that finds the track but cannot serve
 * it -- a Tidal proxy whose account was blocked answers searches fine -- does
 * not end the lookup; the next source is asked.
 */
class RealAudioAPI(
    net: Net,
    private val store: SourceStore,
    private val sources: List<LosslessSource> = listOf(Monochrome(net), HifiApi(net)),
) : AudioAPI {
    private val logger = Logger("LosslessSources")

    override val supportedQualities: List<AudioFormat> = listOf(
        AudioFormat(
            codec = "flac",
            container = "flac",
            qualities = listOf(
                AudioQuality.Lossless(sampleRate = 96_000, channels = 2),
                AudioQuality.Lossless(sampleRate = 44_100, channels = 2),
            ),
        ),
        // Only a hifi-api instance on a lower Tidal tier serves this.
        AudioFormat(
            codec = "aac",
            container = "mp4",
            qualities = listOf(AudioQuality.Lossy(bitrate = 320_000)),
        ),
    )

    private fun sourceFor(type: String): LosslessSource? = sources.firstOrNull { it.type == type }

    /**
     * Source types in published order, each with its hosts. A type this version
     * does not know is skipped rather than fatal, so a new source can be listed
     * before every installed plugin understands it.
     */
    private suspend fun groups(): List<Pair<LosslessSource, List<String>>> {
        val list = store.resolve()
        return list.map { it.type }.distinct().mapNotNull { type ->
            val source = sourceFor(type) ?: return@mapNotNull null
            source to list.filter { it.type == type }.map { it.base }
        }
    }

    override suspend fun getStreamsByTrack(track: MetadataTrack): List<AudioSource> {
        if (track.title.isBlank()) return emptyList()
        val queries = searchQueries(track)

        for ((source, bases) in groups()) {
            val found = firstFitting(source, bases, track, queries)
            if (found.isEmpty()) continue

            // Resolve the stream here rather than leaving it to Spotube, so a
            // source that has the track but cannot serve it hands over to the
            // next source instead of failing the track.
            for (match in found.take(STREAM_ATTEMPTS)) {
                val streams = safeStreams(source, match.ref, match.durationMs, bases)
                if (streams.isEmpty()) continue

                logger.i { "'${track.title}': playing ${source.type} ${match.ref} (${streams.size} stream(s))" }
                val alternatives = found.filter { it != match }.take(MAX_RESULTS - 1)
                return listOf(match.toStreamed(track, streams)) + alternatives.map { it.toBasic(track) }
            }
            logger.w { "'${track.title}': ${source.type} has it but served no stream" }
        }

        logger.w { "'${track.title}': no lossless source can play it" }
        return emptyList()
    }

    /** The fitting matches for the first query that has any, ISRC hits first. */
    private suspend fun firstFitting(
        source: LosslessSource,
        bases: List<String>,
        track: MetadataTrack,
        queries: List<String>,
    ): List<Match> {
        for (query in queries) {
            val results = try {
                source.search(query, bases)
            } catch (error: Exception) {
                if (error is kotlin.coroutines.cancellation.CancellationException) throw error
                logger.e({ "${source.type} search failed for \"$query\"" }, error)
                emptyList()
            }
            val kept = fitting(results, track)
            if (kept.isNotEmpty()) return kept
        }
        return emptyList()
    }

    private suspend fun safeStreams(
        source: LosslessSource,
        ref: String,
        durationMs: Long,
        bases: List<String>,
    ): List<AudioStream> = try {
        source.streams(ref, durationMs, bases)
    } catch (error: Exception) {
        if (error is kotlin.coroutines.cancellation.CancellationException) throw error
        logger.e({ "${source.type} streams failed for $ref" }, error)
        emptyList()
    }

    /**
     * Streams for a match Spotube saved earlier. Empty when the match's source
     * is gone or cannot serve it any more; Spotube then matches the track again,
     * which reaches whichever source works now.
     */
    override suspend fun getStreamsOfAudioSource(source: AudioSource.Basic): List<AudioSource.Streamed> {
        val parts = source.id.split(ID_SEPARATOR, limit = 3)
        if (parts.size < 2) return emptyList()
        val provider = sourceFor(parts[0]) ?: return emptyList()
        val ref = parts[1]
        val durationMs = parts.getOrNull(2)?.toLongOrNull() ?: 0L

        val streams = safeStreams(provider, ref, durationMs, store.basesFor(provider.type))
        if (streams.isEmpty()) {
            logger.w { "no stream for saved match ${source.id}" }
            return emptyList()
        }
        return listOf(
            AudioSource.Streamed(
                id = source.id,
                title = source.title,
                artist = source.artist,
                album = source.album,
                thumbnails = source.thumbnails,
                externalUri = source.externalUri,
                confidence = source.confidence,
                streams = streams,
            )
        )
    }

    private fun Match.id(): String = listOf(source, ref, durationMs.toString()).joinToString(ID_SEPARATOR)

    /** An ISRC hit is the recording itself; anything else was matched on credit, title and length. */
    private fun Match.confidence(track: MetadataTrack): Float {
        val isrc = track.isrcCode
        return if (!isrc.isNullOrBlank() && this.isrc?.uppercase() == isrc.uppercase()) 1.0f else 0.8f
    }

    private fun Match.thumbnails(): List<Thumbnail> =
        artwork?.let { listOf(Thumbnail(url = it, width = 640, height = 640)) }.orEmpty()

    private fun Match.toBasic(track: MetadataTrack) = AudioSource.Basic(
        id = id(),
        title = title,
        artist = artists.joinToString(", ").ifBlank { null },
        album = album,
        thumbnails = thumbnails(),
        externalUri = externalUri,
        confidence = confidence(track),
    )

    private fun Match.toStreamed(track: MetadataTrack, streams: List<AudioStream>) = AudioSource.Streamed(
        id = id(),
        title = title,
        artist = artists.joinToString(", ").ifBlank { null },
        album = album,
        thumbnails = thumbnails(),
        externalUri = externalUri,
        confidence = confidence(track),
        streams = streams,
    )
}
