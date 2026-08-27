"""Generate the app's monochrome mascot assets from the owner's reference art.

The in-app mascot is greyscale by design: the master mockup's own in-app mascots are
greyscale line-art even though the source illustrations are full colour, and the
interface is strictly neutral. So the pipeline is: take the cut-out figure, drop the
hue, nudge contrast so the line-art still reads at avatar sizes, and emit the five
densities the app ships for.

Sources (`design/reference/`, deliberately not published -- see `design/README.md`),
one drawing per slot since 2026-08-26:

  08-shush.png   RGBA  winking, finger to her lips  -> `yukari_bust`   drawer + banner
  09-wave.png    RGBA  waving, both eyes open       -> `yukari_avatar` profile circle
  10-stand.png   RGBA  standing, arms crossed       -> `yukari_hero`   Home's stage
  11-lean.png    RGBA  chin on her hand             -> `yukari_lean`   Servers header

`12-thumbsup.png` is the launcher icon's source and is deliberately **not** built here:
the icon is an adaptive `mipmap` with its own background layer and monochrome layer, and
Android Studio's Image Asset Studio owns that set. It is committed so the icon can be
rebuilt from the same tree.

Four drawings rather than the three crops this replaced, because the Servers header and
Home's stage no longer share one figure: the header wants a bust that leans into the
band, the stage wants a full standing figure. `01-hero`, `02-bust`, `04-sketch` and
`06-hero-clean` are kept in the tree as the provenance of numbers the design system still
quotes, but nothing reads them any more.

Two things here are load-bearing and easy to undo by accident:

- **The source's own alpha is kept.** The pipeline this replaced hard-thresholded it
  (`alpha > 8 -> 255`), which was safe for `02-bust.png` — its matte is nearly binary,
  interior 250-254 — and is wrong for these four: they carry a genuine soft matte over
  ~3% of their pixels at luma 80-115, and promoting all of it to opaque draws a visible
  grey rectangle around her. The threshold survives as the *crop* rule only.
- **Scale is set by the hair mass, not by the drawing's box.** These four are framed
  differently from each other, so matching heights or widths would make her read as
  standing at four different distances. [hair_box] measures the one feature every crop
  shares and `main` prints it, so a change of art can be checked against the number the
  slot showed before.

The Home watermark is not here. What ships is `make_wireframe_map.py`'s computed frame,
with `make_network_map.py` and `make_worldmap_asset.py` as the alternatives for that same
slot.

Run from the repo root: python design/tools/make_mascot_assets.py
"""

from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / "design" / "reference"
RES = REPO / "app" / "src" / "main" / "res"

# Density buckets the app emits for, as multiples of dp. mdpi and hdpi are carried even
# though every minSdk-28 device in practice is xhdpi or denser: Android Lint's
# IconMissingDensityFolder fires on a partial set, and two more small WebPs cost less
# than an unexplained warning in the gate.
DENSITIES = {
    "drawable-mdpi": 1.0,
    "drawable-hdpi": 1.5,
    "drawable-xhdpi": 2.0,
    "drawable-xxhdpi": 3.0,
    "drawable-xxxhdpi": 4.0,
}

# What counts as ink when cropping. Not a matte threshold — see the module docstring.
ALPHA_FLOOR = 8

# The contrast window. Both ends are inside the sources' own range, so the stretch only
# takes the paper to white and the outline to black without clipping the hair's midtones.
LUMA_BLACK, LUMA_WHITE = 8, 252

# Above this the pixel is skin, shirt or paper; below it, hair or outline. Used only to
# find the hair mass, never to quantise the image.
HAIR_LUMA = 130


def load_figure(name: str) -> Image.Image:
    """Return the source as greyscale + its own alpha, cropped to the ink."""
    im = Image.open(SRC / name)
    if im.mode != "RGBA":
        raise SystemExit(f"{name}: expected a cut-out RGBA source, got {im.mode}")
    figure = Image.merge("LA", (im.convert("L"), im.getchannel("A")))
    box = im.getchannel("A").point(lambda v: 255 if v > ALPHA_FLOOR else 0).getbbox()
    return figure.crop(box)


def stretch(im: Image.Image, black: int, white: int) -> Image.Image:
    """Linear contrast stretch on the luma channel only, alpha untouched."""
    luma = np.array(im.getchannel(0)).astype(np.float32)
    luma = (luma - black) * (255.0 / (white - black))
    luma = np.clip(luma, 0, 255).astype(np.uint8)
    return Image.merge("LA", (Image.fromarray(luma), im.getchannel("A")))


def hair_box(im: Image.Image) -> tuple[int, int, int, int]:
    """Bounding box of the hair mass, which is what fixes her apparent scale.

    Dark *and* thick: the outline of the shirt is as dark as the hair, so a plain luma
    threshold returns one component covering the whole figure. An opening at ~1.2% of the
    width erases every line narrow enough to be an outline and leaves the fringe, the bob
    and the side locks — the silhouette a reader identifies her by.
    """
    luma = np.array(im.getchannel(0))
    alpha = np.array(im.getchannel("A"))
    mass = (alpha > 128) & (luma < HAIR_LUMA)
    span = max(2, round(im.width * 0.012))
    mass = ndimage.binary_opening(mass, np.ones((span, span)))
    labels, count = ndimage.label(mass)
    sizes = ndimage.sum(mass, labels, range(1, count + 1))
    rows, columns = np.where(labels == int(np.argmax(sizes)) + 1)
    return int(columns.min()), int(rows.min()), int(columns.max()) + 1, int(rows.max()) + 1


def square_avatar(figure: Image.Image, hair_share: float, hair_top: float) -> Image.Image:
    """Head-and-shoulders square, hair centred, for the one circular avatar.

    The circle is clipped in Compose and the asset is drawn `Crop`, so a fraction of this
    canvas is a fraction of the circle. Two numbers place her, and both are what the crop
    it replaced measured on screen: the hair mass spans [hair_share] of the width — just
    over the whole of it, so the circle cuts the side locks rather than leaving air beside
    them — and its top edge sits [hair_top] down, which lifts the face above centre. An
    avatar cropped on the geometric middle of a figure puts the chin in the middle and
    reads as a portrait of a T-shirt.

    Centred on the *hair*, not on the figure: this drawing raises a hand beside her head,
    and centring the whole silhouette would slide her face off to the left of the circle.
    """
    x0, y0, x1, _ = hair_box(figure)
    side = round((x1 - x0) / hair_share)
    canvas = Image.new("LA", (side, side), (255, 0))
    canvas.paste(figure, (side // 2 - (x0 + x1) // 2, round(side * hair_top) - y0))
    return canvas


def emit(im: Image.Image, stem: str, dp_height: int) -> None:
    """Write one asset at every density, sized so it renders at `dp_height` dp.

    WebP at q90 rather than PNG: on this greyscale line-art the luma error is at most 12
    levels and averages 0.9 (`measured`, xxhdpi) for half the bytes of lossless — visible
    nowhere on a figure whose own outline is anti-aliased, and worth the 60 KB. The alpha
    plane is not a trade at all: libwebp keeps it lossless whatever `quality` says.
    minSdk 28 decodes it everywhere.

    `dp_height` is the height the app actually draws, not a round number with headroom, so
    every density lands on a whole-pixel downscale of the source and none of them upscales.
    Change a slot's height in Kotlin and change it here in the same commit.
    """
    for folder, scale in DENSITIES.items():
        target_h = round(dp_height * scale)
        target_w = max(1, round(im.width * target_h / im.height))
        _write(im, stem, folder, target_w, target_h)


def _write(im: Image.Image, stem: str, folder: str, w: int, h: int) -> None:
    out = im.resize((w, h), Image.LANCZOS).convert("RGBA")
    path = RES / folder
    path.mkdir(parents=True, exist_ok=True)
    out.save(path / f"{stem}.webp", quality=90, method=6)


def report(stem: str, figure: Image.Image, slots: dict[str, float]) -> None:
    """Print what the Kotlin side has to agree with, and her apparent scale per slot.

    The aspect is quoted as the emitted pixel pair because that is the literal each call
    site carries: a box of the wrong ratio letterboxes a `ContentScale.Fit` drawing, which
    is a silent couple of dp between what a screen computes and what it draws.

    The hair width is the check that matters after a change of art. What the three crops
    this replaced put on screen, for comparison: Home 96 dp, Servers 107, drawer 103,
    banner 83, avatar 79.
    """
    x0, y0, x1, y1 = hair_box(figure)
    print(
        f"  ink {figure.width}x{figure.height} px, aspect {figure.width / figure.height:.4f}"
        f", hair {x1 - x0}x{y1 - y0} px"
    )
    for slot, drawn_w in slots.items():
        print(f"    {slot}: drawn {drawn_w:g} dp wide, hair {(x1 - x0) * drawn_w / figure.width:.1f} dp")


def main() -> None:
    print("hero — the full standing figure, arms crossed (10-stand) -> Home's stage")
    hero = stretch(load_figure("10-stand.png"), LUMA_BLACK, LUMA_WHITE)
    report("yukari_hero", hero, {"home": 126})
    emit(hero, "yukari_hero", dp_height=262)

    print("lean — chin on her hand (11-lean) -> the Servers header band")
    lean = stretch(load_figure("11-lean.png"), LUMA_BLACK, LUMA_WHITE)
    report("yukari_lean", lean, {"servers": 152})
    emit(lean, "yukari_lean", dp_height=182)

    print("bust — winking, finger to her lips (08-shush) -> the drawer and the banner")
    bust = stretch(load_figure("08-shush.png"), LUMA_BLACK, LUMA_WHITE)
    report("yukari_bust", bust, {"drawer": 118, "banner": 96})
    emit(bust, "yukari_bust", dp_height=126)

    print("avatar — a square of the waving figure (09-wave) -> the profile circle")
    wave = stretch(load_figure("09-wave.png"), LUMA_BLACK, LUMA_WHITE)
    avatar = square_avatar(wave, hair_share=0.99, hair_top=0.055)
    report("yukari_avatar", avatar, {"profile": 80})
    emit(avatar, "yukari_avatar", dp_height=96)


if __name__ == "__main__":
    main()
