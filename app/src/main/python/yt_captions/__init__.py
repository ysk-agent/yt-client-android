"""Captions-only youtubei client (player POST route)."""

from .client import (
    BootstrapError,
    CaptionTrack,
    CaptionTrackList,
    GateError,
    NoCaptionsError,
    SchemaError,
    Subtitle,
    SubtitleSegment,
    UnavailableError,
    YoutubeCaptionsClient,
)

__all__ = [
    "BootstrapError",
    "CaptionTrack",
    "CaptionTrackList",
    "GateError",
    "NoCaptionsError",
    "SchemaError",
    "Subtitle",
    "SubtitleSegment",
    "UnavailableError",
    "YoutubeCaptionsClient",
]
