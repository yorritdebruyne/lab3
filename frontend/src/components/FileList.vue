<template>
  <div class="card">
    <div class="card-title">Files on {{ node.name }}</div>

    <div class="file-sections">
      <!-- Local files -->
      <div class="file-section">
        <div class="section-header">
          <span class="section-title">Local Files</span>
          <span class="count-badge">{{ localFiles.length }}</span>
        </div>
        <div v-if="localFiles.length === 0" class="no-files">No local files</div>
        <template v-for="file in localFiles" :key="file">
          <div class="file-row">
            <span class="file-icon">📄</span>
            <span class="file-name">{{ file }}</span>
            <span class="file-tag local">local</span>
            <span class="actions">
              <button class="act" title="Download to my computer"
                      :disabled="busy === file" @click="onDownload(file)">⬇</button>
              <button class="act" title="Send to another node"
                      :disabled="busy === file || otherNodes.length === 0" @click="toggleSend(file)">➤</button>
              <button class="act danger" title="Delete from system"
                      :disabled="busy === file" @click="onDelete(file)">🗑</button>
            </span>
          </div>
          <!-- Inline "send to" picker under the file being sent -->
          <div v-if="sendFor === file" class="send-bar">
            <span class="send-label">Send to:</span>
            <select v-model="sendTargetPort" class="send-select">
              <option v-for="t in otherNodes" :key="t.id" :value="t.port">{{ t.name }}</option>
            </select>
            <button class="send-go" :disabled="!sendTargetPort || busy === file" @click="onSend(file)">Send</button>
            <button class="send-cancel" @click="sendFor = null">✕</button>
          </div>
        </template>
      </div>

      <!-- Replica files -->
      <div class="file-section">
        <div class="section-header">
          <span class="section-title">Replica Files</span>
          <span class="count-badge">{{ replicaFiles.length }}</span>
        </div>
        <div v-if="replicaFiles.length === 0" class="no-files">No replica files</div>
        <div v-for="entry in replicaFiles" :key="entry.filename" class="file-row">
          <span class="file-icon">📋</span>
          <span class="file-name">{{ entry.filename }}</span>
          <span v-if="entry.locked" class="file-tag locked">🔒 locked</span>
          <span v-else class="file-tag replica">replica</span>
          <span class="actions">
            <button class="act" title="Download to my computer"
                    :disabled="busy === entry.filename" @click="onDownload(entry.filename)">⬇</button>
          </span>
        </div>
      </div>
    </div>

    <!-- Fixed-height status footer: always present so toggling a message never
         changes the card height (no layout jump on auto-refresh). -->
    <div class="status-footer">
      <span v-if="actionMsg" class="action-msg" :class="{ err: actionErr }">{{ actionMsg }}</span>
      <span v-else-if="loading" class="loading-text">Loading files...</span>
      <span v-else-if="error" class="error-text">{{ error }}</span>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import {
  getNodeLocalFiles, getNodeFileList, getNodeReplicaFiles,
  downloadFileFromNode, deleteFile, sendFileToNode
} from '../api.js'

const props = defineProps({
  node: { type: Object, required: true },
  nodes: { type: Array, default: () => [] }
})
const emit = defineEmits(['changed'])

const localFiles = ref([])
const replicaFiles = ref([])
const loading = ref(false)
const error = ref(null)
// Only show the "Loading files..." line on the FIRST load for a node. Background
// refreshes (the 5s auto-refresh) reuse the data already on screen, so the card
// height never changes and the layout doesn't jump.
const hasLoadedOnce = ref(false)

// Per-file action state
const busy = ref(null)          // filename currently being acted on (disables its buttons)
const sendFor = ref(null)       // filename whose "send to" picker is open
const sendTargetPort = ref(null)
const actionMsg = ref('')
const actionErr = ref(false)

// Candidate targets for "send to another node" = every node except this one.
const otherNodes = computed(() => props.nodes.filter(n => n.id !== props.node.id))

function flash(msg, isErr = false) {
  actionMsg.value = msg
  actionErr.value = isErr
  setTimeout(() => { if (actionMsg.value === msg) actionMsg.value = '' }, 3000)
}

async function onDownload(filename) {
  busy.value = filename
  try {
    await downloadFileFromNode(props.node.port, filename)
  } catch (e) {
    flash(`Download failed: ${e.message}`, true)
  } finally {
    busy.value = null
  }
}

async function onDelete(filename) {
  if (!confirm(`Delete "${filename}" from the system? This removes the original and its replica.`)) return
  busy.value = filename
  try {
    const { ok } = await deleteFile(props.node.port, filename)
    if (ok) { flash(`Deleted ${filename}`); await loadFiles(); emit('changed') }
    else flash(`Delete failed for ${filename}`, true)
  } catch (e) {
    flash(`Delete failed: ${e.message}`, true)
  } finally {
    busy.value = null
  }
}

function toggleSend(filename) {
  sendFor.value = sendFor.value === filename ? null : filename
  sendTargetPort.value = otherNodes.value[0]?.port ?? null
}

async function onSend(filename) {
  if (!sendTargetPort.value) return
  busy.value = filename
  try {
    const { ok } = await sendFileToNode(props.node.port, filename, sendTargetPort.value)
    if (ok) {
      const target = otherNodes.value.find(n => n.port === sendTargetPort.value)
      flash(`Sent ${filename} → ${target?.name ?? 'node'}`)
      sendFor.value = null
      emit('changed')
    } else {
      flash(`Send failed for ${filename}`, true)
    }
  } catch (e) {
    flash(`Send failed: ${e.message}`, true)
  } finally {
    busy.value = null
  }
}

async function loadFiles() {
  if (!hasLoadedOnce.value) loading.value = true   // spinner only on first load
  error.value = null
  try {
    // Local files — simple string list from GET /node/files
    localFiles.value = await getNodeLocalFiles(props.node.ip, props.node.port)

    // Replica files — the filenames ACTUALLY in this node's replicas/ folder
    // (GET /node/replicas), i.e. the files this node owns and physically holds.
    // NOTE: we do NOT derive this from /node/filelist (the gossiped FileRegistry),
    // because that lists every filename known system-wide — which would make every
    // node look like it holds every file. Lock state is cross-referenced from the
    // registry so the 🔒 badge still works.
    const replicaNames = await getNodeReplicaFiles(props.node.ip, props.node.port)
    const registry = await getNodeFileList(props.node.ip, props.node.port)
    const lockedSet = new Set(registry.filter(e => e.locked).map(e => e.filename))
    replicaFiles.value = replicaNames.map(name => ({ filename: name, locked: lockedSet.has(name) }))
  } catch (e) {
    error.value = 'Could not load files from node'
    localFiles.value = []
    replicaFiles.value = []
  } finally {
    loading.value = false
    hasLoadedOnce.value = true
  }
}

// Key on the node ID, NOT the object reference: the parent's 5s auto-refresh
// reassigns selectedNode to a fresh object (same node), and reacting to that
// would reset the spinner and jump the layout every cycle. We only treat it as a
// real "switch" when the id changes; same-node refreshes reload files silently.
watch(() => props.node?.id, (newId, oldId) => {
  if (newId !== oldId) {
    sendFor.value = null
    busy.value = null
    actionMsg.value = ''
    hasLoadedOnce.value = false
  }
  loadFiles()
}, { immediate: true })

// Silent periodic refresh: keeps the file lists current (e.g. a file replicated
// to this node) WITHOUT ever showing the spinner — hasLoadedOnce is already true,
// so loadFiles() updates the data in place and the layout never jumps.
let refreshTimer = null
onMounted(() => {
  refreshTimer = setInterval(() => { if (hasLoadedOnce.value) loadFiles() }, 5000)
})
onUnmounted(() => { if (refreshTimer) clearInterval(refreshTimer) })
</script>

<style scoped>
.file-sections { display: flex; gap: 16px; }

.file-section {
  flex: 1;
  background: #0a0e1a;
  border: 1px solid #131d30;
  border-radius: 8px;
  padding: 14px;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid #131d30;
}

.section-title {
  font-size: 10px;
  letter-spacing: 1.5px;
  text-transform: uppercase;
  color: #4a6080;
}

.count-badge {
  background: #1e2d4a;
  color: #4a9eff;
  padding: 1px 6px;
  border-radius: 8px;
  font-size: 10px;
}

.file-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 4px;
  margin-bottom: 4px;
  transition: background 0.15s;
}
.file-row:hover { background: #0d1221; }

.file-icon { font-size: 13px; flex-shrink: 0; }

.file-name {
  font-size: 12px;
  color: #c8d8f0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-tag {
  font-size: 9px;
  padding: 1px 6px;
  border-radius: 8px;
  letter-spacing: 1px;
  flex-shrink: 0;
}
.file-tag.local { background: #0d2d1a; color: #2ecc71; border: 1px solid #1a4a2a; }
.file-tag.replica { background: #1a2d0d; color: #8bc34a; border: 1px solid #2a4a1a; }
.file-tag.locked { background: #2d1a0d; color: #f39c12; border: 1px solid #4a2a1a; }

.no-files {
  font-size: 11px;
  color: #2a3d5a;
  text-align: center;
  padding: 16px 0;
}

/* Reserved-height footer holds at most one status line, so showing/hiding a
   message keeps the card the same height (prevents the up/down jump). */
.status-footer {
  min-height: 18px;
  margin-top: 12px;
  display: flex;
  align-items: center;
}
.loading-text, .error-text, .action-msg { font-size: 11px; }
.loading-text { color: #4a6080; }
.error-text { color: #e74c3c; }

/* Per-file action buttons */
.actions { display: flex; gap: 4px; flex-shrink: 0; }
.act {
  background: none; border: 1px solid #1e2d4a; color: #4a9eff;
  width: 22px; height: 22px; border-radius: 5px; cursor: pointer;
  font-size: 11px; line-height: 1; display: flex; align-items: center; justify-content: center;
}
.act:hover:not(:disabled) { background: #1e2d4a; }
.act:disabled { opacity: 0.35; cursor: not-allowed; }
.act.danger { color: #e74c3c; border-color: #4a1e1e; }
.act.danger:hover:not(:disabled) { background: #2d1010; }

/* Inline send-to picker */
.send-bar {
  display: flex; align-items: center; gap: 6px;
  margin: 2px 0 8px 26px; padding: 6px 8px;
  background: #0d1221; border: 1px solid #1e2d4a; border-radius: 6px;
}
.send-label { font-size: 10px; color: #4a6080; }
.send-select {
  flex: 1; background: #0a0e1a; color: #c8d8f0;
  border: 1px solid #1e2d4a; border-radius: 4px; font-size: 11px; padding: 2px 4px;
}
.send-go {
  background: #1e3a5a; color: #4a9eff; border: 1px solid #2a4a6a;
  border-radius: 4px; font-size: 11px; padding: 2px 10px; cursor: pointer;
}
.send-go:hover:not(:disabled) { background: #25496f; }
.send-go:disabled { opacity: 0.4; cursor: not-allowed; }
.send-cancel {
  background: none; border: none; color: #4a6080; cursor: pointer; font-size: 12px;
}

.action-msg { color: #2ecc71; }
.action-msg.err { color: #e74c3c; }
</style>