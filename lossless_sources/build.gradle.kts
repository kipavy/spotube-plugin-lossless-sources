import org.spotube.PluginAbility
import org.spotube.PluginCapability
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.zipline.gradle.plugin)
    alias(libs.plugins.spotubeGradle)
}

spotubePlugin {
    name = "Lossless Sources"
    // The release workflow tags the release with this value, and the plugin's
    // update check compares installed copies against that tag.
    version = "2.1.1"
    apiVersion = "0.1.0"
    description = "Lossless FLAC audio source for Spotube that needs no account and no configuration. " +
        "Routes each track to whichever public source can play it: Monochrome first, then any working " +
        "hifi-api (Tidal) instance, from a source list an hourly probe keeps current. " +
        "Set Playback > streaming format to FLAC."
    author = "kipavy"
    contact = "https://github.com/kipavy/spotube-plugin-lossless-sources/issues"
    repository = "https://github.com/kipavy/spotube-plugin-lossless-sources"
    bugs = "https://github.com/kipavy/spotube-plugin-lossless-sources/issues"
    capabilities = listOf(
        PluginCapability.NETWORK_REQUESTS,
        PluginCapability.PERSISTENT_STORAGE,
    )
    abilities = listOf(PluginAbility.AUDIO)
    // No login, so no WebView cookies to read.
    cookieDomains = emptyList()
    license = "MIT"
}

kotlin {
    applyDefaultHierarchyTemplate()

    js {
        browser()
        binaries.executable()
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.zipline.core)
            implementation(libs.spotube.plugin.interfaces)
            implementation(libs.semver)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

zipline {
    mainFunction.set("io.github.kipavy.lossless_sources.main")
}

plugins.withType<YarnPlugin> {
    the<YarnRootExtension>().yarnLockAutoReplace = true
}
