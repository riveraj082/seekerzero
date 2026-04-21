# SeekerZero mobile chat: tool_call event emitter.
#
# Hooks A0's tool_execute_before, which fires once per tool invocation
# right before the tool runs. Publishes a `tool_call` NDJSON event on the
# mobile pub/sub so the phone can show a "Using <tool>..." indicator
# while A0 is doing mid-turn work.
#
# Filters: only the mobile-* contexts, only the top-level agent
# (subordinates call tools too and we don't want to surface every
# sub-agent action on the phone), and skip the "response" tool (that's
# A0's final-reply signal, not a user-facing tool action — the response
# tool's deltas are handled by response_stream/_99_seekerzero_forward).

import queue
import time

from python.helpers.extension import Extension


_MOBILE_CONTEXT_PREFIX = 'mobile-'
_CHAT_BUS_KEY = '_seekerzero_chat_bus'
_SKIP_TOOLS = ('response',)


class SeekerzeroToolStart(Extension):

    async def execute(
        self,
        tool_args: dict | None = None,
        tool_name: str = '',
        **kwargs,
    ):
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

        event = {
            'type': 'tool_call',
            'message_id': assistant_id,
            'tool_name': tool_name,
            'tool_args': tool_args or {},
            'created_at_ms': int(time.time() * 1000),
        }
        with bus['subs_lock']:
            subs = list(bus['subscribers'])
        for q in subs:
            try:
                q.put_nowait(event)
            except queue.Full:
                pass
