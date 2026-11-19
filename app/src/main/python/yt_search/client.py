"""Search-only youtubei (InnerTube) client, tuned for speed.

Design (measured 2026-11-16 on the Muse VM):
- Discovery is separated from playback: this client does keyword search and
  continuation paging only. No cookies, no login state, WEB client identity.
- The warm path is a single POST. The public API key and the client version
  are bootstrapped from a page fetch once, persisted to a local state file,
  and reused until the server rejects them (then re-bootstrapped exactly
  once). The WEB key is stable and public; the client version rotates
  slowly, so per-search bootstrap is pure waste.
- One long-lived httpx AsyncClient with HTTP/2: the proxy CONNECT, the TLS
  handshake and the egress-CA validation are paid once, and concurrent
  searches multiplex over the same connection.
- The User-Agent is kept consistent with the protocol in use (HTTP/2):
  a Firefox UA over HTTP/1.1 is dropped by YouTube's edge, the same UA
  over HTTP/2 passes.
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
STATE_FILE = Path(__file__).resolve().parent.parent / ".bootstrap.json"
STATE_TTL_SECONDS = 24 * 3600
EGRESS_CA_BUNDLE = "/run/hatch/egress-tls/ca-bundle.pem"
SEARCH_URL = "https://www.youtube.com/youtubei/v1/search"
# Bootstrap source: an embed page carries the same INNERTUBE_API_KEY and a
# usable client version at ~138 KB / ~0.5 s, vs ~1.2 MB / ~1.3 s for a search
# results page (measured 2026-11-16). Its client version is accepted by the
# search endpoint with clientName WEB (verified).
BOOTSTRAP_URL = "https://www.youtube.com/embed/dQw4w9WgXcQ"


class BootstrapError(Exception):
    """The API key / client version could not be extracted from the page."""


class GateError(Exception):
    """The server gated the request (login required, consent, rate limit)."""


class SchemaError(Exception):
    """The response shape no longer matches the parser's expectations."""


@dataclass
class SearchItem:
    kind: str  # video | shorts | playlist | channel | other:<renderer>
    video_id: str | None = None
    title: str | None = None
    channel_name: str | None = None
    channel_id: str | None = None
    duration_text: str | None = None
    view_count_text: str | None = None
    published_text: str | None = None


@dataclass
class SearchPage:
    items: list[SearchItem] = field(default_factory=list)
    continuation: str | None = None
    estimated_results: int | None = None


def _text(node: dict | None) -> str | None:
    if not node:
        return None
    if "simpleText" in node:
        return node["simpleText"]
    runs = node.get("runs")
    if runs:
        return "".join(r.get("text", "") for r in runs)
    return None


def _parse_item(node: dict) -> SearchItem | None:
    if "videoRenderer" in node:
        v = node["videoRenderer"]
        owner = (v.get("ownerText") or {}).get("runs") or [{}]
        return SearchItem(
            kind="video",
            video_id=v.get("videoId"),
            title=_text(v.get("title")),
            channel_name=owner[0].get("text"),
            channel_id=(owner[0].get("navigationEndpoint") or {})
            .get("browseEndpoint", {})
            .get("browseId"),
            duration_text=_text(v.get("lengthText")),
            view_count_text=_text(v.get("viewCountText")),
            published_text=_text(v.get("publishedTimeText")),
        )
    if "reelItemRenderer" in node:
        v = node["reelItemRenderer"]
        return SearchItem(
            kind="shorts",
            video_id=v.get("videoId"),
            title=_text(v.get("headline")),
            view_count_text=_text(v.get("viewCountText")),
        )
    if "shortsLockupViewModel" in node:
        v = node["shortsLockupViewModel"]
        video_id = (
            (v.get("onTap") or {})
            .get("innertubeCommand", {})
            .get("reelWatchEndpoint", {})
            .get("videoId")
        )
        title = views = None
        acc = v.get("accessibilityText") or ""
        if acc.endswith(" - play Short"):
            acc = acc[: -len(" - play Short")]
            if ", " in acc:
                title, views = acc.rsplit(", ", 1)
            else:
                title = acc
        return SearchItem(
            kind="shorts", video_id=video_id, title=title, view_count_text=views
        )
    if "lockupViewModel" in node:
        v = node["lockupViewModel"]
        title = (
            (v.get("metadata") or {})
            .get("lockupMetadataViewModel", {})
            .get("title", {})
            .get("content")
        )
        ctype = (v.get("contentType") or "").replace("LOCKUP_CONTENT_TYPE_", "")
        return SearchItem(
            kind=ctype.lower() or "lockup",
            video_id=v.get("contentId"),
            title=title,
        )
    if "playlistRenderer" in node:
        v = node["playlistRenderer"]
        return SearchItem(
            kind="playlist",
            video_id=v.get("playlistId"),
            title=_text(v.get("title")),
        )
    if "channelRenderer" in node:
        v = node["channelRenderer"]
        return SearchItem(
            kind="channel",
            video_id=v.get("channelId"),
            title=_text(v.get("title")),
            channel_id=v.get("channelId"),
        )
    if node:
        return SearchItem(kind="other:" + next(iter(node)))
    return None


def _continuation_token(node: dict) -> str | None:
    cir = node.get("continuationItemRenderer")
    if not cir:
        return None
    return (
        (cir.get("continuationEndpoint") or {})
        .get("continuationCommand", {})
        .get("token")
    )


def _collect_items(container: dict, page: SearchPage) -> None:
    for section in container.get("contents", []):
        # The continuation token sits either as a sibling of the item
        # section (filtered searches) or inside it (unfiltered).
        token = _continuation_token(section)
        if token:
            page.continuation = token
            continue
        isr = section.get("itemSectionRenderer")
        if not isr:
            continue
        for node in isr.get("contents", []):
            token = _continuation_token(node)
            if token:
                page.continuation = token
                continue
            for leaf in _expand_shelf(node):
                item = _parse_item(leaf)
                if item is not None:
                    page.items.append(item)


def _expand_shelf(node: dict) -> list[dict]:
    """Shelves (Shorts etc.) nest their items one level down; expand them."""
    if "reelShelfRenderer" in node:
        return list(node["reelShelfRenderer"].get("items", []))
    if "gridShelfViewModel" in node:
        return list(node["gridShelfViewModel"].get("contents", []))
    if "richShelfRenderer" in node:
        out: list[dict] = []
        for sub in node["richShelfRenderer"].get("contents", []):
            out.extend(_expand_shelf(sub))
        return out
    return [node]


def parse_search_response(data: dict) -> SearchPage:
    page = SearchPage()
    est = data.get("estimatedResults")
    if est is not None:
        try:
            page.estimated_results = int(est)
        except (TypeError, ValueError):
            pass
    primary = (
        (data.get("contents") or {})
        .get("twoColumnSearchResultsRenderer", {})
        .get("primaryContents", {})
        .get("sectionListRenderer")
    )
    if primary:
        _collect_items(primary, page)
        return page
    # Continuation responses carry items under onResponseReceivedCommands.
    for cmd in data.get("onResponseReceivedCommands", []):
        action = cmd.get("appendContinuationItemsAction") or {}
        _collect_items({"contents": action.get("continuationItems", [])}, page)
    if not page.items and not page.continuation:
        raise SchemaError("no items and no continuation in response")
    return page


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


class YoutubeSearchClient:
    """Async search client. One instance = one warm HTTP/2 connection."""

    def __init__(self, hl: str = "en", gl: str = "US", state_file: Path = STATE_FILE):
        self.hl = hl
        self.gl = gl
        self.state_file = state_file
        self._api_key: str | None = None
        self._client_version: str | None = None
        self._client: httpx.AsyncClient | None = None
        self._bootstrap_lock = asyncio.Lock()

    async def __aenter__(self) -> "YoutubeSearchClient":
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
        return True

    async def _bootstrap(self) -> None:
        assert self._client is not None
        resp = await self._client.get(BOOTSTRAP_URL)
        resp.raise_for_status()
        key = re.search(r'"INNERTUBE_API_KEY":"([^"]+)"', resp.text)
        ver = re.search(r'"INNERTUBE_CLIENT_VERSION":"([^"]+)"', resp.text)
        if not key or not ver:
            raise BootstrapError("key/version not found in bootstrap page")
        self._api_key = key.group(1)
        self._client_version = ver.group(1)
        try:
            self.state_file.write_text(
                json.dumps(
                    {
                        "api_key": self._api_key,
                        "client_version": self._client_version,
                        "saved_at": time.time(),
                    }
                )
            )
        except OSError:
            pass  # state persistence is an optimisation, not a requirement

    async def _ensure_bootstrapped(self, force: bool = False) -> None:
        if self._api_key and not force:
            return
        async with self._bootstrap_lock:
            if self._api_key and not force:
                return
            if not force and self._load_state():
                return
            await self._bootstrap()

    # -- search ----------------------------------------------------------

    async def _post_search(self, payload_extra: dict) -> dict:
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
            **payload_extra,
        }
        resp = await self._client.post(
            f"{SEARCH_URL}?key={self._api_key}&prettyPrint=false", json=body
        )
        if resp.status_code == 429:
            raise GateError("rate limited (HTTP 429)")
        resp.raise_for_status()
        return resp.json()

    async def _search_payload(self, payload_extra: dict) -> SearchPage:
        await self._ensure_bootstrapped()
        try:
            data = await self._post_search(payload_extra)
        except httpx.HTTPStatusError as e:
            if e.response.status_code not in (400, 401, 403):
                raise
            # Stale key/version is the usual cause: re-bootstrap once, retry.
            await self._ensure_bootstrapped(force=True)
            data = await self._post_search(payload_extra)
        text = json.dumps(data)
        if '"LOGIN_REQUIRED"' in text or "Sign in to confirm" in text:
            raise GateError("login required by server")
        return parse_search_response(data)

    async def search(self, query: str, params: str | None = None) -> SearchPage:
        extra: dict = {"query": query}
        if params:
            extra["params"] = params
        return await self._search_payload(extra)

    async def next_page(self, continuation: str) -> SearchPage:
        if not continuation:
            raise ValueError("continuation token is empty")
        return await self._search_payload({"continuation": continuation})

    async def search_many(
        self, queries: list[str], concurrency: int = 5
    ) -> list[SearchPage]:
        sem = asyncio.Semaphore(concurrency)

        async def one(q: str) -> SearchPage:
            async with sem:
                return await self.search(q)

        return await asyncio.gather(*(one(q) for q in queries))
