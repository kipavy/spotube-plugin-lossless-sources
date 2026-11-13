package io.github.kipavy.lossless_sources

import io.github.kipavy.lossless_sources.core.Net
import org.spotube.plugin_interfaces.host_apis.HttpClientAPI
import org.spotube.plugin_interfaces.host_apis.HttpMethod
import org.spotube.plugin_interfaces.host_apis.HttpResponse
import org.spotube.plugin_interfaces.host_apis.PersistedStorageAPI
import org.spotube.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Stands in for Spotube's HTTP bridge. Routes are matched on "<METHOD> <url prefix>",
 * longest prefix first; anything unrouted answers 404, and a route may throw to
 * simulate a transport error.
 */
class FakeHttp : HttpClientAPI {
    private val routes = mutableListOf<Pair<String, (String) -> HttpResponse>>()
    val calls = mutableListOf<String>()

    fun on(method: HttpMethod, prefix: String, answer: (String) -> HttpResponse) {
        routes.add("${method.value} $prefix" to answer)
    }

    fun json(method: HttpMethod, prefix: String, status: Int = 200, body: String) =
        on(method, prefix) { HttpResponse(status, mapOf("content-type" to "application/json"), body) }

    fun status(method: HttpMethod, prefix: String, status: Int, headers: Map<String, String> = emptyMap()) =
        on(method, prefix) { HttpResponse(status, headers, "") }

    override suspend fun request(
        method: HttpMethod,
        url: String,
        requestHeaders: Map<String, String>?,
        body: String?,
    ): HttpResponse {
        val key = "${method.value} $url"
        calls.add(key)
        val route = routes.filter { key.startsWith(it.first) }.maxByOrNull { it.first.length }
            ?: return HttpResponse(404, emptyMap(), "{\"detail\":\"not found\"}")
        return route.second(url)
    }
}

class FakeStorage(initial: Map<String, String> = emptyMap()) : PersistedStorageAPI {
    val values = initial.toMutableMap()
    override suspend fun putString(key: String, value: String) { values[key] = value }
    override suspend fun getString(key: String): String? = values[key]
    override suspend fun remove(key: String) { values.remove(key) }
    override suspend fun getKeys(): List<String> = values.keys.toList()
}

fun net(http: FakeHttp) = Net(http)

fun track(
    title: String,
    vararg artists: String,
    durationMs: Long = 0,
    isrc: String? = null,
) = MetadataTrack(
    id = "track-1",
    title = title,
    durationMs = durationMs,
    trackNumber = null,
    discNumber = null,
    artists = artists.map { MetadataArtist.Basic(id = it, name = it, thumbnails = emptyList(), externalUri = null) },
    album = null,
    thumbnails = null,
    explicit = null,
    popularity = null,
    isrcCode = isrc,
    externalUri = null,
)

const val MONO = "https://mono.test"
const val HIFI = "https://hifi.test"
const val LIST_URL = "https://list.test/sources.json"

fun sourcesJson(vararg entries: Pair<String, String>) =
    """{"sources":[${entries.joinToString(",") { """{"type":"${it.first}","base":"${it.second}"}""" }}]}"""

fun monochromeItem(id: String, title: String, artist: String, durationMs: Long, isrc: String = "", playable: Boolean = true) =
    """{"id":"$id","title":"$title","artistNames":["$artist"],"duration":$durationMs,"isrc":"$isrc","playable":$playable,"artwork":"$MONO/a.jpg"}"""

fun hifiItem(id: Long, title: String, artist: String, durationSec: Long, isrc: String = "") =
    """{"id":$id,"title":"$title","duration":$durationSec,"isrc":"$isrc","artist":{"name":"$artist"},"artists":[{"name":"$artist"}],"album":{"title":"Album","cover":"aa-bb-cc"}}"""

@OptIn(ExperimentalEncodingApi::class)
fun btsTrack(url: String, codecs: String = "flac", sampleRate: Int = 44100): String {
    val manifest = Base64.encode("""{"mimeType":"audio/flac","codecs":"$codecs","urls":["$url"]}""".encodeToByteArray())
    return """{"version":"2.0","data":{"trackId":1,"manifestMimeType":"application/vnd.tidal.bts","manifest":"$manifest","sampleRate":$sampleRate,"bitDepth":16}}"""
}
