package io.github.kipavy.lossless_sources

import app.cash.zipline.Zipline
import io.github.kipavy.lossless_sources.audio.RealAudioAPI
import io.github.kipavy.lossless_sources.core.Net
import io.github.kipavy.lossless_sources.core.RealCoreAPI
import io.github.kipavy.lossless_sources.core.SourceStore
import org.spotube.plugin_interfaces.core.runPluginInitialized
import org.spotube.plugin_interfaces.host_apis.HttpClientAPI
import org.spotube.plugin_interfaces.host_apis.HttpClientAPI_SERVICE_NAME
import org.spotube.plugin_interfaces.host_apis.PersistedStorageAPI
import org.spotube.plugin_interfaces.host_apis.PersistedStorageAPI_SERVICE_NAME
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioAPI
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioAPI_SERVICE_NAME
import org.spotube.plugin_interfaces.plugin_apis.core.CoreAPI
import org.spotube.plugin_interfaces.plugin_apis.core.CoreAPI_SERVICE_NAME

private val zipline by lazy { Zipline.get() }

@OptIn(ExperimentalJsExport::class)
@JsExport
fun main() {
    runPluginInitialized {
        val http = zipline.take<HttpClientAPI>(HttpClientAPI_SERVICE_NAME)
        val storage = zipline.take<PersistedStorageAPI>(PersistedStorageAPI_SERVICE_NAME)

        val net = Net(http)
        val store = SourceStore(net, storage)

        zipline.bind<CoreAPI>(CoreAPI_SERVICE_NAME, RealCoreAPI(net))
        zipline.bind<AudioAPI>(AudioAPI_SERVICE_NAME, RealAudioAPI(net, store))
    }
}
