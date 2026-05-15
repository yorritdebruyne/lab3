<template>
  <div class="app">
    <header class="topbar">
      <div class="topbar-left">
        <div class="logo">
          <span class="logo-icon">⬡</span>
          <span class="logo-text">NETWORK MONITOR</span>
        </div>
        <span class="subtitle">Distributed Systems — University of Antwerp</span>
      </div>
      <div class="topbar-right">
        <span class="status-dot" :class="namingServerOnline ? 'online' : 'offline'"></span>
        <span class="status-label">Naming Server</span>
        <button class="btn-refresh" @click="loadAll" title="Refresh">↻</button>
      </div>
    </header>

    <div class="layout">
      <!-- Left panel: node list -->
      <aside class="sidebar">
        <div class="panel-header">
          <h2>Nodes <span class="count-badge">{{ nodes.length }}</span></h2>
        </div>
        <NodeList
            :nodes="nodes"
            :selectedId="selectedNode?.id"
            @select="selectNode"
            @remove="removeNode"
        />
      </aside>

      <!-- Right panel: details + files -->
      <main class="content">
        <NodeDetail v-if="selectedNode" :node="selectedNode" />
        <div v-else class="empty-state">
          <span class="empty-icon">⬡</span>
          <p>Select a node to view details</p>
        </div>

        <FileList v-if="selectedNode" :node="selectedNode" />
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import NodeList from './components/NodeList.vue'
import NodeDetail from './components/NodeDetail.vue'
import FileList from './components/FileList.vue'
import { getAllNodes } from './api.js'

const nodes = ref([])
const selectedNode = ref(null)
const namingServerOnline = ref(false)
let refreshTimer = null

async function loadAll() {
  try {
    const data = await getAllNodes()
    nodes.value = data
    namingServerOnline.value = true
    // If selected node still exists, refresh it
    if (selectedNode.value) {
      const updated = data.find(n => n.id === selectedNode.value.id)
      if (updated) selectedNode.value = updated
      else selectedNode.value = null
    }
  } catch (e) {
    namingServerOnline.value = false
    console.error('Could not reach naming server:', e)
  }
}

function selectNode(node) {
  selectedNode.value = node
}

async function removeNode(node) {
  if (!confirm(`Remove node "${node.name}" from the ring?`)) return
  try {
    await fetch(`http://localhost:8080/api/nodes/${node.name}`, { method: 'DELETE' })
    if (selectedNode.value?.id === node.id) selectedNode.value = null
    await loadAll()
  } catch (e) {
    alert('Could not remove node: ' + e.message)
  }
}

onMounted(() => {
  loadAll()
  refreshTimer = setInterval(loadAll, 5000) // auto-refresh every 5s
})

onUnmounted(() => {
  clearInterval(refreshTimer)
})
</script>

<style>
/* ── Global reset ── */
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

body {
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  background: #0a0e1a;
  color: #c8d8f0;
  min-height: 100vh;
}

/* ── Layout ── */
.app { display: flex; flex-direction: column; height: 100vh; overflow: hidden; }

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  height: 56px;
  background: #0d1221;
  border-bottom: 1px solid #1e2d4a;
  flex-shrink: 0;
}

.topbar-left { display: flex; align-items: center; gap: 20px; }
.topbar-right { display: flex; align-items: center; gap: 10px; }

.logo { display: flex; align-items: center; gap: 10px; }
.logo-icon { font-size: 22px; color: #4a9eff; }
.logo-text {
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 3px;
  color: #4a9eff;
}

.subtitle { font-size: 11px; color: #4a6080; letter-spacing: 1px; }

.status-dot {
  width: 8px; height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.status-dot.online { background: #2ecc71; box-shadow: 0 0 6px #2ecc71; }
.status-dot.offline { background: #e74c3c; box-shadow: 0 0 6px #e74c3c; }
.status-label { font-size: 11px; color: #4a6080; }

.btn-refresh {
  background: none;
  border: 1px solid #1e2d4a;
  color: #4a9eff;
  width: 28px; height: 28px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 16px;
  transition: all 0.2s;
}
.btn-refresh:hover { background: #1e2d4a; border-color: #4a9eff; }

.layout { display: flex; flex: 1; overflow: hidden; }

.sidebar {
  width: 280px;
  flex-shrink: 0;
  background: #0d1221;
  border-right: 1px solid #1e2d4a;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.content {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.panel-header {
  padding: 16px 20px;
  border-bottom: 1px solid #1e2d4a;
}
.panel-header h2 {
  font-size: 11px;
  letter-spacing: 2px;
  text-transform: uppercase;
  color: #4a6080;
  display: flex;
  align-items: center;
  gap: 8px;
}

.count-badge {
  background: #1e2d4a;
  color: #4a9eff;
  padding: 2px 7px;
  border-radius: 10px;
  font-size: 11px;
}

.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: #2a3d5a;
}
.empty-icon { font-size: 48px; }
.empty-state p { font-size: 13px; letter-spacing: 1px; }

/* ── Shared card style ── */
.card {
  background: #0d1221;
  border: 1px solid #1e2d4a;
  border-radius: 8px;
  padding: 20px;
}
.card-title {
  font-size: 10px;
  letter-spacing: 2px;
  text-transform: uppercase;
  color: #4a6080;
  margin-bottom: 16px;
}
</style>