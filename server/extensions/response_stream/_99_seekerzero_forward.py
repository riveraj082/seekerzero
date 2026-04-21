# SeekerZero mobile chat: token delta forwarder.
#
# Hooks A0's response_stream extension point, which fires each time the
# main LLM produces a new parseable JSON fragment during a turn. When the
# agent is invoking the "response" tool, that fragment carries the partial
# assistant text at tool_args.text — we diff against what we've already
# sent and publish the new characters as a `delta` event on the mobile
# pub/sub.
#
# Shared state with the Flask handler lives on context.data[CHAT_BUS_KEY]
# because handler + extensions are loaded via extract_tools.import_module,
# which bypasses sys.modules — so module-level globals do NOT cross that
# boundary. AgentContext is a true singleton keyed by id, so context.data
# IS shared.
#
# Only fires for the mobile-seekerzero context.
#
# Canonical source lives at /a0/usr/patches/seekerzero_forward_response.py
# and is copied into /a0/usr/extensions/response_stream/ by
# agent-zero-post-start.sh.

import queue
import time

from agent import LoopData
from python.helpers.extension import Extension


_MOBILE_CONTEXT_ID = 'mobile-seekerzero'
_CHAT_BUS_KEY = '_seekerzero_chat_bus'


class SeekerzeroForwardResponse(Extension):

    async def execute(
        self,
        loop_data: LoopData = LoopData(),
        text: str = '',
        parsed: dict | None = None,
        **kwargs,
    ):
        if self.agent.context.id != _MOBILE_CONTEXT_ID:
            return
        if not parsed:
            return
        if parsed.get('tool_name') != 'response':
            return
        tool_args = parsed.get('tool_args') or {}
        cur_text = tool_args.get('text')
        if not isinstance(cur_text, str) or not cur_text:
            return

        bus = self.agent.context.data.get(_CHAT_BUS_KEY)
        if not bus:
            return

        with bus['turn_lock']:
            state = bus['turn_state']
            if not state:
                return
            assistant_id = state.get('assistant_id')
            emitted_len = int(state.get('emitted_len', 0))
            if not assistant_id or len(cur_text) <= emitted_len:
                return
            delta = cur_text[emitted_len:]
            state['emitted_len'] = len(cur_text)

        event = {
            'type': 'delta',
            'message_id': assistant_id,
            'role': 'assistant',
            'delta': delta,
            'created_at_ms': int(time.time() * 1000),
        }
        with bus['subs_lock']:
            subs = list(bus['subscribers'])
        for q in subs:
            try:
                q.put_nowait(event)
            except queue.Full:
                pass
