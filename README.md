# Atrium

An ultra-minimalist, black-and-white Android launcher built in pure Kotlin with zero UI frameworks—no Jetpack Compose, XML layouts, or AndroidX Material dependencies.

Atrium delivers pure typography, true OLED black (`#000000`), custom Choreographer-driven spring physics, and keyboard-synchronized universal search.

---

## Features

- **Stripped Visual Atoms**: Zero backgrounds, chips, or card wrappers. Pure typography with subtle press scaling (`0.97f`) and dimming (`0.45f`).
- **Inverted Bottom-to-Top Layout**: App list and search results emerge directly above the search bar. Scrolling down sinks items into the search bar, pulling subsequent items down from above.
- **Horizontal Category Columns**:
  - **Settings (`index = -1`)**: Structured dropdown tree (`▾ DISPLAY`, `▾ ANIMATIONS`, `▾ PERMISSIONS`) for launcher preferences (hide status bar, optical blur, animation velocity, default launcher settings). Completely hidden offscreen until horizontally swiped into view.
  - **Apps (`index = 0`)**: Installed applications sorted alphabetically when search is empty, or fuzzy-ranked when querying.
  - **Contacts (`index = 1`)**: Fast contact search and direct phone dialer integration.
  - **Web (`index = 2`)**: Direct Google web search fallback.
- **FZF v2 Fuzzy Search**: Pure Kotlin zero-allocation port of the `fzf` v2 algorithm (Smith-Waterman with affine gaps and boundary bonuses).
- **Dynamic Category Priority**: Best fuzzy match wins center stage across categories. Ties are broken using category priority (`Contacts` > `Apps` > `Web`). If no matches exist, Web search bounces to the center.
- **Optical Blur & Focus Transitions**: Hardware-accelerated `RenderEffect` optical blur when launching applications and returning to the home screen.
- **Fluid Spring Physics**: Zero-allocation damped spring physics synced to device display refresh rates via `Choreographer`.
- **Keyboard Synchronization**: Real-time WindowInsets animation tracking to pin search input seamlessly above the soft keyboard.

---

## Architecture & Codebase

```
app/src/main/java/com/atrium/launcher/
├── LauncherActivity.kt     # Lifecycle, window insets, system bar styling
├── HomeRoot.kt             # Master layout, gesture tracking & column spring physics
├── AppIndex.kt             # LauncherApps listener, app metadata & launch intents
├── Search.kt               # SearchProvider, AppSearch, ContactsSearch, WebSearch & priority ranking
├── Fzf.kt                  # Pure Kotlin zero-allocation Smith-Waterman FZF v2 algorithm
├── SettingsModel.kt        # Structured settings tree (Category, Toggle, Action, Choice)
├── SettingsColumnView.kt   # Expandable tree ScrollView with query filtering
├── LauncherSettings.kt     # SharedPreferences persistence & system settings intents
├── Spring.kt               # Choreographer-driven physical spring implementation
└── Ui.kt                   # Typography helpers, Label view & ExpoOut interpolator
```

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
