# Pebble appstore listing (draft)

> **Before publishing:** see the two caveats at the bottom — the backend requirement and
> the "Anki" name collision. Decide the public app name and update `displayName` in
> `package.json` first.

**Tagline**
Review your Anki flashcards from your wrist.

**Short description**
Study your real AnkiWeb decks on Pebble — flip each card and grade it OK / Not OK,
straight from your wrist.

**Full description**
Anki brings spaced-repetition flashcards to your Pebble. Pick a deck, read the front,
press Select to reveal the answer, then grade it Up = OK or Down = Not OK — and it
advances to the next card. Long cards scroll, grades flash green/red, and your reviews
sync back to your collection automatically.

Built for Core Time 2 (color, 200×228).

Controls
- Front: Up/Down scroll · Select reveals the answer
- Back: Up = OK · Down = Not OK · Select pages through long answers

Setup (required)
This app connects to your own anki-pebble backend — a small open-source service that
syncs with AnkiWeb. Run it on a server, then open the app's Settings on your phone and
enter your Backend URL and API token. Source & setup guide: github.com/f4nu/ishiki

---

## Caveats to resolve before listing
- **Backend required.** The app does nothing without a self-hosted backend + the phone
  Settings (URL + token). Most store users expect plug-and-play — state this plainly in
  the description or expect "broken" reviews.
- **Name / trademark.** "Anki" is an established brand; a public listing under that name
  risks confusion or takedown. Pick a distinct name (e.g. "Wrist Cards for Anki", or an
  *ishiki*-based name) and set it as `displayName` in `package.json`.
