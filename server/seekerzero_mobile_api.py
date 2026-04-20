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
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from flask import Flask, Request, Response, request as flask_request

from python.helpers.api import ApiHandler, Input, Output, ThreadLockType


_TAILNET_PREFIX = '100.'
_A0_VERSION = '0.9.8.2'

_STUB_FILE = Path('/a0/usr/seekerzero/stub_approvals.json')
_LONG_POLL_MAX_S = 60.0
_POLL_INTERVAL_S = 0.5
_SINCE_LOOKBACK_CAP_MS = 24 * 60 * 60 * 1000


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
        return ['GET']

    async def process(self, input: Input, request: Request) -> Output:
        return {'ok': False, 'error': 'direct call not supported'}
