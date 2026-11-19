# YT Client (Android)

A standalone, cinematic streaming-style Android UI over three in-house
Python YouTube clients, embedded with Chaquopy:

- `yt_search` — keyword search + continuation paging (youtubei search)
- `yt_channel` — channel videos/shorts via browse, latest uploads via the
  RSS feed with a parallel batch (`feeds()`)
- `yt_captions` — caption track lists and subtitle bodies via the WEB
  player endpoint

Kotlin calls a small Python bridge (`app/src/main/python/bridge.py`) that
exposes synchronous JSON-in/JSON-out functions and runs the async clients
with `asyncio.run`. Bootstrap state files live in the app files dir.

Screens: home (search, hero, group tiles, new-feed row), search results
with scroll paging, video detail (caption tracks + subtitle text), and a
channel screen (info header, videos and shorts rows).

Playback is out of scope: the WEB client returns no direct media URLs, so
the Play button navigates to the detail screen.

## Build

Gradle builds run in GitHub Actions (never on the dev VM). The pinned
signing key lives in repo secrets; the fixed APK name is `yt-client.apk`.
The emulator launch workflow installs the APK on an API 35 emulator,
launches every activity, and captures screenshots.
