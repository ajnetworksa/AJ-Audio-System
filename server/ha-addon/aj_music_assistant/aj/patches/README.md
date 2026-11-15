# patches/

Core Music Assistant patches, applied in name order by `install_ajsnap.sh` with `patch -p1`
from the `site-packages` directory (paths start with `music_assistant/...`).

Currently **none** – the "who is playing" info (R5) is served by our provider (`ajsnap/now_playing`)
without touching MA core. Prefer provider-side code; add a patch only when there is no other way.

Make a patch: edit a copy of MA source, then
`diff -u a/music_assistant/x.py b/music_assistant/x.py > patches/010-short-name.patch`
