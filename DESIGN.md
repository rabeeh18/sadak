# Design — "Toposheet"

The interface borrows the language of a **Survey of India topographic sheet** and applies it to a working control tower. The world contributes four things only: **palette, type, density and one signature move**. Layout, navigation and controls stay standard web patterns, so a dispatcher knows where everything is before noticing the style.

**Use scene:** an ops-room monitor that stays on all day under office light, glanced at constantly. That calls for a light, tinted ground, high-contrast ink, and colour that carries state and nothing else.

## Signature move

The map is a **survey sheet**:

- a double neatline frame: a 2 px outer line and a 1 px inner line, with a printed collar between them;
- a lettered **quarter-degree grid** over Punjab: columns A–M (from 73.75° E), rows 1–12 (from 32.55° N). `GridReference.java` and `sheetmap.js` share the same definition;
- graticule ticks in the collar at every half degree (`75°30′E`);
- **grid references everywhere**: trip rows, vehicles, stops and warehouses all show their square (`I10`), and it always matches the map.

Supporting moves: risk scores are set as **spot heights** (a solid triangle plus a figure, coloured by band); KPI cells sit in a **legend strip** like a sheet's marginal legend; vehicle registrations sit in plate outlines.

## Tokens

| Token | Value | Role |
|---|---|---|
| `--ground` | `#e6e9d6` | Sheet margin (page background) |
| `--paper` | `#fbfbf6` | Panels, collar, inputs' surroundings |
| `--paper-2` | `#f2f3e8` | Hover, filter bars |
| `--ink` | `#1e1b16` | Text, rules, primary buttons |
| `--ink-2` | `#48443a` | Secondary text |
| `--ink-3` | `#66624f` | Tertiary text, placeholders (≥ 4.5:1 on paper) |
| `--rule-soft` / `--rule-faint` | `#c9ccb7` / `#dfe1cf` | Hairlines |
| `--survey-blue` | `#1b5c9e` | Planned routes, links, selection, focus |
| `--contour` | `#8e5a2b` | Warehouses, grid lines and letters |
| `--forest` | `#2f6f34` | On plan / low risk / success |
| `--ochre` (+ `--ochre-tint`) | `#7a5806` (`#f5ebbb`) | Medium risk |
| `--orange` | `#b9520f` | High risk |
| `--road` | `#b8321c` | Critical, incidents, destructive actions |

Map symbol colours are brighter versions of the same roles (vehicle arrows `#4f9a4f`, `#e3bd2c`, `#e0731f`, `#d8351b`), with an ink outline so they read on the basemap. The basemap (OpenStreetMap) is toned with `saturate(.62) sepia(.16)` so overlays always win.

## Type

One family, **Archivo** (variable: `wdth` 62–125, `wght` 100–900), used at three widths:

| Use | Setting |
|---|---|
| Sheet titles | 750 weight, `wdth` 125, uppercase, `letter-spacing: .14em` |
| Figures (KPIs, ETAs, spot heights) | 800 weight, `wdth` 72, tabular numerals |
| UI and body | 400–650 weight, `wdth` 100, 14 px / 1.45 |
| Table headers and labels | 650 weight, `wdth` 110, uppercase 11 px, `.07em` tracking |

Scale: 12 · 14 · 16 · 20 · 26 · 40 px. All numerals are tabular.

## Components

- **Panels:** paper, 1 px ink border, square corners; the header row is separated by an ink rule. No shadows except on floating layers (popovers, toasts).
- **Buttons:** square-ish (3 px radius), ink outline; primary = ink fill; blue = survey action (reassign); danger = road outline.
- **Tags:** 1 px outline in the state colour; critical is the only solid fill.
- **Tables:** sticky uppercase headers on an ink rule, faint row rules, hover tint, rows link where a detail page exists.
- **Timelines:** stops, route versions and incident actions as nodes on a hairline. Filled = done, blue ring = next, red = failed.
- **Map symbols:** warehouse = brown square; vehicle = arrow rotated to heading in its risk colour (critical adds one rippling ring); stop = numbered circle; transfer stop = numbered square.

## Motion

One authored moment: vehicles **glide** between position fixes, and only critical vehicles ripple. Selecting a trip dims every other vehicle and route and flies the map to the trip. Everything else is a short ease-out on hover or press. `prefers-reduced-motion` turns animation off.

## Rules for new screens

- Start from the shell: `layout :: header`, `.page`, `.page-head`, `.panel`, `table.data`.
- Colour only carries state. Do not decorate with the band colours.
- Any location a dispatcher might talk about gets its grid reference.
- Every number with a definition says what it measures (see the Analytics notes).
