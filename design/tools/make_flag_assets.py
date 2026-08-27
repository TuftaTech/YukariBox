"""Generate the server row's flat greyscale country flags.

The row's flag slot went through three answers before this one. Two ink letters (honest,
but the reference draws a flag); then the platform's own emoji glyph, desaturated (a real
flag, every country, no assets — but emoji flags are drawn as waving stickers with a
shaded fold, and the reference draws flat rectangles). This is the third: the flag as a
flat rectangle, greyscale, baked here and committed, so every device draws the same thing
regardless of which emoji set its ROM happens to ship.

Source: `flagcdn.com`, the CDN behind flagpedia.net, which publishes national flags as
flat PNGs addressed by ISO 3166-1 alpha-2 code. Its terms (flagpedia.net/terms) state
that "the flag images provided on our website are in the public domain and can be used
freely without restriction", and its download page names software and mobile apps
explicitly — so nothing here needs a notice in the app. Nothing else about them is used,
no names and no metadata, and the download is a one-off that only this script performs:
the app ships the WebPs, not the URL.

Tone, and why not a plain channel average: the flags that differ only in hue must differ
in *tone*, so the conversion is a luminance in **linear light** — sRGB decoded, weighted
Rec. 709, encoded again. A naive `(R+G+B)/3` collapses Brazil's blue globe into its green
field (62 against 66) where this keeps them 45 against 133; a naive gamma-space luminance
is the opposite problem, dropping every saturated red into the 50s where the reference
draws them near 90. The measured reference flags sit at black 10-13, red 79-100, blue
73-80, gold 237, white 249-253, and this lands inside that band without being fitted to
it — the mockup is generated art and is not self-consistent (it draws three different
reds), so it is a sanity check, not a target.

Both ends are then clamped to the palette — nothing darker than `ink` (#0D0D0D) and
nothing lighter than one level under `page` (#FEFEFE), so a flag never out-blacks the
bottom bar or out-whites the page it sits on — and the clamp is applied *after* the
resample, because Lanczos rings on the hard edge between two bands and would otherwise
push a stripe's border back out to 0 or 255.

Geometry: the plate is 37 x 25 dp, and the reference **stretches** a flag to fill it (its
US flag is 38 x 25 where the real one is 1.9:1, its UK flag 37 x 24 where the real one is
2:1). So a flag whose own aspect is anywhere near the plate's is stretched to fill it
exactly. A flag that is square or taller than wide — Switzerland, the Vatican, Nepal's
double pennant — is fitted instead, with transparent padding: squeezing a Swiss cross 48%
wider is not a flag any more, and the transparent margin lets the plate's own fill show
through in both themes rather than baking a light grey into the asset.

One size, not five densities: the assets are baked at the xxxhdpi plate (148 x 100 px) and
scaled down by whoever draws them. 252 flags x 5 buckets is 1,260 files for a slot the
size of a fingernail.

Run from the repo root: python design/tools/make_flag_assets.py
"""

from pathlib import Path
import io
import json
import sys
import tempfile
import urllib.request

import numpy as np
from PIL import Image

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "app" / "src" / "main" / "assets" / "flags"
CACHE = Path(tempfile.gettempdir()) / "yukari-flagcdn"

CODES_URL = "https://flagcdn.com/en/codes.json"
FLAG_URL = "https://flagcdn.com/w320/{code}.png"

# The xxxhdpi plate: 37 x 25 dp at 4x.
TARGET_W, TARGET_H = 148, 100

# Stretch to fill the plate at or above this aspect, fit inside it below.
STRETCH_MIN = 1.2

# The palette's two ends: `ink` and one level under `page`.
TONE_FLOOR, TONE_CEIL = 13, 250

REC709 = np.array([0.2126, 0.7152, 0.0722])


def fetch(url: str, name: str) -> bytes:
    CACHE.mkdir(parents=True, exist_ok=True)
    cached = CACHE / name
    if cached.exists():
        return cached.read_bytes()
    with urllib.request.urlopen(url, timeout=30) as response:
        payload = response.read()
    cached.write_bytes(payload)
    return payload


def to_linear(srgb: np.ndarray) -> np.ndarray:
    """sRGB 0..1 to linear light, the piecewise transfer function, not a bare 2.2."""
    return np.where(srgb <= 0.04045, srgb / 12.92, ((srgb + 0.055) / 1.055) ** 2.4)


def to_srgb(linear: np.ndarray) -> np.ndarray:
    return np.where(linear <= 0.0031308, linear * 12.92, 1.055 * linear ** (1 / 2.4) - 0.055)


def greyscale(image: Image.Image) -> Image.Image:
    rgba = np.asarray(image.convert("RGBA")).astype(np.float64) / 255.0
    luminance = to_srgb(to_linear(rgba[:, :, :3]) @ REC709)
    grey = np.repeat(np.rint(luminance * 255.0)[:, :, None], 3, axis=2)
    out = np.concatenate([grey, np.rint(rgba[:, :, 3:] * 255.0)], axis=2)
    return Image.fromarray(np.clip(out, 0, 255).astype("uint8"), "RGBA")


def clamp_tone(plate: Image.Image) -> Image.Image:
    """Both ends into the palette. After the resample, not before: Lanczos rings on the
    hard edge between two bands and would push a stripe back out to 0 or 255."""
    pixels = np.asarray(plate).astype(int)
    tone = np.clip(pixels[:, :, :3], TONE_FLOOR, TONE_CEIL)
    out = np.concatenate([tone, pixels[:, :, 3:]], axis=2)
    return Image.fromarray(out.astype("uint8"), "RGBA")


def to_plate(flag: Image.Image) -> Image.Image:
    """The flag on a 148 x 100 canvas: stretched to fill, or fitted and padded."""
    if flag.width / flag.height >= STRETCH_MIN:
        return flag.resize((TARGET_W, TARGET_H), Image.LANCZOS)
    scale = min(TARGET_W / flag.width, TARGET_H / flag.height)
    size = (max(1, round(flag.width * scale)), max(1, round(flag.height * scale)))
    canvas = Image.new("RGBA", (TARGET_W, TARGET_H), (0, 0, 0, 0))
    canvas.alpha_composite(flag.resize(size, Image.LANCZOS),
                           ((TARGET_W - size[0]) // 2, (TARGET_H - size[1]) // 2))
    return canvas


def main() -> int:
    codes = sorted(code for code in json.loads(fetch(CODES_URL, "codes.json")) if len(code) == 2)
    OUT.mkdir(parents=True, exist_ok=True)
    for stale in OUT.glob("*.webp"):
        stale.unlink()
    total = 0
    for code in codes:
        payload = fetch(FLAG_URL.format(code=code), f"{code}.png")
        plate = clamp_tone(to_plate(greyscale(Image.open(io.BytesIO(payload)))))
        pixels = np.asarray(plate).astype(int)
        assert (pixels[:, :, 0] == pixels[:, :, 1]).all(), code
        assert (pixels[:, :, 1] == pixels[:, :, 2]).all(), code
        path = OUT / f"{code}.webp"
        plate.save(path, "WEBP", lossless=True, quality=100, method=6)
        total += path.stat().st_size
    print(f"{len(codes)} flags, {total / 1024:.0f} KiB in {OUT.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
