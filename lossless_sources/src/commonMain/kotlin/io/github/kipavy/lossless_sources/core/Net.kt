package io.github.kipavy.lossless_sources.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.spotube.plugin_interfaces.host_apis.HttpClientAPI
import org.spotube.plugin_interfaces.host_apis.HttpMethod
import org.spotube.plugin_interfaces.host_apis.HttpResponse

/**
 * Spotube's HTTP client, made to never throw.
 *
 * Every source here is a free public host that can be asleep, blocked or gone,
 * and one of them failing must only move the lookup on to the next, never
 * abort it. So a transport error comes back as null, like any other answer
 * that is not worth reading.
 */
class Net(private val http: HttpClientAPI) {
    suspend fun request(
        method: HttpMethod,
        url: String,
        headers: Map<String, String> = DEFAULT_HEADERS,
    ): HttpResponse? = try {
        http.request(method, url, headers, null)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    suspend fun get(url: String, headers: Map<String, String> = DEFAULT_HEADERS) =
        request(HttpMethod.Get, url, headers)

    suspend fun head(url: String) = request(HttpMethod.Head, url)

    /** The body as JSON, or null unless the host answered 200 with valid JSON. */
    suspend fun getJson(url: String, headers: Map<String, String> = DEFAULT_HEADERS): JsonElement? {
        val res = get(url, headers) ?: return null
        if (res.statusCode != 200) return null
        return parse(res.body)
    }

    fun parse(text: String?): JsonElement? = parseLenient(text)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** JSON, or null when [text] is empty or not JSON. */
        fun parseLenient(text: String?): JsonElement? {
            if (text.isNullOrBlank()) return null
            return try {
                json.parseToJsonElement(text)
            } catch (error: Exception) {
                null
            }
        }

        val DEFAULT_HEADERS = mapOf(
            "Accept" to "application/json",
            "User-Agent" to "spotube-plugin-lossless-sources",
        )
    }
}

/** Percent-encodes a query parameter value (RFC 3986 unreserved characters kept). */
fun encode(value: String): String {
    val out = StringBuilder()
    for (byte in value.encodeToByteArray()) {
        val code = byte.toInt() and 0xff
        val char = code.toChar()
        if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-_.~") {
            out.append(char)
        } else {
            out.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0f])
        }
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"

/** A header by name, ignoring case: hosts and proxies disagree on it. */
fun HttpResponse.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

/** A number, whether the host sent it as a number or as a string. */
fun JsonElement?.num(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.longOrNull ?: primitive.contentOrNull?.toDoubleOrNull()?.toLong()
}

fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

/** An id that may arrive as a number or a string. */
fun JsonElement?.id(): String? = str()?.takeIf { it.isNotBlank() }
