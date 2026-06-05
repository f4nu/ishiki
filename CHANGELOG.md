# Companion app changelog

Versions for the Android companion app (`companion/`).
Watch app changelog: [`pebble/CHANGELOG.md`](pebble/CHANGELOG.md).

## 0.2.1

### Added

- New log textarea to show what the app is doing.

## 0.2.0

### Added
- Launcher **icon** (石記).
- In-app **live log** — a scrollable box under the status showing served HTTP calls,
  errors, and server lifecycle.
- **Release pipeline** — conditional release-signing config + a GitHub Actions workflow
  that builds the APK and publishes it as a `v<version>` GitHub Release on a version bump
  (debug-signed fallback until the signing secrets are set).

## 0.1.0

### Added
- Initial companion: a minimal Kotlin app with a foreground service running a localhost
  HTTP server (NanoHTTPD on `127.0.0.1:8765`) over AnkiDroid's ContentProvider.
- Endpoints: `GET /decks`, `GET /cards?deckId=`, `POST /review`
  (ease `1`=Again, `2`=Hard, `3`=Good); card id is the opaque `"noteId:ord"`.
- Builds to an installable debug APK.

### Notes
- AnkiDroid records reviews at **submit time** (no backdating) — same-day is fine
  (intervals are day-granular); reviews queued across midnight can drift.
- The AnkiDroid ContentProvider calls are version-sensitive and **not yet verified on a
  physical device**.
