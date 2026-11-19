"""Captions-only youtubei (InnerTube) client, on the player POST route.

Design (measured 2026-11-16 on the Muse VM):
- Responsibility is captions only: given a videoId, return the caption
  track list and subtitle bodies. No search, no playback, no media URLs,
  no cookies or login state. The only contract with the search client is
  the videoId; session and bootstrap are deliberately not shared, because
  the player surface has its own gate profile.
- The player response carries the track list
  (captions.playerCaptionsTracklistRenderer.captionTracks), and the list
  is issued only when playabilityStatus is OK — a failed playability
  check strips captions together with streamingData. So this client must
  pass the same gate as playback: WEB client plus a correct
  signatureTimestamp (STS).
- Bootstrap: the public API key, the client version and the STS are taken
  from one watch-page GET (the embed page carries no STS) and persisted
  to a local state file. The STS is a property of the current player
  build, not of the video, so it is reused across videos and refreshed
  only when the server rejects it. The two rejection faces are explicit:
  UNPLAYABLE "The page needs to be reloaded." means the STS went stale,
  and HTTP 400/401/403 means the key/version went stale. Either triggers
  exactly one re-bootstrap and one retry.
- A warm player POST is ~69 KB / ~0.14 s, vs ~1.5 MB / ~1.5 s for the
  watch page that embeds the same response — hence POST as the main
  route and the page GET only as bootstrap / fallback.
- Track baseUrls are session-bound and short-lived: they are fetched
  promptly and never cached; a stale one is replaced by re-running the
  player POST, which is cheap.
- Auto-translation is NOT offered: the response lists translation
  languages and marks tracks translatable, but fetching a track with
  &tlang= returns HTTP 429 from this egress while unauthenticated
  (verified for both manual and ASR tracks, with and without fmt).
  The track list still exposes translation_languages as information;
  this client simply does not promise translated bodies.
"""

from __future__ import annotations

import asyncio
import json
import os
import re
import time
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import quote, urlparse

import httpx

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"
)
STATE_FILE = Path(__file__).resolve().parent.parent / ".captions-bootstrap.json"
STATE_TTL_SECONDS = 24 * 3600
EGRESS_CA_BUNDLE = "/run/hatch/egress-tls/ca-bundle.pem"
PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"
# Bootstrap source: any watch page carries the key, the client version and
# the STS in one document. This video is only the bootstrap donor; the STS
# it yields is a player-build constant and is reused for every video.
BOOTSTRAP_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
RELOAD_REASON = "The page needs to be reloaded."


class BootstrapError(Exception):
    """The API key / client version / STS could not be extracted."""


class GateError(Exception):
    """The server gated the request (login required, rate limit)."""


class UnavailableError(Exception):
    """The video itself cannot be played (age, region, private, deleted)."""

    def __init__(self, status: str, reason: str | None):
        super().__init__(f"{status}: {reason}")
        self.status = status
        self.reason = reason


class SchemaError(Exception):
    """The response shape no longer matches the parser's expectations."""


class NoCaptionsError(Exception):
    """No track matches the request and no translation can substitute."""


@dataclass
class CaptionTrack:
    language_code: str
    kind: str | None  # None = manual, "asr" = auto-generated
    name: str | None
    base_url: str
    is_translatable: bool = False

    @property
    def is_asr(self) -> bool:
        return self.kind == "asr"


@dataclass
class CaptionTrackList:
    video_id: str
    tracks: list[CaptionTrack] = field(default_factory=list)
    translation_languages: list[str] = field(default_factory=list)


@dataclass
class SubtitleSegment:
    start_ms: int
    duration_ms: int
    text: str


@dataclass
class Subtitle:
    language_code: str
    kind: str | None
    segments: list[SubtitleSegment] = field(default_factory=list)
    raw: str | None = None  # body text when fmt is not json3


def _text(node: dict | None) -> str | None:
    if not node:
        return None
    if "simpleText" in node:
        return node["simpleText"]
    runs = node.get("runs")
    if runs:
        return "".join(r.get("text", "") for r in runs)
    return None


def parse_track_list(video_id: str, data: dict) -> CaptionTrackList:
    """Parse the captions block of a player response (status already OK)."""
    result = CaptionTrackList(video_id=video_id)
    renderer = (
        (data.get("captions") or {}).get("playerCaptionsTracklistRenderer")
        or {}
    )
    for t in renderer.get("captionTracks", []):
        base_url = t.get("baseUrl")
        language = t.get("languageCode")
        if not base_url or not language:
            continue
        result.tracks.append(
            CaptionTrack(
                language_code=language,
                kind=t.get("kind"),
                name=_text(t.get("name")),
                base_url=base_url,
                is_translatable=bool(t.get("isTranslatable")),
            )
        )
    for lang in renderer.get("translationLanguages", []):
        code = lang.get("languageCode")
        if code:
            result.translation_languages.append(code)
    return result


def parse_json3(body: str) -> list[SubtitleSegment]:
    """Normalise a fmt=json3 timedtext body into segments."""
    try:
        data = json.loads(body)
    except ValueError as e:
        raise SchemaError(f"timedtext body is not json3: {e}") from e
    segments: list[SubtitleSegment] = []
    for event in data.get("events", []):
        segs = event.get("segs")
        if not segs:
            continue  # window-style / metadata events carry no text
        text = "".join(s.get("utf8", "") for s in segs).strip()
        if not text:
            continue
        segments.append(
            SubtitleSegment(
                start_ms=int(event.get("tStartMs", 0)),
                duration_ms=int(event.get("dDurationMs", 0)),
                text=text,
            )
        )
    return segments


def _proxy_from_env() -> str | None:
    raw = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
    if not raw:
        return None
    u = urlparse(raw)
    if u.username or u.password:
        # httpx/httpcore mishandle some userinfo forms; re-encode explicitly.
        return (
            f"{u.scheme}://{quote(u.username or '', safe='')}:"
            f"{quote(u.password or '', safe='')}@{u.hostname}:{u.port}"
        )
    return raw


class YoutubeCaptionsClient:
    """Async captions client. One instance = one warm HTTP/2 connection."""

    def __init__(self, hl: str = "en", gl: str = "US", state_file: Path = STATE_FILE):
        self.hl = hl
        self.gl = gl
        self.state_file = state_file
        self._api_key: str | None = None
        self._client_version: str | None = None
        self._sts: int | None = None
        self._client: httpx.AsyncClient | None = None
        self._bootstrap_lock = asyncio.Lock()

    async def __aenter__(self) -> "YoutubeCaptionsClient":
        verify = EGRESS_CA_BUNDLE if Path(EGRESS_CA_BUNDLE).exists() else True
        self._client = httpx.AsyncClient(
            headers={"User-Agent": USER_AGENT},
            timeout=httpx.Timeout(20.0, connect=5.0),
            follow_redirects=True,
            proxy=_proxy_from_env(),
            trust_env=False,
            verify=verify,
            http2=True,
            limits=httpx.Limits(max_connections=4, max_keepalive_connections=4),
        )
        return self

    async def __aexit__(self, *exc) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    # -- bootstrap -------------------------------------------------------

    def _load_state(self) -> bool:
        try:
            state = json.loads(self.state_file.read_text())
        except (OSError, ValueError):
            return False
        if time.time() - state.get("saved_at", 0) > STATE_TTL_SECONDS:
            return False
        self._api_key = state["api_key"]
        self._client_version = state["client_version"]
        self._sts = int(state["sts"])
        return True

    async def _bootstrap(self) -> None:
        assert self._client is not None
        resp = await self._client.get(BOOTSTRAP_URL)
        resp.raise_for_status()
        html = resp.text
        key = re.search(r'"INNERTUBE_API_KEY":"([^"]+)"', html)
        ver = re.search(r'"INNERTUBE_CLIENT_VERSION":"([^"]+)"', html)
        sts = re.search(r'"STS":(\d+)', html)
        if not key or not ver or not sts:
            raise BootstrapError("key/version/STS not found in bootstrap page")
        self._api_key = key.group(1)
        self._client_version = ver.group(1)
        self._sts = int(sts.group(1))
        try:
            self.state_file.write_text(
                json.dumps(
                    {
                        "api_key": self._api_key,
                        "client_version": self._client_version,
                        "sts": self._sts,
                        "saved_at": time.time(),
                    }
                )
            )
        except OSError:
            pass  # state persistence is an optimisation, not a requirement

    async def _ensure_bootstrapped(self, force: bool = False) -> None:
        if self._api_key and self._sts and not force:
            return
        async with self._bootstrap_lock:
            if self._api_key and self._sts and not force:
                return
            if not force and self._load_state():
                return
            await self._bootstrap()

    # -- player ----------------------------------------------------------

    async def _post_player(self, video_id: str) -> dict:
        assert self._client is not None
        body = {
            "context": {
                "client": {
                    "clientName": "WEB",
                    "clientVersion": self._client_version,
                    "hl": self.hl,
                    "gl": self.gl,
                }
            },
            "videoId": video_id,
            "playbackContext": {
                "contentPlaybackContext": {"signatureTimestamp": self._sts}
            },
        }
        resp = await self._client.post(
            f"{PLAYER_URL}?key={self._api_key}&prettyPrint=false", json=body
        )
        if resp.status_code == 429:
            raise GateError("rate limited (HTTP 429)")
        resp.raise_for_status()
        return resp.json()

    async def _player_response(self, video_id: str) -> dict:
        """Player response with playability OK, or the matching error."""
        await self._ensure_bootstrapped()
        refreshed = False
        while True:
            try:
                data = await self._post_player(video_id)
            except httpx.HTTPStatusError as e:
                if e.response.status_code not in (400, 401, 403) or refreshed:
                    raise
                refreshed = True
                await self._ensure_bootstrapped(force=True)
                continue
            ps = data.get("playabilityStatus")
            if ps is None:
                raise SchemaError("no playabilityStatus in player response")
            status = ps.get("status")
            reason = ps.get("reason")
            if status == "OK":
                return data
            if status == "LOGIN_REQUIRED":
                raise GateError(f"login required by server: {reason}")
            if status == "UNPLAYABLE" and reason == RELOAD_REASON and not refreshed:
                # Stale STS: re-bootstrap once and retry.
                refreshed = True
                await self._ensure_bootstrapped(force=True)
                continue
            raise UnavailableError(status or "UNKNOWN", reason)

    # -- public API --------------------------------------------------------

    async def tracks(self, video_id: str) -> CaptionTrackList:
        """The caption track list; empty when the video has no captions."""
        data = await self._player_response(video_id)
        return parse_track_list(video_id, data)

    async def fetch(
        self,
        track: CaptionTrack,
        fmt: str = "json3",
    ) -> Subtitle:
        """Fetch one track's body from its (short-lived) baseUrl."""
        assert self._client is not None
        url = track.base_url + f"&fmt={fmt}"
        resp = await self._client.get(url)
        if resp.status_code == 429:
            raise GateError("rate limited (HTTP 429)")
        resp.raise_for_status()
        if fmt == "json3":
            return Subtitle(
                language_code=track.language_code,
                kind=track.kind,
                segments=parse_json3(resp.text),
            )
        return Subtitle(
            language_code=track.language_code,
            kind=track.kind,
            raw=resp.text,
        )

    async def get_subtitle(
        self,
        video_id: str,
        languages: list[str],
        fmt: str = "json3",
    ) -> Subtitle:
        """Pick one subtitle by language preference.

        For each preferred language, in order: a manual track wins over an
        auto-generated one. Raises NoCaptionsError when no preferred
        language has a track. (Auto-translation is not used as a fallback:
        &tlang= fetches are gated with HTTP 429 while unauthenticated —
        see the module docstring.)
        """
        track_list = await self.tracks(video_id)
        for lang in languages:
            manual = next(
                (t for t in track_list.tracks
                 if t.language_code == lang and not t.is_asr),
                None,
            )
            if manual is not None:
                return await self.fetch(manual, fmt=fmt)
            asr = next(
                (t for t in track_list.tracks
                 if t.language_code == lang and t.is_asr),
                None,
            )
            if asr is not None:
                return await self.fetch(asr, fmt=fmt)
        raise NoCaptionsError(
            f"no captions for {video_id} in {languages} "
            f"(available: {[t.language_code for t in track_list.tracks]})"
        )
