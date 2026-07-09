# Lunamux — store asset generator

Generates the full set of store screenshots for **both** the Apple App Store and
the Google Play Store from a single JSON config, so the whole listing stays
consistent and is trivial to regenerate when the app changes.

Each entry renders your raw screenshots inside clean device frames on the branded
deep-navy / cyan background — no photo editing, no per-image fiddling.

Two companion generators reuse the exact same palette/fonts:

```bash
python3 render_screenshots.py                    # phone/Mac screenshots (both stores)
python3 render_feature_graphic.py                # Google Play feature graphic (1024x500)
python3 render_feature_graphic.py sources/mac/07.png   # …with a different right-hand panel
```

The **Google Play app icon** (512x512) is just a downscale of the 1024 master:

```bash
sips -s format png -z 512 512 ../electron/icons/icon.png --out out/google-play/app-icon-512.png
```

## Layout & frames

| | App Store | Google Play |
|---|---|---|
| Phone frame | iPhone 17 Pro Max (Dynamic Island) | Pixel 9 Pro XL (punch-hole) |
| Uses source | `ios` | `android` |
| Output size | **1320 × 2868** (6.9") | **2880 × 3840** (3:4) |
| With `mac` | MacBook + iPhone (companion hero) | MacBook + Pixel (companion hero) |
| Without `mac` | iPhone fills the canvas | Pixel fills the canvas |

Both outputs are flattened RGB JPEGs, comfortably within each store's size limits
(App Store 6.9" required size; Google Play ≤ 3840 px, ratio ≤ 2:1, ≤ 8 MB).

## Usage

```bash
cd "store-assets"
python3 render_screenshots.py            # uses ./screenshots.json
# or: python3 render_screenshots.py path/to/other.json
```

Requires Python 3 with Pillow (`pip3 install pillow`). Renders in **JetBrains Mono**
— the same font the Lunamux apps use by default — vendored under `fonts/` (OFL).

Outputs land in:

```
out/app-store/01-hero.jpg … 07-code.jpg      (upload to App Store Connect, 6.9")
out/google-play/01-hero.jpg … 07-code.jpg    (upload to Play Console, phone)
```

## Config (`screenshots.json`)

```jsonc
{
  "brand": {
    "eyebrow":    "// terminal · reimagined",       // small label above the headline
    "url":        "https://lunamux.dev",
    "footerNote": "Requires the Termtastic app for Mac"
  },
  "screenshots": [
    {
      "id":         "hero",                          // used in the output filename
      "tagline":    "Your Mac terminal,\nin your pocket.",   // \n = manual line break
      "subtagline": "Organise sessions … mirror them live",  // auto-wraps if long
      "mac":        "sources/mac/01-sessions-mac.png",       // OPTIONAL → companion layout
      "android":    "sources/android/01-sessions.png",       // required (Google Play)
      "ios":        "sources/ios/01-sessions.png"            // required (App Store)
    }
    // …more entries…
  ]
}
```

- Paths are relative to this folder.
- `tagline` / `subtagline` accept `\n` for manual breaks; long lines auto-wrap.
- Omit `mac` on an entry to make the phone fill the canvas (feature screenshots).

## Directory layout

```
store-assets/
├── render_screenshots.py      # the screenshot generator
├── render_feature_graphic.py  # the feature-graphic generator (reuses render_screenshots helpers)
├── screenshots.json           # your config (edit this)
├── README.md
├── fonts/                     # vendored JetBrains Mono (OFL) — the app's default font
├── sources/
│   ├── mac/                   # Mac screenshots (cyan theme, landscape)
│   ├── android/              # Pixel screenshots (1344×2992)
│   └── ios/                  # iPhone screenshots (1320×2868) — see note below
└── out/
    ├── app-store/            # generated — upload to App Store Connect
    └── google-play/          # generated — upload to Play Console
        ├── 01-hero.jpg …     #   phone screenshots
        ├── feature-graphic.png  # 1024×500 feature graphic
        └── app-icon-512.png     # 512×512 hi-res app icon
```

## Adding / updating screenshots

1. Drop new source PNGs into `sources/android/`, `sources/ios/`, `sources/mac/`.
2. Add or edit an entry in `screenshots.json` (id, tagline, subtagline, paths).
3. Re-run `python3 render_screenshots.py`.

## Capturing source screenshots

Capture each screen in the **green theme**, in portrait, on the devices below. Using
these exact devices makes the source resolution match the frame, so there's no
scaling and text stays crisp:

| Source | Capture on | Native size | Notes |
|---|---|---|---|
| `ios`     | **iPhone 17 Pro Max** simulator (Xcode → iOS Simulator) | 1320 × 2868 | 6.9" display. ⌘S saves a `Simulator Screenshot …` PNG to the Desktop. |
| `android` | **Pixel 9 Pro XL** (Android Studio emulator or a real device) | 1344 × 2992 | Use the camera button in the emulator toolbar. |
| `mac`     | **Termtastic** app window on a Retina display | landscape (e.g. 3456 × 2234) | Full window or full screen. ⇧⌘4 then Space to grab the window. |

The `ios` sources in this repo are real iPhone 17 Pro Max simulator captures. The
pipeline will still run if you only supply Android shots, but the App Store set would
then show Android UI inside an iPhone frame — so always recapture on the iPhone
simulator before shipping the App Store listing.

## Tuning the look

Everything is parameterised in `render_screenshots.py`:
- Colours: `GREEN`, `GHI`, `GDIM`, `BG` (from the website's `styles.css`).
- Frame geometry: `draw_phone()` (per-`kind`) and `draw_mac()`.
- Background: `build_background()`. Text sizes: in `render()` (relative to width).
- Store sizes/frames: the `STORES` table at the top.
