"""
AJ extensions to the Snapcast provider (ajsnap fork) - API used by AJ Panel.

ajsnap/now_playing(player_id) -> what this player hears and WHO started it
ajsnap/panels()               -> all players of this provider with now-playing info

Music Assistant stores the user that started playback on the queue (queue_data.userid)
but does not expose it in the API; this makes it available to the panels (R5).
"""

from __future__ import annotations

from collections.abc import Callable
from typing import TYPE_CHECKING, Any

from music_assistant_models.auth import Scope

from music_assistant.helpers.provider_access import resolve_playback_user

if TYPE_CHECKING:
    from music_assistant.providers.ajsnap.provider import SnapCastProvider


def _source_label(active_source: str | None) -> str:
    s = (active_source or "").lower()
    if "airplay" in s:
        return "AirPlay"
    if "ariacast" in s:
        return "AriaCast"
    if "spotify" in s:
        return "Spotify Connect"
    return ""


def register_aj_api(prov: SnapCastProvider) -> list[Callable[[], None]]:
    """Register the AJ API commands; returns unregister callbacks."""
    mass = prov.mass

    async def now_playing(player_id: str) -> dict[str, Any] | None:
        player = mass.players.get_player(player_id)
        if player is None:
            return None
        queue = mass.player_queues.get_active_queue(player_id)
        info: dict[str, Any] = {
            "player_id": player_id,
            "queue_id": queue.queue_id if queue else None,
            "group_id": player.state.active_group or player.state.synced_to,
            "source": _source_label(player.state.active_source),
            "user_id": None,
            "username": None,
            "user_display_name": None,
        }
        if queue is not None:
            if user := await resolve_playback_user(mass, queue.queue_id):
                info["user_id"] = user.user_id
                info["username"] = user.username
                info["user_display_name"] = user.display_name or user.username
            if not info["source"] and queue.display_name:
                info["source"] = _source_label(queue.display_name)
        return info

    async def panels() -> list[dict[str, Any]]:
        result = []
        for player in mass.players.iter_players(False):
            if player.provider_id != prov.instance_id:
                continue
            if np := await now_playing(player.player_id):
                np["name"] = player.display_name
                result.append(np)
        return result

    return [
        mass.register_api_command("ajsnap/now_playing", now_playing, required_scope=Scope.PLAYERS_READ),
        mass.register_api_command("ajsnap/panels", panels, required_scope=Scope.PLAYERS_READ),
    ]
