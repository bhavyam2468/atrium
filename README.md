# Atrium

An ultra-minimalist, black-and-white Android launcher built in pure Kotlin with zero UI frameworks—no Jetpack Compose, XML layouts, or AndroidX Material dependencies.

Atrium delivers pure typography, true OLED black (`#000000`), custom Choreographer-driven spring physics, keyboard-synchronized universal search — and, since 0.2, a **live design studio** that turns the entire home screen into a toy you can shape by touch.

---

## Features

### Design Studio (0.2)

The studio is the launcher, mirrored. Open it from the settings column (or long-press the clock) and a live replica of your home screen appears on a card. Everything on that card is a handle:

- **Tap** any element to cycle its representation — the clock steps through time and date formats, rows switch between name / icon / name+icon styles, the search bar morphs bare → underline → pill, the background flips pure black ↔ wallpaper.
- **Drag** the clock anywhere on a 3×3 snap grid with magnet haptics and guide rails; drag the gap between clock and pins to open it; drag row spacing to let results breathe; drag the screen edges for side insets; drag the background for wallpaper dim.
- **Two fingers** on the clock, pins, rows, bar or background scale size and weight simultaneously — direct, proportional, no sliders.
- **Hold** (420 ms) any element for its focused option sheet — only the settings that matter to that element, with the value you're editing pinned and previewed.
- **Double-tap** resets an element to defaults; the ⟲ chip is a two-stage "reset everything" with a confirmation step.
- The **Aa** chip in the toolbar cycles typefaces, drags horizontally for global weight, and long-presses for the full typography sheet. The dot beside it cycles accent colors.
- **Undo / redo** history (80 steps), preset shuffle, and a Home ↔ Search mode switch so both screens can be styled live.
- A bottom **chip bar** narrates: what you're touching, what it's set to, and what gestures are available — the studio teaches itself; no manual required.

### Everything else

- **Stripped Visual Atoms**: Zero backgrounds, chips, or card wrappers. Pure typography with subtle press scaling (`0.97f`) and dimming (`0.45f`).
- **Inverted Bottom-to-Top Layout**: App list and search results emerge directly above the search bar. Scrolling down sinks items into the search bar, pulling subsequent items down from above.
- **Horizontal Category Columns**:
  - **Settings (`index = -1`)**: Structured tree (`Appearance`, `Behavior`, `System`) with fuzzy search over titles, descriptions and tags. Completely hidden offscreen until horizontally swiped into view.
  - **Apps (`index = 0`)**: Installed applications sorted alphabetically when search is empty, or fuzzy-ranked when querying. Icons optional — circle, squircle, rounded or square masks, monochrome-tintable.
  - **Contacts (`index = 1`)**: Fast contact search and direct phone dialer integration.
  - **Web (`index = 2`)**: Direct Google web search fallback.
- **Variable-Font Typography**: Four OFL variable families bundled (`res/font/`): Inter, Space Grotesk, Fraunces, JetBrains Mono. Weight axes are applied live via `FontVariation` — the clock and labels can each speak their own type at their own weight, size and tracking.
- **KWGT-grade Clock, Zero Nerds**: Five time formats, nine date formats (including hidden), three arrangements (time-above-date, date-above-time, one line with a choice of separators), per-element scale / weight / tracking, uppercase toggle, free 2-D placement — all exposed as touch, never as pattern strings to type.
- **Full-Body Theming**: Alignment (H and V), anchors, insets, gaps, pins, rows, icons, search bar, accent colors, text brightness, wallpaper with dim scrim, tinted text — ~45 live tokens, every one persisted and every one reachable from both the studio and the settings tree.
- **FZF v2 Fuzzy Search**: Pure Kotlin zero-allocation port of the `fzf` v2 algorithm (Smith-Waterman with affine gaps and boundary bonuses). The settings column uses it too.
- **Dynamic Category Priority**: Best fuzzy match wins center stage across categories. Ties are broken using category priority (`Contacts` > `Apps` > `Web`). If no matches exist, Web search bounces to the center.
- **Optical Blur & Focus Transitions**: Hardware-accelerated `RenderEffect` optical blur when launching applications, returning home, and while the studio is open.
- **Fluid Spring Physics**: Zero-allocation damped spring physics synced to device display refresh rates via `Choreographer`.
- **Keyboard Synchronization**: Real-time WindowInsets animation tracking to pin search input seamlessly above the soft keyboard.

---

## Architecture & Codebase

```
app/src/main/java/com/atrium/launcher/
├── LauncherActivity.kt     # Lifecycle, window insets, system bar styling, back dispatch
├── HomeRoot.kt             # Home screen, gestures, columns, studio hosting, focus blur
├── DesignSheet.kt          # Design studio: live replica, zone gestures, history, chip bar
├── Theme.kt                # Token engine: ~45 design tokens, presets, fonts, styling roles
├── HomeComposer.kt         # Shared builders so studio replica == real home screen
├── AppIndex.kt             # LauncherApps listener, app metadata, adaptive icon rendering
├── Search.kt               # SearchProvider, AppSearch, ContactsSearch, WebSearch & ranking
├── Fzf.kt                  # Pure Kotlin zero-allocation Smith-Waterman FZF v2 algorithm
├── SettingsModel.kt        # Settings tree: Category, Toggle, Choice, Adjust, Action, Info
├── SettingsUi.kt           # Settings rows, option sheets, drag-to-adjust values
├── SettingsColumnView.kt   # Settings column: pages, fuzzy search, recent edits
├── LauncherSettings.kt     # SharedPreferences persistence & system settings intents
├── Spring.kt               # Choreographer-driven physical spring implementation
└── Ui.kt                   # Label, PressableRow, ClockText, IconView, ExpoOut
```

Fonts in `app/src/main/res/font/` (Inter, Space Grotesk, Fraunces, JetBrains Mono variable)
under OFL-1.1; licenses in `app/src/main/res/raw/`.

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

## License

MIT
