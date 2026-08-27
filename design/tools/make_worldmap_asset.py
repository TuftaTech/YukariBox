"""Generate the Home screen's halftone world map from real coastlines.

The map is a watermark behind the connect ring, and it went through three answers. A
hand-plotted land mask in Compose (at one cell per ~7 dp Greenland was a rectangle); then
`design/reference/05-worldmap.png`, the owner's drawn halftone, which resolved coastlines
the mask never could; and now this, because that drawing is a *picture* of a world map
rather than a projection of one. Measured against true geometry it agrees on 43% of its
land: the continents are stretched vertically by about a quarter, Greenland is fused to
Canada, the Mediterranean is a smear, Japan is a blob, and Antarctica is missing while the
frame is still proportioned as though it were there.

So the land comes from data and the halftone is drawn on a lattice:

- **Source** — Natural Earth 1:110m land polygons (`design/data/ne_110m_land.geojson`,
  127 polygons, public domain, from the natural-earth-vector repository). Committed rather
  than downloaded so the asset is reproducible with no network.
- **Projection** — Miller cylindrical, the compressed-Mercator that web maps use, over
  lon -180..180 and lat +84..-56. That window is not a crop for taste: it holds all of
  Greenland and all of Patagonia, leaves Antarctica out (as both the mockup and the owner's
  drawing do), and lands the aspect on 2.03, i.e. 200 dp tall at the 407 dp the map is
  drawn full-bleed. The mockup's own map measures ~205 dp tall, so correct geometry is also
  closer to the reference than the 192 dp the drawing gave.
- **Halftone** — a 45-degree staggered lattice: dots every [PITCH_DP] across, rows every
  [ROW_DP], alternate rows offset by half a step. [DOT_DP] is the diameter of a dot over
  solid land, and a dot's *area* scales with how much land its cell covers, so coastlines
  fade out instead of stepping. Both numbers are measured off what shipped, so the new map
  reads at the same weight as the old one: FWHM 5 px and a 12/6 px lattice at xxhdpi.
- **One rasterisation per density**, from the geometry, never by resampling a bitmap. That
  is the other thing the drawing could not do: one image scaled into five buckets beats
  against the pixel grid at four of them, and a halftone is exactly the pattern that shows
  it.

The output is a pure alpha mask — white RGB, alpha carrying the dots — because the app
tints it from the theme's `dot` token at draw time (`ui/kit/YukariWorldMap`), so one file
serves both themes and no grey is baked into the shipped pixels.

Run from the repo root: python design/tools/make_worldmap_asset.py
"""

from pathlib import Path
import json
import math

import numpy as np
from PIL import Image, ImageDraw

REPO = Path(__file__).resolve().parents[2]
LAND = REPO / "design" / "data" / "ne_110m_land.geojson"
RES = REPO / "app" / "src" / "main" / "res"

DENSITIES = {
    "drawable-mdpi": 1.0,
    "drawable-hdpi": 1.5,
    "drawable-xhdpi": 2.0,
    "drawable-xxhdpi": 3.0,
    "drawable-xxxhdpi": 4.0,
}

# The screen the map is drawn full-bleed on: 1220 px at density 3.0. Same constant as the
# mascot generator, and the reason the asset lands 1:1 on the dev panel's pixel grid.
PANEL_DP = 1220 / 3.0

# The window. Latitudes rather than a crop of the drawing: see the module docstring.
NORTH, SOUTH = 84.0, -56.0

# The lattice and the dot, in dp, measured off the asset this replaces.
PITCH_DP = 4.0
ROW_DP = 2.0
DOT_DP = 1.67

# Land coverage at or above which a dot is drawn at full size, and below which it is not
# drawn at all. The upper bound is under 1.0 because a cell in the middle of a continent
# still loses a little coverage to its own corners.
COVER_FULL = 0.80
COVER_FLOOR = 0.05

# Supersampling for both the land mask and the dots. The dots are the reason: a 5 px circle
# drawn directly has visible stair-steps, and this is a texture of ten thousand of them.
SS = 3


def miller(lat_deg: float) -> float:
    """Miller cylindrical y for a latitude, in radians of the x axis. Finite at the poles."""
    return 1.25 * math.log(math.tan(math.pi / 4 + 0.4 * math.radians(lat_deg)))


Y_NORTH, Y_SOUTH = miller(NORTH), miller(SOUTH)
SPAN_X = 2 * math.pi
SPAN_Y = Y_NORTH - Y_SOUTH


def land_mask(width: int) -> Image.Image:
    """Land as white on black, Miller-projected into the [NORTH]..[SOUTH] window.

    Vertices outside the window are left outside it: Pillow clips the fill to the canvas,
    which keeps a coastline a coastline. Clamping them to the edge instead — the obvious
    thing to write — welds the whole Arctic into one flat band across the top.
    """
    height = int(round(width * SPAN_Y / SPAN_X))
    canvas = Image.new("L", (width, height), 0)
    draw = ImageDraw.Draw(canvas)
    holes = []
    for feature in json.loads(LAND.read_text(encoding="utf-8"))["features"]:
        for index, ring in enumerate(feature["geometry"]["coordinates"]):
            points = [
                ((math.radians(lon) + math.pi) / SPAN_X * width,
                 (Y_NORTH - miller(lat)) / SPAN_Y * height)
                for lon, lat in ring
            ]
            if len(points) < 3:
                continue
            if index == 0:
                draw.polygon(points, fill=255)
            else:
                holes.append(points)
    for points in holes:
        draw.polygon(points, fill=0)
    return canvas


def halftone(scale: float) -> Image.Image:
    """The map as an alpha mask at one density, rasterised from the geometry."""
    width = int(round(PANEL_DP * scale))
    mask = np.asarray(land_mask(width * SS)).astype(np.float32) / 255.0
    height = mask.shape[0] // SS
    canvas = Image.new("L", (width * SS, mask.shape[0]), 0)
    draw = ImageDraw.Draw(canvas)
    pitch, row_gap = PITCH_DP * scale, ROW_DP * scale
    cell = max(1, int(round(pitch * SS / 2)))
    for row in range(int(height / row_gap) + 1):
        centre_y = row * row_gap
        offset = pitch / 2.0 if row % 2 else 0.0
        for column in range(int(width / pitch) + 1):
            centre_x = column * pitch + offset
            if centre_x > width:
                break
            sample_y, sample_x = int(centre_y * SS), int(centre_x * SS)
            box = mask[max(0, sample_y - cell):sample_y + cell,
                       max(0, sample_x - cell):sample_x + cell]
            cover = float(box.mean()) if box.size else 0.0
            if cover < COVER_FLOOR:
                continue
            radius = DOT_DP * scale / 2.0 * math.sqrt(min(1.0, cover / COVER_FULL)) * SS
            draw.ellipse([sample_x - radius, sample_y - radius,
                          sample_x + radius, sample_y + radius], fill=255)
    return canvas.resize((width, height), Image.LANCZOS)


def main() -> None:
    for folder, scale in DENSITIES.items():
        alpha = halftone(scale)
        white = Image.new("L", alpha.size, 255)
        out = Image.merge("LA", (white, alpha)).convert("RGBA")
        path = RES / folder
        path.mkdir(parents=True, exist_ok=True)
        target = path / "yukari_worldmap.webp"
        out.save(target, quality=90, method=6)
        print(f"  {folder}/yukari_worldmap.webp  {out.width}x{out.height}"
              f"  {target.stat().st_size / 1024:.0f} KiB")
    print(f"aspect {SPAN_X / SPAN_Y:.3f} -> {PANEL_DP * SPAN_Y / SPAN_X:.1f} dp tall at {PANEL_DP:.1f} dp wide")


if __name__ == "__main__":
    main()
