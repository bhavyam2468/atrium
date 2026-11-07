# Atrium

An ultra-minimalist, black-and-white Android launcher built in pure Kotlin with zero UI frameworks—no Jetpack Compose, XML layouts, or AndroidX Material dependencies.

Atrium delivers pure typography, true OLED black (`#000000`), custom Choreographer-driven spring physics, and keyboard-synchronized universal search.

---

## Design language

Atrium is a typeset home screen, not a grid of icons. Three rules hold the whole thing together:

- **One axis.** Clock, pinned apps, the search field and every result share a single centre axis. Nothing is left-aligned, so nothing feels accidentally offset.
- **Two voices.** Inter Tight carries the large text (a variable grotesque, drawn at weight 250 for the clock so it reads as a poster rather than a label). Geist Mono carries only micro-labels and values at 9.5–10.5 sp with wide tracking, the way instrument panels are labelled. The contrast between the two is the hierarchy.
- **Ink, not colour.** Everything is white at one of eight fixed opacities (`100 → 8%`). Consequence: no dividers are needed, dimming alone separates levels.

**Scale.** The clock is 74 sp against 20 sp app rows — roughly 3.7:1. The clock is the only element that may compete for attention; everything else recedes. Sizes scale with display width, so the ratio holds from small phones to tablets.

---

## Features

- **Stripped Visual Atoms**: Zero backgrounds, chips, or card wrappers. Pure typography with subtle press scaling (`0.975f`) and dimming (`0.5f`).
- **Inverted Bottom-to-Top Layout**: App list and search results emerge directly above the search bar. Scrolling down sinks items into the search bar, pulling subsequent items from above.
- **A home screen that opens in place**: Pinned apps sit on the exact baseline the search results use, and the `SEARCH` affordance occupies the exact box the input will fill. Opening search therefore cross-fades one list into another instead of sliding a new layer over the top.
- **Drawn Clock**: The clock is a custom `View`, not a `TextClock`. Tabular figures (`tnum`) mean digits never reflow; the colon is set at 34% ink; a minute change is a soft 420 ms cross-fade rather than a jump; seconds, date and 12/24-hour format are optional.
- **Horizontal Category Columns**:
  - **Settings (`index = -1`)**: A real preference tree with collapsible sections, drill-down pages (e.g. Pinned Apps, every installed app with a switch) and an in-place filter. Paging here releases the keyboard.
  - **Apps (`index = 0`)**: Installed applications sorted alphabetically when search is empty, or fuzzy-ranked when querying.
  - **Contacts (`index = 1`)**: Fast contact search and direct phone dialer integration.
  - **Web (`index = 2`)**: Direct web search fallback; Google, DuckDuckGo or Brave.
- **FZF v2 Fuzzy Search**: Pure Kotlin zero-allocation port of the `fzf` v2 algorithm (Smith-Waterman with affine gaps and boundary bonuses).
- **Dynamic Category Priority**: Best fuzzy match wins center stage across categories. Ties are broken using category priority (`Contacts` > `Apps` > `Web`).
- **Optical Blur & Focus Transitions**: Hardware-accelerated `RenderEffect` optical blur when launching applications and returning to the home screen, with three depth presets.
- **Fluid Spring Physics**: Zero-allocation damped spring physics synced to device display refresh rates via `Choreographer`, with velocity handed off from the finger on release. Paged columns share a smoothstep depth falloff (fade, 6% scale, 14 dp drift).
- **Motion that stays legible**: Staggered row reveal (capped so deep rows never wait), animated section collapse, switches that are objects rather than text, and a value that flickers through when a choice changes.

---

## Settings

Reachable by paging right from the app column. Everything applies immediately; nothing needs a restart.

| Section | What lives there |
| --- | --- |
| Clock & Date | Time format (auto / 24-hour / 12-hour), show date, show seconds |
| Home Screen | Drill-down **Pinned Apps** picker (every installed app, ordered, live count), restore default pins |
| Display | Hide status bar, hide navigation bar, row density (comfortable / compact) |
| Search | Contact suggestions on/off, web engine (Google / DuckDuckGo / Brave) |
| Motion & Touch | Motion speed (5 steps), staggered reveal, blur-to-focus, blur depth, haptic feedback |
| System | Default launcher, contacts permission, app info, reset all settings |
| About | Version, typefaces and licence, search algorithm, motion engine |

Settings are also searchable — the same fzf matcher indexes titles, descriptions and tags, and the header turns into a back affordance when you are inside a page.

---

## Design preview

`preview/index.html` is a single self-contained page that recreates the interface in a browser: the
same type scale, the same spring integrator, the same pager, the same settings tree. It exists
because the launcher targets a 1080 × 2340 screen and the composition is easier to judge at a fixed
392 × 848 frame. Serve it over any static host — for example `python3 -m http.server` from the
repository root, then open `/preview/`. The page pulls both typefaces from
`app/src/main/res/font/`, so it needs the repository, not just the file.

---

## Architecture & Codebase

```
app/src/main/java/com/atrium/launcher/
├── LauncherActivity.kt     # Lifecycle, system bars, permissions, intro choreography
├── HomeRoot.kt             # Master layout, gesture tracking & column spring physics
├── Clock.kt                # Drawn display clock: tabular time, mono date, minute cross-fade
├── AppIndex.kt             # LauncherApps listener, app metadata, pin persistence & launch
├── Search.kt               # SearchProvider, AppSearch, ContactsSearch, WebSearch & priority ranking
├── Fzf.kt                  # Pure Kotlin zero-allocation Smith-Waterman FZF v2 algorithm
├── SettingsModel.kt        # Preference tree (Category, Page, Toggle, Choice, Action, Info)
├── SettingsColumnView.kt   # Collapsible sections, drill-down pages, switches, in-place updates
├── LauncherSettings.kt     # SharedPreferences persistence & system settings intents
├── Spring.kt               # Choreographer-driven physical spring implementation
└── Ui.kt                   # Ink ramp, type scale, fonts, haptics, switch, chevron, scrim
```

Fonts live in `app/src/main/res/font` and their licences in `app/src/main/assets/licenses`.

---

## Build & Install

### Prerequisites
- Android SDK (API 34+)
- JDK 17+

### Build Debug APK
```bash
./gradlew assembleDebug
```

### Install via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Third-party licences

- **Inter Tight** — Rasmus Andersson, SIL Open Font License 1.1
- **Geist Mono** — Vercel, SIL Open Font License 1.1

Full texts are bundled at `app/src/main/assets/licenses/`.

---

## License

MIT
