# AJ Music Assistant – Home Assistant add-on

Music Assistant **2.10.9** (official image) + our **AJ Snap** provider for the wall panels.
Same features as the official add-on: sign in with Home Assistant, sidebar panel, auto-discovery.

## 1. Add our repository (once)
1. Home Assistant → **Settings → Add-ons → Add-on store**.
2. **⋮** (top right) → **Repositories** → paste `https://github.com/ajnetworksa/AJ-Audio-System` → **Add** → Close.
3. Refresh the page (⋮ → **Check for updates**). A section **AJ Audio System** appears with **AJ Music Assistant**.

> The GitHub repo must be **public** (or HA can't read it).

## 2. Install
1. Open **AJ Music Assistant** → **Install**. HA builds it on the Pi (first time 5–15 min: it downloads the official MA image and adds our files).
2. **Don't start it yet** if the official Music Assistant add-on is running – both use port 8095/1704.

## 3. Move from the official add-on (keeps rooms, groups, users, music providers)
The panels keep their names and groups because the player IDs stay the same.

1. Make a **backup** first: Settings → System → Backups → Create backup (include *Music Assistant*).
2. Stop the official **Music Assistant** add-on and turn **Start on boot** off.
3. Copy its data into the new add-on (needs the *Advanced SSH & Web Terminal* add-on with **Protection mode off**):
   ```sh
   ls /mnt/data/supervisor/addons/data/ | grep music_assistant     # e.g. d5369777_music_assistant and 1a2b3c4d_aj_music_assistant
   OLD=d5369777_music_assistant          # official add-on (name from the list)
   NEW=1a2b3c4d_aj_music_assistant       # ours (name from the list)
   docker run --rm -v /mnt/data/supervisor/addons/data:/d alpine sh -c "cp -a /d/$OLD/. /d/$NEW/"
   ```
   (Fresh start instead: skip this step and set Music Assistant up again in its web page.)
4. Start **AJ Music Assistant**, turn on **Start on boot** and **Show in sidebar**. Open it – sign in with your Home Assistant user as before.

## 4. Turn on the AJ Snap provider (once)
In AJ Music Assistant → **Settings → Providers**:
1. Open the built-in **Snapcast** provider → **Disable** (both use port 1704).
2. **Add provider → AJ Snap (panels)** → Save.
3. The panels reconnect by themselves within ~30 s (Settings → Players shows them).
Also add **AriaCast Receiver** (Add provider → Plugins) for "Play my phone" and panel **Share**.

## 5. Check
- `http://192.168.8.5:8095` opens, or the sidebar entry.
- Installer app → Overview: server OK, rooms online.
- Add-on **Log** shows `ajsnap installed` during build and no errors at start.

## Updates
When we change the server part, the add-on version in `config.yaml` goes up and HA shows **Update** for AJ Music Assistant (Settings → Add-ons). Updating keeps all data.

## Going back to the official add-on
Stop AJ Music Assistant (Start on boot off), start the official one. Optional: copy the data back the same way (swap OLD/NEW).

## For developers
- After changing `server/ajsnap-provider/` or `server/patches/`, run `server/ha-addon/build_addon.bat` (or `.sh`) and commit the `aj/` folder, and bump `version:` in `config.yaml`. CI (`addon-check`) fails if you forget.
- Upgrading Music Assistant: change the tag in `build.yaml` (both arches) and `version:`, check `patches/` still apply.
