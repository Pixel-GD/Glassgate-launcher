# Contributing to Glassgate

Glassgate is an iOS 26 / iOS 27 style home screen for Android 14+ (built for One UI 9).
Read [docs/DESIGN.md](docs/DESIGN.md) for the look and the feature list.

## Building

The APK is built by GitHub Actions on every push and pull request
([.github/workflows/build.yml](.github/workflows/build.yml)):

- **Any branch or PR:** open the workflow run, download the `glassgate-debug-apk` artifact.
- **main:** the [latest pre-release](../../releases/tag/latest) always has `glassgate-debug.apk`.

Locally with the Android SDK: `./gradlew assembleDebug`.

Cloud agent containers can't reach Google's Maven, so Gradle can't run there. Push and let CI
build. Before pushing you can still type-check Kotlin against a downloaded `android.jar` with
the standalone Kotlin compiler, and check AGSL shaders with `skia-python`
(`skia.RuntimeEffect.MakeForShader(src)`), since a shader error only shows up at runtime.

All CI builds are signed with the committed debug key in `tools/debug.jks`, so each new build
installs over the previous one. It is a debug key: never use it for a store release.

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
- One feature per pull request, branched from `main`. Keep edits outside your package small.
