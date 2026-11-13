package io.github.kipavy.lossless_sources

import io.github.kipavy.lossless_sources.audio.Match
import io.github.kipavy.lossless_sources.audio.fitting
import io.github.kipavy.lossless_sources.audio.sameTitle
import io.github.kipavy.lossless_sources.audio.searchQueries
import io.github.kipavy.lossless_sources.audio.simplify
import io.github.kipavy.lossless_sources.core.encode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatchingTest {
    private fun match(ref: String, title: String, artist: String, durationMs: Long = 0, isrc: String? = null) =
        Match("monochrome", ref, title, listOf(artist), null, durationMs, isrc, null, null)

    @Test
    fun simplifyFoldsAccentsAndPunctuation() {
        assertEquals("beyonce", simplify("Beyoncé"))
        assertEquals("cest la cite", simplify("C'est  la cité!"))
    }

    @Test
    fun coversAreDroppedAndIsrcHitsRankFirst() {
        val track = track("Weird Fishes", "Radiohead", isrc = "GBAYE0601690")
        val found = listOf(
            match("1", "Weird Fishes", "Noordpool Orchestra"),
            match("2", "Weird Fishes/Arpeggi", "Radiohead"),
            match("3", "Weird Fishes", "Radiohead", isrc = "gbaye0601690"),
        )
        assertEquals(listOf("3", "2"), fitting(found, track).map { it.ref })
    }

    @Test
    fun anotherVersionIsDroppedOnLength() {
        val track = track("Tainted Love", "Soft Cell", durationMs = 153_000)
        val found = listOf(
            match("club", "Tainted Love", "Soft Cell", durationMs = 535_000),
            match("album", "Tainted Love", "Soft Cell", durationMs = 154_000),
        )
        assertEquals(listOf("album"), fitting(found, track).map { it.ref })
    }

    @Test
    fun isrcHitOnlyFailsWhenOffByAFactorOfTwo() {
        val track = track("Song", "Artist", durationMs = 200_000, isrc = "X1")
        assertEquals(1, fitting(listOf(match("a", "Song", "Artist", 230_000, "X1")), track).size)
        assertEquals(0, fitting(listOf(match("a", "Song", "Artist", 420_000, "X1")), track).size)
    }

    @Test
    fun bogusArtistFallsBackToTitleMatching() {
        val track = track("Walk", "5:16")
        assertEquals(listOf("Walk"), searchQueries(track))
        val found = listOf(match("a", "Walk (Remastered)", "Pantera"), match("b", "Run", "Someone"))
        assertEquals(listOf("a"), fitting(found, track).map { it.ref })
    }

    @Test
    fun queriesTryArtistsThenTitle() {
        assertEquals(
            listOf("One More Time Daft Punk", "One More Time"),
            searchQueries(track("One More Time", "Daft Punk")),
        )
    }

    @Test
    fun encodeIsUtf8PercentEncoding() {
        assertEquals("C%27est%20la%20cit%C3%A9", encode("C'est la cité"))
    }

    @Test
    fun soundtrackTracksAreToldApartByTitle() {
        // Every track of the album is credited to the same composer, at similar lengths.
        val track = track("World Map - Children of Lumière", "Lorien Testard", durationMs = 151_000)
        val found = listOf(
            match("monoco", "Monoco", "Lorien Testard", 150_000),
            match("toile", "World Map - Déchire la Toile", "Lorien Testard", 160_000),
            match("name", "World Map - In Lumière's Name", "Lorien Testard", 155_000),
            match("right", "World Map - Children of Lumière", "Lorien Testard", 151_000),
        )
        assertEquals(listOf("right"), fitting(found, track).map { it.ref })
    }

    @Test
    fun closestTitleRanksFirst() {
        val track = track("Song", "Artist")
        val found = listOf(match("remaster", "Song (Remastered)", "Artist"), match("exact", "Song", "Artist"))
        assertEquals(listOf("exact", "remaster"), fitting(found, track).map { it.ref })
    }

    @Test
    fun titlesMatchAcrossDecorationAndScripts() {
        assertTrue(sameTitle("Weird Fishes/Arpeggi", "Weird Fishes"))
        assertTrue(sameTitle("Song - 2011 Remaster", "Song (Remastered 2011)"))
        assertTrue(sameTitle("C'est la cité", "C’est La Cite"))
        assertTrue(sameTitle("夜に駆ける", "夜に駆ける"))
        assertFalse(sameTitle("Sirène - Rouge d'Iris", "Sirène - Robe de Jour"))
        assertFalse(sameTitle("夜に駆ける", "群青"))
    }

    /** Titles as the YouTube Music metadata plugin sends them: video titles, no ISRC. */
    private val rougeDIris = "Clair Obscur: Expedition 33 (Original Soundtrack) 103 - Sirène - Rouge d'Iris"
    private val childrenOfLumiere = "Clair Obscur: Expedition 33 (Original Soundtrack) 143 - World Map - Children of Lumière"

    @Test
    fun youtubeStyleTitleMatchesOnlyTheSongAtItsEnd() {
        val track = track(rougeDIris, "Lorien Testard", durationMs = 200_000)
        val found = listOf(
            // Starts like the wanted title: 2.1.0 accepted it.
            match("clair", "Clair-Obscur", "Lorien Testard", 219_000),
            // Shares the middle part "Sirène".
            match("robe", "Sirène - Robe de Jour", "Lorien Testard", 199_000),
            match("right", "Sirène - Rouge d'Iris", "Lorien Testard", 199_000),
        )
        assertEquals(listOf("right"), fitting(found, track).map { it.ref })
    }

    @Test
    fun youtubeStyleTitleRanksTheFullSongFirst() {
        val track = track(childrenOfLumiere, "Lorien Testard", durationMs = 152_000)
        val found = listOf(
            match("monoco", "Monoco", "Lorien Testard", 150_000),
            match("lumiere", "Children of Lumière", "Lorien Testard", 150_000),
            match("right", "World Map - Children of Lumière", "Lorien Testard", 151_000),
        )
        assertEquals(listOf("right", "lumiere"), fitting(found, track).map { it.ref })
    }

    @Test
    fun repeatedWordsDoNotMakeAnotherSongLookClose() {
        val track = track("Clair Obscur: Expedition 33 (Original Soundtrack) 41 - Gestral Village - Gestral Merchant", "Lorien Testard", durationMs = 198_000)
        val found = listOf(
            match("golgra", "Gestral Village - Golgra", "Lorien Testard", 194_000),
            // The catalogue's own typo: still the song.
            match("right", "Gestral ViIllage - Gestral Merchant", "Lorien Testard", 197_000),
        )
        assertEquals(listOf("right"), fitting(found, track).map { it.ref })
    }

    @Test
    fun videoDecorationAndArtistPrefixAreIgnored() {
        assertTrue(sameTitle("Aline", "Clair Obscur: Expedition 33 | Aline [Official Music Video]"))
        assertTrue(sameTitle("One More Time", "Daft Punk - One More Time (Official Video)", listOf("Daft Punk")))
        // The artist's name alone is not a song title.
        assertFalse(sameTitle("Daft Punk", "Daft Punk - One More Time (Official Video)", listOf("Daft Punk")))
        // Clean titles behave as before.
        assertTrue(sameTitle("Walk (Remastered)", "Walk"))
        assertFalse(sameTitle("Clair-Obscur", rougeDIris))
    }

    @Test
    fun youtubeStyleTitleIsAlsoSearchedByItsSong() {
        assertEquals(
            listOf(
                "$rougeDIris Lorien Testard",
                "Sirène - Rouge d'Iris Lorien Testard",
                "Rouge d'Iris Lorien Testard",
                rougeDIris,
            ),
            searchQueries(track(rougeDIris, "Lorien Testard")),
        )
    }
}
