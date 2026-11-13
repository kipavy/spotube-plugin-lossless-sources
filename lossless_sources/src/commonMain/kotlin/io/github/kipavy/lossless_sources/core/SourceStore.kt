package io.github.kipavy.lossless_sources.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.spotube.plugin_interfaces.host_apis.PersistedStorageAPI
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** One host of one source type, e.g. monochrome at https://tracks.monochrome.st. */
data class SourceEntry(val type: String, val base: String)

/**
 * Source list maintained in the repository and refreshed by a scheduled probe,
 * so a source that comes back reaches users within the hour instead of waiting
 * for a release. Nothing here is configured by the user.
 */
const val REMOTE_URL =
    "https://raw.githubusercontent.com/kipavy/spotube-plugin-lossless-sources/main/sources.json"

/** Last list that was fetched successfully. Only a cache: it keeps playback working offline. */
const val CACHE_KEY = "sources-cache"

/**
 * How long a cached list is trusted before the published one is fetched again.
 * Six hours: the probe runs hourly, and someone who opens Spotube twice in an
 * evening should not pay for a fetch twice.
 */
const val CACHE_TTL_MS = 6L * 60 * 60 * 1000

/**
 * Shipped so a fresh install can play before the first fetch returns, and so an
 * offline install still has something to try. Order is preference order.
 *
 * Monochrome serves its own files rather than proxying a shared account, so it
 * is what remains when every Tidal proxy is blocked; the hifi-api hosts are
 * here only as a last resort for an install that never reached GitHub.
 */
val DEFAULT_SOURCES = listOf(
    SourceEntry("monochrome", "https://tracks.monochrome.st"),
    SourceEntry("hifi-api", "https://hifi-api-w9y8.onrender.com"),
    SourceEntry("hifi-api", "https://hifi-api-bffw.onrender.com"),
)

private const val ENTRY_SEPARATOR = ","
private const val FIELD_SEPARATOR = "="
private const val STAMP_SEPARATOR = "|"

/**
 * Resolves which sources to try, newest information first: the published list,
 * then the last one that was fetched, then the bundled defaults.
 */
@OptIn(ExperimentalTime::class)
class SourceStore(
    private val net: Net,
    private val storage: PersistedStorageAPI,
    private val remoteUrl: String = REMOTE_URL,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()

    /** Resolved list, kept for the session so every search does not re-fetch it. */
    private var cached: List<SourceEntry>? = null

    /** The sources playback should try, in order. Never throws and never comes back empty. */
    suspend fun resolve(): List<SourceEntry> = lock.withLock {
        cached?.let { return@withLock it }

        val saved = readCache()
        if (saved != null && saved.fresh && saved.entries.isNotEmpty()) {
            return@withLock merge(saved.entries).also { cached = it }
        }

        val remote = fetchRemote()
        if (remote != null) {
            val merged = merge(remote)
            writeCache(merged)
            return@withLock merged.also { cached = it }
        }

        // Offline, or GitHub hiccuped: a stale list beats only the defaults.
        merge(saved?.entries.orEmpty()).also { cached = it }
    }

    /** Hosts of one source type, in published order. */
    suspend fun basesFor(type: String): List<String> =
        resolve().filter { it.type == type }.map { it.base }

    /** Reads sources.json; null on anything unexpected, so a failed fetch falls back instead of failing. */
    private suspend fun fetchRemote(): List<SourceEntry>? {
        val payload = net.getJson(remoteUrl) ?: return null
        val sources = payload["sources"].arr() ?: return null
        val entries = sources.mapNotNull { item ->
            val type = item["type"].str() ?: return@mapNotNull null
            val base = item["base"].str() ?: return@mapNotNull null
            SourceEntry(type, base)
        }
        return entries.ifEmpty { null }
    }

    private class Saved(val entries: List<SourceEntry>, val fresh: Boolean)

    /** Cache is `<millisecondsSinceEpoch>|<type>=<base>,<type>=<base>`, the same layout 1.x wrote. */
    private suspend fun readCache(): Saved? {
        val text = try {
            storage.getString(CACHE_KEY)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
        if (text.isNullOrBlank()) return null

        val stamp = text.substringBefore(STAMP_SEPARATOR, "").toLongOrNull() ?: 0L
        val body = text.substringAfter(STAMP_SEPARATOR, "")
        val entries = body.split(ENTRY_SEPARATOR).mapNotNull { part ->
            val type = part.substringBefore(FIELD_SEPARATOR, "")
            val base = part.substringAfter(FIELD_SEPARATOR, "")
            if (type.isBlank() || base.isBlank()) null else SourceEntry(type, base)
        }
        val age = now() - stamp
        return Saved(entries, fresh = age in 0 until CACHE_TTL_MS)
    }

    private suspend fun writeCache(entries: List<SourceEntry>) {
        val encoded = entries.joinToString(ENTRY_SEPARATOR) { "${it.type}$FIELD_SEPARATOR${it.base}" }
        try {
            storage.putString(CACHE_KEY, "${now()}$STAMP_SEPARATOR$encoded")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Only a cache; the list in memory still serves this session.
        }
    }

    companion object {
        /**
         * Trims whitespace and trailing slashes so "https://host/" and
         * "https://host" are not tried as two hosts; anything not http(s) is dropped.
         */
        fun normalize(base: String): String? {
            val url = base.trim().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            return url
        }

        /**
         * Published entries first, bundled defaults after, deduplicated on
         * type+base. The defaults stay at the end so an empty or broken list
         * still behaves like a plain install rather than leaving nothing to try.
         */
        fun merge(entries: List<SourceEntry>): List<SourceEntry> {
            val merged = LinkedHashSet<SourceEntry>()
            for (entry in entries + DEFAULT_SOURCES) {
                val type = entry.type.trim()
                val base = normalize(entry.base) ?: continue
                if (type.isEmpty()) continue
                merged.add(SourceEntry(type, base))
            }
            return merged.toList()
        }
    }
}
