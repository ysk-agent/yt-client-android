"""Channel client: browse listings + RSS feed for a channel's videos."""

from .client import (
    BootstrapError,
    ChannelFeed,
    ChannelInfo,
    ChannelNotFoundError,
    ChannelPage,
    ChannelVideo,
    FeedItem,
    GateError,
    SchemaError,
    YoutubeChannelClient,
    thumbnail_url,
)

__all__ = [
    "BootstrapError",
    "ChannelFeed",
    "ChannelInfo",
    "ChannelNotFoundError",
    "ChannelPage",
    "ChannelVideo",
    "FeedItem",
    "GateError",
    "SchemaError",
    "YoutubeChannelClient",
    "thumbnail_url",
]
