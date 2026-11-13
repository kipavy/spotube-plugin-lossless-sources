package io.github.kipavy.lossless_sources.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.swiftzer.semver.SemVer
import org.spotube.plugin_interfaces.plugin_apis.core.CoreAPI
import org.spotube.plugin_interfaces.plugin_apis.core.PluginUpdateInfo

const val LATEST_RELEASE_URL =
    "https://api.github.com/repos/kipavy/spotube-plugin-lossless-sources/releases/latest"

/** Nothing to log into. Updates come from this repository's GitHub releases. */
class RealCoreAPI(
    private val net: Net,
    private val latestReleaseUrl: String = LATEST_RELEASE_URL,
) : CoreAPI {
    override val requiresAuthentication: Boolean = false

    // Always "logged in": there is no account, and Spotube must not prompt for one.
    override val loggedInFlow: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

    override suspend fun checkPluginUpdates(currentVersion: SemVer): PluginUpdateInfo? {
        val release = net.getJson(
            latestReleaseUrl,
            mapOf(
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
                "User-Agent" to "spotube-plugin-lossless-sources",
            ),
        ) ?: return null

        val tag = release["tag_name"].str()?.removePrefix("v") ?: return null
        val latest = try {
            SemVer.parse(tag)
        } catch (error: Exception) {
            return null
        }
        if (latest <= currentVersion) return null

        val url = release["assets"].arr()
            ?.firstOrNull { it["name"].str()?.endsWith(".smplug") == true }
            ?.get("browser_download_url").str()
            ?: return null

        return PluginUpdateInfo(
            latestVersion = tag,
            directDownloadUrl = url,
            releaseNotes = release["body"].str() ?: "",
        )
    }

    override fun supportMarkdownText(currentVersion: SemVer): String = SUPPORT

    override suspend fun login() {}

    override suspend fun logout() {}
}

private val SUPPORT = """
# Lossless Sources

Each track is routed to whichever public source can actually play it:

- **Monochrome** — the mainstream catalogue in FLAC, up to 24-bit, served from
  Monochrome's own servers. Matched by ISRC.
- **hifi-api instances** — Tidal, matched by ISRC so remasters and live
  versions are not confused with each other.

**Set Settings → Playback → streaming format to FLAC**, or Spotube may pick a
lower quality stream when one is offered.

## If a track does not play

This plugin is lossless only. A track that no lossless source has will not
play while it is selected; switch the audio plugin back to the built-in
YouTube one for that track.

hifi-api instances proxy Tidal with their own account, and Tidal keeps
blocking those accounts; Monochrome does not depend on one, so it is what plays
most of the time.

There is nothing to configure. The source list is a file in the repository
that an hourly probe keeps current, so a source that appears or comes back
reaches you within the hour without an update.

Report a broken source, or suggest a working one, at
[github.com/kipavy/spotube-plugin-lossless-sources/issues](https://github.com/kipavy/spotube-plugin-lossless-sources/issues).
""".trimIndent()
