#!/usr/bin/env python3
"""Writes app/src/main/res/drawable/{ui,ms}_*.xml from desktop's Material Symbols font.

Desktop renders its icons with the `material-symbols` npm package (Material
Symbols Rounded, a variable font). This instances that same font at the axis
values desktop's CSS gives each icon and converts the glyph to an Android
vector, so Android draws the exact outlines desktop draws on Windows.

    python3 tools/icons/generate.py ../LyricsAdapter

needs fontTools and brotli (`pip install fonttools brotli`). The argument is
the desktop checkout, with `npm install` done.
"""

import sys
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

# Default `.material-symbols-rounded`: FILL 0, wght 400, GRAD 0; the browser picks opsz from the
# font size, which is 24px for nearly every icon on desktop.
UI = {"FILL": 0, "wght": 400, "GRAD": 0, "opsz": 24}
# The filled variant at the same size, for desktop's `fill-icon` glyphs (player bar, selection marks).
UI_FILLED = {**UI, "FILL": 1}
# `.focus-portrait-controls .material-symbols-rounded` in FocusModeLayout.css.
FOCUS = {"FILL": 1, "wght": 400, "GRAD": 0, "opsz": 48}

# Drawable name -> (Material Symbols name, axes, why).
ICONS = {
    "ui_add": ("add", UI, None),
    "ui_add_photo_alternate": ("add_photo_alternate", UI, None),
    "ui_arrow_back": ("arrow_back", UI, None),
    "ui_auto_awesome_mosaic": ("auto_awesome_mosaic", UI, None),
    "ui_check": ("check", UI, None),
    "ui_check_circle": ("check_circle", UI_FILLED, None),
    "ui_checklist": ("checklist", UI, None),
    "ui_chevron_right": ("chevron_right", UI, None),
    "ui_close": ("close", UI, None),
    "ui_cloud": ("cloud", UI, None),
    "ui_dark_mode": ("dark_mode", UI, None),
    "ui_delete": ("delete", UI, None),
    "ui_download": ("download", UI, None),
    "ui_edit": ("edit", UI, None),
    "ui_expand_more": ("expand_more", UI, None),
    "ui_extension": ("extension", UI, None),
    "ui_fullscreen": ("fullscreen", UI, None),
    "ui_hard_drive": ("hard_drive", UI, None),
    "ui_history": ("history", UI, None),
    "ui_info": ("info", UI, None),
    "ui_library_add": ("library_add", UI, None),
    "ui_create_new_folder": ("create_new_folder", UI, None),
    "ui_folder": ("folder", UI, None),
    "ui_light_mode": ("light_mode", UI, None),
    "ui_login": ("login", UI, None),
    "ui_logout": ("logout", UI, None),
    "ui_music_note": ("music_note", UI, None),
    "ui_my_location": ("my_location", UI, None),
    "ui_play_arrow": ("play_arrow", UI, None),
    "ui_queue_music": ("queue_music", UI, None),
    "ui_radio_button_unchecked": ("radio_button_unchecked", UI, None),
    "ui_refresh": ("refresh", UI, None),
    "ui_repeat": ("repeat", UI, None),
    "ui_save": ("save", UI, None),
    "ui_search": ("search", UI, None),
    "ui_settings": ("settings", UI, None),
    "ui_shuffle": ("shuffle", UI, None),
    "ui_sync": ("sync", UI, None),
    "ui_translate": ("translate", UI, None),
    "ui_tune": ("tune", UI, None),
    "ui_view_list": ("view_list", UI, None),
    "ui_visibility": ("visibility", UI, None),
    "ui_visibility_off": ("visibility_off", UI, None),
    "ui_volume_off": ("volume_off", UI, None),
    # Desktop's player bar (`Controls.tsx`): `fill-icon` transport glyphs.
    "ui_play_arrow_filled": ("play_arrow", UI_FILLED, "as desktop's player bar uses it"),
    "ui_pause_filled": ("pause", UI_FILLED, "as desktop's player bar uses it"),
    "ui_skip_next_filled": ("skip_next", UI_FILLED, "as desktop's player bar uses it"),
    # The bottom tab bar and mini player, in the style of iOS Apple Music: filled tab glyphs.
    "ui_library_music_filled": ("library_music", UI_FILLED, "for the library tab"),
    "ui_settings_filled": ("settings", UI_FILLED, "for the settings tab"),
    "ui_fast_forward_filled": ("fast_forward", UI_FILLED, "for the mini player"),
    "ui_fast_rewind_filled": ("fast_rewind", UI_FILLED, "for the mini player"),
    "ui_more_horiz": ("more_horiz", UI, None),
    # Desktop's portrait focus controls (`FocusPortraitControls.tsx`, `FocusTranslateButton.tsx`).
    **{
        f"ms_{name}": (name, FOCUS, "as desktop's portrait controls use it")
        for name in [
            "fast_rewind", "play_arrow", "pause", "fast_forward",
            "volume_off", "volume_down", "volume_up",
            "repeat", "repeat_one", "shuffle", "translate", "progress_activity",
        ]
    },
}

TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
    <group android:translateY="960">
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="{path}" />
    </group>
</vector>
"""


def glyph_path(font: TTFont, name: str) -> str:
    glyphs = font.getGlyphSet()
    pen = SVGPathPen(glyphs, ntos=lambda value: f"{value:g}")
    # Font units are y-up; the vector's group moves the y-down outline back into the viewport.
    glyphs[name].draw(TransformPen(pen, (1, 0, 0, -1, 0, 0)))
    return pen.getCommands()


def main() -> None:
    desktop = Path(sys.argv[1] if len(sys.argv) > 1 else "../LyricsAdapter")
    package = desktop / "node_modules/material-symbols"
    version = __import__("json").loads((package / "package.json").read_text())["version"]
    source = TTFont(package / "material-symbols-rounded.woff2")
    out = Path(__file__).resolve().parents[2] / "app/src/main/res/drawable"

    instances: dict[tuple, TTFont] = {}
    for drawable, (name, axes, why) in ICONS.items():
        key = tuple(sorted(axes.items()))
        if key not in instances:
            instances[key] = instancer.instantiateVariableFont(source, dict(axes), inplace=False)
        font = instances[key]
        # The font swaps in `<name>.fill` outlines once FILL is on.
        glyph = f"{name}.fill" if axes["FILL"] and f"{name}.fill" in font.getGlyphOrder() else name
        comment = (
            f'Material Symbols Rounded "{name}" (FILL {axes["FILL"]}, wght {axes["wght"]}, opsz {axes["opsz"]}), '
            f"material-symbols {version}, Apache-2.0" + (f", {why}" if why else "") + ". Generated by tools/icons/generate.py."
        )
        (out / f"{drawable}.xml").write_text(TEMPLATE.format(comment=comment, path=glyph_path(font, glyph)))
        print(f"{drawable}: {glyph}")


if __name__ == "__main__":
    main()
