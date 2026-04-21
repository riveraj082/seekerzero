# SeekerZero server-side (a0prod)

Canonical source for the `/mobile/*` Flask routes on Agent Zero. These live
on `a0prod` at `/a0/usr/patches/seekerzero_mobile_api.py` and are copied
into `/a0/python/api/` by `agent-zero-post-start.sh` on each container
start.

## Files

- `seekerzero_mobile_api.py` — the handler. Phase 5 Step 4a: `/mobile/chat/*`
  now routes through A0's real agent loop (not a direct Ollama call). On
  `/chat/send` the handler does `AgentContext.get('mobile-seekerzero')
  or create` → `ctx.communicate(UserMessage(content))` and returns
  immediately. The assistant reply is emitted asynchronously as `delta`
  and `final` events by two extensions that hook A0's streaming pipeline
  (see `extensions/`).
- `extensions/response_stream/_99_seekerzero_forward.py` — hooks A0's
  `response_stream` extension point. When the agent is invoking the
  `response` tool for the `mobile-seekerzero` context, this diffs
  `tool_args.text` against the running emitted length and publishes
  `delta` events to the in-memory pub/sub that `/mobile/chat/stream`
  subscribers drain.
- `extensions/monologue_end/_99_seekerzero_final.py` — hooks A0's
  `monologue_end` extension point. When a top-level agent turn completes
  for the `mobile-seekerzero` context, this reads the final assistant
  text from the context log, writes a row to the JSONL mirror
  (`/a0/usr/seekerzero/chat/mobile-seekerzero.jsonl`, used by
  `/chat/history`), publishes a `final` event, and clears the busy flag.
- `extensions/tool_execute_before/_99_seekerzero_tool_start.py` +
  `extensions/tool_execute_after/_99_seekerzero_tool_end.py` — hook the
  tool-execution boundary. For top-level mobile-seekerzero turns, emit
  `tool_call` before a tool runs and `tool_result` after it returns
  (skipping `response`, which is the final-reply signal, not a tool).
  The phone uses these to render a "Using `<tool_name>`…" pill while
  mid-turn tool activity is happening.
- Shared state (subscribers, turn-state, busy flag) lives on
  `AgentContext.data['_seekerzero_chat_bus']`. This is load-bearing:
  handler + extensions are both loaded via `extract_tools.import_module`
  which bypasses `sys.modules`, so module-level globals in this file do
  NOT cross that boundary. The context is the only reliable shared
  object (singleton keyed by id via `AgentContext._contexts`). See
  `get_chat_bus()`.

Routes:
  - `GET /mobile/health` — subordinate health snapshot (stubbed values).
  - `GET /mobile/approvals/pending` — current list of open approval gates.
  - `GET /mobile/approvals/stream?since=<ms>` — long-poll, 60s hold, 24h
    lookback cap on `since`. Returns approvals with
    `created_at_ms > effective_since`.
  - `POST /mobile/approvals/{id}/approve` — resolves the gate as
    approved; removes it from the stub list. Returns
    `{ok, id, resolution, resolved_at_ms}`. 404 if id not found.
  - `POST /mobile/approvals/{id}/reject` — same shape, resolution
    `rejected`.
  - `GET /mobile/chat/contexts` — list available chat contexts.
    v1 returns the single context `mobile-seekerzero`.
  - `GET /mobile/chat/history?context=<id>&before_ms=<ms>&limit=<n>` —
    paginated backlog. `before_ms` is exclusive; omit to get the latest
    `limit` messages (default 50, cap 500).
  - `POST /mobile/chat/send` — body `{context, content}`. Appends the
    user message to the JSONL mirror, pre-allocates an
    `assistant_message_id`, stores it on the context's chat bus as the
    active turn state, and calls `ctx.communicate(UserMessage(content))`
    to start a real A0 turn. Returns `{ok, user_message_id,
    assistant_message_id, created_at_ms}` immediately. The reply arrives
    via the stream, not this POST. Returns 409 if the context is already
    streaming a reply.
  - `GET /mobile/chat/stream?context=<id>&since=<ms>` — **NDJSON** long-
    lived stream. Replays `is_final` log entries with
    `created_at_ms > since_ms` as `user_msg`/`final` events, then yields
    live events (`user_msg`, `delta`, `final`, `keepalive`) from an
    in-memory pub/sub as they occur. Holds the connection until the
    client disconnects. `keepalive` is emitted every 5s of idle so a
    dead TCP connection is noticed within one yield attempt and the
    request thread is released. Each context holds at most one live
    subscriber; a new subscribe evicts any prior subscriber (stale
    clients from aggressive reconnects pile up otherwise and starve
    Flask's thread pool).
- `stub_approvals.json` — sample approval list. Deploys to
  `/a0/usr/seekerzero/stub_approvals.json` on a0prod. Edit to change what
  the phone sees; the handler reads it on every request. Writes go
  through a module-level `threading.Lock` + atomic rename so a
  concurrent `/stream` read never sees a partial file.
- Chat log (created at runtime) — `/a0/usr/seekerzero/chat/<context>.jsonl`.
  Append-only, one message per line. Each line:
  `{id, role, content, created_at_ms, is_final}`. Streaming assistant
  replies are only written as `is_final: true` after the model is done;
  during generation, deltas are published to the pub/sub but not
  persisted.

## Chat event schema (v1)

NDJSON events emitted on `/mobile/chat/stream`:

```json
// User message — either replayed from log or published when POSTed.
{"type":"user_msg","message_id":"msg-u-…","role":"user","content":"…","created_at_ms":1713620000000}

// Token delta — emitted live during assistant reply. Not persisted alone.
{"type":"delta","message_id":"msg-a-…","role":"assistant","delta":"token ","created_at_ms":1713620000123}

// Final message — full assembled assistant reply, persisted to the log.
{"type":"final","message_id":"msg-a-…","role":"assistant","content":"…","created_at_ms":1713620005123}

// Mid-turn tool invocation (Step 4b). Clients can render a "Using <tool>…" pill.
{"type":"tool_call","message_id":"msg-a-…","tool_name":"call_subordinate","tool_args":{…},"created_at_ms":1713620002000}
{"type":"tool_result","message_id":"msg-a-…","tool_name":"call_subordinate","result_preview":"…","truncated":false,"created_at_ms":1713620003500}

// Idle keepalive every ~5s. No message_id; client ignores.
{"type":"keepalive","created_at_ms":1713620025000}
```

On reconnect, the phone sends its last-seen `created_at_ms` as
`since_ms`; the server replays any finals it missed, then streams live.
In-flight assistant replies that were lost mid-stream are only surfaced
via their final entry once the background thread finishes writing it —
clients should not rely on recovering partial deltas across reconnect.

## Approval schema (v1)

```json
{
  "id": "appr-…",
  "created_at_ms": 1713620000000,
  "category": "shell|cost|network|file|other",
  "risk": "low|medium|high",
  "summary": "One-line for the notification",
  "detail": "Full body shown in the Approvals tab",
  "source": "a0-think|a0-work|scheduler|…",
  "task_id": "task-…"
}
```

## Deploy

```bash
# From seekerzero/ repo root:
scp server/seekerzero_mobile_api.py a0prod:/tmp/sz_api.py
scp server/stub_approvals.json       a0prod:/tmp/sz_stubs.json
ssh a0prod 'docker exec agent-zero mkdir -p /a0/usr/seekerzero && \
  docker cp /tmp/sz_api.py   agent-zero:/a0/usr/patches/seekerzero_mobile_api.py && \
  docker cp /tmp/sz_api.py   agent-zero:/a0/python/api/seekerzero_mobile_api.py && \
  docker cp /tmp/sz_stubs.json agent-zero:/a0/usr/seekerzero/stub_approvals.json && \
  docker restart agent-zero'
```

Restart is required so Flask picks up the new route table.

## Test

```bash
# From a0prod itself (XFF to fake a tailnet source IP):
curl -sS -H 'X-Forwarded-For: 100.111.112.90' \
  http://127.0.0.1:50080/mobile/approvals/pending | jq

# Long-poll with since=0 → returns stub items within 24h cap
curl -sS -H 'X-Forwarded-For: 100.111.112.90' \
  "http://127.0.0.1:50080/mobile/approvals/stream?since=0" | jq

# Long-poll with no since → waits up to 60s for a NEW approval
curl -sS -H 'X-Forwarded-For: 100.111.112.90' \
  http://127.0.0.1:50080/mobile/approvals/stream

# Chat: list contexts
curl -sS -H 'X-Forwarded-For: 100.111.112.90' \
  http://127.0.0.1:50080/mobile/chat/contexts | jq

# Chat: history for default context
curl -sS -H 'X-Forwarded-For: 100.111.112.90' \
  "http://127.0.0.1:50080/mobile/chat/history?context=mobile-seekerzero&limit=20" | jq

# Chat: open NDJSON stream in one shell (Ctrl-C to close).
# In another shell, POST /send. The stream should emit user_msg → delta×N → final.
curl -N -sS -H 'X-Forwarded-For: 100.111.112.90' \
  "http://127.0.0.1:50080/mobile/chat/stream?context=mobile-seekerzero&since=0"

# Chat: send a prompt (POST). Returns immediately; reply streams on /stream.
curl -sS -X POST \
  -H 'Content-Type: application/json' \
  -H 'X-Forwarded-For: 100.111.112.90' \
  -d '{"context":"mobile-seekerzero","content":"hello from curl"}' \
  http://127.0.0.1:50080/mobile/chat/send | jq
```
