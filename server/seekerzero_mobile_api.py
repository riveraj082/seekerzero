# SeekerZero mobile API bootstrap handler.
#
# Loaded by A0's ApiHandler auto-loader from /a0/python/api/.
# __init__ registers /mobile/* routes on the Flask webapp with a tailnet-IP
# guard. The handler's own module-level route is loopback-only so direct
# hits are rejected.
#
# Canonical source lives at /a0/usr/patches/seekerzero_mobile_api.py and is
# copied into /a0/python/api/ by agent-zero-post-start.sh.

import json
import queue
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional

import urllib.error
import urllib.request

from flask import Flask, Request, Response, request as flask_request, stream_with_context

from python.helpers.api import ApiHandler, Input, Output, ThreadLockType


_TAILNET_PREFIX = '100.'
_A0_VERSION = '0.9.8.2'

_STUB_FILE = Path('/a0/usr/seekerzero/stub_approvals.json')
_LONG_POLL_MAX_S = 60.0
_POLL_INTERVAL_S = 0.5
_SINCE_LOOKBACK_CAP_MS = 24 * 60 * 60 * 1000

_stub_write_lock = threading.Lock()

# ---- Chat (Phase 5 Step 2) ----------------------------------------------
_CHAT_DIR = Path('/a0/usr/seekerzero/chat')
_CHAT_DEFAULT_CONTEXT = 'mobile-seekerzero'
_CHAT_DISPLAY_NAME = {'mobile-seekerzero': 'Seeker'}
_CHAT_HISTORY_DEFAULT_LIMIT = 50
_CHAT_HISTORY_MAX_LIMIT = 500
_CHAT_TURN_LOOKBACK = 20  # how many prior messages to feed the model
_CHAT_OLLAMA_URL = 'http://YOUR_LAN_IP:11434/api/chat'
_CHAT_OLLAMA_MODEL = 'a0-work'
_CHAT_STREAM_KEEPALIVE_S = 5.0
_CHAT_STREAM_POLL_S = 0.25
_CHAT_STREAM_MAX_SUBS_PER_CONTEXT = 1

_chat_log_lock = threading.Lock()
_chat_busy_lock = threading.Lock()
_chat_busy_contexts: Dict[str, bool] = {}
_chat_subs_lock = threading.Lock()
_chat_subscribers: Dict[str, List['queue.Queue[dict]']] = {}


def _client_ip() -> str:
    xff = flask_request.headers.get('X-Forwarded-For', '')
    if xff:
        return xff.split(',')[0].strip()
    return flask_request.remote_addr or ''


def _is_tailnet(ip: str) -> bool:
    # Tailscale CGNAT: 100.64.0.0/10. We accept any 100.x.y.z for simplicity.
    return ip.startswith(_TAILNET_PREFIX) or ip == '127.0.0.1'


def _forbidden(reason: str) -> Response:
    body = json.dumps({'ok': False, 'error': reason})
    return Response(response=body, status=403, mimetype='application/json')


def _bad_request(reason: str) -> Response:
    body = json.dumps({'ok': False, 'error': reason})
    return Response(response=body, status=400, mimetype='application/json')


def _load_stub_approvals() -> List[Dict[str, Any]]:
    if not _STUB_FILE.exists():
        return []
    try:
        with _STUB_FILE.open('r', encoding='utf-8') as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except (json.JSONDecodeError, OSError):
        return []


def _write_stub_approvals(approvals: List[Dict[str, Any]]) -> None:
    tmp = _STUB_FILE.with_suffix(_STUB_FILE.suffix + '.tmp')
    with _stub_write_lock:
        tmp.write_text(json.dumps(approvals, indent=2), encoding='utf-8')
        tmp.replace(_STUB_FILE)


def _resolve_stub_approval(approval_id: str, resolution: str) -> Optional[Dict[str, Any]]:
    with _stub_write_lock:
        approvals = _load_stub_approvals()
        match = next((a for a in approvals if a.get('id') == approval_id), None)
        if match is None:
            return None
        remaining = [a for a in approvals if a.get('id') != approval_id]
        tmp = _STUB_FILE.with_suffix(_STUB_FILE.suffix + '.tmp')
        tmp.write_text(json.dumps(remaining, indent=2), encoding='utf-8')
        tmp.replace(_STUB_FILE)
        return {
            'id': approval_id,
            'resolution': resolution,
            'resolved_at_ms': int(time.time() * 1000),
            'approval': match,
        }


def _filter_since(approvals: List[Dict[str, Any]], since_ms: int) -> List[Dict[str, Any]]:
    now_ms = int(time.time() * 1000)
    effective_since = max(since_ms, now_ms - _SINCE_LOOKBACK_CAP_MS)
    return [a for a in approvals if a.get('created_at_ms', 0) > effective_since]


def _health_view():
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')

    body: Dict[str, Any] = {
        'ok': True,
        'server_time_ms': int(time.time() * 1000),
        'a0_version': _A0_VERSION,
        'subordinates': [
            {'name': 'a0-think', 'status': 'up', 'last_response_ms': 420},
            {'name': 'a0-work',  'status': 'up', 'last_response_ms': 180},
            {'name': 'a0-embed', 'status': 'up', 'last_response_ms': 95},
        ],
    }
    return Response(
        response=json.dumps(body),
        status=200,
        mimetype='application/json',
    )


def _pending_view():
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')

    approvals = _load_stub_approvals()
    body = {
        'approvals': approvals,
        'server_time_ms': int(time.time() * 1000),
    }
    return Response(json.dumps(body), status=200, mimetype='application/json')


def _stream_view():
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')

    since_raw = flask_request.args.get('since')
    since_ms: Optional[int] = None
    if since_raw is not None and since_raw != '':
        try:
            since_ms = int(since_raw)
        except ValueError:
            return _bad_request('since must be integer milliseconds')

    # No since → start from "now"; long-poll waits for genuinely new approvals.
    if since_ms is None:
        since_ms = int(time.time() * 1000)

    deadline = time.monotonic() + _LONG_POLL_MAX_S

    while True:
        approvals = _load_stub_approvals()
        new_items = _filter_since(approvals, since_ms)
        now_ms = int(time.time() * 1000)

        if new_items:
            body = {
                'approvals': new_items,
                'server_time_ms': now_ms,
                'next_since_ms': now_ms,
            }
            return Response(json.dumps(body), status=200, mimetype='application/json')

        if time.monotonic() >= deadline:
            body = {
                'approvals': [],
                'server_time_ms': now_ms,
                'next_since_ms': now_ms,
            }
            return Response(json.dumps(body), status=200, mimetype='application/json')

        time.sleep(_POLL_INTERVAL_S)


def _resolution_view(approval_id: str, resolution: str) -> Response:
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    if resolution not in ('approved', 'rejected'):
        return _bad_request('unknown resolution')
    result = _resolve_stub_approval(approval_id, resolution)
    if result is None:
        body = json.dumps({'ok': False, 'error': 'approval not found', 'id': approval_id})
        return Response(body, status=404, mimetype='application/json')
    body = {
        'ok': True,
        'id': result['id'],
        'resolution': result['resolution'],
        'resolved_at_ms': result['resolved_at_ms'],
    }
    return Response(json.dumps(body), status=200, mimetype='application/json')


def _approve_view(approval_id: str):
    return _resolution_view(approval_id, 'approved')


def _reject_view(approval_id: str):
    return _resolution_view(approval_id, 'rejected')


# ---- Chat helpers -------------------------------------------------------

def _chat_log_path(context_id: str) -> Path:
    return _CHAT_DIR / f'{context_id}.jsonl'


def _chat_read_log(context_id: str) -> List[Dict[str, Any]]:
    path = _chat_log_path(context_id)
    if not path.exists():
        return []
    out: List[Dict[str, Any]] = []
    try:
        with path.open('r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    out.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    except OSError:
        return []
    return out


def _chat_append_log(context_id: str, record: Dict[str, Any]) -> None:
    with _chat_log_lock:
        _CHAT_DIR.mkdir(parents=True, exist_ok=True)
        path = _chat_log_path(context_id)
        with path.open('a', encoding='utf-8') as f:
            f.write(json.dumps(record, ensure_ascii=False) + '\n')


def _chat_subscribe(context_id: str) -> 'queue.Queue[dict]':
    """Subscribe to a context's event stream.

    Enforces a small max-subs-per-context so stale connections from
    aggressive client reconnects are evicted rather than piling up and
    starving the Flask request-thread pool. Evicted queues get a poison
    pill so their generator can exit cleanly on next read."""
    q: queue.Queue[dict] = queue.Queue(maxsize=1024)
    with _chat_subs_lock:
        bucket = _chat_subscribers.setdefault(context_id, [])
        while len(bucket) >= _CHAT_STREAM_MAX_SUBS_PER_CONTEXT:
            evicted = bucket.pop(0)
            try:
                evicted.put_nowait({'type': '__shutdown__'})
            except queue.Full:
                pass
        bucket.append(q)
    return q


def _chat_unsubscribe(context_id: str, q: 'queue.Queue[dict]') -> None:
    with _chat_subs_lock:
        subs = _chat_subscribers.get(context_id)
        if subs and q in subs:
            subs.remove(q)


def _chat_publish(context_id: str, event: Dict[str, Any]) -> None:
    with _chat_subs_lock:
        subs = list(_chat_subscribers.get(context_id, []))
    for q in subs:
        try:
            q.put_nowait(event)
        except queue.Full:
            pass  # slow subscriber: drop; client will re-sync via history on next connect


def _chat_set_busy(context_id: str, value: bool) -> bool:
    """Returns True if state was changed (i.e. wasn't already the same)."""
    with _chat_busy_lock:
        cur = _chat_busy_contexts.get(context_id, False)
        if value and cur:
            return False
        _chat_busy_contexts[context_id] = value
        return True


def _chat_is_busy(context_id: str) -> bool:
    with _chat_busy_lock:
        return _chat_busy_contexts.get(context_id, False)


def _chat_build_messages(context_id: str, new_user_text: str) -> List[Dict[str, str]]:
    log = _chat_read_log(context_id)
    # Only final messages; dropped ones and in-flight assistant scaffolding are excluded.
    finals = [m for m in log if m.get('is_final')]
    tail = finals[-_CHAT_TURN_LOOKBACK:]
    msgs: List[Dict[str, str]] = []
    for m in tail:
        role = m.get('role')
        content = m.get('content', '')
        if role in ('user', 'assistant') and content:
            msgs.append({'role': role, 'content': content})
    msgs.append({'role': 'user', 'content': new_user_text})
    return msgs


def _chat_stream_from_ollama(context_id: str, assistant_id: str, messages: List[Dict[str, str]]) -> None:
    """Background worker: call Ollama, publish delta events, persist final."""
    buf: List[str] = []
    body = json.dumps({
        'model': _CHAT_OLLAMA_MODEL,
        'messages': messages,
        'stream': True,
    }).encode('utf-8')
    req = urllib.request.Request(
        _CHAT_OLLAMA_URL,
        data=body,
        headers={'Content-Type': 'application/json'},
        method='POST',
    )
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            for raw in resp:
                line = raw.decode('utf-8', errors='replace').strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    continue
                msg = obj.get('message') or {}
                delta = msg.get('content', '')
                if delta:
                    buf.append(delta)
                    _chat_publish(context_id, {
                        'type': 'delta',
                        'message_id': assistant_id,
                        'role': 'assistant',
                        'delta': delta,
                        'created_at_ms': int(time.time() * 1000),
                    })
                if obj.get('done'):
                    break
    except (urllib.error.URLError, OSError, TimeoutError) as e:
        err_text = f'[error talking to a0-work: {e!s}]'
        buf.append(err_text)
        _chat_publish(context_id, {
            'type': 'delta',
            'message_id': assistant_id,
            'role': 'assistant',
            'delta': err_text,
            'created_at_ms': int(time.time() * 1000),
        })
    finally:
        full = ''.join(buf)
        final_ms = int(time.time() * 1000)
        _chat_append_log(context_id, {
            'id': assistant_id,
            'role': 'assistant',
            'content': full,
            'created_at_ms': final_ms,
            'is_final': True,
        })
        _chat_publish(context_id, {
            'type': 'final',
            'message_id': assistant_id,
            'role': 'assistant',
            'content': full,
            'created_at_ms': final_ms,
        })
        _chat_set_busy(context_id, False)


# ---- Chat views ---------------------------------------------------------

def _chat_contexts_view():
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    contexts: List[Dict[str, Any]] = []
    # v1: only the default context exists; future phases may multiplex.
    for cid in (_CHAT_DEFAULT_CONTEXT,):
        log = _chat_read_log(cid)
        last_ms = log[-1].get('created_at_ms', 0) if log else 0
        contexts.append({
            'id': cid,
            'display_name': _CHAT_DISPLAY_NAME.get(cid, cid),
            'last_message_at_ms': last_ms,
        })
    body = {'contexts': contexts, 'server_time_ms': int(time.time() * 1000)}
    return Response(json.dumps(body), status=200, mimetype='application/json')


def _chat_history_view():
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    context_id = flask_request.args.get('context', _CHAT_DEFAULT_CONTEXT)
    before_raw = flask_request.args.get('before_ms')
    limit_raw = flask_request.args.get('limit')
    try:
        before_ms = int(before_raw) if before_raw else None
        limit = int(limit_raw) if limit_raw else _CHAT_HISTORY_DEFAULT_LIMIT
    except ValueError:
        return _bad_request('before_ms and limit must be integers')
    limit = max(1, min(limit, _CHAT_HISTORY_MAX_LIMIT))

    log = _chat_read_log(context_id)
    if before_ms is not None:
        log = [m for m in log if m.get('created_at_ms', 0) < before_ms]
    tail = log[-limit:]
    body = {
        'context': context_id,
        'messages': tail,
        'server_time_ms': int(time.time() * 1000),
    }
    return Response(json.dumps(body), status=200, mimetype='application/json')


def _chat_send_view():
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    try:
        payload = flask_request.get_json(force=True, silent=False) or {}
    except Exception:
        return _bad_request('body must be JSON')
    context_id = payload.get('context') or _CHAT_DEFAULT_CONTEXT
    content = (payload.get('content') or '').strip()
    if not content:
        return _bad_request('content required')

    if not _chat_set_busy(context_id, True):
        body = json.dumps({'ok': False, 'error': 'context busy; wait for current reply to finish'})
        return Response(body, status=409, mimetype='application/json')

    now_ms = int(time.time() * 1000)
    user_id = f'msg-u-{uuid.uuid4().hex[:12]}'
    assistant_id = f'msg-a-{uuid.uuid4().hex[:12]}'

    user_record = {
        'id': user_id,
        'role': 'user',
        'content': content,
        'created_at_ms': now_ms,
        'is_final': True,
    }
    _chat_append_log(context_id, user_record)
    _chat_publish(context_id, {
        'type': 'user_msg',
        'message_id': user_id,
        'role': 'user',
        'content': content,
        'created_at_ms': now_ms,
    })

    messages = _chat_build_messages(context_id, content)
    threading.Thread(
        target=_chat_stream_from_ollama,
        args=(context_id, assistant_id, messages),
        daemon=True,
        name=f'chat-stream-{assistant_id}',
    ).start()

    body = {
        'ok': True,
        'user_message_id': user_id,
        'assistant_message_id': assistant_id,
        'created_at_ms': now_ms,
    }
    return Response(json.dumps(body), status=200, mimetype='application/json')


def _chat_stream_view():
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    context_id = flask_request.args.get('context', _CHAT_DEFAULT_CONTEXT)
    since_raw = flask_request.args.get('since')
    try:
        since_ms = int(since_raw) if since_raw else int(time.time() * 1000)
    except ValueError:
        return _bad_request('since must be integer milliseconds')

    q = _chat_subscribe(context_id)

    def ndjson_line(obj: Dict[str, Any]) -> bytes:
        return (json.dumps(obj, ensure_ascii=False) + '\n').encode('utf-8')

    def generate():
        try:
            # 1) Replay log entries newer than since_ms as user_msg/final events.
            log = _chat_read_log(context_id)
            for m in log:
                if m.get('created_at_ms', 0) <= since_ms:
                    continue
                if not m.get('is_final'):
                    continue
                role = m.get('role')
                if role == 'user':
                    yield ndjson_line({
                        'type': 'user_msg',
                        'message_id': m.get('id'),
                        'role': 'user',
                        'content': m.get('content', ''),
                        'created_at_ms': m.get('created_at_ms'),
                    })
                elif role == 'assistant':
                    yield ndjson_line({
                        'type': 'final',
                        'message_id': m.get('id'),
                        'role': 'assistant',
                        'content': m.get('content', ''),
                        'created_at_ms': m.get('created_at_ms'),
                    })

            # 2) Drain live events from the queue. A short keepalive interval
            # (5s) means a disconnected client is noticed within one yield
            # attempt; the generator then unwinds via GeneratorExit and the
            # finally clause releases the request thread.
            last_event = time.monotonic()
            while True:
                try:
                    ev = q.get(timeout=_CHAT_STREAM_POLL_S)
                    # Poison pill from the subscribe-side evictor: stop cleanly.
                    if ev.get('type') == '__shutdown__':
                        return
                    yield ndjson_line(ev)
                    last_event = time.monotonic()
                except queue.Empty:
                    if time.monotonic() - last_event >= _CHAT_STREAM_KEEPALIVE_S:
                        yield ndjson_line({
                            'type': 'keepalive',
                            'created_at_ms': int(time.time() * 1000),
                        })
                        last_event = time.monotonic()
        except (GeneratorExit, BrokenPipeError, ConnectionResetError):
            # Client disconnected. Fall through to finally.
            pass
        finally:
            _chat_unsubscribe(context_id, q)

    return Response(
        stream_with_context(generate()),
        status=200,
        mimetype='application/x-ndjson',
        headers={
            'Cache-Control': 'no-cache',
            'X-Accel-Buffering': 'no',
        },
    )


class SeekerzeroMobileApi(ApiHandler):
    '''Bootstrap handler. Loader instantiates us once at startup; we register
    /mobile/* routes via side-effect in __init__.'''

    _registered = False

    def __init__(self, app: Flask, thread_lock: ThreadLockType):
        super().__init__(app, thread_lock)
        if not SeekerzeroMobileApi._registered:
            app.add_url_rule(
                '/mobile/health',
                'seekerzero_mobile_health',
                _health_view,
                methods=['GET'],
            )
            app.add_url_rule(
                '/mobile/approvals/pending',
                'seekerzero_mobile_approvals_pending',
                _pending_view,
                methods=['GET'],
            )
            app.add_url_rule(
                '/mobile/approvals/stream',
                'seekerzero_mobile_approvals_stream',
                _stream_view,
                methods=['GET'],
            )
            app.add_url_rule(
                '/mobile/approvals/<approval_id>/approve',
                'seekerzero_mobile_approvals_approve',
                _approve_view,
                methods=['POST'],
            )
            app.add_url_rule(
                '/mobile/approvals/<approval_id>/reject',
                'seekerzero_mobile_approvals_reject',
                _reject_view,
                methods=['POST'],
            )
            app.add_url_rule(
                '/mobile/chat/contexts',
                'seekerzero_mobile_chat_contexts',
                _chat_contexts_view,
                methods=['GET'],
            )
            app.add_url_rule(
                '/mobile/chat/history',
                'seekerzero_mobile_chat_history',
                _chat_history_view,
                methods=['GET'],
            )
            app.add_url_rule(
                '/mobile/chat/send',
                'seekerzero_mobile_chat_send',
                _chat_send_view,
                methods=['POST'],
            )
            app.add_url_rule(
                '/mobile/chat/stream',
                'seekerzero_mobile_chat_stream',
                _chat_stream_view,
                methods=['GET'],
            )
            SeekerzeroMobileApi._registered = True

    @classmethod
    def requires_loopback(cls) -> bool:
        return True

    @classmethod
    def requires_auth(cls) -> bool:
        return False

    @classmethod
    def requires_csrf(cls) -> bool:
        return False

    @classmethod
    def get_methods(cls) -> list[str]:
        return ['GET', 'POST']

    async def process(self, input: Input, request: Request) -> Output:
        return {'ok': False, 'error': 'direct call not supported'}
