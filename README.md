# Ishiki (石記)

Study your Anki flashcards from your wrist on a **Pebble Core Time 2** (`emery`, 200×228,
64-color, 4 buttons). Pick a deck, read the front, reveal the back, grade it, move on —
backed by your real collection.

The backend is **local**: an Android **companion app** reads and writes your on-device
**AnkiDroid** collection and serves the watch over localhost. AnkiDroid does the AnkiWeb
sync itself, so nothing here ever logs into AnkiWeb or touches its (private) sync protocol.

```
AnkiWeb ⇄ AnkiDroid ⇄ (ContentProvider) Ishiki companion ⇄ (localhost:8765) PebbleKit JS ⇄ (Bluetooth) watch
```

## On the watch

- **Deck list** — Up/Down to move, **Select** to open. Due counts refresh each time the
  list is shown (so they reflect what you just reviewed).
- **Front** — Up/Down scroll a long question · **Select** reveals the answer.
- **Back** — a colored action bar on the right edge aligned to the buttons:
  - **Up** = ✓ **Good** (green) · **Select** = ~ **Hard** (yellow) · **Down** = ✗ **Again** (red)
  - The screen flashes the grade's color, then the next card loads.
  - **Hold Up/Down** to scroll a long answer.
- If the watch can't reach the companion (Bluetooth down, app closed, AnkiDroid busy), it
  **vibrates** instead of hanging.

Reviews are **store-and-forward**: a deck's due cards are fetched as a batch and queued
reviews (with timestamps) are flushed to the companion as it becomes reachable.

## Repo layout

```
companion/   Android companion app (Kotlin) — the current backend
pebble/      Pebble watchapp (C) + PebbleKit JS bridge
backend/     legacy Python AnkiWeb proxy (alternative — see below)
versions     per-app versions, read by bump.sh
bump.sh      set versions + rebuild both artifacts
.github/workflows/release.yml   CI: build + publish the signed companion APK on a version bump
CHANGELOG.md   companion app changelog (watch app: pebble/CHANGELOG.md)
CLAUDE.md    full dev / handoff notes
```

## Companion app (Android)

Kotlin, minimal UI, a foreground service running [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)
on `127.0.0.1:8765` over AnkiDroid's ContentProvider:

| Method | Path | Returns |
| --- | --- | --- |
| GET  | `/decks`              | `[{id, name, due}]` |
| GET  | `/cards?deckId=<id>`  | `[{id:"noteId:ord", front, back}]` |
| POST | `/review` `{cardId, ease, timestamp}` | `{ok:true}` — ease `1`=Again, `2`=Hard, `3`=Good |

**Build / install:**
```bash
cd companion
./gradlew assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
```
Or grab the APK from [Releases](../../releases). Install it, open it once, and grant the
**AnkiDroid** permission + notifications. Requires AnkiDroid installed and signed into your
AnkiWeb account. See [companion/README.md](companion/README.md) for details and limitations.

## Watch app (Pebble)

```bash
cd pebble
pebble build                 # -> build/pebble.pbw   (needs the Pebble SDK; see CLAUDE.md)
```
Sideload `pebble.pbw` with the Pebble (Core Devices) app (Android: file manager or Rebble's
*Sideload Helper*; iOS: the Pebble Core share sheet). Then open the app's **Settings** on
the phone and set **Backend URL** = `http://127.0.0.1:8765` (leave the token blank — the
companion is local and unauthenticated).

## Releases & versioning

The two apps version independently in [versions](versions):
```
companion=0.2.0
pebble=0.2.0
```
[bump.sh](bump.sh) applies them and rebuilds both:
```bash
./bump.sh                    # apply versions/ and rebuild both
./bump.sh companion 0.3.0    # set companion's version, then apply + rebuild
./bump.sh pebble 1.1.0       # set pebble's version, then apply + rebuild
```
Pushing to `main` triggers [the release workflow](.github/workflows/release.yml): when the
**companion** version is one that hasn't been released yet, it builds the APK and publishes
a `v<version>` GitHub Release. With the signing secrets set (`KEYSTORE_B64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) the APK is release-signed; otherwise it
falls back to a debug build.

## Limitations

- The companion's AnkiDroid calls (deck query, card columns, review `update`) are
  AnkiDroid-version-sensitive and **not yet verified on a physical device**.
- **No backdating** — AnkiDroid records a review at submit time; same-day reviews are fine
  (Anki intervals are day-granular), only reviews queued across midnight can drift.
- On the answer screen single-press grades; **hold** Up/Down to scroll a long answer.

## Legacy: Python AnkiWeb proxy (`backend/`)

Before the companion, the backend was a Python/FastAPI service that synced AnkiWeb directly
with the official `anki` library and exposed `/decks /next /answer /sync`. It still works
(Dockerized, background auto-sync) and is kept as an alternative for setups without
AnkiDroid, but the on-device companion is the recommended path (no AnkiWeb credentials, no
reverse-engineered protocol, no server to host). Setup is in [backend/](backend/) and the
rationale is in [CLAUDE.md](CLAUDE.md).
