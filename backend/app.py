"""FastAPI sync proxy between the Pebble phone app and AnkiWeb.

Run with:  uvicorn app:app --host 0.0.0.0 --port 8000   (single worker only —
the anki collection is single-process).
"""

from __future__ import annotations

import os
import threading
import time

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException, Query
from pydantic import BaseModel

from anki_client import AnkiClient

load_dotenv()

TOKEN = os.environ["API_TOKEN"]
SYNC_INTERVAL = int(os.environ.get("SYNC_INTERVAL", "120"))  # background sync, seconds

client = AnkiClient(
    collection_path=os.environ.get("COLLECTION_PATH", "./data/collection.anki2"),
    username=os.environ["ANKI_USERNAME"],
    password=os.environ["ANKI_PASSWORD"],
    endpoint=os.environ.get("ANKI_SYNC_ENDPOINT") or None,
)

app = FastAPI(title="anki-pebble backend")


def _require_auth(authorization: str | None) -> None:
    if authorization != f"Bearer {TOKEN}":
        raise HTTPException(status_code=401, detail="bad or missing token")


def _background_sync() -> None:
    # Periodically push wrist reviews to AnkiWeb and pull new due cards. Uses
    # allow_full=False so it can never overwrite un-synced reviews with a full
    # download (full syncs are only auto-resolved at startup / on explicit /sync).
    while True:
        time.sleep(SYNC_INTERVAL)
        try:
            print("background sync:", client.sync(allow_full=False))
        except Exception as exc:
            print("background sync failed:", exc)


@app.on_event("startup")
def _on_startup() -> None:
    try:
        print("initial sync:", client.sync(allow_full=True))
    except Exception as exc:  # surfaced, not fatal — endpoints still work offline
        print("initial sync failed:", exc)
    threading.Thread(target=_background_sync, daemon=True).start()


@app.get("/decks")
def get_decks(authorization: str | None = Header(default=None)):
    _require_auth(authorization)
    return [d.__dict__ for d in client.list_decks()]


@app.get("/next")
def get_next(
    deck_id: int = Query(...),
    authorization: str | None = Header(default=None),
):
    _require_auth(authorization)
    card = client.next_card(deck_id)
    return card if card is not None else {"done": True}


class Answer(BaseModel):
    card_id: int
    ease: int  # 1 = Not OK (Again), 3 = OK (Good)


@app.post("/answer")
def post_answer(body: Answer, authorization: str | None = Header(default=None)):
    _require_auth(authorization)
    client.answer(body.card_id, body.ease)
    return {"ok": True}


@app.post("/sync")
def post_sync(authorization: str | None = Header(default=None)):
    _require_auth(authorization)
    return {"result": client.sync()}
