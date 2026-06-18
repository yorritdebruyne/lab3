<template>
  <div class="card">
    <div class="card-title">Upload File</div>

    <p class="desc">
      Drop a file (or click) to upload it to
      <strong v-if="targetNode" class="target">{{ targetNode.name }}</strong>
      <span v-else class="muted">— select a node first</span>.
      The node stores it locally and replicates it to its hash-owner over TCP.
    </p>

    <div
        class="dropzone"
        :class="{ over: dragOver, disabled: !targetNode || busy }"
        @click="!busy && targetNode && $refs.input.click()"
        @dragover.prevent="onDragOver"
        @dragleave.prevent="dragOver = false"
        @drop.prevent="onDrop"
    >
      <input ref="input" type="file" class="hidden-input" @change="onPick" />
      <span v-if="busy" class="dz-text">Uploading {{ pendingName }}…</span>
      <span v-else-if="!targetNode" class="dz-text muted">Select a node in the sidebar to enable upload</span>
      <span v-else class="dz-text">⬆ Drop a file here, or click to browse</span>
    </div>

    <div v-if="message" class="msg" :class="messageType">{{ message }}</div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { uploadFileToNode } from '../api.js'

const props = defineProps({
  // the node to upload to (the currently selected node)
  targetNode: { type: Object, default: null }
})
const emit = defineEmits(['uploaded'])

const dragOver    = ref(false)
const busy        = ref(false)
const pendingName = ref('')
const message     = ref('')
const messageType = ref('ok') // 'ok' | 'err'

function onDragOver() {
  if (props.targetNode && !busy.value) dragOver.value = true
}

function onDrop(e) {
  dragOver.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file) doUpload(file)
}

function onPick(e) {
  const file = e.target.files?.[0]
  if (file) doUpload(file)
  e.target.value = '' // allow re-uploading the same file name
}

async function doUpload(file) {
  if (!props.targetNode) {
    show('Select a node first', 'err'); return
  }
  busy.value = true
  pendingName.value = file.name
  message.value = ''
  try {
    const res = await uploadFileToNode(props.targetNode.port, file)
    if (res.ok) {
      show(`Stored "${res.body.filename || file.name}" on ${props.targetNode.name} — replicating to its owner…`, 'ok')
      emit('uploaded')
    } else {
      show(res.body.error || `Upload failed (HTTP ${res.status})`, 'err')
    }
  } catch (e) {
    show('Upload failed: ' + e.message, 'err')
  } finally {
    busy.value = false
    pendingName.value = ''
  }
}

function show(text, type) {
  message.value = text
  messageType.value = type
}
</script>

<style scoped>
.desc { font-size: 11px; color: #4a6080; line-height: 1.6; margin-bottom: 14px; }
.desc .target { color: #4a9eff; }
.desc .muted, .dz-text.muted { color: #6a7a95; }

.dropzone {
  border: 1.5px dashed #1e2d4a;
  border-radius: 8px;
  padding: 26px 16px;
  text-align: center;
  cursor: pointer;
  transition: all 0.15s;
  background: #0a0e1a;
}
.dropzone.over { border-color: #4a9eff; background: #0f1e38; }
.dropzone.disabled { cursor: not-allowed; opacity: 0.6; }

.hidden-input { display: none; }
.dz-text { font-size: 12px; color: #c8d8f0; letter-spacing: 0.5px; }

.msg { margin-top: 12px; font-size: 11px; padding: 8px 12px; border-radius: 6px; }
.msg.ok  { color: #2ecc71; background: #0d2d1a; border: 1px solid #14532b; }
.msg.err { color: #e74c3c; background: #2d1313; border: 1px solid #5c2020; }
</style>
