# iOS 26 / 27 parity

How close Glassgate gets to the iPhone Home Screen, what Android adds on top, and where
Android stops us. Checked against iOS 26 (Liquid Glass) and the iOS 27 home screen changes
(Undo / Redo while editing). Update this list when you close a gap.

## Matches iOS

| iOS | Glassgate |
|---|---|
| 4×6 grid, paged, rubber-band edges | Yes (`home/PagedView`) |
| Glass dock, Search pill, folders, menus, edit buttons | Yes, Liquid Glass shader over Glassgate's wallpaper |
| Search pill turns into page dots while swiping | Yes |
| Touch and hold the dots to scrub pages | Yes, on the pill (whole screen width maps to pages) |
| Settings › Home Screen › Search › Show on Home Screen | Yes: off shows dots only |
| Customize: Default / Dark / Clear / Tinted | Yes |
| Customize: Light / Dark / Auto for each look | Yes |
| Customize: Tinted colour slider and wallpaper eyedropper | Yes (hue slider, tap the swatch for wallpaper colour) |
| Customize: Small / Large icons (Large hides names) | Yes |
| Jiggle mode, "−" badges, drag to pages, dock, folders | Yes |
| Edit menu: Add Widget, Customize, Edit Pages | Yes |
| Edit menu: Edit Wallpaper (iOS 26) | Yes, opens Settings › Wallpaper |
| Edit menu: Undo / Redo (iOS 27) | Yes, 30 steps |
| Edit Pages: hide and reorder pages | Yes |
| Folders: 3×3 pages, rename | Yes |
| App Library: categories, Suggestions, Recently Added, A–Z search | Yes |
| Spotlight on swipe down | Yes |
| Long-press menu: quick actions, Edit Home Screen, Share App, Remove App | Yes |
| Require Face ID / Hide and Require Face ID (iOS 18) | Yes, with the Android biometric prompt (face, fingerprint or PIN). Hidden apps are in Settings › Face ID & Privacy › Hidden Apps |
| Newly Downloaded Apps: Add to Home Screen / App Library Only | Yes |
| Notification badges, "Show in App Library" | Yes (Plus edition, needs notification access) |
| Widgets and Smart Stacks | Yes, Android widgets in glass frames plus Glassgate's own |
| Wallpaper: Colour, Photo, Blur, Dark Appearance Dims Wallpaper | Yes for Glassgate's wallpapers |
| Accessibility: Reduce Motion, Reduce Transparency | Yes |
| Settings app look: large titles, inset grouped lists, search, coloured icons, push navigation | Yes; the back swipe drags the page like iOS |

## Android extras

- Grid sizes 4×6, 4×7, 5×6, 5×7, 6×8, and an icon size slider.
- Label size, hide the dock, hide the App Library.
- Icon packs (Nova / ADW format), shaped as squircles.
- Swipe down can open notifications, or notifications on the left and quick settings on
  the right (One UI's split panel), instead of Search.
- Liquid Glass clear-to-tinted slider (iOS 26.1 has a two-way switch).
- Back up and restore the whole home screen to a file; reset to the default layout.

## Still to do (possible on Android)

- **Today View**: a widgets page to the left of page one, with its own search field.
- **Folders in the dock** (the dock model is a list of apps).
- **Widget resize from the long-press menu** (Small / Medium / Large).
- **Drag several apps at once** (tap more icons while dragging one).
- **Clear / Tinted look for Glassgate's own widgets**, matching the icon style.
- **Focus-filtered pages** (show certain pages in a Do Not Disturb mode).

## The walls (not possible for an Android launcher)

- **Closing an app back into its icon.** On Android only the system launcher (Samsung's One UI
  Home) receives the recents animation, so swiping home from an app plays the system's
  animation, not a zoom into Glassgate's icon. Opening an app zooms from the icon, which is
  as close as the public API gets.
- **App switcher / multitasking**: drawn by One UI Home, not by third-party launchers.
- **Lock Screen, Control Center, Notification Center, status bar, Dynamic Island**: system
  UI. Good Lock (LockStar, QuickStar) covers these in this project.
- **Glass over the system wallpaper.** Android 14+ doesn't let apps read the wallpaper's
  pixels, so real refraction and wallpaper blur only work with Glassgate's own wallpapers.
- **Restyling other apps' widgets** (Clear / Tinted widgets): they are drawn by each app.
- **Real iOS 26 layered icons**: Android apps ship flat or two-layer adaptive icons, so Clear
  and Tinted are built from the monochrome or foreground layer.
- **Truly hidden apps**: a launcher can hide an app from itself, but it still shows in
  Android's Settings › Apps and the Play Store.
- **Badges without notification access**: Android has no badge API for launchers, and Play
  Protect blocks sideloaded apps that ask for notification access, hence the Plus edition.
