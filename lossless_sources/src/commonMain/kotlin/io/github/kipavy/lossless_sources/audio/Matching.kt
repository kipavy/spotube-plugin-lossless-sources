package io.github.kipavy.lossless_sources.audio

import org.spotube.plugin_interfaces.plugin_apis.audio.AudioStream
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

/** One candidate recording found by a source. [ref] is what that source needs to fetch the file. */
data class Match(
    val source: String,
    val ref: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long,
    val isrc: String?,
    val artwork: String?,
    val externalUri: String?,
)

/** A lossless catalogue reachable through one or more interchangeable hosts. */
interface LosslessSource {
    /** The `type` this source has in sources.json, also the prefix of its match ids. */
    val type: String

    /** Search results for [query], best first. Empty when no host answered. */
    suspend fun search(query: String, bases: List<String>): List<Match>

    /** Playable streams for one match. Empty when no host can serve it right now. */
    suspend fun streams(ref: String, durationMs: Long, bases: List<String>): List<AudioStream>
}

/**
 * How far a match's length may stray from the track's before it counts as
 * another version: 15 seconds, or a tenth of the track when that is more.
 */
const val LENGTH_SLACK_MS = 15_000L

private val FOLDED = mapOf(
    'à' to 'a', 'á' to 'a', 'â' to 'a', 'ã' to 'a', 'ä' to 'a', 'å' to 'a',
    'ç' to 'c',
    'è' to 'e', 'é' to 'e', 'ê' to 'e', 'ë' to 'e',
    'ì' to 'i', 'í' to 'i', 'î' to 'i', 'ï' to 'i',
    'ñ' to 'n',
    'ò' to 'o', 'ó' to 'o', 'ô' to 'o', 'õ' to 'o', 'ö' to 'o', 'ø' to 'o',
    'ù' to 'u', 'ú' to 'u', 'û' to 'u', 'ü' to 'u',
    'ý' to 'y', 'ÿ' to 'y',
)

/**
 * Lowercases, folds accents and drops punctuation, so "Beyoncé" and "Beyonce",
 * or "C'est la cité" and "C'est La Cite", compare equal.
 */
fun simplify(value: String?): String {
    if (value.isNullOrBlank()) return ""
    val kept = StringBuilder()
    for (raw in value.lowercase()) {
        val char = FOLDED[raw] ?: raw
        if (char in 'a'..'z' || char in '0'..'9') kept.append(char)
        else if (char.isWhitespace()) kept.append(' ')
    }
    return kept.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * A real artist name. Some metadata plugins put the track length ("5:16")
 * where the artist goes, and searching for that finds nothing.
 */
fun isRealName(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    return !name.trim().all { it.isDigit() || it == ':' || it == '.' || it == ' ' }
}

/** The track's artists, or the album's when the track's own are missing or bogus. */
fun artistNames(track: MetadataTrack): List<String> {
    val own = track.artists.map { it.name }.filter(::isRealName)
    if (own.isNotEmpty()) return own
    return track.album?.artists?.map { it.name }?.filter(::isRealName).orEmpty()
}

/** What to search for, best first: "title artists", then the title alone. */
fun searchQueries(track: MetadataTrack): List<String> {
    val names = artistNames(track)
    val queries = mutableListOf<String>()
    if (names.isNotEmpty()) queries.add("${track.title} ${names.joinToString(", ")}")
    queries.add(track.title)
    return queries.distinct()
}

private fun sameIsrc(match: Match, isrc: String?): Boolean =
    !isrc.isNullOrBlank() && match.isrc?.uppercase() == isrc.uppercase()

/** Same song title: equal once simplified, or one extends the other ("Walk" / "Walk (Remastered)"). */
fun sameTitle(a: String, b: String): Boolean {
    val x = simplify(a)
    val y = simplify(b)
    if (x.isEmpty() || y.isEmpty()) return false
    return x == y || x.startsWith("$y ") || y.startsWith("$x ")
}

/**
 * Drops results that only share the title, and ranks an exact ISRC hit first.
 *
 * Searches weigh the title, not the artist, so asking for "Cars and Girls Cliff
 * Richard" comes back full of Prefab Sprout, and "Weird Fishes" brings covers
 * next to Radiohead. An ISRC hit is the recording itself and always survives;
 * anything else has to be credited to one of the track's artists. When the
 * metadata has no usable artist, the title has to match instead.
 */
fun plausible(found: List<Match>, track: MetadataTrack): List<Match> {
    val wanted = artistNames(track).map(::simplify).filter { it.isNotEmpty() }
    val exact = found.filter { sameIsrc(it, track.isrcCode) }
    val rest = found.filter { !sameIsrc(it, track.isrcCode) }

    val credited = if (wanted.isEmpty()) {
        rest.filter { sameTitle(it.title, track.title) }
    } else {
        rest.filter { match ->
            match.artists.any { credit ->
                val name = simplify(credit)
                name.isNotEmpty() && wanted.any { it.contains(name) || name.contains(it) }
            }
        }
    }
    return exact + credited
}

/**
 * Whether a match runs about as long as the track asked for.
 *
 * Every source returns other versions under the same name and credit -- Soft
 * Cell's "Tainted Love" is 2:33 on the album and 8:55 as the club mix -- and
 * only the length tells them apart. A match with the track's own ISRC is the
 * recording, and catalogues disagree on its length by a second or two, so it
 * fails only when off by a factor of two, which means a catalogue error.
 * Either length missing passes: there is nothing to compare.
 */
fun sameLength(match: Match, track: MetadataTrack): Boolean {
    val wanted = track.durationMs
    val actual = match.durationMs
    if (wanted <= 0 || actual <= 0) return true
    if (sameIsrc(match, track.isrcCode)) return actual < wanted * 2 && actual * 2 > wanted

    val gap = if (actual > wanted) actual - wanted else wanted - actual
    return gap <= maxOf(LENGTH_SLACK_MS, wanted / 10)
}

/** The results that are this track, at this length, ISRC hits first. */
fun fitting(found: List<Match>, track: MetadataTrack): List<Match> =
    plausible(found, track).filter { sameLength(it, track) }
