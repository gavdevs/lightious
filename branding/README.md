# Lightious icon

A single L-shaped line and an open play triangle, with white artwork on black.
The standalone mark inherits its surrounding text color. Both paths use a 2.75-unit
stroke with rounded ends and joins, and no fill. The 16px raster favicon uses a
one-pixel optical stroke so the lines remain visible at browser-tab size.

- `lightious-mark.svg`: canonical two-path artwork, tightly framed for headers.
- `lightious-icon.svg`: square icon with an opaque black background.
- `lightious-icon.png`: 512px export for previews and reuse.
- `lightious-concept.png`: revised built-in image generation exploration.
- `concept-prompt.txt`: exact prompt used for that exploration.

The production SVG and Android vector are clean native implementations of the
generated direction. The Android vector uses the same path coordinates in a
108dp viewport, keeping the glyph inside the adaptive icon's central safe area.
The square web icon crops that canvas to its central 72 units. The header uses
a tighter crop and `currentColor` to follow light and dark themes.

The app's `app/lighttool.toml` declares `@mipmap/ic_lightious_launcher`. Its
foreground and monochrome layers share
`app/src/main/res/drawable/ic_lightious_launcher_foreground.xml`. Keep both paths
in that file and the companion's inline header SVG aligned with the master mark
when revising the design.

To regenerate PNGs with ImageMagick 7 from the repository root:

```sh
bash branding/export-icons.sh
```

To also export the SVG, favicon, touch icon, web app icons, and Safari mask into
the adjacent companion checkout:

```sh
bash branding/export-icons.sh ../lightious-invidious
```

These commands only update local assets. Building, installing, and deploying are
separate steps.
