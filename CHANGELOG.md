# Changelog

## 0.2.0 — Watch UI: action bar, third grade, visible flash

### Added
- **Right-side action bar** on the watch (replaces the bottom text legend), drawn with
  graphics primitives — no image resources. On the answer screen: green **✓** (Up) = Good,
  yellow **~** (Select) = Hard, red **✗** (Down) = Again. On the question: grey scroll
  chevrons (Up/Down) + a white "reveal" cue (Select).
- **Third grade (Hard).** Grading is now Good / Hard / Again (was OK / Not OK).
- **Launcher icon** for the companion app (石記).

### Changed
- **Answer flash is now visible** — the green/yellow/red wash is held for 550 ms and the
  next card (served instantly from cache) is buffered until it ends. Previously the instant
  next-card repaint cut the flash to nearly nothing.
- **Deck list refreshes** its due counts whenever it becomes visible (including on Back from
  a deck), so the totals reflect cards just reviewed.

### Notes
- On the answer screen, Select now grades (Hard), so it no longer pages through a long
  answer there; the question still scrolls with Up/Down.

## 0.1.0 — Companion app (local AnkiDroid bridge)

Switched from the AnkiWeb sync proxy to a fully **local** architecture: an Android
companion app reads/writes the on-device **AnkiDroid** collection (AnkiDroid handles the
AnkiWeb sync itself), so nothing reverse-engineers AnkiWeb anymore.

```
AnkiWeb ⇄ AnkiDroid ⇄ (ContentProvider) Ishiki companion ⇄ (localhost) PebbleKit JS ⇄ (BT) watch
```

### Added
- **Android companion app** (`companion/`, Kotlin) — foreground service + localhost HTTP
  server (NanoHTTPD on `127.0.0.1:8765`) over AnkiDroid's ContentProvider. Endpoints:
  `GET /decks`, `GET /cards?deckId=`, `POST /review`. Builds to an installable debug APK
  (`./gradlew assembleDebug`); Gradle wrapper committed.
- **Store-and-forward on the phone** (PebbleKit JS) — a deck's due cards are fetched as a
  batch and served to the watch one at a time; reviews are queued with timestamps in
  `localStorage` and flushed to the companion, surviving it being briefly unreachable.
  "Again" is not re-shown mid-session (cards are served once).
- **Connection watchdog on the watch** — vibrates if a request doesn't go all the way:
  Bluetooth send fails, no reply within 20 s, the bridge can't reach the
  companion/AnkiDroid, or a reply is dropped.

### Changed
- Watch settings default to `http://127.0.0.1:8765`; the API token is now **optional**
  (the companion is localhost-only on the phone — no auth needed).
- Card identity is the opaque `"noteId:ord"` (AnkiDroid answers by note + ord, not card id).
- The watchapp C code is unchanged — the opaque id and existing message protocol carried
  the new contract without modification.

### Notes / limitations
- **No backdating** — AnkiDroid answers a card "now"; same-day reviews are fine (intervals
  are day-granular), only reviews queued across midnight can drift.
- The companion's AnkiDroid calls are **not yet verified on a real device**.
- The Python/AnkiWeb backend stays in the repo as a legacy/fallback option.
