"""FastAPI sync proxy between the Pebble phone app and AnkiWeb.

Run with:  uvicorn app:app --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import os

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException, Query
from pydantic import BaseModel

from anki_client import AnkiClient

load_dotenv()

TOKEN = os.environ["API_TOKEN"]

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


@app.on_event("startup")
def _initial_sync() -> None:
    try:
        print("initial sync:", client.sync())
    except Exception as exc:  # surfaced, not fatal — endpoints still work offline
        print("initial sync failed:", exc)


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
