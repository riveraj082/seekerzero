# SeekerZero mobile chat: tool_result event emitter.
#
# Hooks A0's tool_execute_after, which fires once per tool invocation
# right after the tool completes. Publishes a `tool_result` NDJSON event
# on the mobile pub/sub so the phone's "Using <tool>..." pill can clear
# (or reflect the next tool).
#
# Filters: only the mobile-* contexts, only the top-level agent,
# and skip the "response" tool (the final event carries that content).

import queue
import time

from python.helpers.extension import Extension


_MOBILE_CONTEXT_PREFIX = 'mobile-'
_CHAT_BUS_KEY = '_seekerzero_chat_bus'
_SKIP_TOOLS = ('response',)
_RESULT_PREVIEW_CHARS = 240


class SeekerzeroToolEnd(Extension):

    async def execute(self, response=None, tool_name: str = '', **kwargs):
        ctx_id = self.agent.context.id
        if not isinstance(ctx_id, str) or not ctx_id.startswith(_MOBILE_CONTEXT_PREFIX):
            return
        if getattr(self.agent, 'number', 0) != 0:
            return
        if not tool_name or tool_name in _SKIP_TOOLS:
            return

        bus = self.agent.context.data.get(_CHAT_BUS_KEY)
        if not bus:
            return

        with bus['turn_lock']:
            state = bus['turn_state']
            if not state:
                return
            assistant_id = state.get('assistant_id')
            if not assistant_id:
                return

        msg = ''
        if response is not None:
            raw = getattr(response, 'message', None)
            if isinstance(raw, str):
                msg = raw

        event = {
            'type': 'tool_result',
            'message_id': assistant_id,
            'tool_name': tool_name,
            'result_preview': msg[:_RESULT_PREVIEW_CHARS],
            'truncated': len(msg) > _RESULT_PREVIEW_CHARS,
            'created_at_ms': int(time.time() * 1000),
        }
        with bus['subs_lock']:
            subs = list(bus['subscribers'])
        for q in subs:
            try:
                q.put_nowait(event)
            except queue.Full:
                pass
