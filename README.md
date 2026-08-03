# HiFi Tidal Audio — Spotube audio source plugin

Plays Tidal audio in [Spotube](https://spotube.cc) through public
[hifi-api](https://github.com/uimaxbai/hifi-api) instances. Your library and
playlists stay with whatever metadata plugin you use; this plugin only supplies
the audio.

## Install

1. Download `plugin.smplug` from [Releases](https://github.com/kipavy/spotube-plugin-hifi-tidal/releases)
2. Spotube → Settings → Plugins → Install plugin
3. Pick the downloaded file, then select **HiFi Tidal Audio** as your audio source

## How it works

| Step | Behaviour |
|------|-----------|
| `matches()` | Searches `/search/?s=<title artists>` and ranks an exact **ISRC** hit first |
| `streams()` | Reads `/track/`, decodes the base64 manifest, returns each distinct CDN URL |
| Failover | Endpoints are tried in order; a sleeping or blocked host falls through to the next |

Two details worth knowing, because they are not obvious from the API:

- **The stream URL is not in the response.** `/track/` returns a base64
  `manifest`. Only the `application/vnd.tidal.bts` variant decodes to JSON
  holding a direct CDN link — the Hi-Res variant is a DASH manifest that a plain
  audio element cannot play, so it is skipped rather than handed to Spotube.
- **ISRC makes matching exact.** Spotube passes the ISRC from your metadata
  provider and hifi-api returns one per search result, so when both are present
  the correct recording is picked instead of guessed from the title. That
  matters for remasters, radio edits and live versions, which otherwise look
  identical by name.

## Quality

Streams come back at whatever tier the instance's Tidal account allows. The
bundled instances currently run on a lower tier and return **AAC 320** even when
lossless is requested. The plugin advertises FLAC and lossy presets, and reports
each stream's real codec and bitrate, so a Hi-Fi tier instance would expose FLAC
without any change here.

## When it breaks

These are third-party proxies signing into Tidal with their own accounts, and
Tidal blocks those accounts regularly. Symptoms:

| Response | Meaning |
|----------|---------|
| `Upstream API error` | The instance's Tidal session is dead |
| `Token refresh failed: 403` | Same, at the auth step |
| Search works but playback fails | Catalog reads are unauthenticated; `/track/` is not |

When every instance is down, add a working one to `ENDPOINTS` in
`src/segments/audio_source.ht` and rebuild, or open an issue.

## Build

Requires the Dart SDK and `hetu_script_dev_tools`:

```bash
dart pub global activate hetu_script_dev_tools
make          # compiles src/plugin.ht -> build/plugin.out
make archive  # packages -> build/plugin.smplug
```

CI does the same on every push; run the **Plugin Build** workflow manually with a
version to publish a release.

## License

MIT
