#include <pebble.h>

// CMD: watch -> js   (must match src/pkjs/index.js)
#define CMD_GET_DECKS 1
#define CMD_GET_CARD  2
#define CMD_ANSWER    3
#define CMD_SYNC      4
// MSG_TYPE: js -> watch
#define MSG_DECKS 1
#define MSG_CARD  2
#define MSG_DONE  3
#define MSG_ERROR 4
// Anki ease values (right-side action bar)
#define EASE_AGAIN 1  // red cross  (Down)
#define EASE_HARD  2  // yellow ~   (Select)
#define EASE_GOOD  3  // green check (Up)

#define MAX_DECKS      40
#define DECK_NAME_LEN  48
#define ID_LEN         24
#define TEXT_LEN       1024
#define BAR_W          30   // right-side action bar width

// ---- deck list state -------------------------------------------------------
static char s_deck_ids[MAX_DECKS][ID_LEN];
static char s_deck_names[MAX_DECKS][DECK_NAME_LEN];
static int  s_deck_due[MAX_DECKS];
static int  s_deck_count = 0;
static bool s_decks_loaded = false;

// ---- current card state ----------------------------------------------------
static char s_current_deck_id[ID_LEN];
static char s_card_id[ID_LEN];
static char s_front[TEXT_LEN];
static char s_back[TEXT_LEN];

typedef enum { CARD_LOADING, CARD_FRONT, CARD_BACK, CARD_INFO } CardState;
static CardState s_card_state = CARD_LOADING;

// Answer flash: hold the green/red/yellow wash for a fixed time even though the next
// card (served from the JS cache) arrives almost instantly. The incoming card/done is
// buffered and shown when the flash ends.
#define FLASH_MS 550
typedef enum { PEND_NONE, PEND_CARD, PEND_DONE } Pending;
static bool s_flashing = false;
static Pending s_pending = PEND_NONE;

// ---- windows ---------------------------------------------------------------
static Window *s_menu_window;
static MenuLayer *s_menu_layer;
static Window *s_card_window;
static ScrollLayer *s_scroll;
static TextLayer *s_card_text;
static Layer *s_bar;   // right-side action bar (drawn glyphs, no images)
static bool s_card_loaded = false;

// Connection watchdog: vibrate if a request to the phone/companion doesn't complete.
// 20s > the JS XHR timeout (15s), so a bridge-reported error arrives first and we
// buzz once via MSG_ERROR; this timer only fires when nothing replies at all.
static AppTimer *s_req_timer = NULL;
#define REQ_TIMEOUT_MS 20000
static void arm_req_timer(void);
static void clear_req_timer(void);

// ---- outbox helpers --------------------------------------------------------
static void send_get_decks(void) {
  DictionaryIterator *it;
  if (app_message_outbox_begin(&it) != APP_MSG_OK) return;
  dict_write_uint8(it, MESSAGE_KEY_CMD, CMD_GET_DECKS);
  app_message_outbox_send();
  arm_req_timer();
}

static void send_get_card(const char *deck_id) {
  DictionaryIterator *it;
  if (app_message_outbox_begin(&it) != APP_MSG_OK) return;
  dict_write_uint8(it, MESSAGE_KEY_CMD, CMD_GET_CARD);
  dict_write_cstring(it, MESSAGE_KEY_DECK_ID, deck_id);
  app_message_outbox_send();
  arm_req_timer();
}

static void send_answer(const char *card_id, int ease, const char *deck_id) {
  DictionaryIterator *it;
  if (app_message_outbox_begin(&it) != APP_MSG_OK) return;
  dict_write_uint8(it, MESSAGE_KEY_CMD, CMD_ANSWER);
  dict_write_cstring(it, MESSAGE_KEY_CARD_ID, card_id);
  dict_write_uint8(it, MESSAGE_KEY_EASE, (uint8_t)ease);
  dict_write_cstring(it, MESSAGE_KEY_DECK_ID, deck_id);
  app_message_outbox_send();
  arm_req_timer();
}

// ---- deck list parsing -----------------------------------------------------
static void copy_field(char *dst, int dstsz, const char *start, const char *end) {
  int n = end - start;
  if (n > dstsz - 1) n = dstsz - 1;
  if (n < 0) n = 0;
  memcpy(dst, start, n);
  dst[n] = '\0';
}

// Parse rows of "id\tname\tdue" separated by '\n'.
static void parse_decks(const char *s) {
  s_deck_count = 0;
  while (*s && s_deck_count < MAX_DECKS) {
    const char *nl = strchr(s, '\n');
    const char *end = nl ? nl : (s + strlen(s));
    const char *t1 = strchr(s, '\t');
    if (t1 && t1 < end) {
      const char *t2 = strchr(t1 + 1, '\t');
      copy_field(s_deck_ids[s_deck_count], ID_LEN, s, t1);
      if (t2 && t2 < end) {
        copy_field(s_deck_names[s_deck_count], DECK_NAME_LEN, t1 + 1, t2);
        char duebuf[8];
        copy_field(duebuf, sizeof duebuf, t2 + 1, end);
        s_deck_due[s_deck_count] = atoi(duebuf);
      } else {
        copy_field(s_deck_names[s_deck_count], DECK_NAME_LEN, t1 + 1, end);
        s_deck_due[s_deck_count] = 0;
      }
      s_deck_count++;
    }
    if (!nl) break;
    s = nl + 1;
  }
  s_decks_loaded = true;
}

// ---- card text + scrolling -------------------------------------------------
// Set the scrollable text, measure its height, and reset scroll to the top.
static void set_card_text(const char *t) {
  Layer *tl = text_layer_get_layer(s_card_text);
  GRect f = layer_get_frame(tl);
  f.size.h = 2000;                 // give room to measure full wrapped height
  layer_set_frame(tl, f);
  text_layer_set_text(s_card_text, t);
  GSize used = text_layer_get_content_size(s_card_text);
  f.size.h = used.h + 8;
  layer_set_frame(tl, f);
  GRect sb = layer_get_bounds(scroll_layer_get_layer(s_scroll));
  scroll_layer_set_content_size(s_scroll, GSize(sb.size.w, used.h + 8));
  scroll_layer_set_content_offset(s_scroll, GPoint(0, 0), false);
}

static void scroll_page(int dir) {  // dir: -1 up, +1 down
  GSize cs = scroll_layer_get_content_size(s_scroll);
  int vis = layer_get_bounds(scroll_layer_get_layer(s_scroll)).size.h;
  GPoint off = scroll_layer_get_content_offset(s_scroll);
  int min_y = vis - cs.h;          // most-negative offset (content bottom)
  if (min_y > 0) min_y = 0;
  int ny = off.y - dir * (vis - 20);
  if (ny > 0) ny = 0;
  if (ny < min_y) ny = min_y;
  scroll_layer_set_content_offset(s_scroll, GPoint(0, ny), true);
}

// ---- right-side action bar (drawn, no image resources) ---------------------
static void draw_check(GContext *ctx, int cx, int cy) {  // green ✓ = Good
  graphics_context_set_stroke_color(ctx, GColorGreen);
  graphics_context_set_stroke_width(ctx, 3);
  graphics_draw_line(ctx, GPoint(cx - 7, cy + 1), GPoint(cx - 2, cy + 6));
  graphics_draw_line(ctx, GPoint(cx - 2, cy + 6), GPoint(cx + 8, cy - 7));
}

static void draw_tilde(GContext *ctx, int cx, int cy) {  // yellow ~ = Hard
  graphics_context_set_stroke_color(ctx, GColorYellow);
  graphics_context_set_stroke_width(ctx, 3);
  graphics_draw_line(ctx, GPoint(cx - 9, cy + 2), GPoint(cx - 3, cy - 3));
  graphics_draw_line(ctx, GPoint(cx - 3, cy - 3), GPoint(cx + 3, cy + 3));
  graphics_draw_line(ctx, GPoint(cx + 3, cy + 3), GPoint(cx + 9, cy - 2));
}

static void draw_cross(GContext *ctx, int cx, int cy) {  // red ✗ = Again
  graphics_context_set_stroke_color(ctx, GColorRed);
  graphics_context_set_stroke_width(ctx, 3);
  graphics_draw_line(ctx, GPoint(cx - 7, cy - 7), GPoint(cx + 7, cy + 7));
  graphics_draw_line(ctx, GPoint(cx - 7, cy + 7), GPoint(cx + 7, cy - 7));
}

static void draw_reveal(GContext *ctx, int cx, int cy) {  // white play-triangle = "show"
  graphics_context_set_stroke_color(ctx, GColorWhite);
  graphics_context_set_stroke_width(ctx, 1);
  for (int i = 0; i <= 10; i++) {
    int x = cx - 5 + i;
    int h = (10 - i) * 7 / 10;
    graphics_draw_line(ctx, GPoint(x, cy - h), GPoint(x, cy + h));
  }
}

static void draw_chevron(GContext *ctx, int cx, int cy, bool up) {  // grey scroll hint
  graphics_context_set_stroke_color(ctx, GColorLightGray);
  graphics_context_set_stroke_width(ctx, 2);
  int dy = up ? 3 : -3;
  graphics_draw_line(ctx, GPoint(cx - 6, cy + dy), GPoint(cx, cy - dy));
  graphics_draw_line(ctx, GPoint(cx, cy - dy), GPoint(cx + 6, cy + dy));
}

static void bar_update_proc(Layer *layer, GContext *ctx) {
  GRect b = layer_get_bounds(layer);
  graphics_context_set_fill_color(ctx, GColorBlack);
  graphics_fill_rect(ctx, b, 0, GCornerNone);

  int cx = b.size.w / 2;
  int up_y = b.size.h / 5;
  int mid_y = b.size.h / 2;
  int dn_y = b.size.h * 4 / 5;

  if (s_card_state == CARD_BACK) {
    draw_check(ctx, cx, up_y);   // Up    = Good
    draw_tilde(ctx, cx, mid_y);  // Select = Hard
    draw_cross(ctx, cx, dn_y);   // Down  = Again
  } else if (s_card_state == CARD_FRONT) {
    draw_chevron(ctx, cx, up_y, true);   // Up   = scroll up
    draw_reveal(ctx, cx, mid_y);         // Select = reveal answer
    draw_chevron(ctx, cx, dn_y, false);  // Down = scroll down
  }
  // CARD_LOADING / CARD_INFO: plain black bar
}

static void mark_bar(void) { if (s_bar) layer_mark_dirty(s_bar); }

// ---- card states -----------------------------------------------------------
static void show_front(void) {
  if (!s_card_loaded) return;
  s_card_state = CARD_FRONT;
  window_set_background_color(s_card_window, GColorWhite);
  set_card_text(s_front);
  mark_bar();
}

static void show_info(const char *body) {
  if (!s_card_loaded) return;
  s_card_state = CARD_INFO;
  window_set_background_color(s_card_window, GColorWhite);
  set_card_text(body);
  mark_bar();
}

static void reveal_back(void) {
  s_card_state = CARD_BACK;
  set_card_text(s_back);
  mark_bar();
}

static void flash_done(void *data) {
  s_flashing = false;
  if (!s_card_loaded) return;
  window_set_background_color(s_card_window, GColorWhite);
  if (s_card_state != CARD_LOADING) return;  // e.g. an error already took over
  if (s_pending == PEND_CARD) { s_pending = PEND_NONE; show_front(); }
  else if (s_pending == PEND_DONE) { s_pending = PEND_NONE; show_info("Deck finished"); }
  else { set_card_text("Loading..."); mark_bar(); }  // next card not here yet
}

static void grade(int ease, GColor flash) {
  if (s_card_state != CARD_BACK) return;
  send_answer(s_card_id, ease, s_current_deck_id);
  s_card_state = CARD_LOADING;
  s_pending = PEND_NONE;
  s_flashing = true;
  if (s_card_loaded) {
    window_set_background_color(s_card_window, flash);  // wash stays under the answer text
    mark_bar();                                         // bar -> plain black
    app_timer_register(FLASH_MS, flash_done, NULL);
  }
}

// ---- card window -----------------------------------------------------------
static void card_select_click(ClickRecognizerRef rec, void *ctx) {
  if (!s_card_loaded) return;
  if (s_card_state == CARD_FRONT) reveal_back();
  else if (s_card_state == CARD_BACK) grade(EASE_HARD, GColorYellow);
}

static void card_up_click(ClickRecognizerRef rec, void *ctx) {
  if (s_card_state == CARD_BACK) grade(EASE_GOOD, GColorGreen);
  else scroll_page(-1);
}

static void card_down_click(ClickRecognizerRef rec, void *ctx) {
  if (s_card_state == CARD_BACK) grade(EASE_AGAIN, GColorRed);
  else scroll_page(+1);
}

static void card_click_config(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_SELECT, card_select_click);
  window_single_click_subscribe(BUTTON_ID_UP, card_up_click);
  window_single_click_subscribe(BUTTON_ID_DOWN, card_down_click);
}

static void card_window_load(Window *w) {
  window_set_background_color(w, GColorWhite);
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);

  GRect scroll_frame = GRect(2, 4, b.size.w - BAR_W - 4, b.size.h - 8);
  s_scroll = scroll_layer_create(scroll_frame);
  scroll_layer_set_shadow_hidden(s_scroll, true);
  layer_add_child(root, scroll_layer_get_layer(s_scroll));

  s_card_text = text_layer_create(GRect(0, 0, scroll_frame.size.w, 2000));
  text_layer_set_background_color(s_card_text, GColorClear);
  text_layer_set_text_color(s_card_text, GColorBlack);
  text_layer_set_font(s_card_text, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
  text_layer_set_text_alignment(s_card_text, GTextAlignmentCenter);
  text_layer_set_overflow_mode(s_card_text, GTextOverflowModeWordWrap);
  scroll_layer_add_child(s_scroll, text_layer_get_layer(s_card_text));

  s_bar = layer_create(GRect(b.size.w - BAR_W, 0, BAR_W, b.size.h));
  layer_set_update_proc(s_bar, bar_update_proc);
  layer_add_child(root, s_bar);

  s_card_loaded = true;
  s_card_state = CARD_LOADING;
  set_card_text("Loading...");
}

static void card_window_unload(Window *w) {
  s_card_loaded = false;
  text_layer_destroy(s_card_text);
  scroll_layer_destroy(s_scroll);
  layer_destroy(s_bar);
}

static void show_card_window(void) {
  if (!s_card_window) {
    s_card_window = window_create();
    window_set_click_config_provider(s_card_window, card_click_config);
    window_set_window_handlers(s_card_window, (WindowHandlers) {
      .load = card_window_load,
      .unload = card_window_unload,
    });
  }
  s_card_state = CARD_LOADING;
  window_stack_push(s_card_window, true);
}

// ---- deck menu window ------------------------------------------------------
static uint16_t menu_num_rows(MenuLayer *ml, uint16_t section, void *ctx) {
  if (!s_decks_loaded || s_deck_count == 0) return 1;
  return s_deck_count;
}

static void menu_draw_row(GContext *gctx, const Layer *cell, MenuIndex *idx, void *ctx) {
  if (!s_decks_loaded) {
    menu_cell_basic_draw(gctx, cell, "Loading decks...", NULL, NULL);
    return;
  }
  if (s_deck_count == 0) {
    menu_cell_basic_draw(gctx, cell, "No decks", "Check settings", NULL);
    return;
  }
  char sub[16];
  snprintf(sub, sizeof sub, "%d due", s_deck_due[idx->row]);
  menu_cell_basic_draw(gctx, cell, s_deck_names[idx->row], sub, NULL);
}

static void menu_select(MenuLayer *ml, MenuIndex *idx, void *ctx) {
  if (!s_decks_loaded || s_deck_count == 0) return;
  strncpy(s_current_deck_id, s_deck_ids[idx->row], ID_LEN - 1);
  s_current_deck_id[ID_LEN - 1] = '\0';
  show_card_window();
  send_get_card(s_current_deck_id);
}

static void menu_window_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  s_menu_layer = menu_layer_create(b);
  menu_layer_set_callbacks(s_menu_layer, NULL, (MenuLayerCallbacks) {
    .get_num_rows = menu_num_rows,
    .draw_row = menu_draw_row,
    .select_click = menu_select,
  });
  menu_layer_set_click_config_onto_window(s_menu_layer, w);
  layer_add_child(root, menu_layer_get_layer(s_menu_layer));
}

static void menu_window_unload(Window *w) {
  menu_layer_destroy(s_menu_layer);
  s_menu_layer = NULL;
}

// Fires when the deck list becomes visible (first show, and on Back from a deck):
// refresh the due counts so they reflect cards just reviewed.
static void menu_window_appear(Window *w) {
  send_get_decks();
}

// ---- connection watchdog ---------------------------------------------------
static void clear_req_timer(void) {
  if (s_req_timer) {
    app_timer_cancel(s_req_timer);
    s_req_timer = NULL;
  }
}

static void req_timeout(void *data) {
  s_req_timer = NULL;
  vibes_double_pulse();  // nothing replied — the connection didn't go all the way
  if (s_card_loaded && s_card_state == CARD_LOADING) {
    show_info("Not connected");
  }
}

static void arm_req_timer(void) {
  clear_req_timer();
  s_req_timer = app_timer_register(REQ_TIMEOUT_MS, req_timeout, NULL);
}

// ---- app messages ----------------------------------------------------------
static void inbox_received(DictionaryIterator *iter, void *context) {
  clear_req_timer();  // a reply arrived — the round trip completed
  Tuple *type_t = dict_find(iter, MESSAGE_KEY_MSG_TYPE);
  if (!type_t) return;

  switch (type_t->value->int32) {
    case MSG_DECKS: {
      Tuple *d = dict_find(iter, MESSAGE_KEY_DECKS);
      if (d) {
        parse_decks(d->value->cstring);
        if (s_menu_layer) menu_layer_reload_data(s_menu_layer);
      }
      break;
    }
    case MSG_CARD: {
      Tuple *f = dict_find(iter, MESSAGE_KEY_FRONT);
      Tuple *b = dict_find(iter, MESSAGE_KEY_BACK);
      Tuple *c = dict_find(iter, MESSAGE_KEY_CARD_ID);
      if (f) { strncpy(s_front, f->value->cstring, TEXT_LEN - 1); s_front[TEXT_LEN - 1] = '\0'; }
      if (b) { strncpy(s_back, b->value->cstring, TEXT_LEN - 1); s_back[TEXT_LEN - 1] = '\0'; }
      if (c) { strncpy(s_card_id, c->value->cstring, ID_LEN - 1); s_card_id[ID_LEN - 1] = '\0'; }
      if (s_flashing) s_pending = PEND_CARD; else show_front();  // wait out the flash
      break;
    }
    case MSG_DONE:
      if (s_flashing) s_pending = PEND_DONE; else show_info("Deck finished");
      break;
    case MSG_ERROR: {
      Tuple *e = dict_find(iter, MESSAGE_KEY_ERR);
      const char *msg = e ? e->value->cstring : "Error";
      APP_LOG(APP_LOG_LEVEL_ERROR, "backend error: %s", msg);
      vibes_double_pulse();  // bridge couldn't reach the companion / AnkiDroid
      show_info(msg);
      break;
    }
  }
}

static void inbox_dropped(AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_ERROR, "inbox dropped: %d", (int)reason);
  clear_req_timer();
  vibes_double_pulse();  // a reply was dropped
}

static void outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_ERROR, "outbox failed: %d", (int)reason);
  clear_req_timer();
  vibes_double_pulse();  // couldn't reach the phone over Bluetooth
}

// ---- app lifecycle ---------------------------------------------------------
static void init(void) {
  app_message_register_inbox_received(inbox_received);
  app_message_register_inbox_dropped(inbox_dropped);
  app_message_register_outbox_failed(outbox_failed);
  app_message_open(4096, 256);

  s_menu_window = window_create();
  window_set_window_handlers(s_menu_window, (WindowHandlers) {
    .load = menu_window_load,
    .appear = menu_window_appear,  // refreshes decks on show + on return from a deck
    .unload = menu_window_unload,
  });
  window_stack_push(s_menu_window, true);
  // (menu .appear requests decks; JS also pushes them on 'ready')
}

static void deinit(void) {
  if (s_card_window) window_destroy(s_card_window);
  window_destroy(s_menu_window);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
