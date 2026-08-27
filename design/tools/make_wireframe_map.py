"""Draw the Home watermark as a flat low-poly wireframe of the real world.

The owner's brief, in their words: the exact map of the Earth, in the style of a low-poly
network diagram, without the servers and without the curvature. So what this renders is that
drawing technique applied to accurate geography — angular coastlines, a triangulated
interior, a dot on every corner — and nothing else. No hubs, no routes, no bend.

The reference picture the brief pointed at was third-party and was removed from the tree
before the repository went public. Nothing here ever read it — this generator computes
everything from coastline data — but the measurements below were taken while it was around.

  geography   Natural Earth 1:110m land polygons, Miller cylindrical over lon +-180 and
              lat +84..-56 (all of Greenland, all of Patagonia, no Antarctica — as both the
              mockup and the reference left it out). Aspect 2.03, i.e. 200.5 dp tall
              at the 407 dp the map is drawn full-bleed, which is the band the slot has held
              since the halftone.
  outline     Douglas-Peucker at [SIMPLIFY_DP], which is what turns a coastline into straight
              runs with visible corners. Accurate first, angular second: the tolerance is
              small enough that Italy is still a boot and Japan is still a chain.
  interior    A **Delaunay** mesh over each landmass: its outline vertices plus a coarse
              grid of interior points at [MESH_GRID_DP], triangulated, with the triangles
              outside the coastline thrown away. Ear clipping was tried first and is the
              wrong tool — it fans every diagonal out of one vertex, so each continent got a
              starburst instead of a mesh. Delaunay is what the artwork actually shows.
  corners     A dot on every [DOT_EVERY]-th vertex of the mesh, outline and interior alike.

Rasterised once per density from the geometry, never resampled, and emitted as a pure alpha
mask (white RGB, alpha carrying the ink) so the app tints it from `dot` and one file serves
both themes. It writes `drawable-*/yukari_worldmap.webp` — the slot, shared with
`make_network_map.py` and `make_worldmap_asset.py`; whichever generator runs last owns the
look. If a fourth treatment ever appears, the projection and the simplifier should move to a
module the three of them share.

Run from the repo root: python design/tools/make_wireframe_map.py
"""

from pathlib import Path
import json
import math

import numpy as np
from PIL import Image, ImageDraw
from scipy.spatial import Delaunay

REPO = Path(__file__).resolve().parents[2]
LAND = REPO / "design" / "data" / "ne_110m_land.geojson"
RES = REPO / "app" / "src" / "main" / "res"
PREVIEW = Path(__file__).resolve().parent / "preview"

DENSITIES = {
    "drawable-mdpi": 1.0,
    "drawable-hdpi": 1.5,
    "drawable-xhdpi": 2.0,
    "drawable-xxhdpi": 3.0,
    "drawable-xxxhdpi": 4.0,
}

PANEL_DP = 1220 / 3.0
NORTH, SOUTH = 84.0, -56.0
ANTARCTIC = -56.0

# Supersampling. Pillow draws no anti-aliased line, and this picture is nothing but lines.
SS = 4

# Ink, as alpha over a `dot` tint (`Color.kt`: #C0C0C0 light, #4A4A4A dark). The coast and
# the corners carry the shape; the triangulation is texture and stays well under them.
INK_COAST = 255
INK_MESH = 92
INK_DOT = 255

# Widths and diameters, in dp.
COAST_DP = 0.7
MESH_DP = 0.5
DOT_DP = 1.5

# Outline tolerance, and the smallest ring worth drawing at all (bounding-box diagonal).
# Below that an island simplifies into a two-vertex splinter and reads as dirt.
SIMPLIFY_DP = 1.1
MIN_RING_DP = 6.0

# Spacing of the interior points fed to the triangulator, in dp. Wider than this and a
# continent is one flat sheet of long triangles; much finer and the mesh out-shouts the
# coastline it is supposed to sit inside.
MESH_GRID_DP = 15.0

# How many mesh corners get a dot.
DOT_EVERY = 2


def miller(lat):
    """Miller cylindrical y, finite at the poles."""
    return 1.25 * math.log(math.tan(math.pi / 4 + 0.4 * math.radians(lat)))


Y_NORTH, Y_SOUTH = miller(NORTH), miller(SOUTH)
SPAN_X = 2 * math.pi
SPAN_Y = Y_NORTH - Y_SOUTH
ASPECT = SPAN_X / SPAN_Y


def project(lon, lat, width, height):
    return ((math.radians(lon) + math.pi) / SPAN_X * width,
            (Y_NORTH - miller(lat)) / SPAN_Y * height)


def simplify(points, tolerance):
    """Douglas-Peucker. The corners it leaves are the point, not the bytes it saves."""
    if len(points) < 3:
        return points
    (x0, y0), (x1, y1) = points[0], points[-1]
    dx, dy = x1 - x0, y1 - y0
    length = math.hypot(dx, dy)
    worst, index = -1.0, 0
    for i in range(1, len(points) - 1):
        x, y = points[i]
        distance = (math.hypot(x - x0, y - y0) if length < 1e-9
                    else abs(dy * (x - x0) - dx * (y - y0)) / length)
        if distance > worst:
            worst, index = distance, i
    if worst <= tolerance:
        return [points[0], points[-1]]
    return simplify(points[:index + 1], tolerance)[:-1] + simplify(points[index:], tolerance)


def rings(width, height, tolerance, min_ring, samples=3):
    """Simplified coastline rings, projected. Antarctica is dropped, splinters with it."""
    out = []
    for feature in json.loads(LAND.read_text(encoding="utf-8"))["features"]:
        for ring in feature["geometry"]["coordinates"]:
            if max(lat for _, lat in ring) < ANTARCTIC:
                continue
            run = []
            previous = None
            for lon, lat in ring:
                lat = max(lat, ANTARCTIC)
                if previous is not None:
                    for k in range(1, samples):
                        t = k / samples
                        run.append(project(previous[0] + (lon - previous[0]) * t,
                                           previous[1] + (lat - previous[1]) * t, width, height))
                run.append(project(lon, lat, width, height))
                previous = (lon, lat)
            if len(run) < 4:
                continue
            xs = [x for x, _ in run]
            ys = [y for _, y in run]
            if math.hypot(max(xs) - min(xs), max(ys) - min(ys)) < min_ring:
                continue
            simple = simplify(run, tolerance)
            # A closed ring's first and last vertex are the same point; the triangulator
            # wants each vertex once.
            if len(simple) > 3 and math.dist(simple[0], simple[-1]) < 1e-6:
                simple = simple[:-1]
            if len(simple) >= 3:
                out.append(simple)
    return out


def inside(point, polygon):
    """Ray-cast point-in-polygon, used to keep the mesh on land."""
    x, y = point
    hit = False
    count = len(polygon)
    for i in range(count):
        x0, y0 = polygon[i]
        x1, y1 = polygon[(i + 1) % count]
        if (y0 > y) != (y1 > y):
            cut = x0 + (y - y0) * (x1 - x0) / (y1 - y0)
            if x < cut:
                hit = not hit
    return hit


def mesh_edges(ring, grid):
    """Delaunay edges over a landmass: its own corners plus interior points on a `grid`."""
    xs = [x for x, _ in ring]
    ys = [y for _, y in ring]
    points = list(ring)
    # Staggered rows, not a square grid: a square one triangulates into a repeating diamond
    # lattice, and Eurasia then reads as graph paper with a coastline drawn on it.
    row = 0
    y = min(ys) + grid / 2.0
    while y < max(ys):
        offset = grid / 2.0 if row % 2 else 0.0
        x = min(xs) + grid / 2.0 + offset
        while x < max(xs):
            if inside((x, y), ring):
                points.append((x, y))
            x += grid
        y += grid * 0.87
        row += 1
    if len(points) < 4:
        return [], points
    try:
        mesh = Delaunay(np.array(points))
    except Exception:
        # A degenerate landmass (every vertex on one line after simplification) has no
        # triangulation. It still gets its outline and its dots.
        return [], points
    edges = set()
    for a, b, c in mesh.simplices:
        centre = ((points[a][0] + points[b][0] + points[c][0]) / 3.0,
                  (points[a][1] + points[b][1] + points[c][1]) / 3.0)
        if not inside(centre, ring):
            continue
        for i, j in ((a, b), (b, c), (c, a)):
            edges.add((min(i, j), max(i, j)))
    return [(points[i], points[j]) for i, j in sorted(edges)], points


def render(scale):
    """One frame, as an alpha mask at `scale` density."""
    width = int(round(PANEL_DP * scale)) * SS
    height = int(round(PANEL_DP / ASPECT * scale)) * SS
    canvas = Image.new("L", (width, height), 0)
    draw = ImageDraw.Draw(canvas)

    def pen(dp):
        """Quantised in final pixels: rounding in supersampled space makes the same dp land
        on a different weight in every density bucket."""
        return max(1, int(round(dp * scale))) * SS

    coast = rings(width, height, SIMPLIFY_DP * scale * SS, MIN_RING_DP * scale * SS)

    mesh = pen(MESH_DP)
    corners = []
    for ring in coast:
        edges, points = mesh_edges(ring, MESH_GRID_DP * scale * SS)
        for a, b in edges:
            draw.line([a, b], fill=INK_MESH, width=mesh)
        corners.extend(points)

    outline = pen(COAST_DP)
    for ring in coast:
        draw.line(ring + [ring[0]], fill=INK_COAST, width=outline, joint="curve")

    radius = pen(DOT_DP) / 2.0
    for index, (x, y) in enumerate(corners):
        if index % DOT_EVERY:
            continue
        draw.ellipse([x - radius, y - radius, x + radius, y + radius], fill=INK_DOT)

    return canvas.resize((width // SS, height // SS), Image.LANCZOS)


def main() -> None:
    print(f"aspect {ASPECT:.3f} -> {PANEL_DP / ASPECT:.1f} dp tall at {PANEL_DP:.1f} dp wide")
    PREVIEW.mkdir(parents=True, exist_ok=True)
    for folder, scale in DENSITIES.items():
        alpha = render(scale)
        out = Image.merge("LA", (Image.new("L", alpha.size, 255), alpha)).convert("RGBA")
        path = RES / folder
        path.mkdir(parents=True, exist_ok=True)
        target = path / "yukari_worldmap.webp"
        out.save(target, quality=92, method=6)
        print(f"  {folder}/{target.name}  {out.width}x{out.height}"
              f"  {target.stat().st_size / 1024:.0f} KiB")
        if scale == 3.0:
            page = Image.new("RGB", alpha.size, (254, 254, 254))
            page.paste(Image.new("RGB", alpha.size, (192, 192, 192)), (0, 0), alpha)
            page.save(PREVIEW / "wireframe.png")


if __name__ == "__main__":
    main()
