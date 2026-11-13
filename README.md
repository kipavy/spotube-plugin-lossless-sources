# Lossless Sources — Spotube audio source plugin

An **audio source** for [Spotube](https://spotube.cc) 6. Spotube gets your
library, playlists and metadata from somewhere else (Spotify, MusicBrainz,
whatever you use); this plugin is asked one question per track — *where can I
stream this?* — and answers it with lossless FLAC from public sources.

**It asks you for nothing.** No account, no login, no API key, no settings
screen. Install it, select it as your audio plugin, done.

> [!WARNING]
> **Set Spotube's streaming format to FLAC:** Settings → Playback → streaming
> format → **FLAC**. On another format Spotube may pick the lower quality
> stream a hifi-api instance sometimes offers next to the FLAC one.

> [!IMPORTANT]
> **2.x is for Spotube 6 and is lossless only.** Spotube 6 replaced its plugin
> system and no longer lets a plugin use its built-in YouTube engine, so the
> YouTube fallback of 1.x is gone for now
> ([#5](https://github.com/kipavy/spotube-plugin-lossless-sources/issues/5)).
> A track that no lossless source has will not play while this plugin is
> selected; switch the audio plugin back to the built-in YouTube one for it.
>
> **Still on Spotube 5?** Use
> [1.3.1](https://github.com/kipavy/spotube-plugin-lossless-sources/releases/tag/1.3.1),
> the last version for Spotube 5. It keeps working from the same source list.

## What it can actually play, honestly

| Source | What is in it | State today |
|--------|---------------|-------------|
| **Monochrome** | The mainstream catalogue in FLAC, 16-bit/44.1kHz up to 24-bit/96kHz, served from Monochrome's own servers | 🟢 working |
| **hifi-api instances** | Tidal — the full mainstream catalogue, in FLAC or AAC 320 depending on the instance | 🔴 **every public instance is blocked** |

Sources are tried in that order, so you get the best available rather than the
first available:

1. **Monochrome** — lossless, ISRC-exact. Verified in October 2026 against
   chart pop, French rap, reggaeton, electronic and classic rock: every track
   tried was found and streamed as real FLAC, several of them 24-bit.
2. **Tidal**, if any hifi-api instance is alive — lossless, ISRC-exact.

**Why Monochrome works when hifi-api does not.** Every public hifi-api
instance signs into Tidal with its own shared account, and Tidal has been
blocking those accounts; upstream
([binimum/hifi-api#24](https://github.com/binimum/hifi-api/issues/24)) and
other projects built on them report the same outage. Searching still works on
those instances — catalogue reads need no account — which is why they look
alive while playing nothing. Monochrome moved off hifi-api in April 2026 and
now serves the files itself, so there is no shared account to block.

It is still one operator's free service, and could go away or change without
notice. That is what the self-updating source list below is for: when a
source dies, the plugin stops asking it within the hour, and when one appears
or comes back, the plugin starts using it within the hour — with no update to
install.

## Install

**In Spotube, no download needed.** Settings → Plugins → find **Lossless
Sources** among the available plugins → install. Spotube 6 lists every public
repository tagged `spotube-zipline-plugin`, and this is one of them.

Then pick it as your audio plugin and set the streaming format to FLAC.

Two other ways, if you prefer:

- **From a URL** — paste this repository's URL,
  `https://github.com/kipavy/spotube-plugin-lossless-sources`, into the
  plugin page's install field. Spotube resolves the latest release itself.
- **From a file** — download `lossless-sources.smplug` from
  [Releases](https://github.com/kipavy/spotube-plugin-lossless-sources/releases)
  and install it from the same page.

## Where the source list comes from

Nothing to configure and nothing to log into. On startup the plugin reads
[`sources.json`](sources.json) from this repository. A scheduled probe rewrites
that file **hourly**, keeping only the sources that served a real, decodable
stream — not merely the ones that answered a search. A source that comes back
therefore reaches everyone within the hour.

The probe does not only re-check a fixed list. Each run, before probing, it
looks for new hosts in two places and adds what it finds to `candidates`:

- **Public uptime trackers** for hifi-api instances
  ([this one](https://tidal-uptime.props-76styles.workers.dev), and a second
  one when it answers). Every instance they name, up or down, becomes a
  candidate, so a new instance is tried here without anyone adding it by hand.
- **Monochrome's own client source on GitHub.** The probe reads the API
  address Monochrome's web app uses, so if Monochrome moves its catalogue to a
  new host, the new host is probed and published on its own.

A discovered host is only *published* once it has served a real file, like
any other candidate. The published list is ordered by type — Monochrome, then
hifi-api — and, within a type, in candidate order.

The list is cached for six hours, so a normal start costs no request, and the
cached copy keeps playback alive when GitHub is unreachable. If nothing has
ever been fetched, the bundled defaults are used — Monochrome and two hifi-api
instances — so something answers.

To have a source considered, add it to `candidates` in `sources.json` or open
an issue. `sources` and `status` are written by the probe; editing them by hand
only lasts until the next run.

## Using your own instance

The public hifi-api instances are all blocked, and the projects still
maintained publish none — they are built to be self-hosted. If you run your
own ([ez-hifi-api](https://github.com/itenai/ez-hifi-api),
[tidal-workers](https://github.com/dev-x64/tidal-workers)), you can point a
copy of this plugin at it without installing a toolchain, because CI builds
the package for you:

1. Fork this repository.
2. Add your host to `candidates` in [`sources.json`](sources.json) — not to
   `sources`. The hourly probe checks that it really streams and promotes it
   itself, so a typo fails loudly instead of silently breaking playback.
3. Point `REMOTE_URL` in
   [`SourceStore.kt`](lossless_sources/src/commonMain/kotlin/io/github/kipavy/lossless_sources/core/SourceStore.kt)
   at your fork's `sources.json`, and `LATEST_RELEASE_URL` in
   [`RealCoreAPI.kt`](lossless_sources/src/commonMain/kotlin/io/github/kipavy/lossless_sources/core/RealCoreAPI.kt)
   at your fork's releases.
4. Push. The build workflow produces `lossless-sources.smplug`; run it from the
   Actions tab to publish a release.
5. In Spotube, install from your fork's repository URL.

## How it works

| Step | Behaviour |
|------|-----------|
| `getStreamsByTrack` | Asks each source in list order. Searches "title artists", then the title alone |
| Filtering | Results that are neither the track's ISRC nor credited to one of its artists are dropped (covers come back next to the original) |
| Length check | A match more than 15 s or 10 % off the track's length is another version (extended mix, live take) and is skipped. A match with the track's own ISRC fails only when it is off by a factor of two |
| Stream check | The best match's stream is resolved before answering. A source that has the track but serves nothing hands over to the next source |
| Failover | Within a source, hosts are tried in order; a sleeping or blocked one falls through to the next |
| `getStreamsOfAudioSource` | Spotube saves each match and later asks for its streams again. The id (`<source>:<id>:<duration ms>`) is handed back to its source; if that fails, Spotube matches the track again |

**Monochrome** searches `/search/tracks?q=<title artists>`, keeps results that
have a file behind them, and ranks an exact **ISRC** hit first. The stream is
`/track/<id>` — the FLAC file itself, so there is nothing to decode and no
link that expires.

- **Searching by ISRC alone finds nothing**, but every result carries one, so
  the exact recording is still picked rather than guessed from the title.
- **The host sits behind Cloudflare and answers a transient `521` now and
  then** — measured at two file requests in fourteen, both fine on retry. The
  player streams the URL itself, so the plugin checks the file with a `HEAD`
  first, retrying after 1, 2 and 4 seconds, then moves to the next host.

**hifi-api** searches `/search/?s=<title artists>`, ranks an exact **ISRC** hit
first, then reads `/track/` for each quality and decodes the manifest.

- **The stream URL is not in the response.** `/track/` returns a base64
  `manifest`. Only the `application/vnd.tidal.bts` variant decodes to JSON
  holding a direct CDN link — the Hi-Res variant is an inline DASH manifest
  with no URL to hand the player, so it is skipped.
- **`202`** means all of an instance's playback accounts are busy: retried on
  the same host after 2 s, 4 s and 8 s, then the next host.

## Quality

From Monochrome: the file as it is stored, 16-bit/44.1kHz up to
24-bit/96kHz depending on the release. The host labels every file
`application/octet-stream` and sends no quality fields, so the plugin works
the sample rate out from the file's size and duration, reporting 96 kHz only
when the average bitrate is beyond what 24-bit/44.1kHz PCM can produce, so it
never overstates.

From a hifi-api instance: whatever tier that instance's Tidal account allows. A
lower-tier account returns AAC 320 even when lossless is requested; the plugin
reports it as such.

## Adding a source

A source is one class implementing `LosslessSource` (`search` and `streams`)
in `lossless_sources/src/commonMain/.../audio/`, registered in
`RealAudioAPI`'s source list, plus a probe in `tools/probe_sources.py` (and a
place in its `TYPE_ORDER`).

Unknown types in `sources.json` are ignored rather than fatal, so a new source
can be published before every installed plugin understands it.

## When it breaks

| Response | Meaning |
|----------|---------|
| Monochrome `521` that does not clear | Monochrome's origin is down; playback falls through to the next source |
| hifi-api `Upstream API error` | The instance's Tidal session is dead |
| hifi-api `Token refresh failed: 403` | Same, at the auth step |
| hifi-api search works but playback fails | Catalogue reads are unauthenticated; `/track/` is not |

When every lossless source is down there is nothing to fix on this side — the
probe will publish one as soon as it exists. `tools/probe_sources.py` runs the
same checks locally if you want to test a host before adding it to
`candidates` (`--no-discovery` probes only the listed candidates).

## Build

A Kotlin Multiplatform project built with Gradle and
[Zipline](https://github.com/cashapp/zipline), from Spotube's
[plugin template](https://github.com/team-spotube/spotube-plugin-template).
Needs JDK 21; the wrapper fetches everything else.

```bash
./gradlew :lossless_sources:jvmTest                 # tests against fake hosts
./gradlew :lossless_sources:packageProductionPlugin # -> lossless_sources/build/distributions/plugin-production.smplug
```

The tests run the real routing code against fake Monochrome and hifi-api hosts:
covers and other versions rejected, a source that finds the track but cannot
serve it handing over to the next, `202` retries, hosts that throw, the source
list cache, and the update check.

CI does the same on every push and pull request. Run the **Plugin Build**
workflow manually to publish a release; it is tagged with the `version` in
[`lossless_sources/build.gradle.kts`](lossless_sources/build.gradle.kts).

## License

MIT
