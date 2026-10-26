# Glassgate launcher: design spec

An Android home-screen replacement for One UI 9 (Android 17) that looks and behaves like the
iOS 26 / iOS 27 home screen. Package `com.pixelgd.glassgate`, Kotlin, minSdk 34 (Android 14), targetSdk 36.

## Visual language (iOS 26 "Liquid Glass", carried into iOS 27)
- **Glass material** for dock, folders, search pill, App Library folders, Spotlight sheet and
  context menus: translucent fill (white ~18% light / black ~28% dark) over a blur of what is
  behind, a 1px bright rim highlight on the top-left edge fading to transparent at the bottom-right,
  and a soft specular sheen that shifts with device tilt (accelerometer, low-pass filtered).
  Implemented in `glass/GlassDrawable` + the AGSL lens in `glass/Shaders`: RenderEffect blur,
  edge refraction and slight colour split, tilt-driven specular rim. Android 14+ won't let apps
  read the system wallpaper, so Glassgate draws its own wallpaper (`glass/Backdrop`) and glass
  samples that. With the "system wallpaper" option, glass falls back to a frosted tint.
- **Wallpaper drives tint**: sample the wallpaper's dominant colour (WallpaperManager colours)
  and tint glass surfaces and "Tinted" icons with it.
- **Icon styles** (setting): Default, Dark, Clear (frosted, monochrome white glyph), Tinted
  (wallpaper colour fill, white glyph). Every icon is masked to a continuous-curvature
  squircle (superellipse n≈5), 60dp, corner smoothing like iOS, subtle inner top highlight.
- **Labels**: white, 12sp, SF-like (use system sans, medium), soft shadow; option to hide.
- **Typography**: bold, tight letter-spacing for headers ("App Library", Spotlight).
- **Motion**: spring physics everywhere (DynamicAnimation-style springs hand-rolled, damping
  ~0.8). App open zooms from icon; home gesture returns with icon zoom-back.
- **Default wallpaper**: an iOS 27-inspired "Celosia"-style abstract of layered petals, drawn live
  by an AGSL shader: warm sand-gold fading to soft purple (light), deep indigo with silvery-blue
  edges (dark). Original artwork, not Apple's file.

## Screens and features
1. **Home pages**: 4×6 grid, horizontally paged, page dots replaced by a glass "Search" pill
   above the dock (tap → Spotlight). Drag icons between pages; widgets 2×2 / 4×2 / 4×4 via
   AppWidgetHost, shown in glass-edged rounded cards.
2. **Dock**: up to 4 apps, floating glass slab with 30dp corners, inset from screen edges.
3. **Jiggle / edit mode**: long-press → icons wiggle, "−" badge to remove from home, "Edit"
   and "Done" glass buttons top corners, add-widget button, icon-style picker.
4. **Folders**: drop an icon on another to make one; 3×3 glass grid preview; opens to a large
   glass card with an editable title.
5. **App Library**: swipe past the last page. Auto categories (Suggestions, Recently Added,
   Social, Productivity, Entertainment, Utilities, Games, etc. from ApplicationInfo.category),
   each a glass tile with 3 large + 4 small icons; rounded search bar at top; tap search → A–Z list.
6. **Spotlight**: swipe down anywhere on home → blurred sheet with search field, Siri-style
   app suggestions row, live results for apps, contacts (permission optional) and settings.
7. **Long-press context menus**: glass menu with app shortcuts (LauncherApps shortcuts),
   "Edit Home Screen", "Remove App", "App Info".
8. **Notification badges**: red dot with count via NotificationListenerService (optional
   permission).
9. **Settings**: icon style, grid size (4×6 default, 5×6), label on/off, dock on/off, wallpaper tint.

## Out of scope for v1
Lock screen, Control Center, status bar (handled by Good Lock modules in this project),
pill notifications around the camera.
