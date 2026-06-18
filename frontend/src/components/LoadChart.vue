<template>
  <div class="card">
    <div class="card-title">
      Load Distribution
      <button class="btn-mini" @click="load" :disabled="loading" title="Refresh counts">↻</button>
    </div>
    <p class="desc">Files owned per node (from each node's <code>replicas/</code> folder). Shows how evenly the hash spreads ownership.</p>

    <div v-if="error" class="error-text">{{ error }}</div>
    <div v-else-if="rows.length === 0" class="muted">No nodes in the ring.</div>

    <div v-else class="bars">
      <div v-for="r in rows" :key="r.id" class="bar-row">
        <span class="bar-name">{{ r.name }}</span>
        <div class="bar-track">
          <div class="bar-fill" :style="{ width: barWidth(r.owned) + '%' }"></div>
        </div>
        <span class="bar-count">{{ r.owned }}</span>
      </div>

      <div class="summary">
        <span>{{ totalFiles }} files</span>
        <span>·</span>
        <span>{{ rows.length }} nodes</span>
        <span>·</span>
        <span>avg {{ avg }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { getNodeReplicaFiles } from '../api.js'

const props = defineProps({
  nodes: { type: Array, default: () => [] }
})

const rows = ref([])
const loading = ref(false)
const error = ref(null)

const totalFiles = computed(() => rows.value.reduce((s, r) => s + r.owned, 0))
const maxOwned = computed(() => Math.max(1, ...rows.value.map(r => r.owned)))
const avg = computed(() =>
  rows.value.length ? (totalFiles.value / rows.value.length).toFixed(1) : '0'
)

function barWidth(owned) {
  return (owned / maxOwned.value) * 100
}

async function load() {
  loading.value = true
  error.value = null
  try {
    rows.value = await Promise.all(
      props.nodes.map(async n => {
        let owned = 0
        try {
          const files = await getNodeReplicaFiles(n.ip, n.port) // files this node OWNS (replicas/)
          owned = Array.isArray(files) ? files.length : 0
        } catch {
          owned = 0 // node unreachable → counts as 0, don't fail the whole chart
        }
        return { id: n.id, name: n.name, owned }
      })
    )
  } catch (e) {
    error.value = 'Could not load file counts'
  } finally {
    loading.value = false
  }
}

// Reload whenever the node set changes (add/remove/kill).
watch(() => props.nodes.map(n => n.id).join(','), load, { immediate: true })
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; }
.btn-mini {
  background: none; border: 1px solid #1e2d4a; color: #4a9eff;
  width: 22px; height: 22px; border-radius: 5px; cursor: pointer; font-size: 12px;
}
.btn-mini:hover:not(:disabled) { background: #1e2d4a; }
.btn-mini:disabled { opacity: 0.4; cursor: not-allowed; }

.desc { font-size: 11px; color: #4a6080; line-height: 1.6; margin-bottom: 14px; }
.desc code { color: #8bc34a; }
.error-text { color: #e74c3c; font-size: 11px; }
.muted { color: #4a6080; font-size: 11px; }

.bars { display: flex; flex-direction: column; gap: 8px; }
.bar-row { display: flex; align-items: center; gap: 10px; }
.bar-name {
  width: 90px; flex-shrink: 0;
  font-size: 12px; color: #c8d8f0;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.bar-track {
  flex: 1;
  height: 14px;
  background: #0a0e1a;
  border: 1px solid #131d30;
  border-radius: 4px;
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  background: #4a9eff;
  border-radius: 3px 0 0 3px;
  transition: width 0.4s ease;
  min-width: 2px;
}
.bar-count {
  width: 28px; flex-shrink: 0; text-align: right;
  font-size: 12px; color: #f39c12; font-weight: 700; font-family: monospace;
}

.summary {
  display: flex; gap: 8px;
  margin-top: 12px; padding-top: 10px;
  border-top: 1px solid #131d30;
  font-size: 10px; color: #4a6080; letter-spacing: 1px;
}
</style>
