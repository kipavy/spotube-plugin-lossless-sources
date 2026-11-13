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
interface TrackSource {
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

/**
 * What to search for, best first: "title artists"; then, for a title like the
 * YouTube Music plugin sends ("Album (Original Soundtrack) 103 - Song [Official
 * Video]"), the song part at its end with the artists; then the title alone.
 */
fun searchQueries(track: MetadataTrack): List<String> {
    val names = artistNames(track)
    val queries = mutableListOf<String>()
    if (names.isNotEmpty()) {
        val credit = names.joinToString(", ")
        queries.add("${track.title} $credit")
        titleCandidates(track.title, names).drop(1).map(::withoutBrackets).distinct()
            .filter { it != track.title }
            .take(CLEANED_QUERIES)
            .forEach { queries.add("$it $credit") }
    }
    queries.add(track.title)
    return queries.distinct()
}

/** How many cleaned forms of a messy title are searched before the bare title. */
private const val CLEANED_QUERIES = 2

private fun sameIsrc(match: Match, isrc: String?): Boolean =
    !isrc.isNullOrBlank() && match.isrc?.uppercase() == isrc.uppercase()

/**
 * A title as words: case dropped, Latin accents folded, and "/", "-", brackets
 * and other separators read as spaces, so "Weird Fishes/Arpeggi" has the words
 * "weird fishes arpeggi". Apostrophes are dropped, keeping "C'est" one word.
 * Letters of any script are kept: a Japanese or Cyrillic title has words too.
 */
fun titleWords(value: String): List<String> {
    val kept = StringBuilder()
    for (raw in value.lowercase()) {
        if (raw == '\'' || raw == '’') continue
        val char = FOLDED[raw] ?: raw
        kept.append(if (char.isLetterOrDigit()) char else ' ')
    }
    return kept.split(' ').filter { it.isNotEmpty() }
}

/** Share of the longer title's words the other title also has, 0 to 1. */
fun titleOverlap(a: String, b: String): Double {
    val x = titleWords(a)
    val y = titleWords(b)
    if (x.isEmpty() || y.isEmpty()) return 0.0
    val shared = x.toSet().intersect(y.toSet()).size
    return shared.toDouble() / maxOf(x.toSet().size, y.toSet().size)
}

/** How much of the title two titles must share when neither extends the other. */
const val TITLE_OVERLAP = 0.6

private val BRACKETED = Regex("""\s*[(\[][^()\[\]]*[)\]]""")
private val SEGMENT_SEPARATOR = Regex("""\s+[-–—|]\s+""")

/** The title without its "(...)" and "[...]" parts: "Aline [Official Music Video]" is "Aline". */
fun withoutBrackets(title: String): String = title.replace(BRACKETED, "").trim()

private fun words(title: String): String = titleWords(title).joinToString(" ")

/**
 * The song titles a track title may stand for, the title itself first.
 *
 * Metadata from YouTube carries video titles, not song titles: "Clair Obscur:
 * Expedition 33 (Original Soundtrack) 103 - Sirène - Rouge d'Iris", or
 * "Artist - Song [Official Music Video]". The song is at the end, so besides
 * the title itself, every tail of its " - " or " | " separated parts counts
 * ("Sirène - Rouge d'Iris", "Rouge d'Iris"), each with and without brackets.
 * A part from the middle never counts alone: "Sirène" would accept "Sirène -
 * Robe de Jour". Neither does a part that is only an artist's name.
 */
fun titleCandidates(title: String, artists: List<String> = emptyList()): List<String> {
    val names = artists.map(::words).toSet()
    val segments = title.split(SEGMENT_SEPARATOR).filter { it.isNotBlank() }
    val found = mutableListOf<String>()
    for (start in 1 until segments.size) {
        val tail = segments.drop(start).joinToString(" - ")
        found.add(tail)
        found.add(withoutBrackets(tail))
    }
    found.add(withoutBrackets(title))
    val kept = found.filter { words(it).isNotEmpty() && words(it) !in names }
    return (listOf(title) + kept).distinctBy(::words)
}

/**
 * Whether [found], a catalogue's song title, is the song [wanted] names.
 *
 * It is when it equals one of the wanted title's candidates, extends one
 * ("Walk" asked, "Walk (Remastered)" found), or shares most of its words
 * ("Song - 2011 Remaster" / "Song (Remastered 2011)"). Only the found title may
 * extend: a long wanted title starting like a short one is another song, as
 * "Clair Obscur: Expedition 33 ... - Sirène - Rouge d'Iris" is not the track
 * "Clair-Obscur".
 *
 * Soundtracks are why this has to hold even when the artist matches: every
 * track of "Clair Obscur: Expedition 33" is credited to Lorien Testard, and
 * "Sirène - Rouge d'Iris" and "Sirène - Robe de Jour" share an artist, an
 * album and a length to within seconds. Only the title tells them apart.
 */
fun sameTitle(found: String, wanted: String, artists: List<String> = emptyList()): Boolean {
    val have = words(found)
    if (have.isEmpty()) return false
    return titleCandidates(wanted, artists).any { candidate ->
        val want = words(candidate)
        want.isNotEmpty() && (have == want || have.startsWith("$want ") || titleOverlap(candidate, found) >= TITLE_OVERLAP)
    }
}

/**
 * How close [found] is to [wanted]: words they share, less words only [found]
 * has. Ranks "World Map - Children of Lumière" above "World Map" for a wanted
 * title holding both.
 */
fun titleScore(found: String, wanted: String): Int {
    val have = titleWords(found).toSet()
    val want = titleWords(wanted).toSet()
    return have.intersect(want).size - (have - want).size
}

/**
 * Keeps the results that are this song, best first: an exact ISRC hit, then
 * the closest titles.
 *
 * An ISRC hit is the recording itself and always survives. Anything else needs
 * the same title and, when the metadata has a usable artist, a credit to one
 * of the track's artists: searches weigh the title, not the artist, so asking
 * for "Cars and Girls Cliff Richard" comes back full of Prefab Sprout, and
 * "Weird Fishes" brings covers next to Radiohead.
 */
fun plausible(found: List<Match>, track: MetadataTrack): List<Match> {
    val names = artistNames(track)
    val wanted = names.map(::simplify).filter { it.isNotEmpty() }
    val exact = found.filter { sameIsrc(it, track.isrcCode) }
    val rest = found.filter { !sameIsrc(it, track.isrcCode) }

    val credited = rest.filter { match ->
        sameTitle(match.title, track.title, names) && (wanted.isEmpty() || match.artists.any { credit ->
            val name = simplify(credit)
            name.isNotEmpty() && wanted.any { it.contains(name) || name.contains(it) }
        })
    }.sortedByDescending { titleScore(it.title, track.title) }
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
