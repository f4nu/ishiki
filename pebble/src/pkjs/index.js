// PebbleKit JS bridge for the Ishiki companion app.
//
// Talks to the companion's localhost HTTP server (default http://127.0.0.1:8765):
//   GET  /decks                -> [{id, name, due}]
//   GET  /cards?deckId=<id>     -> [{id:"noteId:ord", front, back}]   (a batch)
//   POST /review {cardId, ease, timestamp}
//
// Store-and-forward lives here: a selected deck's due cards are cached for the
// session and served to the watch one at a time; reviews are queued with the time
// they happened and flushed to the companion, surviving it being briefly
// unreachable. "Again" is not re-queued mid-session (cards are served once).

var Clay = require('pebble-clay');
var clayConfig = require('./config');
var clay = new Clay(clayConfig);

var MAX_TEXT = 900;

// CMD: watch -> js
var CMD_GET_DECKS = 1, CMD_GET_CARD = 2, CMD_ANSWER = 3, CMD_SYNC = 4;
// MSG_TYPE: js -> watch
var MSG_DECKS = 1, MSG_CARD = 2, MSG_DONE = 3, MSG_ERROR = 4;

var QUEUE_KEY = 'reviewQueue';

// ---- settings (Clay) -------------------------------------------------------
function settings() {
  try { return JSON.parse(localStorage.getItem('clay-settings')) || {}; }
  catch (e) { return {}; }
}
function backendUrl() {
  return (settings().BACKEND_URL || 'http://127.0.0.1:8765').replace(/\/+$/, '');
}
function apiToken() { return settings().API_TOKEN || ''; }

// ---- HTTP ------------------------------------------------------------------
function api(method, path, body, onok, onerr) {
  var xhr = new XMLHttpRequest();
  xhr.open(method, backendUrl() + path);
  var token = apiToken();
  if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token); // optional (legacy backend)
  if (body) xhr.setRequestHeader('Content-Type', 'application/json');
  xhr.timeout = 15000;
  xhr.onload = function () {
    if (xhr.status >= 200 && xhr.status < 300) {
      try { onok(xhr.responseText ? JSON.parse(xhr.responseText) : null); }
      catch (e) { onerr('bad json'); }
    } else { onerr('http ' + xhr.status); }
  };
  xhr.onerror = function () { onerr('network'); };
  xhr.ontimeout = function () { onerr('timeout'); };
  xhr.send(body ? JSON.stringify(body) : null);
}

function sendErr(msg) {
  console.log('error: ' + msg);
  Pebble.sendAppMessage({ MSG_TYPE: MSG_ERROR, ERR: String(msg).slice(0, 100) });
}
function trunc(s) { s = s || ''; return s.length > MAX_TEXT ? s.slice(0, MAX_TEXT) : s; }

// ---- review queue (store-and-forward, persisted) ---------------------------
function loadQueue() {
  try { return JSON.parse(localStorage.getItem(QUEUE_KEY)) || []; }
  catch (e) { return []; }
}
function saveQueue(q) { localStorage.setItem(QUEUE_KEY, JSON.stringify(q)); }

function enqueueReview(cardId, ease) {
  var q = loadQueue();
  q.push({ cardId: cardId, ease: ease, timestamp: Date.now() });
  saveQueue(q);
}

var flushing = false;
// Send queued reviews oldest-first; stop (keep the queue) if the companion is down.
function flushQueue() {
  if (flushing) return;
  flushing = true;
  (function step() {
    var q = loadQueue();
    if (q.length === 0) { flushing = false; return; }
    var item = q[0];
    api('POST', '/review',
      { cardId: item.cardId, ease: item.ease, timestamp: item.timestamp },
      function () {
        var cur = loadQueue();
        cur.shift();          // drop the one just accepted
        saveQueue(cur);
        step();               // continue chronologically
      },
      function () {
        flushing = false;     // companion unreachable — retry later
      });
  })();
}

// ---- decks -----------------------------------------------------------------
function fetchDecks() {
  api('GET', '/decks', null, function (decks) {
    var rows = (decks || []).map(function (d) {
      return d.id + '\t' + d.name + '\t' + (d.due || 0);
    }).join('\n');
    Pebble.sendAppMessage({ MSG_TYPE: MSG_DECKS, DECKS: rows });
  }, sendErr);
}

// ---- session (a deck's cached due cards) -----------------------------------
var session = { deckId: null, cards: [], index: 0 };

function startSession(deckId) {
  api('GET', '/cards?deckId=' + encodeURIComponent(deckId), null,
    function (cards) {
      session = { deckId: deckId, cards: cards || [], index: 0 };
      serveCurrent();
    }, sendErr);
}

function serveCurrent() {
  if (session.index >= session.cards.length) {
    Pebble.sendAppMessage({ MSG_TYPE: MSG_DONE });
    return;
  }
  var c = session.cards[session.index];
  Pebble.sendAppMessage({
    MSG_TYPE: MSG_CARD,
    CARD_ID: String(c.id),
    FRONT: trunc(c.front),
    BACK: trunc(c.back)
  });
}

function answerCurrent(cardId, ease) {
  enqueueReview(cardId, ease);   // record with timestamp; persisted
  session.index += 1;            // consume the card (Again is not re-shown)
  serveCurrent();                // serve next from cache immediately (no network)
  flushQueue();                  // push reviews to the companion in the background
}

// ---- events ----------------------------------------------------------------
Pebble.addEventListener('ready', function () {
  flushQueue();   // drain reviews queued in a previous session
  fetchDecks();
});

Pebble.addEventListener('webviewclosed', function () { fetchDecks(); });

Pebble.addEventListener('appmessage', function (e) {
  var d = e.payload;
  switch (d.CMD) {
    case CMD_GET_DECKS: fetchDecks(); break;
    case CMD_GET_CARD:  startSession(d.DECK_ID); break;   // deck selected -> fetch batch
    case CMD_ANSWER:    answerCurrent(d.CARD_ID, d.EASE); break;
    case CMD_SYNC:      flushQueue(); break;
  }
});
