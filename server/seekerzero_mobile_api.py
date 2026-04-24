# SeekerZero mobile API bootstrap handler.
#
# Loaded by A0's ApiHandler auto-loader from /a0/python/api/.
# __init__ registers /mobile/* routes on the Flask webapp with a tailnet-IP
# guard. The handler's own module-level route is loopback-only so direct
# hits are rejected.
#
# Canonical source lives at /a0/usr/patches/seekerzero_mobile_api.py and is
# copied into /a0/python/api/ by agent-zero-post-start.sh.

import asyncio
import json
import mimetypes
import queue
import shutil
import sqlite3
import threading
import time
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

from flask import Flask, Request, Response, request as flask_request, send_file, stream_with_context

from python.helpers.api import ApiHandler, Input, Output, ThreadLockType


_TAILNET_PREFIX = '100.'
_A0_VERSION = '0.9.8.2'

# ---- Chat (Phase 5 Step 4a: routed through A0 agent loop) ----------------
_CHAT_DIR = Path('/a0/usr/seekerzero/chat')
_ATTACH_DIR = Path('/a0/usr/seekerzero/attachments')
_CHAT_DEFAULT_CONTEXT = 'mobile-seekerzero'
_CHAT_MOBILE_PREFIX = 'mobile-'
_CHAT_DISPLAY_NAME = {'mobile-seekerzero': 'Seeker'}
_CHAT_HISTORY_DEFAULT_LIMIT = 50
_CHAT_HISTORY_MAX_LIMIT = 500
_CHAT_STREAM_KEEPALIVE_S = 5.0
_CHAT_STREAM_POLL_S = 0.25
_CHAT_STREAM_MAX_SUBS_PER_CONTEXT = 1
# Hard ceiling on how long a single /mobile/chat/stream generator holds a
# Flask request thread. After this, we return cleanly; the client's
# reconnect loop reopens with an updated since_ms and sees no gap.
# Thread-starvation guard: keepalive/socket-close detection alone can miss
# parked subscribers when the tailnet or phone radio pauses silently.
_CHAT_STREAM_MAX_LIFETIME_S = 60.0

_chat_log_lock = threading.Lock()

# Pub/sub, turn-state, and busy-flag all live on the AgentContext.data dict,
# keyed at CHAT_BUS_KEY. The context is the only object that both the Flask
# handler (loaded by extract_tools, no sys.modules entry) and the stream
# extensions (likewise) reliably share — module-level globals in this file
# are per-module-instance and do NOT cross that boundary. See get_chat_bus().
CHAT_BUS_KEY = '_seekerzero_chat_bus'


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


def _collect_errored_tasks() -> List[Dict[str, Any]]:
    """Return up-to-5 tasks that are currently in the ERROR state or have a
    non-zero retry_count. Used by the Status tab as a poor-man's "recent
    A0 errors" view until we have a proper log-tail endpoint."""
    try:
        from python.helpers.task_scheduler import TaskScheduler, TaskState
    except Exception:
        return []
    try:
        tasks = TaskScheduler.get().get_tasks()
    except Exception:
        return []
    out: List[Dict[str, Any]] = []
    for t in tasks:
        state = t.state.value if hasattr(t.state, 'value') else str(t.state)
        retry_count = int(getattr(t, 'retry_count', 0) or 0)
        if state == 'error' or retry_count > 0:
            last_error_at = getattr(t, 'last_error_at', None)
            err_ms = 0
            if isinstance(last_error_at, datetime):
                err_ms = int(last_error_at.timestamp() * 1000)
            out.append({
                'uuid': t.uuid,
                'name': t.name,
                'state': state,
                'retry_count': retry_count,
                'last_error_at_ms': err_ms,
                'last_error_preview': (getattr(t, 'last_result', '') or '')[:300],
            })
    out.sort(key=lambda d: d.get('last_error_at_ms', 0), reverse=True)
    return out[:5]


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
        'errored_tasks': _collect_errored_tasks(),
    }
    return Response(
        response=json.dumps(body),
        status=200,
        mimetype='application/json',
    )


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
                    rec = json.loads(line)
                except json.JSONDecodeError:
                    continue
                # Legacy records stored attachments as a list of path strings;
                # new ones store enriched {path, filename, mime, size} dicts.
                # Normalize on read so clients only see the enriched shape.
                raw = rec.get('attachments')
                if isinstance(raw, list) and raw and all(isinstance(x, str) for x in raw):
                    rec['attachments'] = [_attach_meta(Path(p)) for p in raw]
                out.append(rec)
    except OSError:
        return []
    return out


def _chat_append_log(context_id: str, record: Dict[str, Any]) -> None:
    with _chat_log_lock:
        _CHAT_DIR.mkdir(parents=True, exist_ok=True)
        path = _chat_log_path(context_id)
        with path.open('a', encoding='utf-8') as f:
            f.write(json.dumps(record, ensure_ascii=False) + '\n')


def get_chat_bus(context_id: str) -> Dict[str, Any]:
    """Return the shared bus dict for a mobile chat context. Creates the
    context (and the bus) on first access. The bus lives on context.data
    so handler + extensions (which are loaded via extract_tools and do not
    share module-level globals) see the same object.
    """
    ctx = _chat_get_or_create_context(context_id)
    bus = ctx.data.get(CHAT_BUS_KEY)
    if bus is None:
        bus = {
            'subs_lock': threading.Lock(),
            'subscribers': [],
            'busy_lock': threading.Lock(),
            'busy': False,
            'turn_lock': threading.Lock(),
            'turn_state': None,  # dict when a turn is in flight, else None
        }
        ctx.data[CHAT_BUS_KEY] = bus
    return bus


def _chat_subscribe(context_id: str) -> 'queue.Queue[dict]':
    """Subscribe to a context's event stream.

    Enforces a small max-subs-per-context so stale connections from
    aggressive client reconnects are evicted rather than piling up and
    starving the Flask request-thread pool. Evicted queues get a poison
    pill so their generator can exit cleanly on next read."""
    bus = get_chat_bus(context_id)
    q: queue.Queue[dict] = queue.Queue(maxsize=1024)
    with bus['subs_lock']:
        bucket = bus['subscribers']
        while len(bucket) >= _CHAT_STREAM_MAX_SUBS_PER_CONTEXT:
            evicted = bucket.pop(0)
            try:
                evicted.put_nowait({'type': '__shutdown__'})
            except queue.Full:
                pass
        bucket.append(q)
    return q


def _chat_unsubscribe(context_id: str, q: 'queue.Queue[dict]') -> None:
    bus = get_chat_bus(context_id)
    with bus['subs_lock']:
        if q in bus['subscribers']:
            bus['subscribers'].remove(q)


def _chat_publish(context_id: str, event: Dict[str, Any]) -> None:
    bus = get_chat_bus(context_id)
    with bus['subs_lock']:
        subs = list(bus['subscribers'])
    for q in subs:
        try:
            q.put_nowait(event)
        except queue.Full:
            pass  # slow subscriber: drop; client will re-sync via history on next connect


def _chat_set_busy(context_id: str, value: bool) -> bool:
    """Returns True if state was changed (i.e. wasn't already the same)."""
    bus = get_chat_bus(context_id)
    with bus['busy_lock']:
        if value and bus['busy']:
            return False
        bus['busy'] = value
        return True


def _chat_is_busy(context_id: str) -> bool:
    bus = get_chat_bus(context_id)
    with bus['busy_lock']:
        return bus['busy']


def chat_turn_state_get(context_id: str) -> Optional[Dict[str, Any]]:
    """Called by response_stream + monologue_end extensions to learn the
    pre-allocated assistant_message_id and running delta position for a turn.
    """
    bus = get_chat_bus(context_id)
    with bus['turn_lock']:
        s = bus['turn_state']
        return dict(s) if s else None


def chat_turn_state_set_emitted_len(context_id: str, new_len: int) -> None:
    bus = get_chat_bus(context_id)
    with bus['turn_lock']:
        if bus['turn_state'] is not None:
            bus['turn_state']['emitted_len'] = new_len


def chat_turn_state_clear(context_id: str) -> Optional[Dict[str, Any]]:
    bus = get_chat_bus(context_id)
    with bus['turn_lock']:
        prev = bus['turn_state']
        bus['turn_state'] = None
        return dict(prev) if prev else None


def _chat_get_or_create_context(context_id: str):
    """Get the persistent A0 context for mobile chat, creating it on first use.
    Import is local so the module loads cleanly even if imported from a
    context where A0's agent framework isn't available (e.g. ad-hoc tests).
    """
    from agent import AgentContext
    from initialize import initialize_agent

    ctx = AgentContext.get(context_id)
    if ctx is None:
        ctx = AgentContext(config=initialize_agent(), id=context_id)
    return ctx


def _chat_dispatch_to_a0(
    context_id: str,
    user_text: str,
    assistant_id: str,
    attachments: Optional[List[str]] = None,
) -> bool:
    """Kick off an A0 turn for the given context. Returns True on success.
    The assistant reply arrives as live events emitted by the mobile
    response_stream + monologue_end extensions; nothing is awaited here."""
    from agent import UserMessage

    bus = get_chat_bus(context_id)
    with bus['turn_lock']:
        bus['turn_state'] = {
            'assistant_id': assistant_id,
            'emitted_len': 0,
            'started_at_ms': int(time.time() * 1000),
        }

    try:
        ctx = _chat_get_or_create_context(context_id)
        ctx.communicate(UserMessage(message=user_text, attachments=list(attachments or [])))
        return True
    except Exception as e:
        # Roll back turn state so the next send isn't stuck waiting for a
        # reply that will never come.
        chat_turn_state_clear(context_id)
        _chat_set_busy(context_id, False)
        _chat_publish(context_id, {
            'type': 'final',
            'message_id': assistant_id,
            'role': 'assistant',
            'content': f'[error dispatching to A0: {e!s}]',
            'created_at_ms': int(time.time() * 1000),
        })
        return False


# ---- Chat views ---------------------------------------------------------

def _context_to_dict(ctx) -> Dict[str, Any]:
    from datetime import datetime
    last = getattr(ctx, 'last_message', None)
    last_ms = 0
    if isinstance(last, datetime):
        last_ms = int(last.timestamp() * 1000)
    name = getattr(ctx, 'name', None)
    display = name if name else _CHAT_DISPLAY_NAME.get(ctx.id, ctx.id)
    return {
        'id': ctx.id,
        'display_name': display,
        'last_message_at_ms': last_ms,
    }


def _list_mobile_contexts() -> List[Dict[str, Any]]:
    """Return all A0 contexts with id prefix 'mobile-', sorted newest first
    by last_message time."""
    from agent import AgentContext
    try:
        everything = AgentContext.all()
    except Exception:
        return []
    mobile = [c for c in everything if isinstance(c.id, str) and c.id.startswith(_CHAT_MOBILE_PREFIX)]
    dicts = [_context_to_dict(c) for c in mobile]
    dicts.sort(key=lambda d: d.get('last_message_at_ms', 0), reverse=True)
    return dicts


def _chat_contexts_view():
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    contexts = _list_mobile_contexts()
    # Ensure the default context is always surfaced, even if the user has
    # never sent anything yet (so the first-launch drawer isn't empty).
    if not any(c['id'] == _CHAT_DEFAULT_CONTEXT for c in contexts):
        contexts.append({
            'id': _CHAT_DEFAULT_CONTEXT,
            'display_name': _CHAT_DISPLAY_NAME.get(_CHAT_DEFAULT_CONTEXT, _CHAT_DEFAULT_CONTEXT),
            'last_message_at_ms': 0,
        })
    body = {'contexts': contexts, 'server_time_ms': int(time.time() * 1000)}
    return Response(json.dumps(body), status=200, mimetype='application/json')


def _chat_contexts_create_view():
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    from agent import AgentContext
    from initialize import initialize_agent
    from python.helpers import persist_chat

    try:
        payload = flask_request.get_json(force=True, silent=True) or {}
    except Exception:
        payload = {}
    requested_id = (payload.get('id') or '').strip()

    if requested_id:
        if not requested_id.startswith(_CHAT_MOBILE_PREFIX):
            return _bad_request('id must start with "mobile-"')
        if AgentContext.get(requested_id) is not None:
            return _bad_request('id already exists')
        new_id = requested_id
    else:
        new_id = f'{_CHAT_MOBILE_PREFIX}{uuid.uuid4().hex[:8]}'
        # Collision guard even though 8 hex chars gives 4B ids.
        while AgentContext.get(new_id) is not None:
            new_id = f'{_CHAT_MOBILE_PREFIX}{uuid.uuid4().hex[:8]}'

    ctx = AgentContext(config=initialize_agent(), id=new_id)
    try:
        persist_chat.save_tmp_chat(ctx)
    except Exception:
        pass  # best-effort; first real turn will persist anyway

    body = {
        'ok': True,
        'id': ctx.id,
        'display_name': getattr(ctx, 'name', None) or ctx.id,
        'created_at_ms': int(time.time() * 1000),
    }
    return Response(json.dumps(body), status=200, mimetype='application/json')


def _chat_contexts_delete_view(context_id: str):
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    if not context_id or not context_id.startswith(_CHAT_MOBILE_PREFIX):
        return _bad_request('only mobile-* contexts can be deleted via /mobile')
    from agent import AgentContext
    from python.helpers import persist_chat

    ctx = AgentContext.get(context_id)
    if ctx is not None:
        try:
            ctx.reset()
        except Exception:
            pass
    AgentContext.remove(context_id)
    try:
        persist_chat.remove_chat(context_id)
    except Exception:
        pass
    # Clean up our mobile JSONL mirror.
    try:
        mirror = _CHAT_DIR / f'{context_id}.jsonl'
        if mirror.exists():
            mirror.unlink()
    except OSError:
        pass
    # Delete any attachments uploaded in this context.
    try:
        attach_dir = _ATTACH_DIR / context_id
        if attach_dir.is_dir():
            shutil.rmtree(attach_dir, ignore_errors=True)
    except OSError:
        pass
    body = {'ok': True, 'id': context_id}
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


_ATTACH_FILENAME_SAFE = set(
    'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-'
)


def _attach_sanitize_filename(raw: str) -> str:
    # Strip path components, restrict to a safe charset, cap length.
    name = (raw or '').replace('\\', '/').split('/')[-1].strip()
    if not name:
        name = 'file'
    cleaned = ''.join(c if c in _ATTACH_FILENAME_SAFE else '_' for c in name)
    # Guard against leading dots creating hidden / .. names.
    cleaned = cleaned.lstrip('.')
    if not cleaned:
        cleaned = 'file'
    return cleaned[:180]


def _attach_context_dir(context_id: str) -> Optional[Path]:
    # Only mobile-* contexts (plus the default) may host attachments.
    if not context_id:
        return None
    if context_id != _CHAT_DEFAULT_CONTEXT and not context_id.startswith(_CHAT_MOBILE_PREFIX):
        return None
    d = _ATTACH_DIR / context_id
    d.mkdir(parents=True, exist_ok=True)
    return d


def _attach_meta(p: Path) -> Dict[str, Any]:
    # Richer metadata for the chat log + UI. Path remains the source of truth
    # that A0 consumes; filename/mime/size are UX affordances for playback.
    try:
        size = p.stat().st_size
    except OSError:
        size = 0
    mime, _ = mimetypes.guess_type(str(p))
    return {
        'path': str(p),
        'filename': p.name,
        'size': size,
        'mime': mime or 'application/octet-stream',
    }


def _attach_validate_path(path: str, context_id: str) -> Optional[Path]:
    # Accept only absolute paths that resolve inside the per-context attachment
    # dir and point at an existing regular file. Prevents the phone from
    # slipping arbitrary filesystem paths into UserMessage.attachments.
    if not path:
        return None
    try:
        p = Path(path).resolve()
    except Exception:
        return None
    base = _attach_context_dir(context_id)
    if base is None:
        return None
    try:
        base_resolved = base.resolve()
    except Exception:
        return None
    try:
        p.relative_to(base_resolved)
    except ValueError:
        return None
    if not p.is_file():
        return None
    return p


def _chat_attachment_download_view(context_id: str, filename: str):
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    # Reject anything that could escape the per-context attachment dir. The
    # filename on disk is always {ts}_{uuid8}_{sanitized-name}, all chars from
    # the _ATTACH_FILENAME_SAFE set — so any slash, backslash, or '..' means
    # a tampered URL.
    if not filename or '/' in filename or '\\' in filename or filename.startswith('.'):
        return _bad_request('invalid filename')
    ctx_dir = _attach_context_dir(context_id)
    if ctx_dir is None:
        return _bad_request('invalid context')
    target = ctx_dir / filename
    try:
        resolved = target.resolve()
        base_resolved = ctx_dir.resolve()
    except Exception:
        return _bad_request('invalid filename')
    try:
        resolved.relative_to(base_resolved)
    except ValueError:
        return _bad_request('invalid filename')
    if not resolved.is_file():
        return Response(
            json.dumps({'ok': False, 'error': 'not found'}),
            status=404,
            mimetype='application/json',
        )
    mime, _ = mimetypes.guess_type(str(resolved))
    return send_file(
        str(resolved),
        mimetype=mime or 'application/octet-stream',
        as_attachment=False,
        conditional=True,
    )


def _chat_attachments_upload_view():
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')

    context_id = (flask_request.form.get('context') or _CHAT_DEFAULT_CONTEXT).strip()
    ctx_dir = _attach_context_dir(context_id)
    if ctx_dir is None:
        return _bad_request('invalid context')

    files = flask_request.files.getlist('files')
    if not files:
        return _bad_request('no files uploaded (expected multipart field "files")')

    saved: List[Dict[str, Any]] = []
    for f in files:
        if not f or not f.filename:
            continue
        safe = _attach_sanitize_filename(f.filename)
        prefix = f'{int(time.time() * 1000)}_{uuid.uuid4().hex[:8]}'
        dest = ctx_dir / f'{prefix}_{safe}'
        # FileStorage.save() streams from the incoming request body via
        # shutil.copyfileobj — no whole-file buffering in memory.
        f.save(str(dest))
        try:
            size = dest.stat().st_size
        except OSError:
            size = 0
        saved.append({
            'path': str(dest),
            'filename': safe,
            'size': size,
            'mime': f.mimetype or 'application/octet-stream',
        })

    if not saved:
        return _bad_request('no files uploaded (expected multipart field "files")')

    body = {'ok': True, 'context': context_id, 'attachments': saved}
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
    raw_attachments = payload.get('attachments') or []
    if not isinstance(raw_attachments, list):
        return _bad_request('attachments must be a list of server paths')

    attachment_paths: List[str] = []
    for entry in raw_attachments:
        if not isinstance(entry, str):
            return _bad_request('attachments must be strings')
        valid = _attach_validate_path(entry, context_id)
        if valid is None:
            return _bad_request(f'invalid attachment path: {entry}')
        attachment_paths.append(str(valid))

    if not content and not attachment_paths:
        return _bad_request('content or attachments required')

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
    attachment_meta: List[Dict[str, Any]] = []
    if attachment_paths:
        attachment_meta = [_attach_meta(Path(p)) for p in attachment_paths]
        user_record['attachments'] = attachment_meta
    _chat_append_log(context_id, user_record)
    publish_evt = {
        'type': 'user_msg',
        'message_id': user_id,
        'role': 'user',
        'content': content,
        'created_at_ms': now_ms,
    }
    if attachment_meta:
        publish_evt['attachments'] = attachment_meta
    _chat_publish(context_id, publish_evt)

    # Hand off to A0's agent loop. The reply will arrive asynchronously as
    # delta events (from the response_stream extension) and a final event
    # (from the monologue_end extension), both of which publish to the
    # same pub/sub the client is subscribed to via /mobile/chat/stream.
    if not _chat_dispatch_to_a0(context_id, content, assistant_id, attachment_paths):
        # _chat_dispatch_to_a0 already cleared busy + published an error final.
        return Response(
            json.dumps({'ok': False, 'error': 'a0 dispatch failed'}),
            status=502,
            mimetype='application/json',
        )

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
            # finally clause releases the request thread. Independently, a
            # max-lifetime ceiling forces a clean return after 60s so a
            # silently-paused client can't park the Flask thread indefinitely.
            started = time.monotonic()
            last_event = started
            while True:
                if time.monotonic() - started >= _CHAT_STREAM_MAX_LIFETIME_S:
                    return
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


# ---- Tasks (Phase 7) ----------------------------------------------------

_TASK_LAST_RESULT_PREVIEW_CHARS = 500


def _run_coro(coro):
    """Run an async coroutine from a sync Flask handler. asyncio.run
    creates a fresh event loop per call; Flask's sync request threads
    don't have an existing loop so this is safe."""
    return asyncio.run(coro)


def _dt_to_ms(dt) -> int:
    if dt is None:
        return 0
    if isinstance(dt, datetime):
        return int(dt.timestamp() * 1000)
    return 0


def _task_to_dict(task) -> Dict[str, Any]:
    state_val = task.state.value if hasattr(task.state, 'value') else str(task.state)
    type_val = task.type.value if hasattr(task.type, 'value') else str(task.type)
    schedule_dict: Optional[Dict[str, Any]] = None
    schedule_obj = getattr(task, 'schedule', None)
    if schedule_obj is not None:
        schedule_dict = {
            'minute': schedule_obj.minute,
            'hour': schedule_obj.hour,
            'day': schedule_obj.day,
            'month': schedule_obj.month,
            'weekday': schedule_obj.weekday,
            'timezone': getattr(schedule_obj, 'timezone', '') or '',
        }
    next_run = None
    try:
        next_run = task.get_next_run()
    except Exception:
        pass
    last_result = (getattr(task, 'last_result', '') or '')
    return {
        'uuid': task.uuid,
        'name': task.name,
        'state': state_val,
        'type': type_val,
        'schedule': schedule_dict,
        'last_run_ms': _dt_to_ms(getattr(task, 'last_run', None)),
        'next_run_ms': _dt_to_ms(next_run),
        'last_result_preview': last_result[:_TASK_LAST_RESULT_PREVIEW_CHARS],
        'last_result_truncated': len(last_result) > _TASK_LAST_RESULT_PREVIEW_CHARS,
        'created_at_ms': _dt_to_ms(getattr(task, 'created_at', None)),
        'updated_at_ms': _dt_to_ms(getattr(task, 'updated_at', None)),
    }


def _tasks_list_view():
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    from python.helpers.task_scheduler import TaskScheduler, TaskType
    scheduler = TaskScheduler.get()
    tasks = scheduler.get_tasks()
    scheduled = [t for t in tasks if getattr(t, 'type', None) == TaskType.SCHEDULED]
    body = {
        'tasks': [_task_to_dict(t) for t in scheduled],
        'server_time_ms': int(time.time() * 1000),
    }
    return Response(json.dumps(body), status=200, mimetype='application/json')


def _task_detail_view(task_uuid: str):
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    from python.helpers.task_scheduler import TaskScheduler
    task = TaskScheduler.get().get_task_by_uuid(task_uuid)
    if task is None:
        return Response(
            json.dumps({'ok': False, 'error': 'task not found', 'uuid': task_uuid}),
            status=404,
            mimetype='application/json',
        )
    body = _task_to_dict(task)
    body['last_result_full'] = getattr(task, 'last_result', '') or ''
    body['prompt'] = getattr(task, 'prompt', '') or ''
    body['system_prompt'] = getattr(task, 'system_prompt', '') or ''
    return Response(json.dumps(body), status=200, mimetype='application/json')


def _task_create_view():
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    try:
        payload = flask_request.get_json(force=True, silent=False) or {}
    except Exception:
        return _bad_request('body must be JSON')

    name = (payload.get('name') or '').strip()
    prompt = (payload.get('prompt') or '').strip()
    system_prompt = (payload.get('system_prompt') or '').strip()
    sched_dict = payload.get('schedule') or {}

    if not name:
        return _bad_request('name required')
    if not prompt:
        return _bad_request('prompt required')
    if not isinstance(sched_dict, dict):
        return _bad_request('schedule must be an object')

    from python.helpers.task_scheduler import TaskSchedule, ScheduledTask, TaskScheduler

    try:
        schedule = TaskSchedule(
            minute=str(sched_dict.get('minute', '*')),
            hour=str(sched_dict.get('hour', '*')),
            day=str(sched_dict.get('day', '*')),
            month=str(sched_dict.get('month', '*')),
            weekday=str(sched_dict.get('weekday', '*')),
            timezone=str(sched_dict.get('timezone') or ''),
        )
    except Exception as e:
        return _bad_request(f'invalid schedule: {e}')

    task = ScheduledTask.create(
        name=name,
        system_prompt=system_prompt,
        prompt=prompt,
        schedule=schedule,
    )

    try:
        _run_coro(TaskScheduler.get().add_task(task))
    except Exception as e:
        return Response(
            json.dumps({'ok': False, 'error': f'add_task failed: {e!s}'}),
            status=500,
            mimetype='application/json',
        )

    return Response(
        json.dumps({'ok': True, 'uuid': task.uuid, 'task': _task_to_dict(task)}),
        status=200,
        mimetype='application/json',
    )


def _task_run_view(task_uuid: str):
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    from python.helpers.task_scheduler import TaskScheduler
    scheduler = TaskScheduler.get()
    task = scheduler.get_task_by_uuid(task_uuid)
    if task is None:
        return Response(
            json.dumps({'ok': False, 'error': 'task not found', 'uuid': task_uuid}),
            status=404,
            mimetype='application/json',
        )

    # Kick off in a background thread so the POST returns immediately.
    def _fire():
        try:
            asyncio.run(scheduler.run_task_by_uuid(task_uuid))
        except Exception as e:
            # Scheduler logs its own errors; we just don't want to crash
            # the thread.
            pass

    threading.Thread(target=_fire, daemon=True, name=f'task-run-{task_uuid}').start()

    return Response(
        json.dumps({'ok': True, 'uuid': task_uuid, 'started_at_ms': int(time.time() * 1000)}),
        status=200,
        mimetype='application/json',
    )


def _task_set_state_view(task_uuid: str, enabled: bool) -> Response:
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    from python.helpers.task_scheduler import TaskScheduler, TaskState
    scheduler = TaskScheduler.get()
    task = scheduler.get_task_by_uuid(task_uuid)
    if task is None:
        return Response(
            json.dumps({'ok': False, 'error': 'task not found', 'uuid': task_uuid}),
            status=404,
            mimetype='application/json',
        )
    new_state = TaskState.IDLE if enabled else TaskState.DISABLED
    try:
        _run_coro(scheduler.update_task(task_uuid, state=new_state))
    except Exception as e:
        return Response(
            json.dumps({'ok': False, 'error': f'update failed: {e!s}'}),
            status=500,
            mimetype='application/json',
        )
    return Response(
        json.dumps({'ok': True, 'uuid': task_uuid, 'state': new_state.value}),
        status=200,
        mimetype='application/json',
    )


def _task_enable_view(task_uuid: str):
    return _task_set_state_view(task_uuid, enabled=True)


def _task_disable_view(task_uuid: str):
    return _task_set_state_view(task_uuid, enabled=False)


def _task_delete_view(task_uuid: str):
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    from python.helpers.task_scheduler import TaskScheduler
    scheduler = TaskScheduler.get()
    task = scheduler.get_task_by_uuid(task_uuid)
    if task is None:
        return Response(
            json.dumps({'ok': False, 'error': 'task not found', 'uuid': task_uuid}),
            status=404,
            mimetype='application/json',
        )
    try:
        _run_coro(scheduler.remove_task_by_uuid(task_uuid))
    except Exception as e:
        return Response(
            json.dumps({'ok': False, 'error': f'remove failed: {e!s}'}),
            status=500,
            mimetype='application/json',
        )
    return Response(
        json.dumps({'ok': True, 'uuid': task_uuid}),
        status=200,
        mimetype='application/json',
    )


# ---- Push queue (Phase 1: durable queue + long-poll endpoint) -----------
# SeekerZero push pipe. Anything that needs to wake the phone asynchronously
# (scheduled-task deliveries, mid-background chat completions, etc.) calls
# enqueue_push(...) from anywhere in the process. The phone long-polls
# /mobile/push/pending (since_id cursor) and acks via /mobile/push/ack.
#
# Storage: a dedicated SQLite DB so schema churn and WAL growth can't spill
# into the shared knowledge/telemetry DBs. Not part of the integrity
# baseline (DB files excluded as of 2026-04-20).
#
# Wake-up: a process-local Condition. enqueue_push() is expected to be
# called from the same Flask process that serves /mobile/push/pending, so a
# module-level cond is sufficient. (If that ever stops being true, swap to
# a polling fallback inside the long-poll loop; the sleep is already there.)

_PUSH_DB = Path('/a0/usr/databases/seekerzero_push.db')
_PUSH_LONGPOLL_MAX_S = 55.0
_PUSH_POLL_INTERVAL_S = 0.5
_PUSH_FETCH_LIMIT = 50

_push_cond = threading.Condition()
_push_db_lock = threading.Lock()
_push_db_ready = False


def _push_db_conn():
    _PUSH_DB.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(_PUSH_DB), timeout=5.0, isolation_level=None)
    conn.execute('PRAGMA journal_mode=WAL')
    conn.execute('PRAGMA synchronous=NORMAL')
    return conn


def _push_db_init() -> None:
    global _push_db_ready
    if _push_db_ready:
        return
    with _push_db_lock:
        if _push_db_ready:
            return
        conn = _push_db_conn()
        try:
            conn.execute('''
                CREATE TABLE IF NOT EXISTS push_queue (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at_ms INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    deep_link TEXT,
                    payload_json TEXT,
                    delivered INTEGER NOT NULL DEFAULT 0,
                    acked_at_ms INTEGER
                )
            ''')
            conn.execute(
                'CREATE INDEX IF NOT EXISTS idx_push_undelivered '
                'ON push_queue(delivered, id)'
            )
        finally:
            conn.close()
        _push_db_ready = True


def enqueue_push(
    title: str,
    body: str,
    deep_link: Optional[str] = None,
    payload: Optional[Dict[str, Any]] = None,
) -> int:
    '''Insert a push row and wake any pending long-pollers. Returns new id.'''
    _push_db_init()
    now_ms = int(time.time() * 1000)
    payload_json = json.dumps(payload) if payload is not None else None
    conn = _push_db_conn()
    try:
        cur = conn.execute(
            'INSERT INTO push_queue '
            '(created_at_ms, title, body, deep_link, payload_json) '
            'VALUES (?, ?, ?, ?, ?)',
            (now_ms, title, body, deep_link, payload_json),
        )
        new_id = int(cur.lastrowid)
    finally:
        conn.close()
    with _push_cond:
        _push_cond.notify_all()
    return new_id


def _push_fetch_pending(since_id: int) -> List[Dict[str, Any]]:
    _push_db_init()
    conn = _push_db_conn()
    try:
        cur = conn.execute(
            'SELECT id, created_at_ms, title, body, deep_link, payload_json '
            'FROM push_queue '
            'WHERE id > ? AND delivered = 0 '
            'ORDER BY id ASC LIMIT ?',
            (int(since_id), _PUSH_FETCH_LIMIT),
        )
        out: List[Dict[str, Any]] = []
        for row in cur.fetchall():
            payload = None
            if row[5]:
                try:
                    payload = json.loads(row[5])
                except Exception:
                    payload = None
            out.append({
                'id': row[0],
                'created_at_ms': row[1],
                'title': row[2],
                'body': row[3],
                'deep_link': row[4],
                'payload': payload,
            })
        return out
    finally:
        conn.close()


def _push_pending_view():
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    since_raw = flask_request.args.get('since_id', '0')
    try:
        since_id = int(since_raw)
    except ValueError:
        return _bad_request('since_id must be integer')

    items = _push_fetch_pending(since_id)
    if items:
        return Response(
            json.dumps({'ok': True, 'items': items}),
            status=200,
            mimetype='application/json',
        )

    deadline = time.monotonic() + _PUSH_LONGPOLL_MAX_S
    while time.monotonic() < deadline:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        with _push_cond:
            _push_cond.wait(timeout=min(remaining, _PUSH_POLL_INTERVAL_S))
        items = _push_fetch_pending(since_id)
        if items:
            return Response(
                json.dumps({'ok': True, 'items': items}),
                status=200,
                mimetype='application/json',
            )

    return Response(
        json.dumps({'ok': True, 'items': []}),
        status=200,
        mimetype='application/json',
    )


def _push_ack_view():
    ip = _client_ip()
    if not _is_tailnet(ip):
        return _forbidden('not a tailnet peer')
    try:
        payload = flask_request.get_json(force=True, silent=False) or {}
    except Exception:
        return _bad_request('body must be JSON')
    ids = payload.get('ids')
    if not isinstance(ids, list) or not all(isinstance(x, int) for x in ids):
        return _bad_request('ids must be a list of integers')
    if not ids:
        return Response(
            json.dumps({'ok': True, 'acked': 0}),
            status=200,
            mimetype='application/json',
        )
    _push_db_init()
    now_ms = int(time.time() * 1000)
    placeholders = ','.join('?' * len(ids))
    conn = _push_db_conn()
    try:
        cur = conn.execute(
            f'UPDATE push_queue SET delivered = 1, acked_at_ms = ? '
            f'WHERE id IN ({placeholders}) AND delivered = 0',
            [now_ms] + ids,
        )
        n = cur.rowcount
    finally:
        conn.close()
    return Response(
        json.dumps({'ok': True, 'acked': int(n)}),
        status=200,
        mimetype='application/json',
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
                '/mobile/chat/contexts',
                'seekerzero_mobile_chat_contexts',
                _chat_contexts_view,
                methods=['GET'],
            )
            app.add_url_rule(
                '/mobile/chat/contexts',
                'seekerzero_mobile_chat_contexts_create',
                _chat_contexts_create_view,
                methods=['POST'],
            )
            app.add_url_rule(
                '/mobile/chat/contexts/<context_id>',
                'seekerzero_mobile_chat_contexts_delete',
                _chat_contexts_delete_view,
                methods=['DELETE'],
            )
            app.add_url_rule(
                '/mobile/chat/history',
                'seekerzero_mobile_chat_history',
                _chat_history_view,
                methods=['GET'],
            )
            app.add_url_rule(
                '/mobile/chat/attachments',
                'seekerzero_mobile_chat_attachments_upload',
                _chat_attachments_upload_view,
                methods=['POST'],
            )
            app.add_url_rule(
                '/mobile/chat/attachments/<context_id>/<path:filename>',
                'seekerzero_mobile_chat_attachment_download',
                _chat_attachment_download_view,
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
            app.add_url_rule(
                '/mobile/tasks/scheduled',
                'seekerzero_mobile_tasks_list',
                _tasks_list_view,
                methods=['GET'],
            )
            app.add_url_rule(
                '/mobile/tasks',
                'seekerzero_mobile_tasks_create',
                _task_create_view,
                methods=['POST'],
            )
            app.add_url_rule(
                '/mobile/tasks/<task_uuid>',
                'seekerzero_mobile_task_detail',
                _task_detail_view,
                methods=['GET'],
            )
            app.add_url_rule(
                '/mobile/tasks/<task_uuid>/run',
                'seekerzero_mobile_task_run',
                _task_run_view,
                methods=['POST'],
            )
            app.add_url_rule(
                '/mobile/tasks/<task_uuid>/enable',
                'seekerzero_mobile_task_enable',
                _task_enable_view,
                methods=['POST'],
            )
            app.add_url_rule(
                '/mobile/tasks/<task_uuid>/disable',
                'seekerzero_mobile_task_disable',
                _task_disable_view,
                methods=['POST'],
            )
            app.add_url_rule(
                '/mobile/tasks/<task_uuid>',
                'seekerzero_mobile_task_delete',
                _task_delete_view,
                methods=['DELETE'],
            )
            app.add_url_rule(
                '/mobile/push/pending',
                'seekerzero_mobile_push_pending',
                _push_pending_view,
                methods=['GET'],
            )
            app.add_url_rule(
                '/mobile/push/ack',
                'seekerzero_mobile_push_ack',
                _push_ack_view,
                methods=['POST'],
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
