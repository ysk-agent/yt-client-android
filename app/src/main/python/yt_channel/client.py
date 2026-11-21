"""Channel client: a channel's video listings, two routes.

Design (measured 2026-11-16 on the Muse VM):
- Browse route (catalogue): POST youtubei/v1/browse with the channel ID
  and a tab selector. 30 items per page, continuation paging, and three
  sorts (Latest / Popular / Oldest) implemented by the server as chips
  whose tap targets are themselves continuation tokens. No
  signatureTimestamp is needed here — the search client's key/version
  bootstrap suffices — but this client keeps its own bootstrap and
  session anyway, so the three clients fail independently.
- Feed route (new arrivals): GET youtube.com/feeds/videos.xml, the
  channel's public RSS. No key, no bootstrap, tolerant of parallel
  fetching. Latest 15 uploads only, Shorts mixed in, but with exact
  published timestamps, exact view counts and the video description —
  metadata the browse grid only carries in compact/relative form or
  not at all.
- Transport quirk of the Muse VM egress (measured 2026-11-16): on a
  single HTTP/2 connection, parallel requests are served strictly one
  at a time (~0.12-0.14 s apart, a clean staircase), while HTTP/1.1
  over a pool of persistent connections genuinely overlaps. That
  serialisation is a property of the VM's egress gateway, NOT of
  YouTube or of direct connections: re-measured 2026-11-21 on the Mac
  (direct residential egress), HTTP/2 multiplexing fetched the
  156-channel 経済 group in 0.20 s warm / 0.30 s cold against
  0.68-0.77 s for the HTTP/1.1 pool, and raising HTTP/1.1 concurrency
  beyond 32 made it slower (1.3-2.3 s). The feed route therefore uses
  HTTP/2 by default (one connection, all streams in parallel — also
  the right shape for the Android app, which connects directly);
  pass feed_http2=False when running behind the VM egress.
- The channel grid renders in the lockup format (richItemRenderer >
  lockupViewModel; Shorts use shortsLockupViewModel). View counts are
  compact text ("145K") and publish times relative ("2w ago") there;
  that coarseness is a property of the source, not of this parser.
- The browse response also carries channel-level data (name, handle,
  subscriber and video counts, description, keywords); it is parsed
  into ChannelInfo and attached to the first page.
- Thumbnail URLs are built from the videoId
  (i.ytimg.com/vi/<id>/hqdefault.jpg), not taken from the response's
  signed URLs: the pattern is stable and unsigned, and hqdefault
  effectively always exists.
"""

from __future__ import annotations

import asyncio
import json
import os
import re
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from urllib.parse import quote, urlparse

import httpx

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"
)
STATE_FILE = Path(__file__).resolve().parent.parent / ".channel-bootstrap.json"
STATE_TTL_SECONDS = 24 * 3600
EGRESS_CA_BUNDLE = "/run/hatch/egress-tls/ca-bundle.pem"
BROWSE_URL = "https://www.youtube.com/youtubei/v1/browse"
FEED_URL = "https://www.youtube.com/feeds/videos.xml?channel_id={}"
BOOTSTRAP_URL = "https://www.youtube.com/embed/dQw4w9WgXcQ"
# Tab selectors (protobuf constants, identical for every channel;
# extracted from a live channel page and verified 2026-11-16).
TAB_PARAMS = {
    "videos": "EgZ2aWRlb3PyBgQKAjoA",
    "shorts": "EgZzaG9ydHPyBgUKA5oBAA==",
    "streams": "EgdzdHJlYW1z8gYECgJ6AA==",
}
SORTS = ("latest", "popular", "oldest")
ATOM = {"atom": "http://www.w3.org/2005/Atom",
        "yt": "http://www.youtube.com/xml/schemas/2015",
        "media": "http://search.yahoo.com/mrss/"}


class BootstrapError(Exception):
    """The API key / client version could not be extracted from the page."""


class GateError(Exception):
    """The server gated the request (rate limit)."""


class SchemaError(Exception):
    """The response shape no longer matches the parser's expectations."""


class ChannelNotFoundError(Exception):
    """The channel ID (or handle) does not exist."""


def thumbnail_url(video_id: str, size: str = "hqdefault") -> str:
    return f"https://i.ytimg.com/vi/{video_id}/{size}.jpg"


@dataclass
class ChannelVideo:
    video_id: str
    kind: str  # video | shorts | other:<contentType>
    title: str | None = None
    duration_text: str | None = None
    view_count_text: str | None = None
    published_text: str | None = None

    @property
    def is_shorts(self) -> bool:
        return self.kind == "shorts"

    @property
    def thumbnail_url(self) -> str:
        return thumbnail_url(self.video_id)


@dataclass
class ChannelInfo:
    channel_id: str
    name: str | None = None
    handle: str | None = None
    description: str | None = None
    subscriber_count_text: str | None = None
    video_count_text: str | None = None
    keywords: str | None = None


@dataclass
class ChannelPage:
    items: list[ChannelVideo] = field(default_factory=list)
    continuation: str | None = None
    channel: ChannelInfo | None = None
    sort: str | None = None
    # Sort chip tokens seen on this page (label -> continuation token).
    chip_tokens: dict = field(default_factory=dict, repr=False)


@dataclass
class FeedItem:
    video_id: str
    title: str | None = None
    published_at: datetime | None = None
    updated_at: datetime | None = None
    view_count: int | None = None
    description: str | None = None
    is_shorts: bool = False

    @property
    def thumbnail_url(self) -> str:
        return thumbnail_url(self.video_id)


@dataclass
class ChannelFeed:
    channel_id: str
    channel_name: str | None = None
    items: list[FeedItem] = field(default_factory=list)


# -- generic JSON walking ---------------------------------------------------


def _walk(node, out: list) -> None:
    if isinstance(node, dict):
        out.append(node)
        for v in node.values():
            _walk(v, out)
    elif isinstance(node, list):
        for v in node:
            _walk(v, out)


def _text(node: dict | None) -> str | None:
    if not node:
        return None
    if "simpleText" in node:
        return node["simpleText"]
    runs = node.get("runs")
    if runs:
        return "".join(r.get("text", "") for r in runs)
    return None


# -- browse parsing -----------------------------------------------------------


def _parse_lockup(vm: dict) -> ChannelVideo | None:
    video_id = vm.get("contentId")
    if not video_id:
        return None
    ctype = (vm.get("contentType") or "").replace("LOCKUP_CONTENT_TYPE_", "")
    meta = (vm.get("metadata") or {}).get("lockupMetadataViewModel") or {}
    title = (meta.get("title") or {}).get("content")
    parts: list[str] = []
    inner = (meta.get("metadata") or {}).get("contentMetadataViewModel") or {}
    for row in inner.get("metadataRows", []):
        for p in row.get("metadataParts", []):
            content = (p.get("text") or {}).get("content")
            if content:
                parts.append(content)
    duration = None
    nodes: list[dict] = []
    _walk(vm, nodes)
    for n in nodes:
        overlay = n.get("thumbnailBottomOverlayViewModel") or {}
        for badge in overlay.get("badges", []):
            text = (badge.get("thumbnailBadgeViewModel") or {}).get("text")
            if text and re.fullmatch(r"[\d:]+", text):
                duration = text
    return ChannelVideo(
        video_id=video_id,
        kind=ctype.lower() or "video",
        title=title,
        duration_text=duration,
        view_count_text=parts[0] if parts else None,
        published_text=parts[1] if len(parts) > 1 else None,
    )


def _parse_shorts_lockup(vm: dict) -> ChannelVideo | None:
    video_id = (
        (vm.get("onTap") or {})
        .get("innertubeCommand", {})
        .get("reelWatchEndpoint", {})
        .get("videoId")
    )
    if not video_id:
        return None
    title = views = None
    acc = vm.get("accessibilityText") or ""
    if acc.endswith(" - play Short"):
        acc = acc[: -len(" - play Short")]
        if ", " in acc:
            title, views = acc.rsplit(", ", 1)
        else:
            title = acc
    return ChannelVideo(
        video_id=video_id, kind="shorts", title=title, view_count_text=views
    )


def _parse_grid_contents(contents: list, page: ChannelPage) -> None:
    for node in contents:
        if not isinstance(node, dict):
            continue
        cir = node.get("continuationItemRenderer")
        if cir:
            token = (
                (cir.get("continuationEndpoint") or {})
                .get("continuationCommand", {})
                .get("token")
            )
            if token:
                page.continuation = token
            continue
        content = (node.get("richItemRenderer") or {}).get("content") or {}
        item = None
        if "lockupViewModel" in content:
            item = _parse_lockup(content["lockupViewModel"])
        elif "shortsLockupViewModel" in content:
            item = _parse_shorts_lockup(content["shortsLockupViewModel"])
        elif "videoRenderer" in content:  # older channels / other tabs
            v = content["videoRenderer"]
            item = ChannelVideo(
                video_id=v.get("videoId"),
                kind="video",
                title=_text(v.get("title")),
                duration_text=_text(v.get("lengthText")),
                view_count_text=_text(v.get("viewCountText")),
                published_text=_text(v.get("publishedTimeText")),
            )
        if item is not None and item.video_id:
            page.items.append(item)


def _parse_chips(grid: dict, page: ChannelPage) -> None:
    nodes: list[dict] = []
    _walk(grid, nodes)
    for n in nodes:
        chip = n.get("chipViewModel")
        if not chip:
            continue
        label = (chip.get("text") or "").lower()
        token = (
            ((chip.get("tapCommand") or {}).get("innertubeCommand") or {})
            .get("continuationCommand", {})
            .get("token")
        )
        if label in SORTS and token:
            page.chip_tokens[label] = token


def _parse_channel_info(data: dict) -> ChannelInfo | None:
    nodes: list[dict] = []
    _walk(data, nodes)
    info: ChannelInfo | None = None
    for n in nodes:
        cm = n.get("channelMetadataRenderer")
        if cm:
            vanity = cm.get("vanityChannelUrl") or ""
            info = ChannelInfo(
                channel_id=cm.get("externalId") or "",
                name=cm.get("title"),
                handle=vanity.rstrip("/").rsplit("/", 1)[-1] or None,
                description=cm.get("description"),
                keywords=cm.get("keywords"),
            )
            break
    if info is None:
        return None
    for n in nodes:
        header = n.get("pageHeaderViewModel")
        if not header:
            continue
        inner: list[dict] = []
        _walk(header, inner)
        for m in inner:
            cmv = m.get("contentMetadataViewModel")
            if not cmv:
                continue
            for row in cmv.get("metadataRows", []):
                for p in row.get("metadataParts", []):
                    content = (p.get("text") or {}).get("content") or ""
                    if "subscriber" in content:
                        info.subscriber_count_text = content
                    elif re.fullmatch(r"[\d.,KM]+ videos", content):
                        info.video_count_text = content
        break
    return info


def parse_browse_page(data: dict) -> ChannelPage:
    page = ChannelPage()
    nodes: list[dict] = []
    _walk(data, nodes)
    grid = next((n["richGridRenderer"] for n in nodes if "richGridRenderer" in n), None)
    if grid is not None:
        _parse_grid_contents(grid.get("contents", []), page)
        _parse_chips(grid, page)
    # Continuation responses carry items in an action instead of a grid:
    # paging appends, sort-chip switches reload the grid's contents.
    for n in nodes:
        for key in ("appendContinuationItemsAction",
                    "reloadContinuationItemsCommand"):
            action = n.get(key)
            if action:
                _parse_grid_contents(action.get("continuationItems", []), page)
    page.channel = _parse_channel_info(data)
    return page


# -- feed parsing -------------------------------------------------------------


def parse_feed(channel_id: str, xml_text: str) -> ChannelFeed:
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as e:
        raise SchemaError(f"feed is not valid XML: {e}") from e
    feed = ChannelFeed(
        channel_id=channel_id,
        channel_name=root.findtext("atom:title", namespaces=ATOM),
    )
    for entry in root.findall("atom:entry", ATOM):
        video_id = entry.findtext("yt:videoId", namespaces=ATOM)
        if not video_id:
            continue
        group = entry.find("media:group", ATOM)
        description = thumbnail = None
        view_count = None
        if group is not None:
            description = group.findtext("media:description", namespaces=ATOM)
            stats = group.find("media:community/media:statistics", ATOM)
            if stats is not None and stats.get("views"):
                try:
                    view_count = int(stats.get("views"))
                except ValueError:
                    pass

        def _dt(tag: str) -> datetime | None:
            raw = entry.findtext(f"atom:{tag}", namespaces=ATOM)
            if not raw:
                return None
            try:
                return datetime.fromisoformat(raw)
            except ValueError:
                return None

        # Shorts are marked in the feed itself: their alternate link is
        # /shorts/<id> instead of /watch?v=<id>.
        link_el = entry.find("atom:link", ATOM)
        link_href = link_el.get("href") if link_el is not None else None
        feed.items.append(
            FeedItem(
                video_id=video_id,
                title=entry.findtext("atom:title", namespaces=ATOM),
                published_at=_dt("published"),
                updated_at=_dt("updated"),
                view_count=view_count,
                description=description,
                is_shorts=bool(link_href and "/shorts/" in link_href),
            )
        )
    return feed


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


class YoutubeChannelClient:
    """Async channel client. One instance = one warm HTTP/2 connection."""

    def __init__(
        self,
        hl: str = "en",
        gl: str = "US",
        state_file: Path = STATE_FILE,
        feed_http2: bool = True,
    ):
        self.hl = hl
        self.gl = gl
        self.state_file = state_file
        self.feed_http2 = feed_http2
        self._api_key: str | None = None
        self._client_version: str | None = None
        self._client: httpx.AsyncClient | None = None
        self._feed_client: httpx.AsyncClient | None = None
        self._bootstrap_lock = asyncio.Lock()

    @staticmethod
    def _make_client(http2: bool, max_connections: int) -> httpx.AsyncClient:
        verify = EGRESS_CA_BUNDLE if Path(EGRESS_CA_BUNDLE).exists() else True
        return httpx.AsyncClient(
            headers={"User-Agent": USER_AGENT},
            timeout=httpx.Timeout(20.0, connect=5.0),
            follow_redirects=True,
            proxy=_proxy_from_env(),
            trust_env=False,
            verify=verify,
            http2=http2,
            limits=httpx.Limits(
                max_connections=max_connections,
                max_keepalive_connections=max_connections,
            ),
        )

    async def __aenter__(self) -> "YoutubeChannelClient":
        self._client = self._make_client(http2=True, max_connections=8)
        # Feeds: HTTP/2 multiplexed over a small pool by default — one
        # TLS handshake and every stream in parallel (see module
        # docstring). feed_http2=False falls back to the old HTTP/1.1
        # wide pool for the VM-egress route, which serialises HTTP/2.
        if self.feed_http2:
            self._feed_client = self._make_client(http2=True, max_connections=8)
        else:
            self._feed_client = self._make_client(http2=False, max_connections=40)
        return self

    async def __aexit__(self, *exc) -> None:
        for attr in ("_client", "_feed_client"):
            client = getattr(self, attr)
            if client is not None:
                await client.aclose()
                setattr(self, attr, None)

    # -- bootstrap (browse route only; the feed needs none) ---------------

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

    # -- browse -------------------------------------------------------------

    async def _post_browse(self, payload_extra: dict) -> dict:
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
            f"{BROWSE_URL}?key={self._api_key}&prettyPrint=false", json=body
        )
        if resp.status_code == 429:
            raise GateError("rate limited (HTTP 429)")
        if resp.status_code == 404:
            raise ChannelNotFoundError("browse target not found (HTTP 404)")
        resp.raise_for_status()
        return resp.json()

    async def _browse(self, payload_extra: dict) -> dict:
        await self._ensure_bootstrapped()
        try:
            return await self._post_browse(payload_extra)
        except httpx.HTTPStatusError as e:
            if e.response.status_code not in (400, 401, 403):
                raise
            await self._ensure_bootstrapped(force=True)
            return await self._post_browse(payload_extra)

    async def _tab_page(self, channel_id: str, tab: str, sort: str) -> ChannelPage:
        if sort not in SORTS:
            raise ValueError(f"sort must be one of {SORTS}, got {sort!r}")
        data = await self._browse(
            {"browseId": channel_id, "params": TAB_PARAMS[tab]}
        )
        page = parse_browse_page(data)
        if not page.items and page.channel is None:
            raise ChannelNotFoundError(f"no such channel: {channel_id}")
        if sort != "latest":
            token = page.chip_tokens.get(sort)
            if token is None:
                raise SchemaError(f"sort chip {sort!r} not in browse response")
            sorted_page = parse_browse_page(
                await self._browse({"continuation": token})
            )
            sorted_page.channel = page.channel
            page = sorted_page
        page.sort = sort
        return page

    async def videos(self, channel_id: str, sort: str = "latest") -> ChannelPage:
        return await self._tab_page(channel_id, "videos", sort)

    async def shorts(self, channel_id: str, sort: str = "latest") -> ChannelPage:
        return await self._tab_page(channel_id, "shorts", sort)

    async def streams(self, channel_id: str, sort: str = "latest") -> ChannelPage:
        return await self._tab_page(channel_id, "streams", sort)

    async def next_page(self, continuation: str) -> ChannelPage:
        if not continuation:
            raise ValueError("continuation token is empty")
        return parse_browse_page(await self._browse({"continuation": continuation}))

    async def videos_all(
        self, channel_id: str, sort: str = "latest", limit: int | None = None
    ) -> list[ChannelVideo]:
        page = await self.videos(channel_id, sort=sort)
        items = list(page.items)
        while page.continuation and (limit is None or len(items) < limit):
            page = await self.next_page(page.continuation)
            items.extend(page.items)
        return items[:limit] if limit is not None else items

    async def channel_info(self, channel_id: str) -> ChannelInfo:
        page = await self.videos(channel_id)
        if page.channel is None:
            raise SchemaError("no channel metadata in browse response")
        return page.channel

    async def resolve_handle(self, handle: str) -> str:
        """Resolve an @handle to a channel ID via one page GET."""
        assert self._client is not None
        slug = handle if handle.startswith("@") else f"@{handle}"
        resp = await self._client.get(f"https://www.youtube.com/{slug}")
        if resp.status_code == 404:
            raise ChannelNotFoundError(f"no such handle: {handle}")
        resp.raise_for_status()
        m = re.search(r'"externalId":"(UC[\w-]{22})"', resp.text)
        if not m:
            m = re.search(r'/channel/(UC[\w-]{22})', resp.text)
        if not m:
            raise SchemaError("channel ID not found on handle page")
        return m.group(1)

    # -- feed -----------------------------------------------------------------

    async def feed(self, channel_id: str) -> ChannelFeed:
        assert self._feed_client is not None
        resp = await self._feed_client.get(FEED_URL.format(channel_id))
        if resp.status_code == 404:
            raise ChannelNotFoundError(f"no feed for channel: {channel_id}")
        if resp.status_code == 429:
            raise GateError("rate limited (HTTP 429)")
        resp.raise_for_status()
        return parse_feed(channel_id, resp.text)

    async def feeds(
        self, channel_ids: list[str], concurrency: int = 32
    ) -> tuple[dict[str, ChannelFeed], dict[str, Exception]]:
        """Fetch many channels' feeds in parallel.

        Returns (feeds, errors): one channel's failure never fails the
        batch, which is what polling many channels needs.
        """
        sem = asyncio.Semaphore(concurrency)
        feeds: dict[str, ChannelFeed] = {}
        errors: dict[str, Exception] = {}

        async def one(cid: str) -> None:
            async with sem:
                try:
                    feeds[cid] = await self.feed(cid)
                except Exception as e:  # noqa: BLE001 - collected per channel
                    errors[cid] = e

        await asyncio.gather(*(one(cid) for cid in channel_ids))
        return feeds, errors
