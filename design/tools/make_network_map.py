"""Render the Home watermark as a snapshot of a server network.

Not a drawing and not a generated image: the frame is computed. Coastlines come from the
same Natural Earth 1:110m polygons the halftone map uses, the nodes sit at the real
coordinates of the hubs a subscription actually serves, and the links are the routes
between them, bowed the way a flight path is.

Two treatments, because they are two different pictures:

  curved  the map bent over a horizon, which is what the owner's reference shows — a flat
          world warped along an arc, not a true globe. A real orthographic sphere was tried
          first and is wrong for this: at any radius that keeps the horizon inside a 200 dp
          band, all that is left on screen is the Arctic.
  flat    the Miller map the app already ships, with the links bowed over it.

Everything is emitted the way the halftone is: a pure alpha mask (white RGB, alpha carrying
the ink) at five densities, each rasterised from the geometry rather than resampled, so
nothing can moire and one file serves both themes. Alpha is the only tone control there is,
and it is spent deliberately — coastline, idle link, live link, node, halo and packet each
have their own level, and the app tints all of it from `dot`.

One asset ships and it is the app's single watermark slot, `yukari_worldmap` — the same name
`make_worldmap_asset.py` writes. Whichever generator you run last owns the slot, so switching
the whole look is one command and a rebuild, and nothing in the app changes.

Run from the repo root:
    python design/tools/make_network_map.py                # curved, five densities
    python design/tools/make_network_map.py --kind flat     # the other treatment
    python design/tools/make_network_map.py --preview       # PNGs of both, nothing shipped
"""

from pathlib import Path
import argparse
import json
import math

from PIL import Image, ImageDraw

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

# The band the watermark occupies, in dp: the dev panel's width, and the height the
# projected halftone settled on, so swapping one for the other moves nothing on Home.
PANEL_DP = 1220 / 3.0
BAND_DP = 200.5

# Supersampling. Pillow draws no anti-aliased line, and this picture is nothing but lines.
SS = 4

# Ink, as alpha over a `dot` tint (`Color.kt`: #C0C0C0 light, #4A4A4A dark). The ceiling is 255 because
# `dot` *is* the watermark's darkest value in the palette; what these levels set is the
# order of reading inside it — coast under links, links under nodes.
INK_LINK = 132
INK_COAST = 255
INK_MESH = 58
INK_HALO = 74
INK_LIVE = 190
INK_NODE = 255
INK_PACKET = 255

# Stroke widths and radii, in dp.
COAST_DP = 0.7
LINK_DP = 0.55
LIVE_DP = 0.7
NODE_DP = 1.4
LIVE_NODE_DP = 2.0
HALO_DP = 4.5
PACKET_DP = 1.6

# How high a link bows, as a fraction of the straight-line distance between its ends.
BOW = 0.26

# The coastline is simplified until no vertex is further than this from the line it sits on
# (Douglas-Peucker, in dp of the drawn map). That angular outline is the reference's, and it
# is also what makes the mesh chords below land on corners instead of mid-curve.
SIMPLIFY_DP = 0.9

# Rings whose projected bounding box is smaller than this are dropped. Without it the
# archipelagos simplify into confetti — a scatter of two-vertex triangles that reads as dirt
# on the screen rather than as islands.
MIN_RING_DP = 7.0

# Chords across a continent's interior, as the reference draws them: about four per
# landmass, each cutting from one coast to another. A chord joins a vertex to the one a
# sixth of the ring ahead of it, and the starting vertices are a quarter of the ring apart.
# Only rings with at least `MESH_MIN` vertices get any — an island needs no diagonal, and a
# denser rule than this turns Eurasia into a cobweb.
MESH_CHORDS = 4
MESH_MIN = 18

# A dot on the crown of every link at least this long, in dp. The reference puts one at the
# top of each bow, and they are what makes the arcs read as a lattice rather than as swoops.
APEX_MIN_DP = 90.0

# No two nodes closer than this on screen. A subscription's hubs cluster in Europe hard
# enough to draw one grey blob there, and the picture is about reach, not about Frankfurt.
NODE_GAP_DP = 16.0

# Everything south of this is Antarctica, which neither reference draws.
ANTARCTIC = -56.0

# The frame of the simulation: which links are carrying traffic and where their packet has
# got to, as a fraction of the link. Written out by name rather than sampled, because a
# seeded sample is keyed to how many edges survived the node thinning — which is a function
# of the density being rendered — and to `random.sample`'s algorithm, which Python does not
# promise across versions. Both would let the picture change without anyone editing it.
LIVE_ROUTES = [
    ("New York", "Frankfurt", 0.38),
    ("Frankfurt", "Dubai", 0.55),
    ("Singapore", "Tokyo", 0.30),
    ("San Jose", "Tokyo", 0.62),
    ("Johannesburg", "Sao Paulo", 0.47),
    ("Miami", "Sao Paulo", 0.58),
    ("Mumbai", "Singapore", 0.35),
    ("Auckland", "Sydney", 0.50),
    ("Moscow", "Almaty", 0.44),
]


# The hubs, at their real coordinates, in priority order: the thinning below keeps the
# earlier one when two land on top of each other. This is the list `core/NodeGeo` can name —
# the cities a subscription's labels actually mention — so the picture is of this app's
# world rather than of a stock airline route map.
HUBS = [
    ("Frankfurt", 50.11, 8.68), ("Amsterdam", 52.37, 4.90), ("London", 51.51, -0.13),
    ("New York", 40.71, -74.01), ("Tokyo", 35.68, 139.65), ("Singapore", 1.35, 103.82),
    ("Moscow", 55.76, 37.62), ("San Jose", 37.34, -121.89), ("Sao Paulo", -23.55, -46.63),
    ("Sydney", -33.87, 151.21), ("Dubai", 25.20, 55.27), ("Mumbai", 19.08, 72.88),
    ("Hong Kong", 22.32, 114.17), ("Johannesburg", -26.20, 28.05), ("Toronto", 43.65, -79.38),
    ("Paris", 48.86, 2.35), ("Stockholm", 59.33, 18.07), ("Istanbul", 41.01, 28.98),
    ("Seoul", 37.57, 126.98), ("Madrid", 40.42, -3.70), ("Warsaw", 52.23, 21.01),
    ("Helsinki", 60.17, 24.94), ("Zurich", 47.38, 8.54), ("Milan", 45.46, 9.19),
    ("Vienna", 48.21, 16.37), ("Prague", 50.08, 14.44), ("Bucharest", 44.43, 26.10),
    ("Athens", 37.98, 23.73), ("Dublin", 53.35, -6.26), ("Lisbon", 38.72, -9.14),
    ("Riga", 56.95, 24.11), ("Tallinn", 59.44, 24.75), ("Kyiv", 50.45, 30.52),
    ("Almaty", 43.24, 76.89), ("Tbilisi", 41.72, 44.79), ("Tel Aviv", 32.09, 34.78),
    ("Cairo", 30.04, 31.24), ("Lagos", 6.52, 3.38), ("Nairobi", -1.29, 36.82),
    ("Taipei", 25.03, 121.57), ("Jakarta", -6.21, 106.85), ("Bangkok", 13.76, 100.50),
    ("Auckland", -36.85, 174.76), ("Chicago", 41.88, -87.63), ("Dallas", 32.78, -96.80),
    ("Miami", 25.76, -80.19), ("Seattle", 47.61, -122.33), ("Vancouver", 49.28, -123.12),
    ("Buenos Aires", -34.60, -58.38), ("Santiago", -33.45, -70.67), ("Bogota", 4.71, -74.07),
]

# Long-haul links by name. Nearest-neighbour edges alone draw dense local meshes and an
# empty ocean; these are the routes that make it one network.
BACKBONE = [
    ("New York", "London"), ("New York", "Frankfurt"), ("Toronto", "Amsterdam"),
    ("San Jose", "Tokyo"), ("Seattle", "Seoul"), ("San Jose", "Singapore"),
    ("Miami", "Sao Paulo"), ("Sao Paulo", "Lisbon"), ("Buenos Aires", "Madrid"),
    ("Frankfurt", "Dubai"), ("Amsterdam", "Mumbai"), ("Dubai", "Singapore"),
    ("Singapore", "Sydney"), ("Singapore", "Tokyo"), ("Hong Kong", "Tokyo"),
    ("Moscow", "Almaty"), ("Almaty", "Hong Kong"), ("Istanbul", "Dubai"),
    ("Cairo", "Frankfurt"), ("Lagos", "Lisbon"), ("Johannesburg", "Dubai"),
    ("Johannesburg", "Sao Paulo"), ("Nairobi", "Mumbai"), ("Auckland", "Sydney"),
    ("Jakarta", "Singapore"), ("Taipei", "Hong Kong"), ("Mumbai", "Singapore"),
    ("Tokyo", "Sydney"), ("Vancouver", "Tokyo"), ("Chicago", "Dublin"),
    ("Bogota", "Miami"), ("Santiago", "Sao Paulo"), ("Tel Aviv", "Frankfurt"),
    ("Bangkok", "Singapore"), ("Seoul", "Tokyo"), ("Dallas", "San Jose"),
    # Four more long chords, added for the picture rather than for plausibility: the
    # reference's arcs cross each other into a lattice, and with the hubs thinned this far
    # the nearest-neighbour mesh alone leaves the sky empty.
    ("New York", "Tokyo"), ("San Jose", "Sydney"), ("Lagos", "Miami"),
    ("Istanbul", "Mumbai"),
]


def great_circle(lat_a, lon_a, lat_b, lon_b):
    """Central angle between two points, in radians."""
    pa, pb = math.radians(lat_a), math.radians(lat_b)
    delta = math.radians(lon_b - lon_a)
    return math.acos(max(-1.0, min(1.0, math.sin(pa) * math.sin(pb)
                                    + math.cos(pa) * math.cos(pb) * math.cos(delta))))


def topology(hubs, neighbours=2):
    """Every hub wired to its nearest few, plus [BACKBONE]. Deterministic."""
    index = {name: i for i, (name, _, _) in enumerate(hubs)}
    edges = set()
    for i, (_, lat_a, lon_a) in enumerate(hubs):
        near = sorted((great_circle(lat_a, lon_a, lat_b, lon_b), j)
                      for j, (_, lat_b, lon_b) in enumerate(hubs) if j != i)
        for _, j in near[:neighbours]:
            edges.add((min(i, j), max(i, j)))
    for a, b in BACKBONE:
        if a in index and b in index:
            i, j = index[a], index[b]
            edges.add((min(i, j), max(i, j)))
    return sorted(edges)


class Flat:
    """The Miller map the app already ships. Latitudes as in `make_worldmap_asset.py`."""

    name = "flat"

    def __init__(self, width, height, north=84.0, south=-56.0):
        self.w, self.h = width, height
        self.yn, self.ys = self._miller(north), self._miller(south)

    @staticmethod
    def _miller(lat):
        return 1.25 * math.log(math.tan(math.pi / 4 + 0.4 * math.radians(lat)))

    # Fraction of the frame's height the map is drawn into, and where that band starts.
    # The flat treatment uses all of it; the curved one leaves room under the map for its
    # own bend and a margin above it, so the Arctic coast is a coast and not a cut.
    FILL = 1.0
    TOP = 0.0

    def project(self, lat, lon):
        x = (math.radians(lon) + math.pi) / (2 * math.pi) * self.w
        y = self.TOP * self.h +             (self.yn - self._miller(lat)) / (self.yn - self.ys) * self.h * self.FILL
        return (x, y)


class Curved(Flat):
    """The same map bent over a horizon: the owner's reference, and not a real sphere.

    Two terms, both quadratic in the distance from the centre column. `DROP` pushes the
    edges of the map down, which is what draws the horizon as an arc; `SQUEEZE` flattens the
    latitudes there, which is the foreshortening a sphere would give. Everything stays
    single-valued, so a coastline is still a polyline and a link is still a bow.
    """

    name = "curved"

    FILL = 0.76
    TOP = 0.05
    DROP = 0.17
    SQUEEZE = 0.15

    def project(self, lat, lon):
        x, y = super().project(lat, lon)
        xn = (x / self.w) * 2.0 - 1.0
        return (x, y * (1.0 - self.SQUEEZE * xn * xn) + self.DROP * self.h * xn * xn)


def simplify(points, tolerance):
    """Douglas-Peucker on a projected polyline. Angular is the point, not a saving."""
    if len(points) < 3:
        return points
    (x0, y0), (x1, y1) = points[0], points[-1]
    dx, dy = x1 - x0, y1 - y0
    length = math.hypot(dx, dy)
    worst, index = -1.0, 0
    for i in range(1, len(points) - 1):
        x, y = points[i]
        if length < 1e-9:
            distance = math.hypot(x - x0, y - y0)
        else:
            distance = abs(dy * (x - x0) - dx * (y - y0)) / length
        if distance > worst:
            worst, index = distance, i
    if worst <= tolerance:
        return [points[0], points[-1]]
    left = simplify(points[:index + 1], tolerance)
    right = simplify(points[index:], tolerance)
    return left[:-1] + right


def mesh_chords(ring):
    """The long chords the reference draws across a continent's interior."""
    count = len(ring)
    if count < MESH_MIN:
        return []
    span = max(2, count // 6)
    step = max(2, count // MESH_CHORDS)
    return [[ring[i], ring[(i + span) % count]] for i in range(0, count, step)]


def coast_paths(projection, tolerance, min_ring, samples=3):
    """Coastline rings, projected, subdivided so long spans follow the bend, then simplified.

    Subdivide first and simplify after, in that order: subdivision is what lets a long
    segment bend, and the simplifier is what turns the result back into few straight runs
    with visible corners — which is the reference's outline and what the interior chords
    need to land on.
    """
    paths = []
    for feature in json.loads(LAND.read_text(encoding="utf-8"))["features"]:
        for ring in feature["geometry"]["coordinates"]:
            if max(lat for _, lat in ring) < ANTARCTIC:
                continue
            run = []
            previous = None
            for lon, lat in ring:
                if lat < ANTARCTIC:
                    lat = ANTARCTIC
                if previous is not None:
                    for k in range(1, samples):
                        t = k / samples
                        run.append(projection.project(previous[1] + (lat - previous[1]) * t,
                                                     previous[0] + (lon - previous[0]) * t))
                run.append(projection.project(lat, lon))
                previous = (lon, lat)
            if len(run) < 2:
                continue
            xs = [x for x, _ in run]
            ys = [y for _, y in run]
            if math.hypot(max(xs) - min(xs), max(ys) - min(ys)) < min_ring:
                continue
            paths.append(simplify(run, tolerance))
    return paths


def link_path(projection, a, b, steps=48):
    """A bowed link between two hubs, in screen space.

    Screen space rather than a great circle on purpose: a great circle from San Jose to
    Tokyo crosses the antimeridian, and on a flat map the honest rendering of that is two
    stubs at opposite edges. Every route map in this genre draws the chord instead, and so
    does the owner's reference — the long arcs sweeping across the frame are the point.
    """
    (x0, y0) = projection.project(a[1], a[2])
    (x1, y1) = projection.project(b[1], b[2])
    span = math.hypot(x1 - x0, y1 - y0)
    if span < 1e-6:
        return []
    # Control point of a quadratic bezier, offset perpendicular to the chord and always
    # toward the top of the frame, so links bow away from the map rather than through it.
    mx, my = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    nx, ny = -(y1 - y0) / span, (x1 - x0) / span
    if ny > 0:
        nx, ny = -nx, -ny
    cx, cy = mx + nx * BOW * span, my + ny * BOW * span
    points = []
    for i in range(steps + 1):
        t = i / steps
        u = 1.0 - t
        points.append((u * u * x0 + 2 * u * t * cx + t * t * x1,
                       u * u * y0 + 2 * u * t * cy + t * t * y1))
    return [points]


def visible_hubs(projection, scale):
    """Hubs that fit the frame, thinned so no two sit closer than [NODE_GAP_DP]."""
    gap = NODE_GAP_DP * scale * SS
    kept = []
    for index, hub in enumerate(HUBS):
        x, y = projection.project(hub[1], hub[2])
        if not (0 <= x <= projection.w and 0 <= y <= projection.h):
            continue
        if any(math.hypot(x - px, y - py) < gap for px, py, _ in kept):
            continue
        kept.append((x, y, index))
    return kept


def render(projection, scale):
    """One frame, as an alpha mask at `scale` density."""
    canvas = Image.new("L", (projection.w, projection.h), 0)
    draw = ImageDraw.Draw(canvas)
    # The graph is built over the hubs that survived the thinning, not over all of them and
    # then filtered: filtering drops every edge that pointed at a thinned hub, which left a
    # third of the mesh missing and Stockholm on screen with no link at all.
    nodes = visible_hubs(projection, scale)
    hubs = [HUBS[index] for _, _, index in nodes]
    places = {hub[0]: i for i, hub in enumerate(hubs)}
    edges = topology(hubs)
    wired = set(edges)
    live = {}
    for a, b, position in LIVE_ROUTES:
        pair = None
        if a in places and b in places:
            pair = (min(places[a], places[b]), max(places[a], places[b]))
        if pair is None or pair not in wired:
            # Loud on purpose: a route whose endpoint lost the thinning, or that the mesh
            # never wired, silently ships one packet fewer than every document claims.
            print(f"  !! live route {a}-{b} is not on the map at this density")
            continue
        live[pair] = position

    def pen(dp):
        """Stroke width, quantised in *final* pixels: rounding it in supersampled space
        made the same dp land on a different weight in each density bucket."""
        return max(1, int(round(dp * scale))) * SS

    def stroke(runs, ink, dp):
        width = pen(dp)
        for run in runs:
            draw.line([(round(x), round(y)) for x, y in run], fill=ink, width=width, joint="curve")

    def disc(x, y, dp, ink):
        r = pen(dp) / 2.0
        draw.ellipse([x - r, y - r, x + r, y + r], fill=ink)

    idle, hot = [], []
    for pair in edges:
        runs = link_path(projection, hubs[pair[0]], hubs[pair[1]])
        (hot if pair in live else idle).extend(runs)

    # Ascending ink, so a later element always wins its crossings: the interior mesh under
    # the links, the links under the coast, the live ones over it, the nodes over everything.
    rings = coast_paths(projection, SIMPLIFY_DP * scale * SS, MIN_RING_DP * scale * SS)
    stroke([chord for ring in rings for chord in mesh_chords(ring)], INK_MESH, COAST_DP)
    stroke(idle, INK_LINK, LINK_DP)
    stroke(rings, INK_COAST, COAST_DP)
    endpoints = {i for pair in live for i in pair}
    for i in endpoints:
        x, y = projection.project(hubs[i][1], hubs[i][2])
        disc(x, y, HALO_DP, INK_HALO)
    stroke(hot, INK_LIVE, LIVE_DP)
    for i, hub in enumerate(hubs):
        x, y = projection.project(hub[1], hub[2])
        disc(x, y, LIVE_NODE_DP if i in endpoints else NODE_DP, INK_NODE)

    # A dot on the crown of every long bow. The reference has one at the top of each arc, and
    # they are what make the links read as a lattice rather than as a handful of swoops.
    for pair in edges:
        (x0, y0) = projection.project(hubs[pair[0]][1], hubs[pair[0]][2])
        (x1, y1) = projection.project(hubs[pair[1]][1], hubs[pair[1]][2])
        if math.hypot(x1 - x0, y1 - y0) < APEX_MIN_DP * scale * SS:
            continue
        runs = link_path(projection, hubs[pair[0]], hubs[pair[1]])
        if runs:
            disc(*runs[0][len(runs[0]) // 2], NODE_DP, INK_NODE)

    # A packet in flight on each live link: the one thing that makes this a frame of a
    # simulation rather than a diagram. Its position comes from [LIVE_ROUTES], so it is a
    # decision in the table rather than a draw from a generator.
    for pair, position in live.items():
        runs = link_path(projection, hubs[pair[0]], hubs[pair[1]])
        if not runs:
            continue
        run = runs[0]
        disc(*run[round(position * (len(run) - 1))], PACKET_DP, INK_PACKET)

    return canvas.resize((projection.w // SS, projection.h // SS), Image.LANCZOS)


def frame(kind, scale):
    width = int(round(PANEL_DP * scale)) * SS
    height = int(round(BAND_DP * scale)) * SS
    projection = Curved(width, height) if kind == "curved" else Flat(width, height)
    return render(projection, scale)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", action="store_true",
                        help="write a PNG of each treatment to design/tools/preview and stop")
    parser.add_argument("--kind", choices=("curved", "flat"), default="curved",
                        help="which treatment to ship (default: curved, the owner's reference)")
    args = parser.parse_args()
    if args.preview:
        PREVIEW.mkdir(parents=True, exist_ok=True)
        for kind in ("curved", "flat"):
            alpha = frame(kind, 3.0)
            page = Image.new("RGB", alpha.size, (254, 254, 254))
            page.paste(Image.new("RGB", alpha.size, (192, 192, 192)), (0, 0), alpha)
            path = PREVIEW / f"network-{kind}.png"
            page.save(path)
            print(f"  {path.relative_to(REPO)}  {alpha.width}x{alpha.height}")
        return
    for folder, scale in DENSITIES.items():
        alpha = frame(args.kind, scale)
        white = Image.new("L", alpha.size, 255)
        out = Image.merge("LA", (white, alpha)).convert("RGBA")
        path = RES / folder
        path.mkdir(parents=True, exist_ok=True)
        target = path / "yukari_worldmap.webp"
        out.save(target, quality=92, method=6)
        print(f"  {folder}/{target.name}  {out.width}x{out.height}"
              f"  {target.stat().st_size / 1024:.0f} KiB  ({args.kind})")


if __name__ == "__main__":
    main()
