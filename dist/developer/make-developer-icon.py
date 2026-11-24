#!/usr/bin/env python3
"""Render the Google Play developer icon (venkat.app) and its alternates.

Usage:  python3 make-developer-icon.py      (needs Pillow; run from any directory)

No SVG rasteriser was available on the build machine, so this script draws the
same design as developer-icon.svg with Pillow at 4x and downsamples with
LANCZOS. The main icon's geometry (V points, stroke width, coral dot, shadow
offset) is read from developer-icon.svg itself, so the SVG stays the source.

Outputs (next to this script):
  developer-icon-512.png               main: white V + coral dot on ink-blue gradient
  developer-icon-alt1-512.png          light: ink-blue V + coral dot on pale blue
  developer-icon-alt2-512.png          sheets: V from two overlapping rounded sheets
  developer-icon-circle-preview.png    main icon masked to a circle, on white and dark
"""
import math
import os
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
SVG = os.path.join(HERE, "developer-icon.svg")
N = 512
SS = 4  # supersampling factor
W = N * SS
SAFE_R = 0.8 * N / 2  # 204.8: Play may crop to this circle
NS = {"s": "http://www.w3.org/2000/svg"}

# Offkit palette (launcher background + tiles, ui/theme/Theme.kt)
INK_GRADIENT = ["#5B82C6", "#415F91", "#284777"]
LIGHT_GRADIENT = ["#EEF3FF", "#D6E3FF", "#C2D3F5"]
INK_DARK = "#284777"
WHITE = "#FFFFFF"
CORAL = "#FF7A59"
SHADOW = (0x10, 0x22, 0x4A)


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def read_svg():
    root = ET.parse(SVG).getroot()
    v = root.find(".//s:polyline[@id='v']", NS)
    dot = root.find(".//s:circle[@id='dot']", NS)
    shadow = root.find(".//s:g[@id='shadow']", NS)
    pts = [tuple(float(c) for c in p.split(",")) for p in v.get("points").split()]
    dy = float(shadow.get("transform").split("(")[1].split()[1].rstrip(")"))
    return {
        "points": pts,
        "stroke": float(v.get("stroke-width")),
        "dot": (float(dot.get("cx")), float(dot.get("cy")), float(dot.get("r"))),
        "shadow_dy": dy,
        "shadow_alpha": float(shadow.get("stroke-opacity")),
    }


def gradient(stops):
    """Diagonal 3-stop gradient from top-left to bottom-right, full bleed."""
    cols = [rgb(s) for s in stops]
    small = Image.new("RGB", (N, N))
    px = []
    for y in range(N):
        for x in range(N):
            t = (x + y) / (2 * (N - 1))
            if t <= 0.5:
                a, b, u = cols[0], cols[1], t / 0.5
            else:
                a, b, u = cols[1], cols[2], (t - 0.5) / 0.5
            px.append(tuple(round(a[i] + (b[i] - a[i]) * u) for i in range(3)))
    small.putdata(px)
    return small.resize((W, W), Image.BICUBIC).convert("RGBA")


def s(v):
    return v * SS


def stroke_polyline(draw, pts, width, fill):
    """Round-capped, round-joined polyline (matches SVG stroke-linecap/linejoin=round)."""
    r = width / 2
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        dx, dy = x1 - x0, y1 - y0
        L = math.hypot(dx, dy)
        nx, ny = -dy / L * r, dx / L * r
        draw.polygon([(s(x0 + nx), s(y0 + ny)), (s(x1 + nx), s(y1 + ny)),
                      (s(x1 - nx), s(y1 - ny)), (s(x0 - nx), s(y0 - ny))], fill=fill)
    for x, y in pts:
        draw.ellipse([s(x - r), s(y - r), s(x + r), s(y + r)], fill=fill)


def circle(draw, cx, cy, r, fill):
    draw.ellipse([s(cx - r), s(cy - r), s(cx + r), s(cy + r)], fill=fill)


def rounded_sheet(cx, cy, length, width, radius, angle_deg, steps=24):
    """Polygon (in 1x units) of a rounded rectangle centred at cx,cy, long axis at angle."""
    hl, hw = length / 2, width / 2
    corners = [(hl - radius, hw - radius, 0), (-(hl - radius), hw - radius, 90),
               (-(hl - radius), -(hw - radius), 180), (hl - radius, -(hw - radius), 270)]
    local = []
    for ccx, ccy, a0 in corners:
        for i in range(steps + 1):
            a = math.radians(a0 + 90 * i / steps)
            local.append((ccx + radius * math.cos(a), ccy + radius * math.sin(a)))
    th = math.radians(angle_deg)
    c, sn = math.cos(th), math.sin(th)
    return [(cx + x * c - y * sn, cy + x * sn + y * c) for x, y in local]


def layer():
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def check_safe(layers, name):
    """Every non-transparent monogram pixel must lie inside the safe circle."""
    for lay in layers:
        alpha = lay.getchannel("A")
        bbox = alpha.getbbox()
        if bbox is None:
            continue
        x0, y0, x1, y1 = bbox
        data = alpha.load()
        c = W / 2
        lim = (SAFE_R * SS) ** 2
        for y in range(y0, y1, 2):
            for x in range(x0, x1, 2):
                if data[x, y] and (x + 0.5 - c) ** 2 + (y + 0.5 - c) ** 2 > lim:
                    raise SystemExit(f"{name}: content at ({x / SS:.0f},{y / SS:.0f}) outside safe circle")
    # report the monogram bbox in 1x units for centring checks
    union = None
    for lay in layers:
        b = lay.getchannel("A").getbbox()
        if b:
            union = b if union is None else (min(union[0], b[0]), min(union[1], b[1]),
                                             max(union[2], b[2]), max(union[3], b[3]))
    print(f"{name}: monogram bbox 1x = {[round(v / SS) for v in union]}, "
          f"centre = ({(union[0] + union[2]) / 2 / SS:.1f}, {(union[1] + union[3]) / 2 / SS:.1f})")


def finish(bg, layers, name):
    check_safe(layers, name)
    for lay in layers:
        bg = Image.alpha_composite(bg, lay)
    out = bg.resize((N, N), Image.LANCZOS).convert("RGBA")
    out.putalpha(255)  # opaque everywhere: 32-bit PNG, no transparent corners
    assert out.size == (N, N) and out.getchannel("A").getextrema() == (255, 255)
    out.save(os.path.join(HERE, name), optimize=True)
    return out


def design_v(g, bg_stops, v_colour, dot_colour, shadow_alpha, name):
    bg = gradient(bg_stops)
    sh, shd = layer()
    a = round(255 * shadow_alpha)
    dy = g["shadow_dy"]
    stroke_polyline(shd, [(x, y + dy) for x, y in g["points"]], g["stroke"], SHADOW + (a,))
    cx, cy, r = g["dot"]
    circle(shd, cx, cy + dy, r, SHADOW + (a,))
    fg, fgd = layer()
    stroke_polyline(fgd, g["points"], g["stroke"], rgb(v_colour) + (255,))
    circle(fgd, cx, cy, r, rgb(dot_colour) + (255,))
    return finish(bg, [sh, fg], name)


def design_sheets(g, name):
    """The V as two overlapping rounded sheets: white left, coral right on top."""
    bg = gradient(INK_GRADIENT)
    (lx, ly), (bx, by), (rx, ry) = g["points"]
    width, radius = 82, 26
    sheets = []
    # Each sheet runs from width/2 beyond its arm's top point to `tail` beyond the
    # apex. The white sheet stops short so its lower end tucks under the coral one.
    for (x0, y0), colour, tail in (((lx, ly), WHITE, 0.0), ((rx, ry), CORAL, width / 2)):
        L = math.hypot(bx - x0, by - y0)
        ux, uy = (bx - x0) / L, (by - y0) / L
        t0, t1 = -width / 2, L + tail
        mx, my = x0 + ux * (t0 + t1) / 2, y0 + uy * (t0 + t1) / 2
        ang = math.degrees(math.atan2(uy, ux))
        sheets.append((rounded_sheet(mx, my, t1 - t0, width, radius, ang), colour))
    dy = g["shadow_dy"]
    sh, shd = layer()
    for poly, _ in sheets:
        shd.polygon([(s(x), s(y + dy)) for x, y in poly], fill=SHADOW + (round(255 * 0.28),))
    left, ld = layer()
    ld.polygon([(s(x), s(y)) for x, y in sheets[0][0]], fill=rgb(WHITE) + (255,))
    # shadow the coral sheet casts onto the white one, clipped to the white sheet
    cast, cd = layer()
    cd.polygon([(s(x - 4), s(y + 4)) for x, y in sheets[1][0]], fill=SHADOW + (round(255 * 0.22),))
    cast.putalpha(Image.composite(cast.getchannel("A"), Image.new("L", (W, W), 0), left.getchannel("A")))
    right, rd = layer()
    rd.polygon([(s(x), s(y)) for x, y in sheets[1][0]], fill=rgb(CORAL) + (255,))
    return finish(bg, [sh, left, cast, right], name)


def circle_preview(icon, name):
    """Icon masked to a full circle on a white panel and a dark panel."""
    pad = 44
    panel = N + 2 * pad
    mask = Image.new("L", (N * SS, N * SS), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, N * SS - 1, N * SS - 1], fill=255)
    mask = mask.resize((N, N), Image.LANCZOS)
    out = Image.new("RGBA", (panel * 2, panel), (0, 0, 0, 255))
    for i, colour in enumerate(("#FFFFFF", "#1B1B1F")):
        p = Image.new("RGBA", (panel, panel), rgb(colour) + (255,))
        p.paste(icon, (pad, pad), mask)
        out.paste(p, (i * panel, 0))
    out.save(os.path.join(HERE, name), optimize=True)


def main():
    g = read_svg()
    main_icon = design_v(g, INK_GRADIENT, WHITE, CORAL, g["shadow_alpha"], "developer-icon-512.png")
    design_v(g, LIGHT_GRADIENT, INK_DARK, CORAL, 0.16, "developer-icon-alt1-512.png")
    design_sheets(g, "developer-icon-alt2-512.png")
    circle_preview(main_icon, "developer-icon-circle-preview.png")


if __name__ == "__main__":
    main()
