# HiFi Tidal Audio — Spotube audio source plugin

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

So, concretely, as of **September 2026**:

- A Grateful Dead show, a jazz session, an old public-domain record → **plays**,
  in lossless FLAC.
- A mainstream studio track → **plays if someone uploaded that album to the
  Archive**, which is true more often than you would expect. Verified end to
  end: Daft Punk's *One More Time* resolves to
  `archive.org/download/discovery-daft-punk-2001-flac/01 One More Time.flac`
  and streams as `audio/flac`. Queen and the Grateful Dead resolve too.
- Anything nobody uploaded → **does not play**. Coverage is a lottery, not a
  catalogue: there is no guarantee for any given track, and user uploads can be
  taken down again without notice.

That is not a bug in this plugin, and reinstalling will not fix it. Every
public hifi-api instance signs into Tidal with its own shared account, and
Tidal has been blocking those accounts; upstream
([binimum/hifi-api#24](https://github.com/binimum/hifi-api/issues/24)) and
other projects built on them report the same outage. Searching still works on
those instances — catalogue reads need no account — which is why they look
alive while playing nothing.

**When a Tidal instance comes back, this plugin starts using it on its own**,
within the hour, with no update to install. That is what the source list below
is for.

## Install

1. Download `plugin.smplug` from [Releases](https://github.com/kipavy/spotube-plugin-hifi-tidal/releases)
2. Spotube → Settings → Plugins → Install plugin
3. Pick the downloaded file, then select **HiFi Tidal Audio** as your audio source

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
