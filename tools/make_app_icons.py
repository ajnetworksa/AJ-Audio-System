#!/usr/bin/env python3
"""Per-app launcher icons: the AJ logo mark + a small badge so the three apps are easy to tell apart.
   Installer = gear, Client = play, Panel = speaker. Uses apps/branding/logo_mark.png (made by make_branding.py).
usage: python3 tools/make_app_icons.py
writes apps/branding/<app>/icon_1024.png + android-res/ (aj-setup, aj-audio) and apps/panel/res/mipmap-*/ (panel)."""
import math, os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
BR = os.path.join(HERE, "..", "apps", "branding")
NAVY = (11, 18, 32, 255)
GREEN = (107, 188, 70, 255)
WHITE = (255, 255, 255, 255)
S = 1024                                   # drawing size of the glyph (mark + badge)
mark = Image.open(os.path.join(BR, "logo_mark.png")).convert("RGBA")


def gear(d, cx, cy, r, color, hole):
    teeth, outer, inner = 8, r, r * 0.78
    pts = []
    for i in range(teeth * 4):
        a = 2 * math.pi * i / (teeth * 4) - math.pi / 2
        rr = outer if i % 4 in (1, 2) else inner
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    d.polygon(pts, fill=color)
    d.ellipse([cx - r * 0.36, cy - r * 0.36, cx + r * 0.36, cy + r * 0.36], fill=hole)


def play(d, cx, cy, r, color):
    d.polygon([(cx - r * 0.38, cy - r * 0.55), (cx - r * 0.38, cy + r * 0.55), (cx + r * 0.62, cy)], fill=color)


def speaker(d, cx, cy, r, color, bg):
    w = r * 0.16
    d.rectangle([cx - r * 0.62, cy - r * 0.22, cx - r * 0.30, cy + r * 0.22], fill=color)
    d.polygon([(cx - r * 0.32, cy - r * 0.22), (cx + r * 0.02, cy - r * 0.55), (cx + r * 0.02, cy + r * 0.55), (cx - r * 0.32, cy + r * 0.22)], fill=color)
    for k in (0.38, 0.62):                 # sound waves
        box = [cx - r * k + r * 0.12, cy - r * k, cx + r * k + r * 0.12, cy + r * k]
        d.arc(box, -48, 48, fill=color, width=int(w))


def glyph(kind):
    """transparent S×S: logo mark top-left, round badge bottom-right"""
    g = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    m = mark.crop(mark.getbbox())
    k = S * 0.78 / max(m.size)                       # scale UP as well (thumbnail() only shrinks)
    m = m.resize((int(m.width * k), int(m.height * k)), Image.LANCZOS)
    g.alpha_composite(m, (int((S * 0.86 - m.width) / 2), int(S * 0.03)))
    d = ImageDraw.Draw(g)
    r = S * 0.19
    cx = cy = S - r - S * 0.02
    d.ellipse([cx - r - S * 0.025, cy - r - S * 0.025, cx + r + S * 0.025, cy + r + S * 0.025], fill=NAVY)   # gap ring
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=GREEN if kind != "setup" else WHITE)
    if kind == "setup":
        gear(d, cx, cy, r * 0.78, GREEN, WHITE)
    elif kind == "client":
        play(d, cx, cy, r * 0.8, NAVY)
    else:
        speaker(d, cx, cy, r * 0.85, NAVY, GREEN)
    return g


def tile(g, size, radius_frac, frac, rounded=True):
    t = Image.new("RGBA", (size, size), (0, 0, 0, 0) if rounded else NAVY)
    if rounded:
        ImageDraw.Draw(t).rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * radius_frac), fill=NAVY)
    x = g.resize((int(size * frac),) * 2, Image.LANCZOS)
    t.alpha_composite(x, ((size - x.width) // 2, (size - x.height) // 2))
    return t


def android(g, res):
    dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
    for dn, k in dens.items():
        p = os.path.join(res, f"mipmap-{dn}"); os.makedirs(p, exist_ok=True)
        tile(g, int(48 * k), 0.22, 0.74).save(os.path.join(p, "ic_launcher.png"))
        fg = Image.new("RGBA", (int(108 * k),) * 2, (0, 0, 0, 0))
        x = g.resize((int(64 * k),) * 2, Image.LANCZOS)          # inside the 66/108 safe zone
        fg.alpha_composite(x, ((fg.width - x.width) // 2, (fg.height - x.height) // 2))
        fg.save(os.path.join(p, "ic_launcher_foreground.png"))
    os.makedirs(os.path.join(res, "mipmap-anydpi-v26"), exist_ok=True)
    open(os.path.join(res, "mipmap-anydpi-v26", "ic_launcher.xml"), "w").write(
        '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background"/>\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n</adaptive-icon>\n')
    os.makedirs(os.path.join(res, "values"), exist_ok=True)
    open(os.path.join(res, "values", "ic_launcher_background.xml"), "w").write(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#0B1220</color>\n</resources>\n')


for kind, out in (("setup", "aj-setup"), ("client", "aj-audio")):
    g = glyph(kind)
    d = os.path.join(BR, out); os.makedirs(d, exist_ok=True)
    tile(g, 1024, 0, 0.74, rounded=False).convert("RGB").save(os.path.join(d, "icon_1024.png"))
    tile(g, 512, 0.22, 0.74).save(os.path.join(d, "icon_512.png"))
    android(g, os.path.join(d, "android-res"))

g = glyph("panel")
tile(g, 512, 0.22, 0.74).save(os.path.join(BR, "panel_icon_512.png"))
android(g, os.path.join(HERE, "..", "apps", "panel", "res"))
print("app icons written")
