<template>
  <div class="card">
    <div class="card-title">
      Activity Feed
      <span class="feed-status" :class="connected ? 'on' : 'off'">
        {{ connected ? 'live' : 'reconnecting…' }}
      </span>
    </div>

    <div v-if="events.length === 0" class="feed-empty">
      Waiting for activity… (add, remove, or kill a node)
    </div>

    <ul v-else class="feed-list">
      <li v-for="(e, i) in events" :key="e.timestamp + '-' + i" class="feed-item">
        <span class="feed-time">{{ time(e.timestamp) }}</span>
        <span class="feed-type" :class="'t-' + (e.type || 'INFO').toLowerCase()">{{ e.type }}</span>
        <span class="feed-msg">{{ e.message }}</span>
        <span class="feed-src">{{ e.source }}</span>
      </li>
    </ul>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

const events = ref([])
const connected = ref(false)
let source = null

const MAX = 60

function time(ts) {
  try { return new Date(ts).toLocaleTimeString() } catch { return '' }
}

function handleFrame(msg) {
  try {
    const e = JSON.parse(msg.data)
    events.value.unshift(e)                         // newest on top
    if (events.value.length > MAX) events.value.pop()
  } catch { /* ignore malformed frame */ }
}

function connect() {
  // SSE stream served by the naming server (through Nginx on the VM, directly on localhost)
  source = new EventSource(`${window.location.origin}/api/events`)
  source.onopen = () => { connected.value = true }
  // The server tags frames as `event: system`, so they arrive as a NAMED event.
  // EventSource.onmessage only fires for the default (unnamed) "message" event,
  // so we must listen for "system" explicitly. (onmessage kept as a fallback
  // in case an unnamed event is ever sent.)
  source.addEventListener('system', handleFrame)
  source.onmessage = handleFrame
  source.onerror = () => {
    connected.value = false
    // EventSource auto-reconnects; nothing else to do
  }
}

onMounted(connect)
onUnmounted(() => { if (source) source.close() })
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; }
.feed-status { font-size: 10px; letter-spacing: 1px; text-transform: none; padding: 2px 8px; border-radius: 10px; }
.feed-status.on  { color: #2ecc71; border: 1px solid #2ecc71; }
.feed-status.off { color: #e67e22; border: 1px solid #e67e22; }

.feed-empty { font-size: 11px; color: #4a6080; padding: 16px 0; text-align: center; }

.feed-list {
  list-style: none; margin: 0; padding: 0;
  max-height: 260px; overflow-y: auto;
  display: flex; flex-direction: column; gap: 4px;
}
.feed-item {
  display: flex; align-items: baseline; gap: 10px;
  padding: 6px 10px;
  background: #0a0e1a; border: 1px solid #131d30; border-radius: 6px;
  font-size: 12px;
}
.feed-time { color: #4a6080; font-size: 10px; font-family: monospace; flex-shrink: 0; }
.feed-type {
  flex-shrink: 0; font-size: 9px; letter-spacing: 1px;
  padding: 1px 7px; border-radius: 4px; font-weight: 700;
}
.feed-type.t-join        { color: #2ecc71; background: #0d2d1a; }
.feed-type.t-leave       { color: #e74c3c; background: #2d1313; }
.feed-type.t-redistribute{ color: #4a9eff; background: #0f2a4a; }
.feed-type.t-replicate   { color: #2ecc71; background: #0d2d1a; }
.feed-type.t-upload      { color: #c8d8f0; background: #1e2d4a; }
.feed-type.t-failure     { color: #f39c12; background: #2e2410; }
.feed-type.t-info        { color: #8aa0c0; background: #131d30; }
.feed-msg { color: #c8d8f0; flex: 1; }
.feed-src { color: #4a6080; font-size: 10px; flex-shrink: 0; }
</style>
