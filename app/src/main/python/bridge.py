"""Synchronous JSON bridge between Kotlin (via Chaquopy) and the three
youtubei clients (yt_search, yt_captions, yt_channel).

Every public function takes plain arguments and returns a JSON string:
  success: {"ok": true, "data": ...}
  failure: {"ok": false, "error": {"type": "<ErrorClass>", "message": "..."}}

The clients are async; each call runs its own event loop and its own client
instance. Bootstrap state (API key / client version / STS) is persisted per
client under the app files dir, which Kotlin passes to init() — the clients'
default .bootstrap.json location is not writable on Android.
"""

from __future__ import annotations

import asyncio
import json
from dataclasses import asdict, is_dataclass
from datetime import datetime
from pathlib import Path

_FILES_DIR: Path | None = None


def init(files_dir: str) -> str:
    global _FILES_DIR
    _FILES_DIR = Path(files_dir)
    _FILES_DIR.mkdir(parents=True, exist_ok=True)
    return json.dumps({"ok": True, "data": {"files_dir": str(_FILES_DIR)}})


def _state(name: str) -> Path:
    assert _FILES_DIR is not None, "bridge.init() not called"
    return _FILES_DIR / f"{name}.bootstrap.json"


def _encode(obj):
    if is_dataclass(obj):
        return {k: _encode(v) for k, v in asdict(obj).items()}
    if isinstance(obj, datetime):
        return obj.isoformat()
    if isinstance(obj, dict):
        return {k: _encode(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_encode(v) for v in obj]
    return obj


def _ok(data) -> str:
    return json.dumps({"ok": True, "data": _encode(data)}, ensure_ascii=False)


def _err(e: Exception) -> str:
    return json.dumps(
        {"ok": False, "error": {"type": type(e).__name__, "message": str(e)}},
        ensure_ascii=False,
    )


def _run(coro_fn) -> str:
    try:
        return _ok(asyncio.run(coro_fn()))
    except Exception as e:  # noqa: BLE001 - mapped to JSON for Kotlin
        return _err(e)


# -- search -----------------------------------------------------------------

def _videos_only(items):
    """Shorts exclusion: yt_search tags Shorts as kind == "shorts"
    (reelItemRenderer / shortsLockupViewModel). Only plain videos pass;
    playlists, channels and lockups are dropped here too."""
    return [i for i in items if i.kind == "video"]


def search(query: str) -> str:
    async def go():
        from yt_search import YoutubeSearchClient

        async with YoutubeSearchClient(
            hl="ja", gl="JP", state_file=_state("yt_search")
        ) as c:
            page = await c.search(query)
            return {
                "items": _videos_only(page.items),
                "continuation": page.continuation,
                "estimated_results": page.estimated_results,
            }

    return _run(go)


def search_next(continuation: str) -> str:
    async def go():
        from yt_search import YoutubeSearchClient

        async with YoutubeSearchClient(
            hl="ja", gl="JP", state_file=_state("yt_search")
        ) as c:
            page = await c.next_page(continuation)
            return {"items": _videos_only(page.items), "continuation": page.continuation}

    return _run(go)


# -- channel ----------------------------------------------------------------

def channel_videos(channel_id: str, sort: str = "latest") -> str:
    async def go():
        from yt_channel import YoutubeChannelClient

        async with YoutubeChannelClient(
            hl="ja", gl="JP", state_file=_state("yt_channel")
        ) as c:
            page = await c.videos(channel_id, sort=sort)
            items = []
            for item in page.items:
                if item.kind == "shorts":
                    continue
                d = _encode(item)
                # Browse items carry no channel identity of their own;
                # stamp it so rows can show the channel name.
                d["channel_id"] = channel_id
                if page.channel is not None and page.channel.name:
                    d["channel_name"] = page.channel.name
                items.append(d)
            return {
                "items": items,
                "continuation": page.continuation,
                "channel": page.channel,
            }

    return _run(go)


def channel_info(channel_id: str) -> str:
    async def go():
        from yt_channel import YoutubeChannelClient

        async with YoutubeChannelClient(
            hl="ja", gl="JP", state_file=_state("yt_channel")
        ) as c:
            return await c.channel_info(channel_id)

    return _run(go)


def resolve_handle(handle: str) -> str:
    async def go():
        from yt_channel import YoutubeChannelClient

        async with YoutubeChannelClient(
            hl="ja", gl="JP", state_file=_state("yt_channel")
        ) as c:
            return {"channel_id": await c.resolve_handle(handle)}

    return _run(go)


def feeds(channel_ids_json: str) -> str:
    """Parallel feed batch. Returns a flat item list sorted newest first,
    each item carrying its channel name, plus per-channel error types."""

    async def go():
        from yt_channel import YoutubeChannelClient

        ids = json.loads(channel_ids_json)
        async with YoutubeChannelClient(
            hl="ja", gl="JP", state_file=_state("yt_channel")
        ) as c:
            feed_map, errors = await c.feeds(ids)
            items = []
            for cid, feed in feed_map.items():
                for item in feed.items:
                    # Shorts exclusion: the Atom feed marks Shorts via
                    # their /shorts/<id> alternate link (FeedItem.is_shorts).
                    if item.is_shorts:
                        continue
                    d = _encode(item)
                    d["channel_id"] = cid
                    d["channel_name"] = feed.channel_name
                    items.append(d)
            items.sort(key=lambda d: d.get("published_at") or "", reverse=True)
            return {
                "items": items,
                "errors": {cid: type(e).__name__ for cid, e in errors.items()},
            }

    return _run(go)


# -- captions ---------------------------------------------------------------

def caption_tracks(video_id: str) -> str:
    async def go():
        from yt_captions import YoutubeCaptionsClient

        async with YoutubeCaptionsClient(
            hl="ja", gl="JP", state_file=_state("yt_captions")
        ) as c:
            tl = await c.tracks(video_id)
            return {
                "video_id": tl.video_id,
                "tracks": [
                    {
                        "language_code": t.language_code,
                        "kind": t.kind,
                        "is_asr": t.is_asr,
                        "name": t.name,
                        "is_translatable": t.is_translatable,
                    }
                    for t in tl.tracks
                ],
                "translation_languages": tl.translation_languages,
            }

    return _run(go)


def fetch_subtitle(video_id: str, language_code: str, kind: str = "") -> str:
    """Fetch one track's body. The track list is re-issued inside the same
    run because caption baseUrls are short-lived."""

    async def go():
        from yt_captions import YoutubeCaptionsClient

        want_asr = kind == "asr"
        async with YoutubeCaptionsClient(
            hl="ja", gl="JP", state_file=_state("yt_captions")
        ) as c:
            tl = await c.tracks(video_id)
            track = next(
                (
                    t
                    for t in tl.tracks
                    if t.language_code == language_code and t.is_asr == want_asr
                ),
                None,
            )
            if track is None:
                from yt_captions import NoCaptionsError

                raise NoCaptionsError(
                    f"track {language_code} (asr={want_asr}) not found for {video_id}"
                )
            sub = await c.fetch(track)
            return {
                "language_code": sub.language_code,
                "kind": sub.kind,
                "segments": sub.segments,
            }

    return _run(go)


def get_subtitle(video_id: str, languages_json: str) -> str:
    async def go():
        from yt_captions import YoutubeCaptionsClient

        languages = json.loads(languages_json)
        async with YoutubeCaptionsClient(
            hl="ja", gl="JP", state_file=_state("yt_captions")
        ) as c:
            sub = await c.get_subtitle(video_id, languages)
            return {
                "language_code": sub.language_code,
                "kind": sub.kind,
                "segments": sub.segments,
            }

    return _run(go)
