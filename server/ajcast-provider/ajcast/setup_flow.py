"""Setup flow for the AJ Cast plugin.

AJ apps (AJ Panel Share, AJ Audio Client "Play my phone") always choose the room/group themselves
(players/cmd/select_source), so the "connected player" is OPTIONAL here: "No default" is offered and preselected.
A default player is only used by third-party AriaCast senders that cannot choose a room."""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from music_assistant.constants import CONF_ENTRY_WARN_PREVIEW
from music_assistant.helpers.config_entries import create_player_selector
from music_assistant_models.config_entries import ConfigValueOption
from music_assistant.models.setup_flow import SetupFlowError

from . import CONF_MASS_PLAYER_ID, NO_DEFAULT_PLAYER

if TYPE_CHECKING:
    from music_assistant.models.setup_flow import SetupSession


def _optional_player_selector(session: SetupSession, selected: Any) -> Any:
    """Player picker with a "No default" choice first (preselected)."""
    entry = create_player_selector(session.mass, CONF_MASS_PLAYER_ID, selected)
    entry.options = [
        ConfigValueOption(NO_DEFAULT_PLAYER, title="No default – AJ apps choose the room"),
        *(entry.options or []),
    ]
    if not selected or not any(o.value == selected for o in entry.options):
        entry.default_value = NO_DEFAULT_PLAYER
        entry.value = NO_DEFAULT_PLAYER
    return entry


async def run_setup(session: SetupSession) -> None:
    """
    Configure the player that receives AriaCast audio.

    :param session: The setup session driving the flow.
    """
    setup_data = dict(session.context.setup_data)
    errors: dict[str, str] | None = None
    while True:
        prefill: dict[str, Any] = {**session.context.values, **setup_data}
        values = await session.form(
            [
                CONF_ENTRY_WARN_PREVIEW,
                _optional_player_selector(session, prefill.get(CONF_MASS_PLAYER_ID)),
            ],
            step_id="user",
            errors=errors,
            last_step=True,
        )
        setup_data.update(values)
        try:
            await session.finish(setup_data)
            return
        except SetupFlowError as err:
            errors = {"base": err.translation_key or str(err)}
