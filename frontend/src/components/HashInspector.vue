<template>
  <div class="card">
    <div class="card-title">Hash Inspector</div>
    <p class="desc">
      Type a filename to see its ring hash and which node would own it according to its hash.
      The hash is computed by the naming server, so it matches the ownership rule exactly.
    </p>

    <form class="inspect-form" @submit.prevent="inspect">
      <input
          v-model="filename"
          class="inspect-input"
          type="text"
          placeholder="e.g. report.txt"
          spellcheck="false"
      />
      <button class="btn-inspect" type="submit" :disabled="loading || !filename.trim()">
        {{ loading ? '…' : 'Resolve' }}
      </button>
    </form>

    <div v-if="error" class="error-text">{{ error }}</div>

    <div v-if="result" class="result">
      <div class="result-row">
        <span class="result-label">Filename</span>
        <span class="result-value">{{ result.filename }}</span>
      </div>
      <div class="result-row">
        <span class="result-label">Ring hash</span>
        <span class="result-value hash">{{ result.hash }}</span>
        <span class="result-range">/ 32768</span>
      </div>
      <div class="result-row">
        <span class="result-label">Owner</span>
        <span v-if="ownerName" class="result-value owner">
          {{ ownerName }} <span class="owner-id">(ID {{ result.ownerId }})</span>
        </span>
        <span v-else class="result-value muted">no node in ring</span>
      </div>

      <!-- mini scale showing where the file lands relative to node IDs -->
      <div class="scale" v-if="nodes.length">
        <div class="scale-track">
          <div
              class="scale-tick"
              v-for="n in nodes"
              :key="n.id"
              :style="{ left: pct(n.id) + '%' }"
              :class="{ isowner: n.id === result.ownerId }"
              :title="`${n.name} — ID ${n.id}`"
          ></div>
          <div class="scale-file" :style="{ left: pct(result.hash) + '%' }" title="this file"></div>
        </div>
        <div class="scale-legend">
          <span><i class="dot node"></i> node</span>
          <span><i class="dot owner"></i> owner</span>
          <span><i class="dot file"></i> file</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { getFileHash } from '../api.js'

const props = defineProps({
  // pass the current node list so we can place ticks on the mini scale
  nodes: { type: Array, default: () => [] }
})

const RING_MAX = 32768

const filename = ref('')
const result = ref(null)
const loading = ref(false)
const error = ref(null)

const ownerName = computed(() => {
  if (!result.value || result.value.ownerId == null) return null
  const owner = props.nodes.find(n => n.id === result.value.ownerId)
  return owner ? owner.name : `ID ${result.value.ownerId}`
})

function pct(ringValue) {
  return Math.max(0, Math.min(100, (ringValue / RING_MAX) * 100))
}

async function inspect() {
  if (!filename.value.trim()) return
  loading.value = true
  error.value = null
  try {
    result.value = await getFileHash(filename.value.trim())
  } catch (e) {
    error.value = 'Could not reach naming server'
    result.value = null
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.desc { font-size: 11px; color: #4a6080; line-height: 1.6; margin-bottom: 14px; }

.inspect-form { display: flex; gap: 8px; }
.inspect-input {
  flex: 1;
  background: #0a0e1a;
  border: 1px solid #1e2d4a;
  color: #c8d8f0;
  padding: 9px 12px;
  border-radius: 6px;
  font-family: inherit;
  font-size: 12px;
  outline: none;
  transition: border-color 0.15s;
}
.inspect-input:focus { border-color: #4a9eff; }

.btn-inspect {
  background: #0f2a4a;
  border: 1px solid #4a9eff;
  color: #4a9eff;
  padding: 0 16px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 11px;
  letter-spacing: 1px;
  transition: all 0.15s;
}
.btn-inspect:hover:not(:disabled) { background: #1a3d6a; }
.btn-inspect:disabled { opacity: 0.4; cursor: not-allowed; }

.error-text { color: #e74c3c; font-size: 11px; margin-top: 12px; }

.result { margin-top: 16px; display: flex; flex-direction: column; gap: 8px; }
.result-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 8px 12px;
  background: #0a0e1a;
  border-radius: 6px;
  border: 1px solid #131d30;
}
.result-label {
  width: 90px;
  font-size: 10px;
  letter-spacing: 1.5px;
  text-transform: uppercase;
  color: #4a6080;
  flex-shrink: 0;
}
.result-value { font-size: 13px; color: #c8d8f0; }
.result-value.hash { color: #f39c12; font-weight: 700; font-family: monospace; }
.result-value.owner { color: #4a9eff; font-weight: 700; }
.result-value.muted { color: #4a6080; }
.owner-id { color: #4a6080; font-weight: 400; font-size: 11px; }
.result-range { font-size: 10px; color: #4a6080; }

/* mini scale */
.scale { margin-top: 6px; padding: 4px 12px 0; }
.scale-track {
  position: relative;
  height: 6px;
  background: #0a0e1a;
  border: 1px solid #1e2d4a;
  border-radius: 3px;
  margin: 14px 0 10px;
}
.scale-tick {
  position: absolute;
  top: -3px;
  width: 2px; height: 12px;
  background: #4a6080;
  transform: translateX(-1px);
}
.scale-tick.isowner { background: #4a9eff; box-shadow: 0 0 5px #4a9eff; }
.scale-file {
  position: absolute;
  top: -5px;
  width: 10px; height: 10px;
  background: #f39c12;
  border-radius: 50%;
  transform: translateX(-5px);
  box-shadow: 0 0 6px #f39c12;
}
.scale-legend { display: flex; gap: 16px; font-size: 10px; color: #4a6080; }
.scale-legend .dot {
  display: inline-block; width: 8px; height: 8px; border-radius: 50%;
  margin-right: 4px; vertical-align: middle;
}
.scale-legend .dot.node { background: #4a6080; border-radius: 1px; }
.scale-legend .dot.owner { background: #4a9eff; border-radius: 1px; }
.scale-legend .dot.file { background: #f39c12; }
</style>
