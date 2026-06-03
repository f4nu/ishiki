# anki-pebble

Review your AnkiWeb cards from your wrist on a Pebble (targeting **Core Time 2** —
200×228, 64-color, 4 buttons).

**Flow:** pick a deck → see the card front → press **Select** to reveal the back →
**Up = OK**, **Down = Not OK** → next card.

**Controls**
- *Front:* Up/Down scroll a long question · **Select** reveals the answer
- *Back:* **Up = OK** (green) · **Down = Not OK** (red) · **Select** pages down a long
  answer (wraps to top). Grading flashes the screen green/red, then loads the next card.

## Architecture

The watch has no internet of its own; it reaches the network through a JavaScript
sandbox inside the Pebble phone app, which calls our backend. Three tiers:

```
Pebble watch (C)  <--AppMessage / BT-->  PebbleKit JS (phone)  <--HTTPS-->  Sync proxy (Python, this repo)  <--official anki lib-->  AnkiWeb
```

- **backend/** — FastAPI service. Keeps a local collection synced with AnkiWeb using
  the official `anki` package and exposes a tiny REST API. Built first; testable with
  no Pebble tooling.
- **pebble/** — the watchapp (C) + PebbleKit JS bridge. Next phase; needs the Pebble SDK.

## Backend API

All requests require `Authorization: Bearer <API_TOKEN>`.

| Method | Path | Returns |
| --- | --- | --- |
| GET  | `/decks`               | `[{id, name, due}]` |
| GET  | `/next?deck_id=<id>`   | `{card_id, front, back}` or `{done: true}` |
| POST | `/answer` `{card_id, ease}` | `{ok: true}` — ease: `1` = Not OK (Again), `3` = OK (Good) |
| POST | `/sync`                | `{result: ...}` |

## Watch ⇄ phone message protocol (for the upcoming `pebble/` side)

AppMessage keys (to be declared in `package.json`):

- **Watch → JS:** `CMD` (1=decks, 2=card, 3=answer, 4=sync), `DECK_ID`, `CARD_ID`, `EASE`
- **JS → Watch:** `MSG_TYPE` (1=decks, 2=card, 3=done, 4=error),
  `DECKS` (newline-joined rows `id\tname\tdue`), `FRONT`, `BACK`, `CARD_ID`, `ERR`

## Setup — backend

Requires Python 3.10+ (3.12 confirmed).

```bash
cd backend
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env        # then edit: AnkiWeb creds + a random API_TOKEN
uvicorn app:app --host 0.0.0.0 --port 8000
```

On first start it does a **one-time full download** of your collection from AnkiWeb
into `backend/data/`. Subsequent reviews sync incrementally.

Quick test:

```bash
TOKEN=...   # the API_TOKEN you set in .env
curl -H "Authorization: Bearer $TOKEN" localhost:8000/decks
```

Or verify the core logic **offline, no AnkiWeb account needed** (builds a throwaway
collection and exercises decks / next / answer / text-cleaning):

```bash
cd backend && . .venv/bin/activate && python smoke_test.py
```

## Exposing the backend to your phone

The phone's Pebble app must be able to reach the backend. Secure options:

- **Tailscale** on both the server and phone — private, no port-forwarding.
- A small **VPS** with HTTPS (Caddy / Cloudflare Tunnel).

Don't expose it raw on the public internet — the bearer token is the only guard.

## Roadmap

- [x] Decide architecture (custom sync proxy) + target (Core Time 2)
- [x] **Backend** — `decks` / `next` / `answer` built & validated against anki 25.09.4
- [x] **Backend** — AnkiWeb `sync` working end-to-end (full download validated)
- [x] Pebble toolchain + SDK 4.9.169 installed
- [x] **Watchapp + PebbleKit JS bridge** — builds to `.pbw` for emery
- [x] Run in emulator + end-to-end study loop
- [x] Settings page (Clay): backend URL + API token, set on the phone
- [x] Color cues (green OK / red Not OK + grade flash) and long-card scrolling
- [ ] Real-phone deploy (set Tailscale/VPS URL in settings)

## Caveats (personal use)

- AnkiWeb has no official public API; this uses Anki's own `anki` library to sync
  *your own* account. Fine for personal use.
- MVP card selection queries due/new cards directly rather than fully driving the v3
  scheduler queue (daily limits / sibling burying not enforced yet). Answers still feed
  the real scheduler, so AnkiWeb stays correct.
- `sync_login` can block briefly, and first-run full-download handling is the
  known-fragile bit — hardened on the first real sync.
