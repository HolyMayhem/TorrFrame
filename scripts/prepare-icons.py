#!/usr/bin/env python3
"""Export Android density resources from the owner's untouched PNG masters.

Requires Pillow. Run from any directory with: python3 scripts/prepare-icons.py
"""
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageCms

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "android-tv/app/src/main/res"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def export(source: str, folder: str, name: str, base_size: tuple[int, int]) -> None:
    with Image.open(ROOT / "icons" / source) as original:
        image = original.convert("RGBA")
        assert image.width * base_size[1] == image.height * base_size[0], source
        # Android resources use sRGB; retain transparency and the supplied artwork.
        if profile := original.info.get("icc_profile"):
            image = ImageCms.profileToProfile(
                image, ImageCms.ImageCmsProfile(BytesIO(profile)),
                ImageCms.createProfile("sRGB"), outputMode="RGBA",
            )
        for density, scale in DENSITIES.items():
            destination = RES / f"{folder}-{density}" / f"{name}.png"
            destination.parent.mkdir(parents=True, exist_ok=True)
            size = tuple(round(edge * scale) for edge in base_size)
            image.resize(size, Image.Resampling.LANCZOS).save(destination, optimize=True)
            print(f"{destination.relative_to(ROOT)}: {size[0]}x{size[1]}")


if __name__ == "__main__":
    export("TorrFrame square icon.png", "mipmap", "app_icon", (80, 80))
    export("TorrFrame icon 16x9.png", "drawable", "tv_banner", (160, 90))
    export("TorrFrame FrameElement.png", "drawable", "brand_mark", (32, 32))
