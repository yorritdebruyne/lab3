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

    <!-- Add Node Modal -->
    <div v-if="showAddModal" class="modal-backdrop" @click.self="showAddModal = false">
      <div class="modal">
        <div class="modal-header">
          <span class="modal-title">Add Node</span>
          <button class="btn-close" @click="showAddModal = false">✕</button>
        </div>
        <div class="modal-body">
          <p class="modal-desc">Select a node to launch. The node will start, register itself, and join the ring automatically.</p>

          <p v-if="addableNodes.length === 0" class="modal-desc">All nodes are already in the ring.</p>

          <!-- One row per launchable node not currently in the ring -->
          <div
            v-for="n in addableNodes"
            :key="n.service"
            class="launch-row"
            :class="{ launching: addLoading && launchTarget === n.service }"
          >
            <div class="launch-info">
              <span class="launch-name">{{ n.name }}</span>
              <span class="launch-meta">port {{ n.port }} · TCP {{ n.tcp }}</span>
            </div>
            <button
              class="btn-launch"
              :disabled="addLoading"
              @click="submitAddNode(n.service)"
            >
              {{ addLoading && launchTarget === n.service ? 'Starting...' : '▶ Start' }}
            </button>
          </div>

          <div v-if="addError" class="add-error">{{ addError }}</div>
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="showAddModal = false">Close</button>
        </div>
      </div>
    </div>

    <div class="layout">
      <!-- Left panel: node list -->
      <aside class="sidebar">
        <div class="panel-header">
          <h2>Nodes <span class="count-badge">{{ nodes.length }}</span></h2>
          <button class="btn-add" @click="showAddModal = true" title="Add node">+ Add</button>
        </div>
        <NodeList
            :nodes="nodes"
            :selectedId="selectedNode?.id"
            :crashing="crashingNodes"
            @select="selectNode"
            @remove="removeNode"
            @kill="killNode"
        />
      </aside>

      <!-- Right panel: system tools + node details + files -->
      <main class="content">
        <!-- System-wide tools (always visible) -->
        <Topology :nodes="nodes" :crashing="crashingNodes" :selectedId="selectedNode?.id" @select="selectNode" />
        <EventFeed />
        <FileUpload :targetNode="selectedNode" @uploaded="onFileUploaded" />
        <LoadChart :nodes="nodes" />
        <HashInspector :nodes="nodes" />

        <!-- Per-node detail (when a node is selected) -->
        <NodeDetail v-if="selectedNode" :node="selectedNode" />
        <FileList v-if="selectedNode" :node="selectedNode" :nodes="nodes" @changed="onFileUploaded" />
        <div v-else class="empty-state">
          <span class="empty-icon">⬡</span>
          <p>Select a node to view its details and files</p>
        </div>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import NodeList from './components/NodeList.vue'
import NodeDetail from './components/NodeDetail.vue'
import FileList from './components/FileList.vue'
import HashInspector from './components/HashInspector.vue'
import LoadChart from './components/LoadChart.vue'
import Topology from './components/Topology.vue'
import EventFeed from './components/EventFeed.vue'
import FileUpload from './components/FileUpload.vue'
import { getAllNodes, removeNode as apiRemoveNode, launchNode, stopNode, killNode as apiKillNode } from './api.js'

const nodes = ref([])
const selectedNode = ref(null)
const namingServerOnline = ref(false)
let refreshTimer = null

// Names of nodes that were hard-killed and not yet dropped from the ring.
// Kept so the UI shows them "crashing" until the failure is detected.
const crashingNodes = ref([])

// Add node modal state
const showAddModal = ref(false)
const addLoading = ref(false)
const addError = ref(null)
const launchTarget = ref(null)

// All nodes that can be launched or re-launched from the GUI.
const launchableNodes = [
  { service: 'node-a', name: 'nodeA',       port: 8081, tcp: 9001 },
  { service: 'node-b', name: 'nodeBeta',    port: 8082, tcp: 9002 },
  { service: 'node-c', name: 'nodeGamma',   port: 8083, tcp: 9003 },
  { service: 'node-d', name: 'nodeDelta',   port: 8084, tcp: 9004 },
  { service: 'node-e', name: 'nodeEpsilon', port: 8085, tcp: 9005 },
  { service: 'node-f', name: 'nodeZeta',    port: 8086, tcp: 9006 }
]

// Only show nodes that are not currently in the ring.
const addableNodes = computed(() =>
  launchableNodes.filter(n => !nodes.value.some(node => node.name === n.name))
)

async function loadAll() {
  try {
    const data = await getAllNodes()
    nodes.value = data
    namingServerOnline.value = true
    // Drop any "crashing" markers for nodes the ring has already removed —
    // i.e. the failure was detected and recovery is done.
    crashingNodes.value = crashingNodes.value.filter(
      name => data.some(n => n.name === name)
    )
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

// After an upload, give the node a moment to replicate over TCP, then refresh
// so the new file appears on the ring and in the load chart.
function onFileUploaded() {
  setTimeout(loadAll, 1200)
}

async function removeNode(node) {
  if (!confirm(`Remove node "${node.name}" from the ring?`)) return
  try {
    await apiRemoveNode(node.name)
    const launchable = launchableNodes.find(n => n.name === node.name)
    if (launchable) await stopNode(launchable.service).catch(() => {})
    if (selectedNode.value?.id === node.id) selectedNode.value = null
    await loadAll()
  } catch (e) {
    alert('Could not remove node: ' + e.message)
  }
}

// Hard-kill a node to simulate a crash. We deliberately do NOT call the graceful
// DELETE /api/nodes here: we stop the container and let the ring DETECT the
// failure itself (PingScheduler → FailureHandler → FailureAgent).
async function killNode(node) {
  const svc = launchableNodes.find(n => n.name === node.name)
  if (!svc) {
    alert(`No Docker service is known for "${node.name}", so it can't be killed from the GUI.`)
    return
  }
  if (!confirm(`Hard-kill "${node.name}"? This simulates a crash — the ring must detect and recover on its own.`)) return
  try {
    await apiKillNode(svc.service)   // SIGKILL — a real crash, so the FailureAgent runs (not a graceful stop)
    if (!crashingNodes.value.includes(node.name)) crashingNodes.value.push(node.name)
  } catch (e) {
    alert('Could not kill node: ' + e.message)
  }
}

async function submitAddNode(serviceName) {
  addError.value = null
  addLoading.value = true
  launchTarget.value = serviceName
  try {
    const result = await launchNode(serviceName)
    if (result.error) {
      addError.value = result.error
      return
    }
    showAddModal.value = false
    // Wait a moment for the node to bootstrap and register itself
    setTimeout(loadAll, 3000)
  } catch (e) {
    addError.value = 'Launch failed: ' + e.message
  } finally {
    addLoading.value = false
    launchTarget.value = null
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
  padding: 12px 20px;
  border-bottom: 1px solid #1e2d4a;
  display: flex;
  align-items: center;
  justify-content: space-between;
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

.btn-add {
  background: #0f2a4a;
  border: 1px solid #4a9eff;
  color: #4a9eff;
  font-size: 10px;
  padding: 3px 10px;
  border-radius: 5px;
  cursor: pointer;
  letter-spacing: 1px;
  transition: all 0.15s;
}
.btn-add:hover { background: #1a3d6a; }

/* Modal */
.modal-backdrop {
  position: fixed; inset: 0;
  background: rgba(0,0,0,0.7);
  display: flex; align-items: center; justify-content: center;
  z-index: 100;
}
.modal {
  background: #0d1221;
  border: 1px solid #1e2d4a;
  border-radius: 10px;
  width: 440px;
  max-width: 95vw;
}
.modal-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #1e2d4a;
}
.modal-title { font-size: 12px; letter-spacing: 2px; text-transform: uppercase; color: #4a9eff; }
.btn-close { background: none; border: none; color: #4a6080; cursor: pointer; font-size: 14px; }
.btn-close:hover { color: #e74c3c; }

.modal-body { padding: 20px; display: flex; flex-direction: column; gap: 12px; }

.form-row { display: flex; flex-direction: column; gap: 5px; }
.form-row label { font-size: 10px; letter-spacing: 1.5px; text-transform: uppercase; color: #4a6080; }
.form-row input {
  background: #0a0e1a;
  border: 1px solid #1e2d4a;
  color: #c8d8f0;
  padding: 8px 12px;
  border-radius: 6px;
  font-family: inherit;
  font-size: 12px;
  outline: none;
  transition: border-color 0.15s;
}
.form-row input:focus { border-color: #4a9eff; }

.modal-hint {
  font-size: 10px;
  color: #4a6080;
  background: #0a0e1a;
  border: 1px solid #131d30;
  border-radius: 6px;
  padding: 10px;
  line-height: 1.7;
}
.modal-hint code {
  display: block;
  margin-top: 4px;
  color: #8bc34a;
  word-break: break-all;
  font-size: 10px;
}

.modal-desc { font-size: 11px; color: #4a6080; margin-bottom: 4px; }

.launch-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 14px;
  background: #0a0e1a;
  border: 1px solid #1e2d4a;
  border-radius: 6px;
  transition: border-color 0.15s;
}
.launch-row:hover { border-color: #4a9eff; }
.launch-row.launching { border-color: #f39c12; }

.launch-info { display: flex; flex-direction: column; gap: 3px; }
.launch-name { font-size: 13px; color: #c8d8f0; font-weight: 600; }
.launch-meta { font-size: 10px; color: #4a6080; letter-spacing: 1px; }

.btn-launch {
  background: #0f2a4a; border: 1px solid #4a9eff; color: #4a9eff;
  padding: 6px 14px; border-radius: 5px; cursor: pointer;
  font-size: 11px; letter-spacing: 1px;
  transition: all 0.15s;
}
.btn-launch:hover:not(:disabled) { background: #1a3d6a; }
.btn-launch:disabled { opacity: 0.4; cursor: not-allowed; }

.add-error { font-size: 11px; color: #e74c3c; }

.modal-footer {
  display: flex; justify-content: flex-end; gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid #1e2d4a;
}
.btn-cancel {
  background: none; border: 1px solid #1e2d4a; color: #4a6080;
  padding: 7px 16px; border-radius: 6px; cursor: pointer; font-size: 11px;
}
.btn-cancel:hover { border-color: #4a6080; color: #c8d8f0; }
.btn-confirm {
  background: #0f2a4a; border: 1px solid #4a9eff; color: #4a9eff;
  padding: 7px 16px; border-radius: 6px; cursor: pointer; font-size: 11px;
  letter-spacing: 1px;
}
.btn-confirm:hover:not(:disabled) { background: #1a3d6a; }
.btn-confirm:disabled { opacity: 0.4; cursor: not-allowed; }

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