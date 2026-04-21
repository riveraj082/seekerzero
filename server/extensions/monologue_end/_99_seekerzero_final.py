# SeekerZero mobile chat: final-event emitter + JSONL mirror.
#
# Hooks A0's monologue_end, which fires after a full agent turn completes
# (i.e. after the "response" tool has been invoked and the monologue exits).
# At this point we take the pre-allocated assistant_message_id from the
# context's chat bus, read the final response text from the context log,
# write a row to the mobile JSONL mirror, publish a `final` event, and
# clear the busy flag.
#
# Shared state with the Flask handler lives on context.data[CHAT_BUS_KEY] —
# see the forwarder extension's header for the why.
#
# Only fires for the mobile-* contexts and only for the top-level
# agent (subordinate turns also call monologue_end, and we don't want to
# terminate the user-visible reply on a subordinate's completion).
#
# Canonical source lives at /a0/usr/patches/seekerzero_final.py and is
# copied into /a0/usr/extensions/monologue_end/ by agent-zero-post-start.sh.

import json
import queue
import time
from pathlib import Path

from agent import LoopData
from python.helpers.extension import Extension


_MOBILE_CONTEXT_PREFIX = 'mobile-'
_CHAT_BUS_KEY = '_seekerzero_chat_bus'
_CHAT_DIR = Path('/a0/usr/seekerzero/chat')


def _extract_final_text(agent) -> str:
    """Pull the final assistant text from the most recent response log item,
    defensively expand any surviving §§include(...) references, then strip
    A0's task-stats footer so the phone only sees the actual reply.

    The web UI's LiveResponse extension appends to a log item of type
    'response' on each parseable chunk; by monologue_end, its content is
    the complete assistant reply. A0's _05_task_stats_display extension
    then appends a cost / timing / budget footer after a `---` horizontal
    rule — informative on desktop, visually heavy on a small screen, so
    we strip it here. The footer starts at the first `---` line and runs
    to end-of-content; we cut there.

    Includes expansion is a safety net for a known upstream race (see
    project_a0_save_tool_call_race.md); A0's ReplaceIncludeAlias
    extension normally handles it at response_stream time."""
    try:
        logs = agent.context.log.logs
        for item in reversed(logs):
            if getattr(item, 'type', None) == 'response':
                content = getattr(item, 'content', '')
                if isinstance(content, str) and content:
                    expanded = _safe_expand_includes(content)
                    return _strip_task_stats_footer(expanded)
    except Exception:
        pass
    return ''


def _strip_task_stats_footer(text: str) -> str:
    """Remove A0's task-stats footer. The footer is appended by the
    _05_task_stats_display monologue_end extension in the form:

        <assistant reply text>
        ---
        ⏱ Task completed in 4.8s
        <cost table, cache line, daily budget line>

    Cut at the first line that is exactly "---" after some reply text."""
    if not text:
        return text
    lines = text.splitlines()
    cut = None
    for i, line in enumerate(lines):
        if line.strip() == '---' and i > 0:
            cut = i
            break
    if cut is None:
        return text
    trimmed = '\n'.join(lines[:cut]).rstrip()
    return trimmed


def _safe_expand_includes(text: str) -> str:
    try:
        from python.helpers.strings import replace_file_includes
        return replace_file_includes(text)
    except Exception:
        return text


def _append_mirror(context_id: str, record: dict) -> None:
    try:
        _CHAT_DIR.mkdir(parents=True, exist_ok=True)
        path = _CHAT_DIR / f'{context_id}.jsonl'
        with path.open('a', encoding='utf-8') as f:
            f.write(json.dumps(record, ensure_ascii=False) + '\n')
    except OSError:
        pass  # mirror is best-effort; A0's own chat.json is the durable record


class SeekerzeroFinal(Extension):

    async def execute(self, loop_data: LoopData = LoopData(), **kwargs):
        ctx_id = self.agent.context.id
        if not isinstance(ctx_id, str) or not ctx_id.startswith(_MOBILE_CONTEXT_PREFIX):
            return
        # Only top-level agent finals reach the user.
        if getattr(self.agent, 'number', 0) != 0:
            return

        bus = self.agent.context.data.get(_CHAT_BUS_KEY)
        if not bus:
            return

        with bus['turn_lock']:
            state = bus['turn_state']
            bus['turn_state'] = None
        if not state:
            # No turn in flight from the mobile side (e.g. reply was driven
            # from the web UI on the same context). Nothing to emit.
            return

        assistant_id = state.get('assistant_id')
        if not assistant_id:
            with bus['busy_lock']:
                bus['busy'] = False
            return

        final_text = _extract_final_text(self.agent)
        now_ms = int(time.time() * 1000)

        _append_mirror(ctx_id, {
            'id': assistant_id,
            'role': 'assistant',
            'content': final_text,
            'created_at_ms': now_ms,
            'is_final': True,
        })

        event = {
            'type': 'final',
            'message_id': assistant_id,
            'role': 'assistant',
            'content': final_text,
            'created_at_ms': now_ms,
        }
        with bus['subs_lock']:
            subs = list(bus['subscribers'])
        for q in subs:
            try:
                q.put_nowait(event)
            except queue.Full:
                pass

        with bus['busy_lock']:
            bus['busy'] = False
