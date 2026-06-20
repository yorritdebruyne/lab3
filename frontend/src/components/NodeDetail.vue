<template>
  <div class="card">
    <div class="card-title">Node Details</div>
    <div class="detail-grid">
      <div class="detail-row">
        <span class="detail-label">Name</span>
        <span class="detail-value highlight">{{ node.name }}</span>
      </div>
      <div class="detail-row">
        <span class="detail-label">Ring ID</span>
        <span class="detail-value">{{ node.id }}</span>
      </div>
      <div class="detail-row">
        <span class="detail-label">IP Address</span>
        <span class="detail-value">{{ node.ip }}</span>
      </div>
      <div class="detail-row">
        <span class="detail-label">Port</span>
        <span class="detail-value">{{ node.port }}</span>
      </div>
      <div class="detail-row">
        <span class="detail-label">Status</span>
        <span class="detail-value">
          <span class="badge online">online</span>
        </span>
      </div>
      <div class="detail-row" v-if="state">
        <span class="detail-label">Previous ID</span>
        <span class="detail-value ring-id">{{ state.prevId }}</span>
      </div>
      <div class="detail-row" v-if="state">
        <span class="detail-label">Next ID</span>
        <span class="detail-value ring-id">{{ state.nextId }}</span>
      </div>
    </div>

    <!-- Ring visualisation -->
    <div v-if="state" class="ring-visual">
      <div class="ring-node prev">
        <span class="ring-label">PREV</span>
        <span class="ring-id-val">{{ state.prevId }}</span>
      </div>
      <div class="ring-arrow">←</div>
      <div class="ring-node current">
        <span class="ring-label">YOU</span>
        <span class="ring-id-val">{{ node.id }}</span>
      </div>
      <div class="ring-arrow">→</div>
      <div class="ring-node next">
        <span class="ring-label">NEXT</span>
        <span class="ring-id-val">{{ state.nextId }}</span>
      </div>
    </div>

    <!-- Fixed-height footer so toggling a status line never changes the card
         height (otherwise the "Files on node" card below it gets pushed down). -->
    <div class="status-footer">
      <span v-if="loading" class="loading-text">Loading state...</span>
      <span v-else-if="error" class="error-text">{{ error }}</span>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { getNodeState } from '../api.js'

const props = defineProps({
  node: { type: Object, required: true }
})

const state = ref(null)
const loading = ref(false)
const error = ref(null)
// Spinner only on the first load for a node; background refreshes update in place.
const hasLoadedOnce = ref(false)

async function loadState() {
  if (!hasLoadedOnce.value) loading.value = true
  error.value = null
  try {
    state.value = await getNodeState(props.node.ip, props.node.port)
  } catch (e) {
    error.value = 'Could not reach node'
    state.value = null
  } finally {
    loading.value = false
    hasLoadedOnce.value = true
  }
}

// Key on the node ID, not the object reference: the parent's 5s auto-refresh
// hands us a fresh object for the SAME node, and reacting to that would re-show
// the spinner and jump the layout every cycle.
watch(() => props.node?.id, (newId, oldId) => {
  if (newId !== oldId) hasLoadedOnce.value = false
  loadState()
}, { immediate: true })

// Silent periodic refresh (prev/next IDs change on topology churn) — never shows
// the spinner, so the card height stays constant.
let refreshTimer = null
onMounted(() => {
  refreshTimer = setInterval(() => { if (hasLoadedOnce.value) loadState() }, 5000)
})
onUnmounted(() => { if (refreshTimer) clearInterval(refreshTimer) })
</script>

<style scoped>
.detail-grid { display: flex; flex-direction: column; gap: 8px; margin-bottom: 20px; }

.detail-row {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  background: #0a0e1a;
  border-radius: 6px;
  border: 1px solid #131d30;
}

.detail-label {
  width: 120px;
  font-size: 10px;
  letter-spacing: 1.5px;
  text-transform: uppercase;
  color: #4a6080;
  flex-shrink: 0;
}

.detail-value { font-size: 13px; color: #c8d8f0; }
.detail-value.highlight { color: #4a9eff; font-weight: 700; }
.detail-value.ring-id { color: #f39c12; font-family: monospace; }

.badge {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 10px;
  letter-spacing: 1px;
}
.badge.online { background: #0d2d1a; color: #2ecc71; border: 1px solid #2ecc71; }

/* Ring visual */
.ring-visual {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 16px;
  background: #0a0e1a;
  border-radius: 8px;
  border: 1px solid #131d30;
  margin-top: 4px;
}

.ring-node {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 10px 16px;
  border-radius: 6px;
  border: 1px solid #1e2d4a;
  min-width: 80px;
}
.ring-node.current { border-color: #4a9eff; background: #0f1e38; }
.ring-node.prev, .ring-node.next { background: #0a0e1a; }

.ring-label { font-size: 9px; letter-spacing: 2px; color: #4a6080; }
.ring-id-val { font-size: 13px; color: #f39c12; font-weight: 700; }
.ring-node.current .ring-id-val { color: #4a9eff; }

.ring-arrow { font-size: 18px; color: #1e2d4a; }

.status-footer {
  min-height: 18px;
  margin-top: 12px;
  display: flex;
  align-items: center;
}
.loading-text, .error-text { font-size: 11px; }
.loading-text { color: #4a6080; }
.error-text { color: #e74c3c; }
</style>