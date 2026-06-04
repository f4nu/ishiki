# anki-pebble — project handoff & plan

Study your AnkiWeb cards from a Pebble smartwatch (targeting **Core Time 2** / `emery`,
200×228, 64-color). The watch shows a card front, you reveal the back, grade it
**OK / Not OK**, and it advances — backed by your real AnkiWeb collection.

This file is the source of truth for **what exists**, **what's next**, and **how the
planned SaaS rewrite should be approached**. Read it fully before changing anything.

---

## 0. Resume here (session handoff)

> **Direction change (current focus): the backend is moving on-device.** Instead of the
> Python proxy logging into AnkiWeb directly (fragile, ToS risk), an Android **companion
> app** (`companion/`, Kotlin) reads/writes the local **AnkiDroid** collection via its
> ContentProvider and serves the watch over localhost HTTP. AnkiDroid does the AnkiWeb
> sync natively. New shape:
>
> `AnkiWeb ⇄ AnkiDroid ⇄ (ContentProvider) Ishiki companion ⇄ (localhost:8765) PebbleKit JS ⇄ (BT) watch`
>
> The Python backend (§2–§3, §10) is now the **legacy/alternative** path — kept working as
> a reference/fallback, superseded by the companion. Companion details: `companion/README.md`.

**What this is:** the Pebble Core Time 2 watchapp is **done**. There are two interchangeable
backends: the legacy Python/AnkiWeb proxy (§2–§3 — runs on real hardware, Dockerized) and
the new local **AnkiDroid companion** (`companion/` — scaffolded, **not yet built/tested on
a device**). The multi-user SaaS (§4–§9) is on hold given the local direction.

**Repo / git**
- Remote: `git@github.com:f4nu/ishiki.git`, branch `main`.
- Commit as **`f4nu <mattiafanuc@gmail.com>`** (NOT mattia@abiby.it). End AI-authored
  commits with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

**Not in the repo** (gitignored — must be recreated locally): `backend/.env` (AnkiWeb
creds + API token), `backend/data/` (the synced collection — rebuilt on first sync),
`backend/.venv/`, `pebble/node_modules/`, `pebble/build/`. The Pebble toolchain
(`~/.pebble-tool-venv` + the SDK) is machine-local too.

**Rebuild the environment from a fresh clone**
```bash
# backend
cd backend && python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env            # fill creds + API_TOKEN=$(openssl rand -hex 32)
python smoke_test.py            # offline sanity check (no account needed)

# pebble toolchain (needs Python 3.10–3.13; we used 3.12)
python3 -m venv ~/.pebble-tool-venv && ~/.pebble-tool-venv/bin/pip install pebble-tool
export PATH="$HOME/.pebble-tool-venv/bin:$PATH"
pebble sdk install latest       # SDK 4.9.x + ARM toolchain
cd ../pebble && npm install && pebble build   # -> build/pebble.pbw (target: emery)
```

**Open threads — decide what's next**
1. **Finish the AnkiDroid companion** (`companion/`) — the current direction. Scaffolded:
   `/decks`, `/cards?deckId=`, `/review` over localhost via AnkiDroid's ContentProvider
   (NanoHTTPD, foreground service). TODO: build in Android Studio + test on a device with
   AnkiDroid; commit the Gradle wrapper jar; then **update the Pebble side** to the new
   contract — `/cards` (batch) + `/review`, store-and-forward queue with "Again removed
   from session", and treat `cardId` as the opaque string `"noteId:ord"` (the watch
   currently speaks the old `/next` + `/answer` and `parseInt`s the id). Ship APK on GitHub
   releases, later F-Droid (deps are FOSS). See `companion/README.md` (incl. the
   no-backdating limitation).
2. **Pebble store listing** — `pebble/STORE.md` (backend-required + "Anki" name caveats).
3. **SaaS** (§4–§9) — on hold; the local companion sidesteps the AnkiWeb ToS problem.

---

## 1. The one architectural fact that governs everything

**AnkiWeb has no public API, and the only working implementation of the Anki sync
protocol + scheduler is the official `anki` *Python* package.** There is no usable PHP
port, and reimplementing the sync protocol or the FSRS/SM-2 scheduler in PHP is not
realistic (huge, undocumented, version-coupled, fragile).

Therefore the Laravel rewrite **cannot** talk to AnkiWeb directly. The viable shape is:

```
Pebble watch (C)
   │  AppMessage / BT
PebbleKit JS (phone)            <-- unchanged; just point it at the new URL + per-user key
   │  HTTPS  (Bearer = user's API key)
Laravel  = CONTROL PLANE        <-- NEW: auth, accounts, API keys, billing, rate limits,
   │  internal HTTPS (service token)      entitlements, the public /decks /next /answer API
Python "anki-worker" = DATA PLANE  <-- EVOLVED from today's FastAPI app: per-user AnkiWeb
   │  official `anki` lib                 sync + scheduler operations
AnkiWeb
```

Laravel owns users, keys, money, and policy. The Python worker owns the collection
files and every call into the `anki` library. They talk over a small internal API.

Do **not** try to make Laravel sync with AnkiWeb itself. If you remember nothing else,
remember this.

---

## 2. Current state (single-user prototype — WORKING)

A 3-tier prototype is built and validated end-to-end against a real AnkiWeb account.

### Repo layout
```
anki-pebble/
├── CLAUDE.md                 # this file
├── README.md                 # user-facing setup + roadmap
├── .gitignore
├── backend/                  # Python FastAPI sync proxy (single-user, .env-config)
│   ├── app.py                # FastAPI: /decks /next /answer /sync, Bearer auth
│   ├── anki_client.py        # wraps anki Collection: sync + scheduler + HTML->text
│   ├── smoke_test.py         # offline test of the anki logic (no account needed)
│   ├── requirements.txt      # anki>=24.11, fastapi, uvicorn, python-dotenv
│   ├── .env.example          # ANKI_USERNAME/PASSWORD, API_TOKEN, COLLECTION_PATH
│   └── .venv/                # (gitignored) anki 25.09.4 installed here
└── pebble/                   # Pebble watchapp (emery)
    ├── package.json          # displayName "Anki", uuid, messageKeys, pebble-clay dep
    ├── src/c/pebble.c        # UI: deck menu + card window (scroll, color cues, states)
    ├── src/pkjs/index.js     # bridge: reads Clay settings, calls backend, AppMessage
    └── src/pkjs/config.js    # Clay schema: BACKEND_URL + API_TOKEN
```

### What's validated
- ✅ Backend logic (`decks` / `next` / `answer`) against **anki 25.09.4** (`smoke_test.py`).
- ✅ Real **AnkiWeb sync** — first-run full download confirmed working against a live account.
- ✅ Watchapp **builds** to a `.pbw` for `emery`; runs in the emulator end-to-end.
- ✅ Phone **settings page** (Clay) sets backend URL + API token; token is not in source.
- ✅ Color cues (green OK / red Not OK + grade flash) and long-card scrolling.
- ✅ **Dockerized** backend + **background auto-sync** (wrist reviews propagate to AnkiWeb);
  image build verified. Listens on 127.0.0.1:8000 behind your own TLS proxy.
- ✅ Committed & pushed to `f4nu/ishiki` (initial prototype + Docker deploy).

### Backend REST contract (the watch depends on this — KEEP IT STABLE)
Auth: `Authorization: Bearer <token>` on every request.

| Method | Path | Returns |
|---|---|---|
| GET  | `/decks`                    | `[{id, name, due}]` |
| GET  | `/next?deck_id=<id>`        | `{card_id, front, back}` or `{done: true}` |
| POST | `/answer` `{card_id, ease}` | `{ok: true}` — ease `1`=Not OK (Again), `3`=OK (Good) |
| POST | `/sync`                     | `{result: "..."}` |

**The Laravel public API must expose exactly these routes + Bearer auth**, so the watch
side needs zero code changes — only new settings (URL + per-user key).

### Pebble ⇄ phone protocol (AppMessage)
Keys (declared in `pebble/package.json` `messageKeys`):
`CMD, DECK_ID, CARD_ID, EASE, MSG_TYPE, DECKS, FRONT, BACK, ERR, BACKEND_URL, API_TOKEN`.

- Watch → JS: `CMD` (1=decks, 2=card, 3=answer, 4=sync), `DECK_ID`, `CARD_ID`, `EASE`.
- JS → Watch: `MSG_TYPE` (1=decks, 2=card, 3=done, 4=error), `DECKS` (rows `id\tname\tdue`
  joined by `\n`), `FRONT`, `BACK`, `CARD_ID`, `ERR`.

### Watch controls
- *Front:* Up/Down scroll a long question · **Select** reveals the answer.
- *Back:* **Up = OK** (green) · **Down = Not OK** (red) · **Select** pages a long answer
  (wraps to top). Grading flashes green/red, then loads the next card.

---

## 3. Hard-won `anki` library details (MUST be preserved in the Python worker)

These were discovered by trial against anki **25.09.4**. The sync protocol is coupled to
the library version — **pin `anki` and upgrade deliberately**. All of this lives in
`backend/anki_client.py` today and should be reused.

**Sync flow (the fragile part):**
1. `auth = col.sync_login(username, password, endpoint=None)` → `SyncAuth(hkey, endpoint, io_timeout_secs)`. Can briefly block the thread (benign warning).
2. `out = col.sync_collection(auth, sync_media=False)` — `sync_media` is **required** in 25.09; we skip media (watch is text-only).
3. **Follow the shard redirect:** `if out.new_endpoint: auth.endpoint = out.new_endpoint`.
   Skipping this makes the full download `400 "missing original size"`.
4. `req = int(out.required)` — enum: `0 NO_CHANGES, 1 NORMAL_SYNC, 2 FULL_SYNC, 3 FULL_DOWNLOAD, 4 FULL_UPLOAD`. `<=1` means done.
5. Full sync: `col.full_upload_or_download(auth=auth, server_usn=out.server_media_usn, upload=(req==4))` — **keyword-only**; `server_usn` comes from `out.server_media_usn`.
6. A full sync replaces the file on disk → **close and reopen the Collection** afterward.

**Store the `hkey`, not the password.** `sync_login` returns a long-lived `hkey`; persist
that (encrypted) and reconstruct `SyncAuth(hkey=..., endpoint=...)` for later syncs. Only
need the password once, at link time.

**Scheduler / cards (v3 scheduler):**
- Enable: `col.set_v3_scheduler(True)`.
- Decks + due counts: `col.sched.deck_due_tree()`, walk nodes
  (`deck_id`, `name`, `review_count + learn_count + new_count`).
- Next card (version-stable approach used today): `ids = col.find_cards('deck:"NAME" (is:due OR is:new)')`, then `card = col.get_card(ids[0])`. (Does **not** fully honor v3 queue ordering / daily limits / sibling burying — acceptable for MVP; revisit if needed.)
- **Set the review timer before answering:** `card.timer_started = time.time()`; otherwise
  `answerCard` crashes in `time_taken()` (None). Needed both when fetching and when answering (the card is re-fetched).
- Grade: `col.sched.answerCard(card, ease)` with ease `1=Again .. 4=Easy` (we use 1 and 3).

**HTML → plain text (`to_text`):** strip `<style>…</style>` and `<script>…</script>`
*blocks* (not just tags — the rendered question prepends the note CSS), strip `[sound:…]`
and the rendered `[anki:play:…]`, convert `<br>` and block-end tags to newlines, strip
remaining tags, `html.unescape`, collapse whitespace.

**Concurrency:** an `anki` `Collection` is **not** thread-safe and a file can be open by
only one process. Today a single `RLock` serializes access. For multi-user you need
**one collection file per user** and **serialized access per user** (see §6).

---

## 4. Target: multi-user SaaS

### Product goal
Public service. Free tier = one deck (or rate-limited reviews/day); paid tier unlocks
everything. Self-serve signup, billing portal, per-user API keys for the watch.

### Laravel = control plane responsibilities
- Email/password auth + sessions (Breeze or Jetstream).
- Account management; multiple AnkiWeb accounts per user is possible later, **start with one
  linked AnkiWeb account per user**.
- **API keys** (Sanctum personal access tokens, or a dedicated `api_keys` table) — these are
  the Bearer tokens the watch sends. One user can have several (per device).
- The **public REST API** the watch hits (`/decks /next /answer /sync`) — authenticates the
  API key, checks entitlements + rate limits, then proxies to the Python worker.
- **Billing** (Laravel Cashier + Stripe), plans, the customer portal, webhooks.
- **Entitlements & rate limiting** (free vs paid; deck cap / review cap / request rate).
- Encryption of AnkiWeb `hkey` at rest; audit/logging; abuse controls.

### Python anki-worker = data plane responsibilities
Evolve `backend/` (don't rewrite from scratch — reuse `anki_client.py`):
- Internal-only HTTP API (authenticated by a shared service token, **not** exposed publicly),
  every endpoint scoped by a `user_id` (or AnkiWeb account id).
- `POST /link` — given username+password, do `sync_login`, return the `hkey` (Laravel stores
  it encrypted). Worker never persists the password.
- Per-user collection files on a persistent volume: `data/<user_id>/collection.anki2`.
- `GET /decks`, `GET /next`, `POST /answer`, `POST /sync` — same semantics as today but
  keyed by user; load/lock that user's collection, run the op, return JSON.
- Concurrency: a lock per `user_id`; consider an LRU of open collections, or open-per-request
  with care. Long term: shard users across worker instances.

### What does NOT change
The **Pebble watchapp and pkjs are already SaaS-ready** — they read `BACKEND_URL` +
`API_TOKEN` from the Clay settings page. The user just sets the public URL and their
personal API key. (Optional later: nicer onboarding, show plan/usage on the watch.)

---

## 5. Suggested Laravel data model (starting point)
- `users` — standard auth.
- `anki_accounts` — `user_id`, `ankiweb_username`, `hkey` (encrypted), `endpoint`,
  `last_synced_at`, `status`. (Start: one row per user.)
- `api_keys` — `user_id`, `name`, `token_hash`, `last_used_at`, `revoked_at`. (Or use
  Sanctum tokens.)
- `plans` / Cashier `subscriptions` — free vs pro; entitlement flags (deck cap, review cap).
- `usage_counters` — per-user/day counts for rate limiting and the free-tier cap.

## 6. Public API auth & entitlement flow (per watch request)
1. Resolve API key → user (reject/limit if missing/revoked).
2. Check subscription/entitlements: free tier → restrict to one allowed deck and/or a daily
   review cap; enforce per-key rate limit.
3. Look up the user's `anki_account`; call the worker with `user_id` + service token.
4. Return the worker's JSON (same shape the watch already parses).

## 7. Billing
- Laravel Cashier (Stripe). Plans: Free (1 deck or N reviews/day, rate-limited) and Pro
  (unlimited). Stripe Checkout + customer portal; handle webhooks to flip entitlements.
- Gate features in the public API middleware, not on the watch.

---

## 8. Roadmap (phased)

**Phase 0 — keep prototype working.** Tag/commit current single-user version first.

**Phase A — Laravel skeleton.** Breeze/Jetstream auth; users; dashboard; Sanctum API keys
(create/revoke/list).

**Phase B — Worker multi-user refactor.** Generalize `backend/` to per-user collections +
internal service-token auth; add `POST /link` (hkey exchange); keep `smoke_test.py` green.

**Phase C — AnkiWeb linking UI.** Laravel form to enter AnkiWeb creds → call worker `/link`
→ store encrypted `hkey`. Never log credentials.

**Phase D — Public API + proxy.** Laravel `/decks /next /answer /sync` (Bearer = API key) →
entitlement/rate-limit middleware → proxy to worker. Point the emulator at it to verify the
watch is unchanged.

**Phase E — Billing.** Cashier + Stripe; Free/Pro plans; entitlements wired into the
middleware; customer portal.

**Phase F — Rate limiting & quotas.** Per-key rate limits; free-tier deck/review caps;
usage counters; friendly `MSG_ERROR` strings on the watch (e.g. "Upgrade to study more").

**Phase G — Hardening & deploy.** Queue workers, worker scaling/sharding, secrets mgmt,
backups of per-user collections, monitoring, ToS/privacy pages, GDPR delete.

**Phase H — Watch polish (optional).** Onboarding, plan/usage display, extra grades
(Hard/Easy), explicit "Sync now", deck due-count refresh.

---

## 9. Risks & open decisions — READ BEFORE GOING COMMERCIAL

- **AnkiWeb Terms of Service / maintainer stance (BIGGEST RISK).** AnkiWeb's sync protocol
  is undocumented and **not a public API**; the Anki maintainers explicitly discourage
  third-party access. A *personal* tool using your *own* account is one thing; a **paid,
  public service that logs into many users' AnkiWeb accounts and proxies their data** is a
  different, much riskier proposition — it may violate AnkiWeb's terms, get your servers/IPs
  blocked, and will break whenever the protocol changes. **Do not build the paid tier
  without resolving this.** Options:
  - Get explicit permission/guidance from the Anki maintainers.
  - **Run your own Anki sync server** (`python -m anki.syncserver`) and have users sync their
    Anki *to you* instead of to AnkiWeb — cleaner footing, but you become responsible for
    their data and it changes onboarding (still Python-based; scheduler ops unchanged).
  - Be a good citizen: cache, rate-limit your calls to AnkiWeb, never hammer it.
- **Storing other people's credentials/collections = serious liability.** Store the `hkey`,
  not the password; encrypt at rest; minimize retention; plan for breach/GDPR; let users
  delete everything. Treat collection files as sensitive personal data.
- **Library version coupling.** Sync breaks across `anki` versions (we already hit 3 API
  changes in 25.09). Pin it; test before upgrading; expect maintenance.
- **Scaling the worker.** Collections are single-writer files held in a single Python
  process. Memory/disk/locking will be the scaling bottleneck — plan sharding early.
- **Scheduler fidelity.** The MVP `find_cards` approach ignores v3 queue order / daily
  limits / burying. Decide whether the product needs true scheduler fidelity (use the v3
  queued-cards API) before charging.

---

## 10. Dev notes / commands

**Backend (current, single-user):**
```bash
cd backend && python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env        # set ANKI_USERNAME/PASSWORD, API_TOKEN=$(openssl rand -hex 32)
python smoke_test.py        # offline logic check (no account)
uvicorn app:app --port 8000 # first run does a full download into backend/data/
```

**Deploy (Docker, single-user):** `cd backend && docker compose up -d --build` — persists
the collection in a volume, **background auto-syncs** every `SYNC_INTERVAL`s so wrist
reviews reach AnkiWeb without manual `/sync` (`allow_full=False` there, so it won't
clobber un-synced reviews). Listens on 127.0.0.1:8000 — front it with your own TLS proxy.

**Pebble watchapp:**
```bash
# toolchain lives in ~/.pebble-tool-venv (pebble-tool v5.0.36, SDK 4.9.169)
export PATH="$HOME/.pebble-tool-venv/bin:$PATH"
cd pebble && npm install          # pebble-clay
pebble build                       # -> build/pebble.pbw  (target: emery)
pebble install --emulator emery --logs
pebble emu-app-config              # open the Clay settings page in a browser
# Emulator needs (one-time, sudo): libsdl2-2.0-0 libglib2.0-0 libpixman-1-0 libsndio7.0
```

**Environment facts**
- Python 3.12, `anki` 25.09.4 (pinned behavior — see §3).
- Pebble platform `emery` (Core Time 2). `diorite` (Core 2 Duo, B&W) is a future target;
  the UI currently assumes color + 200×228.
- Token currently flows: backend `.env` `API_TOKEN` ⇄ Clay `API_TOKEN` setting. In the SaaS
  this becomes a per-user API key issued by Laravel.
