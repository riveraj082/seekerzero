# SeekerZero server-side (a0prod)

Canonical source for the `/mobile/*` Flask routes on Agent Zero. These live
on `a0prod` at `/a0/usr/patches/seekerzero_mobile_api.py` and are copied
into `/a0/python/api/` by `agent-zero-post-start.sh` on each container
start.

## Files

- `seekerzero_mobile_api.py` — the handler. Routes:
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
- `stub_approvals.json` — sample approval list. Deploys to
  `/a0/usr/seekerzero/stub_approvals.json` on a0prod. Edit to change what
  the phone sees; the handler reads it on every request. Writes go
  through a module-level `threading.Lock` + atomic rename so a
  concurrent `/stream` read never sees a partial file.

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
```
