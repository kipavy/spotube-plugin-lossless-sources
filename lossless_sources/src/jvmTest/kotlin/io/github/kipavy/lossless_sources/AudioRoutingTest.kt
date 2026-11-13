package io.github.kipavy.lossless_sources

import io.github.kipavy.lossless_sources.audio.HifiApi
import io.github.kipavy.lossless_sources.audio.Monochrome
import io.github.kipavy.lossless_sources.audio.RealAudioAPI
import io.github.kipavy.lossless_sources.core.SourceStore
import kotlinx.coroutines.test.runTest
import org.spotube.plugin_interfaces.host_apis.HttpMethod
import org.spotube.plugin_interfaces.host_apis.HttpResponse
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioSource
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AudioRoutingTest {
    private val daftPunk = track("One More Time", "Daft Punk", durationMs = 320_000, isrc = "GBDUW0000053")

    /** Both sources published, no retry delays, everything else up to the test. */
    private fun api(http: FakeHttp): RealAudioAPI {
        http.json(HttpMethod.Get, LIST_URL, body = sourcesJson("monochrome" to MONO, "hifi-api" to HIFI))
        val net = net(http)
        val store = SourceStore(net, FakeStorage(), LIST_URL)
        return RealAudioAPI(net, store, listOf(Monochrome(net, emptyList()), HifiApi(net, emptyList())))
    }

    @Test
    fun monochromeServesFlacWithQualityFromFileSize() = runTest {
        val http = FakeHttp()
        http.json(
            HttpMethod.Get, "$MONO/search/tracks",
            body = """{"tracks":[${monochromeItem("7", "One More Time", "Daft Punk", 320_357, "GBDUW0000053")}]}""",
        )
        // ~2.4 Mbit/s average: beyond 24-bit/44.1, so reported as 96 kHz.
        http.status(HttpMethod.Head, "$MONO/track/7", 200, mapOf("Content-Length" to "96000000"))

        val result = api(http).getStreamsByTrack(daftPunk)

        val streamed = assertIs<AudioSource.Streamed>(result.single())
        assertEquals("monochrome:7:320357", streamed.id)
        assertEquals(1.0f, streamed.confidence)
        val stream = assertIs<AudioStream.Lossless>(streamed.streams.single())
        assertEquals("$MONO/track/7", stream.url)
        assertEquals(96_000, stream.sampleRate)
        assertTrue(http.calls.none { it.startsWith("GET $HIFI") }, "hifi-api must not be asked")
    }

    @Test
    fun unservableMonochromeMatchFallsThroughToHifiApi() = runTest {
        val http = FakeHttp()
        http.json(
            HttpMethod.Get, "$MONO/search/tracks",
            body = """{"tracks":[${monochromeItem("7", "One More Time", "Daft Punk", 320_357)}]}""",
        )
        http.status(HttpMethod.Head, "$MONO/track/7", 404)
        http.json(HttpMethod.Get, "$HIFI/search/", body = """{"data":{"items":[${hifiItem(42, "One More Time", "Daft Punk", 320, "GBDUW0000053")}]}}""")
        http.json(HttpMethod.Get, "$HIFI/track/?id=42&quality=HI_RES_LOSSLESS", body = btsTrack("https://cdn.test/42.flac"))
        http.json(HttpMethod.Get, "$HIFI/track/?id=42&quality=LOSSLESS", body = btsTrack("https://cdn.test/42.flac"))
        http.json(HttpMethod.Get, "$HIFI/track/?id=42&quality=HIGH", body = btsTrack("https://cdn.test/42.m4a", codecs = "mp4a.40.2"))

        val result = api(http).getStreamsByTrack(daftPunk)

        val streamed = assertIs<AudioSource.Streamed>(result.first())
        assertEquals("hifi-api:42:320000", streamed.id)
        assertEquals("https://resources.tidal.com/images/aa/bb/cc/640x640.jpg", streamed.thumbnails.single().url)
        // Same URL for two qualities is kept once; the AAC one is a separate stream.
        assertEquals(2, streamed.streams.size)
        assertIs<AudioStream.Lossless>(streamed.streams[0])
        val aac = assertIs<AudioStream.Lossy>(streamed.streams[1])
        assertEquals("mp4", aac.container)
    }

    @Test
    fun busyInstanceIsRetriedOn202() = runTest {
        val http = FakeHttp()
        http.json(HttpMethod.Get, "$HIFI/search/", body = """{"data":{"items":[${hifiItem(42, "One More Time", "Daft Punk", 320)}]}}""")
        var tries = 0
        http.on(HttpMethod.Get, "$HIFI/track/?id=42&quality=HI_RES_LOSSLESS") {
            tries++
            if (tries == 1) HttpResponse(202, emptyMap(), "") else HttpResponse(200, emptyMap(), btsTrack("https://cdn.test/42.flac"))
        }
        val net = net(http)
        http.json(HttpMethod.Get, LIST_URL, body = sourcesJson("hifi-api" to HIFI))
        val api = RealAudioAPI(net, SourceStore(net, FakeStorage(), LIST_URL), listOf(HifiApi(net, listOf(0L))))

        val result = api.getStreamsByTrack(daftPunk)

        assertEquals(2, tries)
        assertIs<AudioSource.Streamed>(result.first())
    }

    @Test
    fun coverAndWrongLengthAreNeverPlayed() = runTest {
        val http = FakeHttp()
        http.json(
            HttpMethod.Get, "$MONO/search/tracks",
            body = """{"tracks":[${monochromeItem("1", "One More Time", "Some Cover Band", 320_000)},${monochromeItem("2", "One More Time", "Daft Punk", 600_000)}]}""",
        )
        http.status(HttpMethod.Head, "$MONO/track/", 200)

        assertTrue(api(http).getStreamsByTrack(daftPunk).isEmpty())
    }

    @Test
    fun hostsThatThrowOrAreDownGiveAnEmptyResult() = runTest {
        val http = FakeHttp()
        http.on(HttpMethod.Get, MONO) { throw RuntimeException("connection reset") }
        assertTrue(api(http).getStreamsByTrack(daftPunk).isEmpty())
    }

    @Test
    fun savedMatchIsResolvedAgainstItsSource() = runTest {
        val http = FakeHttp()
        http.status(HttpMethod.Head, "$MONO/track/7", 200, mapOf("content-length" to "40000000"))
        val saved = AudioSource.Basic("monochrome:7:320357", "One More Time", "Daft Punk", null, emptyList(), null, 1f)

        val streamed = api(http).getStreamsOfAudioSource(saved).single()

        assertEquals(44_100, assertIs<AudioStream.Lossless>(streamed.streams.single()).sampleRate)
    }

    @Test
    fun matchFromAnUnknownSourceAsksSpotubeToRematch() = runTest {
        val http = FakeHttp()
        val saved = AudioSource.Basic("youtube:abc", "x", null, null, emptyList(), null, 1f)
        assertTrue(api(http).getStreamsOfAudioSource(saved).isEmpty())
    }
}
