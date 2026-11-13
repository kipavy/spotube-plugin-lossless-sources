package io.github.kipavy.lossless_sources

import io.github.kipavy.lossless_sources.core.CACHE_KEY
import io.github.kipavy.lossless_sources.core.CACHE_TTL_MS
import io.github.kipavy.lossless_sources.core.DEFAULT_SOURCES
import io.github.kipavy.lossless_sources.core.SourceEntry
import io.github.kipavy.lossless_sources.core.SourceStore
import kotlinx.coroutines.test.runTest
import org.spotube.plugin_interfaces.host_apis.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceStoreTest {
    private val now = 1_000_000_000_000L

    @Test
    fun publishedListComesFirstThenDefaultsAndIsCached() = runTest {
        val http = FakeHttp()
        http.json(HttpMethod.Get, LIST_URL, body = sourcesJson("monochrome" to "$MONO/", "hifi-api" to HIFI))
        val storage = FakeStorage()
        val store = SourceStore(net(http), storage, LIST_URL) { now }

        val list = store.resolve()

        assertEquals(SourceEntry("monochrome", MONO), list[0])
        assertEquals(SourceEntry("hifi-api", HIFI), list[1])
        assertEquals(DEFAULT_SOURCES, list.drop(2))
        assertTrue(storage.values[CACHE_KEY]!!.startsWith("$now|monochrome=$MONO,"))

        // Kept for the session: no second fetch.
        store.resolve()
        assertEquals(1, http.calls.size)
    }

    @Test
    fun freshCacheSkipsTheNetwork() = runTest {
        val http = FakeHttp()
        val storage = FakeStorage(mapOf(CACHE_KEY to "${now - 1000}|hifi-api=$HIFI"))
        val list = SourceStore(net(http), storage, LIST_URL) { now }.resolve()

        assertEquals(SourceEntry("hifi-api", HIFI), list[0])
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun staleCacheIsUsedWhenTheFetchFails() = runTest {
        val http = FakeHttp()
        http.on(HttpMethod.Get, LIST_URL) { throw RuntimeException("offline") }
        // Written by 1.x, YouTube entry included: unknown types are kept and skipped by the router.
        val storage = FakeStorage(mapOf(CACHE_KEY to "${now - CACHE_TTL_MS - 1}|hifi-api=$HIFI,youtube=https://youtube.com"))
        val list = SourceStore(net(http), storage, LIST_URL) { now }.resolve()

        assertEquals(listOf(SourceEntry("hifi-api", HIFI), SourceEntry("youtube", "https://youtube.com")), list.take(2))
        assertEquals(1, http.calls.size)
    }

    @Test
    fun nothingAnywhereStillGivesTheDefaults() = runTest {
        val list = SourceStore(net(FakeHttp()), FakeStorage(), LIST_URL) { now }.resolve()
        assertEquals(DEFAULT_SOURCES, list)
    }

    @Test
    fun badEntriesAreDropped() {
        val merged = SourceStore.merge(
            listOf(SourceEntry("monochrome", "ftp://x"), SourceEntry("", MONO), SourceEntry("hifi-api", " $HIFI// "))
        )
        assertEquals(SourceEntry("hifi-api", HIFI), merged[0])
        assertEquals(DEFAULT_SOURCES, merged.drop(1))
    }
}
