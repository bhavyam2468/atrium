# Atrium

An ultra-minimalist Android launcher built in pure Kotlin with zero UI frameworks — no Jetpack Compose, no XML layouts, no AndroidX Material. Every pixel is drawn with `Canvas` and `TextView`, every animation runs on `Choreographer`, and every visual decision is a **token** you can change by touching the thing you want to change.

---

## The idea

Most launchers give you a settings screen and expect you to translate what you see into a list of switches. Atrium inverts that: the home screen *is* the editor.

**Hold anything, then shape it.**

| On the home screen | Hold it and drag | Tap it |
| :--- | :--- | :--- |
| **Clock** | ↔ through 16 time patterns · ↕ through 8 weight steps · pinch to scale | Next arrangement (date above / below / inline / time only…) |
| **Clock · corner grip** | Free move — release and it snaps to the nearest of nine anchors | — |
| **Apps** | ↔ alignment · ↕ spacing · pinch to scale | Next arrangement (stack / row / grid / single line) |
| **An app name** | — | Launch, or release to pin / unpin |
| **Atmosphere** (empty space) | ↔ hue · ↕ depth | Next material (void / solid / gradient / aurora / wallpaper) |
| **Search field** | ↔ type size · ↕ bottom inset | Next field style (bare / underline / pill / box / rule) |
| **Results list** | ↔ type size · ↕ row spacing | — |

While you hold something, a readout appears next to your finger with the control's name, its live value, and a one-line hint about the gesture — then it dissolves. A small compass of hairline arrows shows which axes are live. The settings column on the left follows along: hold the clock and it jumps to the Clock section, so the two ways of editing are always the same object.

Nothing is modal, nothing is a form, and there is exactly one line of tutorial copy in the entire app, shown once.

---

## Screens

```
                    ┌─────────────────────────────┐
                    │  Wednesday, 3 September      │  ← clock: any format,
   swipe right      │  10:35                       │     face, anchor, size
  ┌──────────┐      │                             │
  │ SETTINGS │ ◄──► │  YouTube                     │  ← pinned apps: any layout,
  │  · Clock │      │  PhonePe                     │     icons, monogram, case
  │  · Apps  │      │  Spotify                     │
  │  · ...   │      │                             │
  └──────────┘      └─────────────────────────────┘
                                 search            ← drag up: the surface rises
```

Horizontal columns: **settings** ← **apps** → **contacts** → **web**.
Vertical: drag down anywhere and the search surface rises with the keyboard, columns breathing inward as the home surface recedes and dims.

---

## Token system

`Tokens.kt` is the single source of truth: **95 keys across 9 sections**, each with a default, a domain, a label, help text, search keywords, and — where it makes sense — the live gesture that drives it.

```
Clock ······ 23   time + date pattern (32 presets, or any java.text pattern),
                  face (bundled, system, or a named family), arrangement,
                  anchor, alignment, weight, tracking, case, opacity, date
                  scale, tabular figures, blinking colon, animated digits,
                  inline divider, shadow, glow, offsets
Apps ······· 20   count, arrangement (stack / wrap / 3 grids / one line),
                  anchor, alignment, icons + size + treatment, labels,
                  monogram / numbered / bracketed, case, weight, tracking,
                  spacing, opacity, hierarchy fountain, icon bloom, offsets
Search ······ 8   field style, type size, alignment, placeholder + case,
                  leading glyph, bottom inset, hairline rule
Results ···· 12   type size, alignment, icons + size, row content, spacing,
                  dim, headers + size, stagger, max rows, tracking
Background · 11   material, depth, hue, saturation, angle, glow, vignette,
                  grain, living light, wallpaper + dim + blur
Palette ····· 3   one hue, one tint, one warmth for the whole surface
Typography ·· 6   interface face, global scale, gutter, ink brightness,
                  ink hue, weight shift
Motion ······ 8   tempo, springiness, optical blur + amount, depth, haptics,
                  entrance, stagger
System ······ 4   bars, contacts column, web column
```

Every one of these is read by at least one surface — there are no decorative knobs.

A single numeric key can produce dozens of appearances. The clock alone: 16 hour patterns × 16 date patterns × 6 typefaces × 4 arrangements × 9 anchors × 3 alignments × continuous weight, size, tracking, case and opacity — **over a hundred thousand combinations**, and it never clips (patterns shrink to fit).

### Presets

Six curations, each a sparse patch that cross-fades the whole surface:

`Vitrine` (the house default) · `Broadsheet` (serif masthead) · `Nocturne` (hour alone, enormous) · `Terminal` (mono, dense, left) · `Index` (grid of monograms) · `Quiet` (three apps, a hint of light)

Each preset is drawn as a live miniature of the home screen it produces — clock at its anchor and arrangement, app block in its layout, surface in its material — so you choose a starting point by looking at it.

Also: **Surprise me**, which shuffles into a coherent, always-legible look (ink contrast and sizes are floored so a shuffle can never produce an unreadable screen), plus full **undo/redo** and **reset everything**.

---

## Settings that scale

The deck is built for hundreds of options, not ten:

- **Three levels** — Section → Group → Token, entered one page at a time so the column never scrolls sideways.
- **Search** — FZF over title, group, help, unit and keywords, with title matches boosted. Type a token's exact name and the deck navigates into its section by itself.
- **Recent** — the last five things you shaped, plus every non-default row marked with a tick.
- **Contextual labels** — "Scale" on the home HUD, "Scale · Size" in the deck, so seven sections can each have a size knob without ambiguity.
- **Diff view** — "Changed only", plus a dot beside every row you have touched.
- **Real controls** — numeric rows are drag tracks (fine near the origin, coarse when you pull away), booleans are sliding switches, choices are stepped strips, text opens a field. No sliders, no steppers.
- **Copy token sheet** — the whole state as `key<TAB>value`, importable again.

---

## Typography

Four bundled variable fonts (SIL Open Font License, licences in `assets/licenses/`):

| Role | Face | Notes |
| :--- | :--- | :--- |
| Interface + default clock | **Inter** | variable weight + optical size |
| Condensed / wide clock | **Space Grotesk** | distinctive grotesque numerals |
| Serif clock | **Instrument Serif** | high-contrast display serif |
| Monospace clock | **JetBrains Mono** | tabular, engineered |

The clock is drawn by `ClockView` onto a canvas with per-glyph painting, so digits cross-fade individually as they change (no flicker on unchanged glyphs), tabular figures keep it perfectly still, and a blinking colon is optional. Weight, tracking, case and opacity are continuous tokens.

---

## Motion

- **Choreographer springs** with finger velocity preserved on release, so a fling continues where the finger left off.
- **Optical blur** (`RenderEffect`) to and from app launches.
- **Depth parity**: when the deck opens the home surface recedes to 16% and dims behind a scrim, so the two layers read as one space.
- **Staggered reveals** for results, and an **entrance** token (rise / fade / mask / none).
- **Tempo and springiness** are tokens; every duration in the app is multiplied by tempo.
- **Haptics** in four words — tick, touch, hold, land — silenced by a single token.

---

## Architecture

```
app/src/main/java/com/atrium/launcher/
├── Tokens.kt            # 91-key schema, store, undo, presets, formatting (no Android deps)
├── Type.kt              # typefaces, density scales, ink palette, interpolators, helpers
├── Studio.kt            # live-target vocabulary, global registry, hit testing, grips
├── Hud.kt               # the readout + the compass of live axes
├── ClockView.kt         # 100+ clock variants, drawn by hand, self-editing
├── PinsView.kt          # the app block: 5 arrangements, icons, monograms, case
├── BackgroundView.kt    # void / solid / gradient / aurora / wallpaper + grain + vignette
├── ResultRow.kt         # token-driven search rows
├── SettingsRegistry.kt  # sections, groups, FZF search, system actions
├── SettingsColumnView.kt# the deck: index, sections, nav rows, token gestures
├── HomeRoot.kt          # composition, columns, gesture arbitration, the whisper
├── HomeRoot's Col       # per-column springs, inverted scroll, staggering
├── LauncherActivity.kt  # lifecycle, insets, system bars
├── AppIndex.kt          # LauncherApps, icons, pins
├── Search.kt / Fzf.kt   # providers, fzf v2 fuzzy matching
├── Spring.kt            # Choreographer spring + one-shot ramp
└── LauncherSettings.kt  # window policy, system intents, wallpaper picking
```

`Tokens.kt` and `Fzf.kt` are deliberately free of Android imports, so the whole configuration surface is unit-testable on a plain JVM (schema defaults are canonical, choices are validated, ranges clamp, presets reference real keys, every format pattern parses, presets and shuffles stay legible).

---

## Build

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK 36 (minSdk 30) and JDK 17+.

---

## Licence

MIT. Bundled typefaces are licensed under the SIL Open Font License; see `app/src/main/assets/licenses/`.
