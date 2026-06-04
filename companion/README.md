# Ishiki companion (Android)

A minimal Kotlin app that bridges **AnkiDroid** to the Ishiki Pebble watchapp. It reads
decks/due-cards and submits reviews through AnkiDroid's ContentProvider and exposes them to
PebbleKit JS over a localhost HTTP server. AnkiDroid handles AnkiWeb sync natively, so
nothing here ever touches AnkiWeb directly.

```
AnkiWeb ⇄ AnkiDroid ⇄ (ContentProvider) Ishiki companion ⇄ (localhost:8765) PebbleKit JS ⇄ (BT) watch
```

## Status
Scaffolded — **not yet built or tested on a device** (authored without an Android SDK).
Build it in Android Studio and test against a phone with AnkiDroid installed.

## Endpoints (127.0.0.1:8765)
| Method | Path | Returns |
|---|---|---|
| GET  | `/decks`              | `[{id, name, due}]` |
| GET  | `/cards?deckId=<id>`  | `[{id:"noteId:ord", front, back}]` (up to 50) |
| POST | `/review` `{cardId, ease, timestamp?, timeTaken?}` | `{ok:true}` |

- `cardId` is opaque (`"<noteId>:<ord>"`) — AnkiDroid answers by note+ord, not a card id.
- `ease`: 1 = Again, 3 = Good (clamped to the card's button count). `rating` is an alias.

## Build
Needs Android Studio (bundles SDK + Gradle) or a local Android SDK.
```bash
cd companion
gradle wrapper            # one-time: generate ./gradlew, then commit the wrapper jar
./gradlew assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
```
Or open `companion/` in Android Studio and Run.

## Use
1. Install the APK, open it once, grant the AnkiDroid permission + notifications.
2. It runs a foreground service serving `http://127.0.0.1:8765`.
3. In the Pebble app, set the watchapp's **Backend URL** to `http://127.0.0.1:8765`.

## Known limitations / TODO
- **No backdating.** AnkiDroid answers a card *now*; the API can't record a historical
  review time. Same-day submission is fine (Anki intervals are day-granular); only reviews
  queued across midnight drift. `timestamp` is accepted but advisory.
- **Pebble side not updated yet.** The watch still speaks the old `/next` + `/answer`
  contract. To use this companion the watch needs to: fetch `/cards` (batch) and cache
  them, queue reviews and POST `/review` with the opaque string `cardId` (not `parseInt`),
  and own store-and-forward + "Again removed from session". See repo `CLAUDE.md` §0.
- **FGS type** is `specialUse` (fine for sideload / F-Droid; Play Store would need review).
- Verify on a real device: the `schedule` selection (`"limit=?, deckID=?"`), the card-text
  columns (`question_simple`/`answer_simple`), and the deck-count parsing — all are
  AnkiDroid-version-sensitive.

## Shipping
- Short term: APK on GitHub releases.
- Long term: F-Droid — deps are FOSS (NanoHTTPD). Commit the Gradle wrapper jar so it
  builds reproducibly.
