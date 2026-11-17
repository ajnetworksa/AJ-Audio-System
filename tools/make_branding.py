#!/usr/bin/env python3
"""Builds every logo / app icon the apps need from the company logo (FIX-033).
usage: python3 tools/make_branding.py <company_logo.png (green mark on white)>
writes apps/branding/: logo_mark.png, icon_1024.png (iOS, no alpha), android-res/ (launcher icons, adaptive)."""
import os, sys
from PIL import Image, ImageDraw

NAVY = (11, 18, 32)          # #0B1220 – app background / icon tile
src = sys.argv[1]
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "apps", "branding")
os.makedirs(root, exist_ok=True)

# 1. green mark with transparent background (white -> alpha)
im = Image.open(src).convert("RGBA")
px = im.load()
w, h = im.size
for y in range(h):
    for x in range(w):
        r, g, b, a = px[x, y]
        white = min(r, g, b)                 # how close to white
        alpha = max(0, min(255, int((255 - white) * 255 / 110))) if g >= r else 255
        if r > 235 and g > 235 and b > 235: alpha = 0
        px[x, y] = (r, g, b, min(a, alpha))
im = im.crop(im.getbbox())
mark = im.copy(); mark.thumbnail((512, 512), Image.LANCZOS)
mark.save(os.path.join(root, "logo_mark.png"))

def tile(size, radius_frac, mark_frac, alpha_bg=True):
    """navy tile with the mark centred"""
    t = Image.new("RGBA", (size, size), (0, 0, 0, 0) if alpha_bg else NAVY + (255,))
    d = ImageDraw.Draw(t)
    if alpha_bg:
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * radius_frac), fill=NAVY + (255,))
    m = im.copy(); m.thumbnail((int(size * mark_frac), int(size * mark_frac)), Image.LANCZOS)
    t.alpha_composite(m, ((size - m.width) // 2, (size - m.height) // 2))
    return t

tile(1024, 0, 0.62, alpha_bg=False).convert("RGB").save(os.path.join(root, "icon_1024.png"))
tile(512, 0.22, 0.62).save(os.path.join(root, "icon_512.png"))

# 2. Android launcher icons: legacy (rounded tile) + adaptive (foreground in the 66/108 safe zone)
res = os.path.join(root, "android-res")
dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
for d, k in dens.items():
    p = os.path.join(res, f"mipmap-{d}"); os.makedirs(p, exist_ok=True)
    tile(int(48 * k), 0.22, 0.64).save(os.path.join(p, "ic_launcher.png"))
    fg = Image.new("RGBA", (int(108 * k),) * 2, (0, 0, 0, 0))
    m = im.copy(); m.thumbnail((int(56 * k), int(56 * k)), Image.LANCZOS)
    fg.alpha_composite(m, ((fg.width - m.width) // 2, (fg.height - m.height) // 2))
    fg.save(os.path.join(p, "ic_launcher_foreground.png"))
os.makedirs(os.path.join(res, "mipmap-anydpi-v26"), exist_ok=True)
open(os.path.join(res, "mipmap-anydpi-v26", "ic_launcher.xml"), "w").write(
    '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@color/ic_launcher_background"/>\n'
    '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n</adaptive-icon>\n')
os.makedirs(os.path.join(res, "values"), exist_ok=True)
open(os.path.join(res, "values", "ic_launcher_background.xml"), "w").write(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#0B1220</color>\n</resources>\n')
print("branding written to", os.path.abspath(root))
