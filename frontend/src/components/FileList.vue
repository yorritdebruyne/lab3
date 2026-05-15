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
        <div v-for="file in localFiles" :key="file" class="file-row">
          <span class="file-icon">📄</span>
          <span class="file-name">{{ file }}</span>
          <span class="file-tag local">local</span>
        </div>
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
        </div>
      </div>
    </div>

    <div v-if="loading" class="loading-text">Loading files...</div>
    <div v-if="error" class="error-text">{{ error }}</div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { getNodeLocalFiles, getNodeFileList } from '../api.js'

const props = defineProps({
  node: { type: Object, required: true }
})

const localFiles = ref([])
const replicaFiles = ref([])
const loading = ref(false)
const error = ref(null)

async function loadFiles() {
  loading.value = true
  error.value = null
  try {
    // Local files — simple string list from GET /node/files
    localFiles.value = await getNodeLocalFiles(props.node.ip, props.node.port)

    // File registry — FileEntry list from GET /node/filelist
    // These are files this node knows about (replicas + system-wide)
    // We show only entries that are NOT in localFiles as "replicas"
    const allEntries = await getNodeFileList(props.node.ip, props.node.port)
    replicaFiles.value = allEntries.filter(
        entry => !localFiles.value.includes(entry.filename)
    )
  } catch (e) {
    error.value = 'Could not load files from node'
    localFiles.value = []
    replicaFiles.value = []
  } finally {
    loading.value = false
  }
}

watch(() => props.node, loadFiles, { immediate: true })
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

.loading-text, .error-text { font-size: 11px; margin-top: 12px; }
.loading-text { color: #4a6080; }
.error-text { color: #e74c3c; }
</style>