#!/usr/bin/env python3
"""
render_feature_graphic.py — Lunamux Google Play feature-graphic generator.

Renders the 1024 x 500 feature graphic that Google Play shows at the top of the
store listing. The look is kept perfectly in sync with the store screenshots by
reusing the SAME brand palette, monospaced font, navy background and glow helpers
from ``render_screenshots.py`` — so a rebrand (e.g. the green -> cyan "Lunamux"
switch) only has to be made in one place.

Layout:
  - left  : glowing cyan orb (the Lunamux moon, matching the app icon) + the
            "Lunamux" wordmark + a "> A terminal for the agentic age" tagline.
  - right : a real Mac screenshot inside a rounded terminal window with a soft
            cyan halo, echoing the companion look used in the screenshots.

Everything is rendered at 2x supersampling and downscaled with LANCZOS so the
wordmark and UI stay crisp.

Output:
  out/google-play/feature-graphic.png   1024 x 500  (upload to Play Console -> Main store listing)

Usage:
  python3 render_feature_graphic.py [path/to/mac-screenshot.png]

Called by: the project owner when regenerating the store listing assets.
@see render_screenshots.py for the shared palette/font/background helpers.
@see README.md for the overall store-asset workflow.
"""

import os
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageChops

# Reuse the exact brand look from the screenshot generator (single source of truth).
from render_screenshots import BG, GREEN, GHI, GDIM, font, build_background, halo, rrmask

# --- output target (Google Play feature graphic spec) ---
OUT_W, OUT_H = 1024, 500
SS = 2                      # supersample factor for crisp text/UI
W, H = OUT_W * SS, OUT_H * SS

WORDMARK = "lunamux"
TAGLINE = "A terminal for the agentic age"
DEFAULT_PANEL = "sources/mac/sessions-mac.png"


def draw_orb(size):
    """Render the Lunamux "moon" orb: a cyan gradient sphere with a soft glow.

    Mirrors the app-icon orb — a bright off-centre specular highlight fading to a
    mid-cyan body and a darker rim, over an additive outer glow.

    @param size integer diameter of the solid sphere in pixels (glow extends past it).
    @return an RGBA Image of side ``size * 2`` with the orb centred.
    """
    pad = size // 2
    s = size + pad * 2
    orb = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    px = orb.load()
    cx = cy = s / 2
    rad = size / 2
    # Specular highlight sits up-left of centre, like the app icon.
    hx, hy = cx - rad * 0.32, cy - rad * 0.32
    body = GREEN            # #4dc8f5 mid cyan
    hi = (200, 235, 255)    # near-white cyan highlight
    rim = (18, 70, 104)     # dark rim
    for y in range(s):
        for x in range(s):
            d = (((x - cx) ** 2 + (y - cy) ** 2) ** 0.5) / rad
            if d <= 1.0:
                # Highlight falloff -> body -> rim shading across the sphere.
                hd = (((x - hx) ** 2 + (y - hy) ** 2) ** 0.5) / rad
                t = max(0.0, min(1.0, hd / 1.25))          # 0 at highlight, 1 far
                col = tuple(int(hi[i] + (body[i] - hi[i]) * t) for i in range(3))
                edge = max(0.0, (d - 0.72) / 0.28)          # darken toward the rim
                col = tuple(int(col[i] + (rim[i] - col[i]) * (edge ** 1.6)) for i in range(3))
                a = 255 if d < 0.985 else int(255 * (1 - (d - 0.985) / 0.015))
                px[x, y] = (col[0], col[1], col[2], max(0, a))
    # Soft outer glow.
    glow = Image.new("L", (s, s), 0)
    ImageDraw.Draw(glow).ellipse([pad, pad, pad + size, pad + size], fill=150)
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.18))
    glow_rgb = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    glow_rgb.paste(Image.new("RGBA", (s, s), (77, 200, 245, 255)), (0, 0), glow)
    out = Image.alpha_composite(glow_rgb, orb)
    return out


def fit_cover(img, box_w, box_h):
    """Scale-and-centre-crop an image to exactly cover a box (like CSS cover).

    @param img source Image.
    @param box_w,box_h target size.
    @return an RGB Image of size (box_w, box_h).
    """
    src_r = img.width / img.height
    box_r = box_w / box_h
    if src_r > box_r:
        nh = box_h
        nw = int(box_h * src_r)
    else:
        nw = box_w
        nh = int(box_w / src_r)
    img = img.resize((nw, nh), Image.LANCZOS)
    left = (nw - box_w) // 2
    top = (nh - box_h) // 2
    return img.crop((left, top, left + box_w, top + box_h))


def main():
    """Render the feature graphic and save it as ``feature-graphic.png``.

    @param argv[1] optional path to the Mac screenshot shown in the right panel.
    """
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    panel_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PANEL

    img = build_background(W, H)

    # --- right: Mac screenshot in a rounded terminal window with a halo ---
    pw, ph = int(W * 0.46), int(H * 0.74)
    px0 = W - pw - int(W * 0.05)
    py0 = (H - ph) // 2
    box = [px0, py0, px0 + pw, py0 + ph]
    r = int(H * 0.045)
    img = halo(img, box, r, (26, 104, 145), int(H * 0.06), 150)
    if os.path.exists(panel_path):
        shot = fit_cover(Image.open(panel_path).convert("RGB"), pw, ph)
        img.paste(shot, (px0, py0), rrmask((pw, ph), r))
        ImageDraw.Draw(img).rounded_rectangle(box, r, outline=(40, 80, 110), width=SS)

    d = ImageDraw.Draw(img)

    # --- left: orb + wordmark + tagline ---
    lx = int(W * 0.06)                      # left margin for the text column
    orb_d = int(H * 0.23)
    orb = draw_orb(orb_d)
    oy = int(H * 0.20)
    img.paste(orb, (lx - orb_d // 2, oy - orb_d // 2), orb)
    img.paste(orb, (lx - orb_d // 2, oy - orb_d // 2), orb)  # 2x for a denser glow

    # Wordmark, sized to fit the left column width.
    wf = font(int(H * 0.20), bold=True)
    wy = int(H * 0.44)
    d.text((lx, wy), WORDMARK, font=wf, fill=GHI)

    # Tagline with a cyan prompt caret, under the wordmark.
    tf = font(int(H * 0.062), bold=False)
    ty = wy + int(H * 0.235)
    caret = "❯ "                       # ❯
    d.text((lx, ty), caret, font=tf, fill=GREEN)
    cw = d.textlength(caret, font=tf)
    d.text((lx + cw, ty), TAGLINE, font=tf, fill=GDIM)

    out = img.resize((OUT_W, OUT_H), Image.LANCZOS)
    dest = os.path.join("out", "google-play", "feature-graphic.png")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    out.save(dest, "PNG")
    print(f"wrote {dest} ({OUT_W}x{OUT_H}) using panel: {panel_path}")


if __name__ == "__main__":
    main()
