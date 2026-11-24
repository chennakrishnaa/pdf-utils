#!/usr/bin/env python3
"""Render the Google Play developer page header (venkat.app) and an alternate.

Usage:  python3 make-developer-header.py      (needs Pillow; run from any directory)

Same visual language as make-developer-icon.py: the Offkit ink-blue diagonal
gradient (top-left to bottom-right), a white round-capped "V" with the coral
dot, and the flat offset shadow (#10224A at 28 %, shifted straight down). The
V geometry is read from developer-icon.svg and scaled up.

Play requirements: 4096 x 2304, JPEG or 24-bit PNG (no alpha). Play overlays
the developer icon and name on the lower left and crops the sides on narrow
screens, so the header carries no text, keeps the lower-left third and the
outer 15 % on each side free of anything important, and puts the focal "V."
right of centre inside both the 4:3 and the 16:6 centre crops.

Rendering: the gradient is computed in floating point at 1x with a little
noise added before quantising (dither, no banding); shapes are drawn as masks
at 2x and downsampled with LANCZOS, then painted in one flat colour each.

Outputs (next to this script):
  developer-header-4096x2304.png       main: white "V." on the ink-blue gradient
  developer-header-4096x2304.jpg       same, JPEG q92 (written when the PNG > 1 MB)
  developer-header-alt-4096x2304.png   light: ink "V." on the pale-blue gradient
  developer-header-alt-4096x2304.jpg   same, JPEG q92 (written when the PNG > 1 MB)
  developer-header-preview.png         composition check: mock icon overlay + crops
"""
import math
import os
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw, ImageMath

HERE = os.path.dirname(os.path.abspath(__file__))
SVG = os.path.join(HERE, "developer-icon.svg")
ICON = os.path.join(HERE, "developer-icon-512.png")
W, H = 4096, 2304
SS = 2  # supersampling factor for shapes
NS = {"s": "http://www.w3.org/2000/svg"}

# Offkit palette (same values as make-developer-icon.py)
INK_GRADIENT = ["#5B82C6", "#415F91", "#284777"]
LIGHT_GRADIENT = ["#EEF3FF", "#D6E3FF", "#C2D3F5"]
INK_DARK = "#284777"
WHITE = "#FFFFFF"
CORAL = "#FF7A59"
SHADOW = "#10224A"

# Focal "V." : icon units scaled by K, monogram centre placed at (VX, VY)
K = 5.2
VX, VY = 2660, 1150


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


# ---------------------------------------------------------------- background

def gradient(stops, seed_sigma=32.0, dither=0.6):
    """Diagonal 3-stop gradient, top-left to bottom-right, with float dither."""
    hx = Image.new("F", (W, 1))
    hx.putdata([float(x) for x in range(W)])
    vy = Image.new("F", (1, H))
    vy.putdata([float(y) for y in range(H)])
    hx = hx.resize((W, H), Image.NEAREST)
    vy = vy.resize((W, H), Image.NEAREST)
    span = float(W + H - 2)
    t = ImageMath.lambda_eval(lambda a: (a["x"] + a["y"]) / span, x=hx, y=vy)
    del hx, vy
    # Gaussian noise centred on 128; scaled to about +-dither LSB
    noise = Image.effect_noise((W, H), seed_sigma).convert("F")
    cols = [rgb(s) for s in stops]
    chans = []
    for i in range(3):
        c0, c1, c2 = (float(c[i]) for c in cols)
        ch = ImageMath.lambda_eval(
            lambda a: a["t"] * 0 + c0
            + (c1 - c0) * 2 * a["min"](a["t"], 0.5)
            + (c2 - c1) * 2 * a["max"](a["t"] - 0.5, 0.0)
            + (a["n"] - 128.0) * (dither / seed_sigma) + 0.5,
            t=t, n=noise)
        chans.append(ch.convert("L"))
        del ch
    return Image.merge("RGB", chans)


# ---------------------------------------------------------------- shapes

def mask():
    m = Image.new("L", (W * SS, H * SS), 0)
    return m, ImageDraw.Draw(m)


def sc(pts):
    return [(x * SS, y * SS) for x, y in pts]


def stroke_polyline(draw, pts, width):
    """Round-capped, round-joined polyline (1x units)."""
    r = width / 2
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        dx, dy = x1 - x0, y1 - y0
        L = math.hypot(dx, dy)
        nx, ny = -dy / L * r, dx / L * r
        draw.polygon(sc([(x0 + nx, y0 + ny), (x1 + nx, y1 + ny),
                         (x1 - nx, y1 - ny), (x0 - nx, y0 - ny)]), fill=255)
    for x, y in pts:
        draw.ellipse([(x - r) * SS, (y - r) * SS, (x + r) * SS, (y + r) * SS], fill=255)


def disc(draw, cx, cy, r):
    draw.ellipse([(cx - r) * SS, (cy - r) * SS, (cx + r) * SS, (cy + r) * SS], fill=255)


def tile(draw, x0, y0, x1, y1, radius):
    draw.rounded_rectangle([x0 * SS, y0 * SS, x1 * SS, y1 * SS], radius=radius * SS, fill=255)


def rounded_sheet(cx, cy, length, width, radius, angle_deg, steps=32):
    """Polygon (1x units) of a rounded rectangle centred at cx,cy, long axis at angle."""
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


def paint(bg, m, colour, opacity=1.0):
    """Downsample a 2x mask and paint one flat colour through it."""
    small = m.resize((W, H), Image.LANCZOS)
    if opacity < 1.0:
        small = small.point(lambda v: round(v * opacity))
    bg.paste(rgb(colour), None, small)


def draw_shape(fn):
    m, d = mask()
    fn(d)
    return m


# ---------------------------------------------------------------- design

def v_geometry(g):
    """Focal V points, stroke, dot and shadow offset in header pixels."""
    cx0, cy0 = 256.0, 259.0  # centre of the icon's monogram bbox
    pts = [(VX + (x - cx0) * K, VY + (y - cy0) * K) for x, y in g["points"]]
    dx, dy, dr = g["dot"]
    dot = (VX + (dx - cx0) * K, VY + (dy - cy0) * K, dr * K)
    return pts, g["stroke"] * K, dot, g["shadow_dy"] * K


def arm_angle(g):
    (lx, ly), (bx, by), _ = g["points"]
    return math.degrees(math.atan2(by - ly, bx - lx))  # left arm, about 62 deg


def render(g, stops, fg, sheet_light, sheet_dark, light_op, dark_op, shadow_op, name):
    bg = gradient(stops)
    pts, stroke, (dcx, dcy, dr), sdy = v_geometry(g)
    ang = arm_angle(g)
    (lx, ly), (bx, by), (rx, ry) = pts

    # 1. Two giant chevron bands behind everything, parallel to the V arms,
    #    apex below the canvas: a quiet echo of the monogram.
    for off, width, op in ((0, 560, dark_op), (900, 420, light_op * 0.8)):
        apx, apy = bx, by + 700 + off
        arm = 3600
        a = math.radians(ang)
        left = (apx - math.cos(a) * arm, apy - math.sin(a) * arm)
        right = (apx + math.cos(a) * arm, apy - math.sin(a) * arm)
        m = draw_shape(lambda d: stroke_polyline(d, [left, (apx, apy), right], width))
        paint(bg, m, sheet_dark if off == 0 else sheet_light, op)
        del m

    # 2. App-family sheets: overlapping rounded tiles, upper left of centre,
    #    clear of the lower-left third where Play puts the icon and name.
    tiles = [  # x0, y0, size, radius, colour, opacity
        (1020, 330, 620, 150, sheet_light, light_op),
        (1400, 620, 620, 150, sheet_light, light_op * 1.4),
    ]
    # Translucent sheets cast no shadow (it would show through as grey);
    # only the opaque elements below get the flat offset shadow.
    for x0, y0, size, rad, colour, op in tiles:
        m = draw_shape(lambda d: tile(d, x0, y0, x0 + size, y0 + size, rad))
        paint(bg, m, colour, op)
        del m
    # small coral tile overlapping the lower-left corner of the front sheet:
    # the one warm accent besides the dot, kept well away from the V
    cx0, cy0, cs = 1250, 1060, 230
    m = draw_shape(lambda d: tile(d, cx0, cy0 + sdy * 0.5, cx0 + cs, cy0 + cs + sdy * 0.5, 58))
    paint(bg, m, SHADOW, shadow_op)
    m = draw_shape(lambda d: tile(d, cx0, cy0, cx0 + cs, cy0 + cs, 58))
    paint(bg, m, CORAL)

    # 3. One long sheet along the right arm direction, behind the V, upper right
    m = draw_shape(lambda d: d.polygon(sc(rounded_sheet(3330, 560, 1500, 300, 150, -ang + 180)), fill=255))
    paint(bg, m, sheet_light, light_op)

    # 4. Focal "V." with the flat offset shadow
    m = draw_shape(lambda d: (stroke_polyline(d, [(x, y + sdy) for x, y in pts], stroke),
                              disc(d, dcx, dcy + sdy, dr)))
    paint(bg, m, SHADOW, shadow_op)
    m = draw_shape(lambda d: stroke_polyline(d, pts, stroke))
    paint(bg, m, fg)
    m = draw_shape(lambda d: disc(d, dcx, dcy, dr))
    paint(bg, m, CORAL)
    del m

    assert bg.mode == "RGB" and bg.size == (W, H)
    bg.save(os.path.join(HERE, name), optimize=True)
    print(f"{name}: V bbox x {lx - stroke / 2:.0f}..{dcx + dr:.0f}, "
          f"y {ly - stroke / 2:.0f}..{by + stroke / 2 + sdy:.0f}")
    return bg


# ---------------------------------------------------------------- preview

def preview(header, name):
    """Header with a mock Play overlay (circular icon + name bars), then crops."""
    PW = 1600
    s = PW / W
    top = header.resize((PW, round(H * s)), Image.LANCZOS).convert("RGBA")
    ov = Image.new("RGBA", top.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    # safe-area guides: 15 % side margins and the lower-left third
    for x in (0.15 * PW, 0.85 * PW):
        d.line([(x, 0), (x, top.height)], fill=(255, 255, 255, 90), width=1)
    d.rectangle([0, top.height * 2 / 3, PW / 3, top.height], outline=(255, 214, 102, 160), width=2)
    # mock developer name and subtitle bars (stand-ins, no text)
    ix, iy, ir = 0.13 * PW, 0.80 * top.height, 0.09 * top.height
    d.rounded_rectangle([ix + ir + 24, iy - 26, ix + ir + 300, iy - 2], radius=12, fill=(255, 255, 255, 235))
    d.rounded_rectangle([ix + ir + 24, iy + 10, ix + ir + 200, iy + 26], radius=8, fill=(255, 255, 255, 150))
    top = Image.alpha_composite(top, ov)
    # real developer icon, circular, with a white ring
    icon = Image.open(ICON).convert("RGBA")
    dia = round(2 * ir)
    big = 4 * dia
    cm = Image.new("L", (big, big), 0)
    ImageDraw.Draw(cm).ellipse([0, 0, big - 1, big - 1], fill=255)
    ring = Image.new("RGBA", (dia + 8, dia + 8), (0, 0, 0, 0))
    ImageDraw.Draw(ring).ellipse([0, 0, dia + 7, dia + 7], fill=(255, 255, 255, 255))
    top.alpha_composite(ring, (round(ix - ir - 4), round(iy - ir - 4)))
    ic = icon.resize((dia, dia), Image.LANCZOS)
    top.paste(ic, (round(ix - ir), round(iy - ir)), cm.resize((dia, dia), Image.LANCZOS))

    # centre crops: 4:3 and 16:6, side by side, same height
    c43 = header.crop(((W - H * 4 // 3) // 2, 0, (W + H * 4 // 3) // 2, H))
    h166 = W * 6 // 16
    c166 = header.crop((0, (H - h166) // 2, W, (H + h166) // 2))
    gap, pad = 24, 24
    ch = round((PW - gap) / (4 / 3 + 16 / 6))
    a = c43.resize((round(ch * 4 / 3), ch), Image.LANCZOS)
    b = c166.resize((PW - gap - a.width, ch), Image.LANCZOS)
    out = Image.new("RGB", (PW, top.height + pad + ch), (0xF4, 0xF5, 0xF8))
    out.paste(top.convert("RGB"), (0, 0))
    out.paste(a, (0, top.height + pad))
    out.paste(b, (a.width + gap, top.height + pad))
    out.save(os.path.join(HERE, name), optimize=True)


def main():
    g = read_svg()
    main_hdr = render(g, INK_GRADIENT, WHITE, sheet_light=WHITE, sheet_dark=SHADOW,
                      light_op=0.07, dark_op=0.10, shadow_op=g["shadow_alpha"],
                      name="developer-header-4096x2304.png")
    jpeg_if_large(main_hdr, "developer-header-4096x2304")
    preview(main_hdr, "developer-header-preview.png")
    del main_hdr
    alt = render(g, LIGHT_GRADIENT, INK_DARK, sheet_light=WHITE, sheet_dark=INK_DARK,
                 light_op=0.45, dark_op=0.05, shadow_op=0.16,
                 name="developer-header-alt-4096x2304.png")
    jpeg_if_large(alt, "developer-header-alt-4096x2304")


def jpeg_if_large(img, stem):
    """The dither noise makes the PNG large; write a q92 JPEG next to it if > 1 MB."""
    if os.path.getsize(os.path.join(HERE, stem + ".png")) > 1_000_000:
        img.save(os.path.join(HERE, stem + ".jpg"), quality=92, subsampling=0, optimize=True)


if __name__ == "__main__":
    main()
