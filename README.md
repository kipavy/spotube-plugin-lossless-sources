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
| **hifi-api instances** | Tidal — the full mainstream catalogue, in FLAC or AAC 320 depending on the instance | 🔴 **every public instance is blocked** |
| **Internet Archive** | Live recordings, public-domain material, and studio albums people have uploaded — all in FLAC | 🟢 working, and cannot be blocked |
| **YouTube** | Everything else — the mainstream catalogue, in Opus or AAC | 🟢 working, but **lossy** (~128–160 kbps) |

Sources are tried in that order, so you get the best available rather than the
first available:

1. **Tidal**, if any instance is alive — lossless, ISRC-exact.
2. **The Internet Archive** — lossless FLAC. Verified: Daft Punk's *One More
   Time* resolves to
   `archive.org/download/discovery-daft-punk-2001-flac/01 One More Time.flac`
   and streams as `audio/flac`. Coverage is a lottery, though — it depends on
   someone having uploaded that album, and uploads can be taken down.
3. **YouTube**, if neither had it — not lossless, but it has essentially
   everything, so a track plays instead of failing.

So today's Top 40 plays. It plays in FLAC when the Archive happens to hold the
album, and in YouTube's Opus or AAC otherwise. The plugin never silently
downgrades without telling you: each stream reports its real codec and bitrate,
which Spotube shows.

That is not a bug in this plugin, and reinstalling will not fix it. Every
public hifi-api instance signs into Tidal with its own shared account, and
Tidal has been blocking those accounts; upstream
([binimum/hifi-api#24](https://github.com/binimum/hifi-api/issues/24)) and
other projects built on them report the same outage. Searching still works on
those instances — catalogue reads need no account — which is why they look
alive while playing nothing.

**When a Tidal instance comes back, this plugin starts using it on its own**,
within the hour, with no update to install — and lossless silently takes over
from YouTube again. That is what the source list below is for.

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

> **If nothing plays at all, check your streaming format first.** Spotube picks
> a container preset by index and then keeps only the streams whose container
> matches it, with no fallback — so a mismatch fails silently for every track.
> `mp4` is listed first here because that is what the Tidal instances return.
> Settings → Playback → streaming format/quality.

## Where the source list comes from

Nothing to configure and nothing to log into. On startup the plugin reads
[`sources.json`](sources.json) from this repository. A scheduled probe rewrites
that file **hourly**, keeping only the sources that served a real, decodable
stream — not merely the ones that answered a search. A source that comes back
therefore reaches everyone within the hour.

Order in that file is preference order: the router asks each source in turn and
takes the first with a match, so mainstream proxies sit above the Archive.

The list is cached for six hours, so a normal start costs no request, and the
cached copy keeps playback alive when GitHub is unreachable. If nothing has
ever been fetched, the bundled defaults are used — which always include the
Archive, so something answers.

To have a source considered, add it to `candidates` in `sources.json` or open
an issue. `sources` and `status` are written by the probe; editing them by hand
only lasts until the next run.

## Using your own instance

Every public hifi-api instance is blocked, and the projects still maintained
publish none — they are built to be self-hosted. If you run your own
([ez-hifi-api](https://github.com/itenai/ez-hifi-api),
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
would be a lie on every install. Upstream issue pending.

## How it works

| Step | Behaviour |
|------|-----------|
| `matches()` | Asks each source in list order, stops at the first with results |
| `streams()` | Reads the source prefix off the match id and hands it back to that source |
| Failover | Within a source, hosts are tried in order; a sleeping or blocked one falls through to the next |
| `202` | A hifi-api instance's playback accounts are all busy — retried on the same host after 2s, 4s, 8s, then the next host |

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

**YouTube** uses the engine Spotube already ships, so there is no host, no key
and nothing to probe — it is bundled last in the source order and always
available. The ISRC is searched first when the metadata provider supplied one,
because it names one exact recording; otherwise it is title and artists. Audio
tracks below 64 kbps are discarded.

**Internet Archive** indexes items — whole albums and concerts — not songs, so
searching for a track title finds nothing. The plugin searches by performer
first, opens the most-downloaded items and reads their file lists for a FLAC
whose title or filename matches the track. If that finds nothing it retries as
free text over artist and title, which catches uploads that left `creator` as
the uploader's name. The URL it returns is the file itself: nothing to resolve,
nothing to expire, and no account that can be blocked.

## Quality

From the Archive: whatever the uploader posted, usually 16-bit/44.1kHz FLAC —
vinyl rips and 24-bit uploads exist too, and are reported as 16/44.1.

From a hifi-api instance: whatever tier that instance's Tidal account allows. A
lower-tier account returns AAC 320 even when lossless is requested. The plugin
reports each stream's real codec and bitrate, so a Hi-Fi tier instance would
expose FLAC without any change here.

## Adding a source

A source is one file in `src/sources/` exposing `matches(track, query, bases)`
and `streams(match, reference, bases)`, plus an entry in the router's
`sourceFor` and a probe in `tools/probe_sources.py`. Match ids carry their
source as a prefix (`archive:<item>|<file>`), which is how `streams()` gets
handed back to the source that produced the match.

Unknown types in `sources.json` are ignored rather than fatal, so a new source
can be published before every installed plugin understands it.

## When it breaks

| Response | Meaning |
|----------|---------|
| `Upstream API error` | The instance's Tidal session is dead |
| `Token refresh failed: 403` | Same, at the auth step |
| Search works but playback fails | Catalogue reads are unauthenticated; `/track/` is not |

A match that cannot produce a stream no longer costs you the track. The router
still stops at the first source that has a match — a blocked instance answers
the catalogue perfectly, so it usually wins — but a match that comes back with
no streams, or with lossless alone while Spotube is asking for `mp4`, is backed
by the same recording from YouTube. Spotube keeps only the streams whose
container matches the selected preset and reduces over them, which throws on an
empty list rather than falling back, so this is what stands between a blocked
instance and silence.

When every instance is down there is nothing to fix on this side — the probe
will publish one as soon as it exists, and the Archive keeps answering
meanwhile. `tools/probe_sources.py` runs the same checks locally if you want to
test a host before adding it to `candidates`.

One rough edge worth knowing: if the very first start has no cached list *and*
cannot reach GitHub, that lookup fails rather than falling back. Dio throws on
connection errors, Hetu has no try/catch, and its Future binding exposes only
`then`, so there is nowhere to catch it. Any later start uses the cache.

## Build

Requires the Dart SDK and `hetu_script_dev_tools`:

```bash
dart pub global activate hetu_script_dev_tools
make          # compiles src/plugin.ht -> build/plugin.out
make test     # runs the bytecode against fake sources
make archive  # packages -> build/plugin.smplug
```

`make test` runs the compiled plugin on plain Dart against fake hifi-api and
Archive servers — one dead, one that answers `202` twice before serving audio —
with the same `LocalStorage` and `SpotubeForm` bindings Spotube provides. It
exists because Hetu resolves identifiers at runtime: undefined names, a binding
that wants `List<String>`, or a client that throws instead of returning a
status all compile perfectly and only fail once a user presses play.

`harness/bin/smoke_archive.dart` is a manual check against the real
archive.org; it needs the network, so it is not part of `make test`.

CI does the same on every push; run the **Plugin Build** workflow manually with
a version to publish a release.

## License

MIT
