# Contributing to Glassgate

Glassgate is an iOS 26 / iOS 27 style home screen for Android 14+ (built for One UI 9).
Read [docs/DESIGN.md](docs/DESIGN.md) for the look and the feature list.

## Building

GitHub Actions builds on every push and pull request
([.github/workflows/build.yml](.github/workflows/build.yml)). It builds two minified,
non-debuggable release APKs from the `edition` flavors:

| APK | Flavor | What's different |
|---|---|---|
| `glassgate.apk` | `standard` | No notification listener, no contacts. Installs from a browser or file manager without Play Protect blocking it. |
| `glassgate-plus.apk` | `plus` | Adds notification badges (`badges/BadgeService`) and Spotlight contact search. Play Protect blocks sideloaded apps that request notification access, so users must choose "Install anyway". |

Both share the `com.pixelgd.glassgate` app id, so Plus installs over Standard. Code that needs a
Plus-only permission checks `BuildConfig.FEATURE_BADGES` / `BuildConfig.FEATURE_CONTACTS`, and
Plus-only manifest entries go in `app/src/plus/AndroidManifest.xml`.

- **Any branch or PR:** open the workflow run and download the `glassgate-apks` artifact.
- **main:** the [latest pre-release](../../releases/tag/latest) always has both APKs.

Locally with the Android SDK: `./gradlew assembleStandardDebug`.

**Signing.** Release APKs are signed with the owner's key from two repository secrets,
`GLASSGATE_KEYSTORE_B64` (the base64 of a JKS keystore whose key alias is `glassgate`) and
`GLASSGATE_KEYSTORE_PASSWORD` (store and key password). Without them, CI signs with the public
debug key in `tools/debug.jks`, which anyone can use, so only builds from the secret key should
be shared. Debug builds always use the debug key.

Cloud agent containers can't reach Google's Maven, so Gradle can't run there. Push and let CI
build. Before pushing you can still type-check Kotlin against a downloaded `android.jar` with
the standalone Kotlin compiler, and check AGSL shaders with `skia-python`
(`skia.RuntimeEffect.MakeForShader(src)`), since a shader error only shows up at runtime.

## Code layout

Everything is in `app/src/main/java/com/pixelgd/glassgate/`:

| Package | What lives there |
|---|---|
| `Launcher.kt` | Process-wide services (`Launcher.of(context)`): apps, layout, icons, wallpaper, tilt, prefs |
| `LauncherActivity.kt` | The HOME activity, the `overlay` layer, and the `HomeActions` hooks |
| `apps/` | `AppEntry`, `AppRepository` (installed apps, launching, shortcuts) |
| `layout/` | `HomeLayout` model (pages, folders, widgets, dock) and `LayoutStore` (saving, reconciling) |
| `icons/` | `IconRenderer`: squircle icons in Default, Dark, Clear and Tinted styles |
| `glass/` | Liquid Glass: `GlassDrawable`, `GlassStyle`, AGSL `Shaders`, `Backdrop`/`WallpaperSource`, `Tilt`, `Squircle` |
| `home/` | `HomeView`, `PagedView`, `PageGridView`, `AppIconView`, `FolderIconView`, `DockView`, `SearchPillView` |
| `util/` | `Spring` (use it for every animation), dp/sp helpers |

Feature modules each get their own package and plug in through one `HomeActions` method in
`LauncherActivity`:

| Module | Package | Hook |
|---|---|---|
| Spotlight search | `spotlight/` | `openSpotlight()` |
| Folders | `folders/` | `openFolder()` (and folder creation by drag, with `edit/`) |
| App Library | `library/` | `createTrailingPage()` |
| Context menus | `menu/` | `showContextMenu()` |
| Jiggle / edit mode | `edit/` | `enterEditMode()` |
| Widgets | `widgets/` | replace the placeholder in `PageGridView.bind()` |
| Notification badges | `badges/` | `AppIconView.badgeDrawer` (set by `BadgeDrawer.attach`); `Badges.total(apps)` for a folder's count |
| Settings | `settings/` | `Prefs` in `Launcher.kt` (listen with `prefs.addListener`); open with `SettingsActivity.open(context)` |

Module UIs are added to `LauncherActivity.overlay` and removed on dismiss
(`dismissOverlays()` is called on Home and Back).

## Conventions

- Kotlin, plain Android Views. No Compose. AndroidX only where it saves real work.
- Glass surfaces use `GlassDrawable(view, GlassStyle.X)`; add a `GlassStyle` preset instead of
  hand-tuning numbers in a view.
- Motion uses `util.Spring` with the shared presets.
- Edit the home layout only through `launcher.layout.edit { ... }`.
- "Remove from Home Screen" puts an app's key in `HomeLayout.libraryOnly` so it isn't re-added;
  `placeFirstFree` takes it back out (use it for "Add to Home Screen"). Hidden pages have
  `HomePage.hidden` and aren't shown on the home screen.
- Back closes the topmost overlay; make an overlay `DismissibleOverlay` to animate out.
- One feature per pull request, branched from `main`. Keep edits outside your package small.
