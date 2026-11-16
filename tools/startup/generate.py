#!/usr/bin/env python3
"""Writes the startup screen's artwork from the desktop app's (`public/` in its checkout).

    python3 tools/startup/generate.py ../LyricsAdapter      # needs Pillow

`startup_background.jpg` is desktop's `wall-background.jpg`, its icon blurred into soft colour
fields. `startup_icon.webp` is desktop's `default-cover.jpg` as its startup screen shows it
(`src/styles/startup.css`): corners rounded by 23 % and its two soft shadows, baked in, because the
system's launch window can only show a drawable. The icon takes the middle third of the picture:
Android 12's launch window draws its icon 288dp wide, which makes the logo 96dp there and in the app.
The colour printed at the end is the background's average, for `startup_background` in colors.xml.
"""
import os
import shutil
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageStat

DESKTOP = os.path.join(sys.argv[1], "public")
RES = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "res")
SCALE = 3  # xxhdpi
CANVAS = 288 * SCALE
ICON = 96 * SCALE
RADIUS = round(ICON * 0.23)
SHADOW = (94, 62, 145)
# CSS `box-shadow`: vertical offset, blur radius (twice the Gaussian's deviation) and opacity.
SHADOWS = [(18, 48, 0.2), (2, 6, 0.08)]


def rounded_mask(size, radius):
    # Drawn four times as large and scaled down, for smooth corners.
    large = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(large).rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1), radius * 4, fill=255)
    return large.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(os.path.join(RES, "drawable-nodpi"), exist_ok=True)
    os.makedirs(os.path.join(RES, "drawable-xxhdpi"), exist_ok=True)
    background = os.path.join(DESKTOP, "wall-background.jpg")
    shutil.copyfile(background, os.path.join(RES, "drawable-nodpi", "startup_background.jpg"))

    mask = rounded_mask(ICON, RADIUS)
    origin = (CANVAS - ICON) // 2
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    for offset, blur, opacity in SHADOWS:
        shape = Image.new("L", (CANVAS, CANVAS), 0)
        shape.paste(mask, (origin, origin + offset * SCALE))
        alpha = shape.filter(ImageFilter.GaussianBlur(blur * SCALE / 2)).point(lambda value: round(value * opacity))
        shadow = Image.new("RGBA", (CANVAS, CANVAS), SHADOW + (0,))
        shadow.putalpha(alpha)
        canvas = Image.alpha_composite(canvas, shadow)
    icon = Image.open(os.path.join(DESKTOP, "default-cover.jpg")).convert("RGBA").resize((ICON, ICON), Image.LANCZOS)
    icon.putalpha(mask)
    layer = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    layer.paste(icon, (origin, origin))
    canvas = Image.alpha_composite(canvas, layer)
    canvas.save(os.path.join(RES, "drawable-xxhdpi", "startup_icon.webp"), "WEBP", quality=95, method=6)

    mean = ImageStat.Stat(Image.open(background).convert("RGB")).mean
    print("startup_background colour: #%02X%02X%02X" % tuple(round(value) for value in mean))


main()
