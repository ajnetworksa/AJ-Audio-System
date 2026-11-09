#!/bin/sh
# Prepares the local add-on folder: copies provider + patches + installer into aj_music_assistant/aj/
# Then copy aj_music_assistant/ to the HA host's /addons/ folder.
set -e
cd "$(dirname "$0")"
rm -rf aj_music_assistant/aj && mkdir -p aj_music_assistant/aj
cp -r ../ajsnap-provider/ajsnap ../patches ../install_ajsnap.sh aj_music_assistant/aj/
echo "ready: $(pwd)/aj_music_assistant  ->  copy to \\\\<ha-host>\\addons\\"
