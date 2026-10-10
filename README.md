# GeoInt for Android

An Android app that runs the [GeoInt](https://github.com/marouane53/GeoInt) photo-geolocation method on your
phone: pick or share a photo → Claude reads it like a GeoGuessr pro, searches the web to prove it, and gives a
country / city / pin with an honest confidence and radius. "Open in Maps" drops the pin in Google Maps.

## Install (phone)

1. Open the **Releases** page of this repo on your phone and download the latest `GeoInt-N.apk`.
2. Open it and allow "install unknown apps" for your browser.
3. In the app: **Settings** → paste an Anthropic API key from <https://console.anthropic.com> (you pay per photo).

## What it really is (read this)

GeoInt upstream is an **agent skill**: a method (`SKILL.md`), a clue library (`references/`), and ~40 Python
tools (OCR, StreetCLIP/GeoCLIP priors, Street View sweeps with MegaLoc, OSM queries, satellite scans) that need a
computer and ~6 GB of models. **None of the Python tools run on a phone.** This app keeps:

- the method, rewritten for a phone (`core/.../GeoIntPrompt.kt`): gut call first, read every string, search,
  country funnel, no midpoints, no fabricated verification, calibrated confidence;
- the full clue library, bundled verbatim (`core/src/main/resources/geoint/`, MIT): the reading checklist, country
  funnel and global discriminators are always in context; the regional files (Europe, Americas, Asia, Oceania,
  Africa & Middle East, China, Street View) are loaded by Claude on demand through a `read_clue_library` tool;
- metadata like `meta.py`: EXIF GPS, time, UTC offset, camera, file name;
- `intake.py`-style zoom: the full frame plus four overlapping zoomed tiles so small signs stay legible;
- Claude's server-side web search and web fetch instead of the local research tools.

It does **not** do reverse image search, Street View matching, OSM/satellite scans or the per-photo archive.
Expect country/city-level answers on ordinary photos and street-level only when there is readable text or a
landmark; the upstream skill on a laptop will beat it on hard cases.

## Cost

Defaults: Claude Opus 5.5, effort high, Quick mode, tiles on. Expect very roughly $0.10–0.50 per Quick run
(images, reasoning and searches add up); Deep mode with many searches can pass $1. The app shows the estimated cost after each run. Sonnet 5.5 is about
half the price; Haiku 5.5 is very cheap but noticeably weaker at this.

## Build

```bash
./gradlew :core:test            # engine tests (run anywhere with a JDK)
./gradlew :app:assembleRelease  # needs the Android SDK
```

GitHub Actions builds the APK on every push and publishes it as a release.

Layout: `core/` is plain Kotlin (prompt, Claude agent loop, result parsing, tests); `app/` is the Compose UI,
photo loading and settings. The API key is stored in the app's private storage and only sent to api.anthropic.com.

Credits: method and clue library © GeoInt contributors, MIT (`core/src/main/resources/geoint/LICENSE`).
