# Lossless Sources — Spotube audio source plugin

An **audio source** for [Spotube](https://spotube.cc). Spotube gets your
library, playlists and metadata from somewhere else (Spotify, MusicBrainz,
whatever you use); this plugin is asked one question per track — *where can I
stream this?* — and answers it from public sources.

**It asks you for nothing.** No account, no login, no API key, no settings
screen. Install it, select it as your audio source, done.

## What it can actually play, honestly

| Source | What is in it | State today |
|--------|---------------|-------------|
| **Monochrome** | The mainstream catalogue in FLAC, 16-bit/44.1kHz up to 24-bit/96kHz, served from Monochrome's own servers | 🟢 working |
| **hifi-api instances** | Tidal — the full mainstream catalogue, in FLAC or AAC 320 depending on the instance | 🔴 **every public instance is blocked** |
| **YouTube** | Everything else, in Opus or AAC | 🟢 working, but **lossy** (~128–160 kbps) |

Sources are tried in that order, so you get the best available rather than the
first available:

1. **Monochrome** — lossless, ISRC-exact. Verified in October 2026 against
   chart pop, French rap, reggaeton, electronic and classic rock: every track
   tried was found and streamed as real FLAC, several of them 24-bit.
2. **Tidal**, if any hifi-api instance is alive — lossless, ISRC-exact.
3. **YouTube**, if neither had it — not lossless, but it has essentially
   everything, so a track plays instead of failing.

The plugin never silently downgrades without telling you: each stream reports
its real codec and bitrate, which Spotube shows.

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

**In Spotube, no download needed.** Settings → *Metadata provider plugins* →
find **Lossless Sources** under *Available plugins* → install. Spotube lists
every public repository tagged `spotube-plugin`, and this is one of them.

Then pick it as your audio source.

Two other ways, if you prefer:

- **From a URL** — paste this repository's URL,
  `https://github.com/kipavy/spotube-plugin-lossless-sources`, into the text
  field on that page and press the grey download button. Spotube resolves the
  latest release itself.
- **From a file** — download `plugin.smplug` from
  [Releases](https://github.com/kipavy/spotube-plugin-lossless-sources/releases)
  and use the orange upload button beside the same field.

> **For lossless, set the streaming format to `flac`.** Spotube picks a
> container preset by index and then keeps only the streams whose container
> matches it, with no fallback. `mp4` is listed first so a fresh install
> always plays something — every lossless match is also offered as the same
> recording from YouTube in `mp4` — but on that preset you get the YouTube
> copy. Settings → Playback → streaming format/quality.

> **If nothing plays at all, switch Spotube's YouTube engine.** Every track
> that no lossless source has, and every track on the `mp4` preset, reaches
> you through YouTube, and this plugin does not fetch YouTube itself — it asks
> the engine Spotube ships. YouTube breaks those engines one at a time, so one
> can fail while another still works. If the official *YouTube Audio* plugin
> plays nothing either, that is the cause: Settings → Playback → YouTube
> Engine, and pick a different one — NewPipe if YouTubeExplode is selected,
> and the other way round. On desktop, yt-dlp is the one you can update
> yourself (`yt-dlp -U`), so it recovers first.

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
hifi-api — and, within a type, in candidate order. The router asks each source
in turn and takes the first with a match.

The list is cached for six hours, so a normal start costs no request, and the
cached copy keeps playback alive when GitHub is unreachable. If nothing has
ever been fetched, the bundled defaults are used — Monochrome, two hifi-api
instances and YouTube — so something answers.

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
3. Point `REMOTE_URL` in [`src/segments/sources.ht`](src/segments/sources.ht)
   at your fork's `sources.json`, so your copy reads your list rather than
   this one.
4. Push. The build workflow produces `plugin.smplug`; run it from the Actions
   tab with a version to cut a release.
5. In Spotube, install from your fork's repository URL.

There is deliberately no settings field for this. Spotube's only way to show a
plugin form is the `authentication` ability, which also puts a permanent
"Plugin requires authentication" warning and a **Login** button on the plugin
card — for a plugin whose whole point is that it asks you for nothing, that
would be a lie on every install. Tracked upstream as
[KRTirtho/spotube#3120](https://github.com/KRTirtho/spotube/issues/3120).

## How it works

| Step | Behaviour |
|------|-----------|
| `matches()` | Asks each source in list order, stops at the first with results |
| Length check | A match more than 15 s or 10 % off the track's length is another version (extended mix, live take) and is skipped, so the next source is asked. A match with the track's own ISRC fails only when it is off by a factor of two. On YouTube, the last resort, a wrong length moves the match down instead of dropping it |
| `streams()` | Reads the source prefix off the match id and hands it back to that source |
| Failover | Within a source, hosts are tried in order; a sleeping or blocked one falls through to the next |
| Lossless-only match | The same recording from YouTube is appended, so every container preset has something to play |
| Unknown source prefix | A match cached from a source this version no longer has is played from YouTube by its title and artists |

**Monochrome** searches `/search/tracks?q=<title artists>`, keeps results that
have a file behind them, and ranks an exact **ISRC** hit first. The stream is
`/track/<id>` — the FLAC file itself, so there is nothing to decode and no
link that expires. Three details worth knowing:

- **Searching by ISRC alone finds nothing**, but every result carries one, so
  the exact recording is still picked rather than guessed from the title.
- **Covers come back next to the original.** Asking for *Weird Fishes* returns
  Radiohead, the Noordpool Orchestra and Lianne La Havas, so anything that is
  neither the ISRC hit nor credited to one of the track's artists is dropped.
- **The host sits behind Cloudflare and answers a transient `521` now and
  then** — measured at two file requests in fourteen, both fine on retry.
  Spotube streams the URL itself and never retries, so the plugin checks the
  file with a `HEAD` first, retrying after 1, 2 and 4 seconds, then moves to
  the next host.

**hifi-api** searches `/search/?s=<title artists>`, ranks an exact **ISRC** hit
first, then reads `/track/` and decodes the manifest. Two details worth knowing,
because they are not obvious from the API:

- **The stream URL is not in the response.** `/track/` returns a base64
  `manifest`. Only the `application/vnd.tidal.bts` variant decodes to JSON
  holding a direct CDN link — the Hi-Res variant is a DASH manifest that a plain
  audio element cannot play, so it is skipped rather than handed to Spotube.
- **ISRC makes matching exact.** Spotube passes the ISRC from your metadata
  provider and hifi-api returns one per search result, so when both are present
  the correct recording is picked instead of guessed from the title. That
  matters for remasters, radio edits and live versions, which otherwise look
  identical by name.

| Response | Meaning |
|----------|---------|
| `202` | All of an instance's playback accounts are busy — retried on the same host after 2s, 4s, 8s, then the next host |

**YouTube** uses the engine Spotube already ships, so there is no host, no key
and nothing to probe — it is bundled last in the source order and always
available. The ISRC is searched first when the metadata provider supplied one,
because it names one exact recording; otherwise it is title and artists. Audio
tracks below 64 kbps are discarded.

## Quality

From Monochrome: the file as it is stored, 16-bit/44.1kHz up to
24-bit/96kHz depending on the release. The host labels every file
`application/octet-stream` and sends no quality fields, so the plugin works
the quality out from the file's size and duration: the bitrate it reports is
the file's real average, and it reports 24-bit only when that average is
beyond what 16-bit/44.1kHz PCM can produce, so it never overstates.

From a hifi-api instance: whatever tier that instance's Tidal account allows. A
lower-tier account returns AAC 320 even when lossless is requested. The plugin
reports each stream's real codec and bitrate, so a Hi-Fi tier instance would
expose FLAC without any change here.

## Adding a source

A source is one file in `src/sources/` exposing `matches(track, query, bases)`
and `streams(match, reference, bases)`, plus an entry in the router's
`sourceFor` and a probe in `tools/probe_sources.py` (and a place in its
`TYPE_ORDER`). Match ids carry their source as a prefix
(`monochrome:<id>`), which is how `streams()` gets handed back to the source
that produced the match.

Unknown types in `sources.json` are ignored rather than fatal, so a new source
can be published before every installed plugin understands it.

## When it breaks

| Response | Meaning |
|----------|---------|
| Monochrome `521` that does not clear | Monochrome's origin is down; playback falls through to the next source |
| hifi-api `Upstream API error` | The instance's Tidal session is dead |
| hifi-api `Token refresh failed: 403` | Same, at the auth step |
| hifi-api search works but playback fails | Catalogue reads are unauthenticated; `/track/` is not |

A match that cannot produce a stream does not cost you the track. The router
still stops at the first source that has a match — a blocked instance answers
the catalogue perfectly, so it can win — but a match that comes back with no
streams, or with lossless alone while Spotube is asking for `mp4`, is backed by
the same recording from YouTube. Spotube keeps only the streams whose
container matches the selected preset and reduces over them, which throws on an
empty list rather than falling back, so this is what stands between a blocked
source and silence.

When every lossless source is down there is nothing to fix on this side — the
probe will publish one as soon as it exists, and YouTube keeps answering
meanwhile. `tools/probe_sources.py` runs the same checks locally if you want to
test a host before adding it to `candidates` (`--no-discovery` probes only the
listed candidates).

One rough edge worth knowing: if the very first start has no cached list *and*
cannot reach GitHub, that lookup fails rather than falling back. Dio throws on
connection errors, Hetu has no try/catch, and its Future binding exposes only
`then`, so there is nowhere to catch it. Any later start uses the cache.

## Build

Requires the Dart SDK and `hetu_script_dev_tools` 0.1.0+2 — later versions
compile hetu_script 0.6 bytecode, which Spotube's 0.4.2 runtime cannot load:

```bash
dart pub global activate hetu_script_dev_tools 0.1.0+2
make          # compiles src/plugin.ht -> build/plugin.out
make test     # runs the bytecode against fake sources
make archive  # packages -> build/plugin.smplug
```

`make test` runs the compiled plugin on plain Dart against fake hifi-api and
Monochrome servers — a dead instance, one that answers `202` twice before
serving audio, and a Monochrome host that answers `521` twice before serving
the file — with the same `LocalStorage` and `SpotubeForm` bindings Spotube
provides. It exists because Hetu resolves identifiers at runtime: undefined
names, a binding that wants `List<String>`, or a client that throws instead of
returning a status all compile perfectly and only fail once a user presses
play.

CI does the same on every push; run the **Plugin Build** workflow manually with
a version to publish a release.

## License

MIT
