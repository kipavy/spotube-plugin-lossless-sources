package io.github.kipavy.lossless_sources

import io.github.kipavy.lossless_sources.core.RealCoreAPI
import kotlinx.coroutines.test.runTest
import net.swiftzer.semver.SemVer
import org.spotube.plugin_interfaces.host_apis.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoreAPITest {
    private val latest = "https://api.test/releases/latest"

    private fun release(tag: String) = """{"tag_name":"$tag","body":"notes","assets":[
        {"name":"lossless-sources.smplug","browser_download_url":"https://dl.test/lossless-sources.smplug"}]}"""

    @Test
    fun newerReleaseIsOffered() = runTest {
        val http = FakeHttp()
        http.json(HttpMethod.Get, latest, body = release("2.1.0"))
        val update = RealCoreAPI(net(http), latest).checkPluginUpdates(SemVer.parse("2.0.0"))!!
        assertEquals("2.1.0", update.latestVersion)
        assertEquals("https://dl.test/lossless-sources.smplug", update.directDownloadUrl)
    }

    @Test
    fun sameOrOlderReleaseAndFailuresAreIgnored() = runTest {
        val http = FakeHttp()
        http.json(HttpMethod.Get, latest, body = release("2.0.0"))
        assertNull(RealCoreAPI(net(http), latest).checkPluginUpdates(SemVer.parse("2.0.0")))
        assertNull(RealCoreAPI(net(FakeHttp()), latest).checkPluginUpdates(SemVer.parse("2.0.0")))
    }
}
