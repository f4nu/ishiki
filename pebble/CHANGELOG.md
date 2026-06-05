# Watch app changelog

Versions for the Pebble watchapp (`pebble/`, target: `emery` / Core Time 2).
Companion app changelog: [`../CHANGELOG.md`](../CHANGELOG.md).

## 0.2.2

### Added

- **Hold Up/Down to scroll** a long answer on the back (single-press still grades).

## 0.2.0

### Added
- Right-side **action bar** (drawn with graphics primitives, no image resources),
  replacing the bottom text legend. On the answer screen: ✓ **Good** (Up), ~ **Hard**
  (Select), ✗ **Again** (Down). On the question: scroll chevrons (Up/Down) + a reveal cue
  (Select).
- **Third grade** — Good / Hard / Again (was OK / Not OK).
- Deck list **refreshes its due counts** on return from a deck.

### Changed
- The answer **flash is held ~550 ms** and the next card is buffered until it ends, so the
  grade color is actually visible (previously the instant next-card repaint hid it).

### Fixed
- `/decks` was fetched **twice on launch** (PebbleKit JS `ready` + the menu's first
  `appear`); the first appear is now skipped.

## 0.1.0

### Added
- Deck menu, card front/back reveal, and grading.
- Clay **settings page** (backend URL + optional API token).
- Color cues and a green/red grade flash; long-card scrolling.
- **Store-and-forward** bridge (PebbleKit JS): fetch a deck's due cards as a batch, serve
  them one at a time, and queue reviews (with timestamps) to flush when the backend is
  reachable.
- **Connection watchdog**: vibrate if a request doesn't complete — Bluetooth down, no
  reply, a dropped reply, or the bridge reporting an error.
