<template>
  <div class="card">
    <div class="card-title">
      Ring Topology
      <span class="topo-meta">{{ nodes.length }} nodes · {{ fileDots.length }} files</span>
    </div>

    <div v-if="nodes.length === 0" class="topo-empty">No nodes in the ring.</div>

    <svg v-else class="topo-svg" :viewBox="`0 0 ${SIZE} ${SIZE}`" role="img"
         aria-label="Consistent-hashing ring topology">
      <defs>
        <marker id="arrow" markerWidth="9" markerHeight="9" refX="7" refY="3"
                orient="auto" markerUnits="userSpaceOnUse">
          <path d="M0,0 L7,3 L0,6 Z" fill="#3a4d6f" />
        </marker>
      </defs>

      <!-- the ring -->
      <circle :cx="CX" :cy="CY" :r="R" class="ring" />
      <text :x="CX" :y="CY - R - 8" class="scale-text" text-anchor="middle">0 / {{ RING_MAX }}</text>

      <!-- next-pointer edges as arcs that follow the ring (sorted by id, wrapping) -->
      <path v-for="e in edges" :key="e.key" :d="e.d"
            class="edge" fill="none" marker-end="url(#arrow)" />

      <!-- files as dots on the ring, coloured by owning node -->
      <circle v-for="f in fileDots" :key="f.filename"
              :cx="point(f.hash).x" :cy="point(f.hash).y" r="5"
              class="file-dot" :style="{ fill: colorForNode(f.ownerId) }">
        <title>{{ f.filename }} — hash {{ f.hash }} → owner {{ ownerName(f.ownerId) }}</title>
      </circle>

      <!-- nodes (positions decluttered so near-identical hashes don't overlap) -->
      <g v-for="item in nodeLayout" :key="item.n.id"
         class="node-g" :class="{ crashing: isCrashing(item.n), selected: item.n.id === selectedId }"
         :transform="`translate(${polar(item.deg).x}, ${polar(item.deg).y})`"
         @click="$emit('select', item.n)">
        <circle r="15" class="node-marker"
                :style="{ stroke: isCrashing(item.n) ? '#e74c3c' : colorForNode(item.n.id) }" />
        <text class="node-name" y="-22" text-anchor="middle">{{ item.n.name }}</text>
        <text class="node-id" y="30" text-anchor="middle">{{ item.n.id }}</text>
      </g>
    </svg>

    <div v-if="nodes.length" class="topo-legend">
      <span><i class="lg ring-dot"></i> ring position = hash</span>
      <span><i class="lg arrow-dot"></i> next pointer</span>
      <span><i class="lg file-dot-lg"></i> file (colour = owner)</span>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { getNodeLocalFiles, getFileHash } from '../api.js'

const props = defineProps({
  nodes:      { type: Array,  default: () => [] },
  crashing:   { type: Array,  default: () => [] },
  selectedId: { type: Number, default: null }
})
defineEmits(['select'])

// ── geometry ──────────────────────────────────────────────────────────────
const SIZE = 440, CX = SIZE / 2, CY = SIZE / 2, R = 160, RING_MAX = 32768

// id=0 at the top (12 o'clock), increasing clockwise
function angle(id) { return (id / RING_MAX) * 2 * Math.PI - Math.PI / 2 }
function point(id, radius = R) {
  return { x: CX + radius * Math.cos(angle(id)),
           y: CY + radius * Math.sin(angle(id)) }
}
// degrees variant + polar point, used to draw the next-pointer arcs along the ring
function ringAngleDeg(id) { return (id / RING_MAX) * 360 - 90 }
function polar(deg, radius = R) {
  const r = deg * Math.PI / 180
  return { x: CX + radius * Math.cos(r), y: CY + radius * Math.sin(r) }
}

// Stable, distinct colour per node id (golden-angle hue).
function colorForNode(id) {
  if (id == null) return '#4a6080'
  const hue = (Math.abs(id) * 137.508) % 360
  return `hsl(${hue.toFixed(0)}, 65%, 62%)`
}

function isCrashing(n) { return props.crashing.includes(n.name) }
function ownerName(id) {
  const o = props.nodes.find(n => n.id === id)
  return o ? o.name : (id == null ? 'none' : `ID ${id}`)
}

// ── declutter: spread nodes whose hashes land almost on the same spot ──────────
// Node angle = hash, but two nodes with near-identical hashes would render on top
// of each other. We group consecutive nodes closer than MIN_SEP and fan them out
// around their average angle so every marker + label stays readable. The nudge is
// a few degrees at most and only affects genuinely-colliding nodes.
const MIN_SEP = 12 // degrees
const nodeLayout = computed(() => {
  const sorted = [...props.nodes].sort((a, b) => a.id - b.id)
  const out = sorted.map(n => ({ n, deg: ringAngleDeg(n.id) }))
  let i = 0
  while (i < out.length) {
    let j = i
    while (j + 1 < out.length && (out[j + 1].deg - out[j].deg) < MIN_SEP) j++
    const k = j - i + 1
    if (k > 1) {
      const mean = out.slice(i, j + 1).reduce((s, o) => s + o.deg, 0) / k
      for (let t = i; t <= j; t++) out[t].deg = mean + (t - i - (k - 1) / 2) * MIN_SEP
    }
    i = j + 1
  }
  return out
})

// ── next-pointer edges: arcs that follow the ring, clockwise, wrapping last→first ──
const edges = computed(() => {
  const L = nodeLayout.value
  if (L.length < 2) return []
  const PAD = 5 // degrees of gap so the arc doesn't run under the node markers
  const out = []
  for (let i = 0; i < L.length; i++) {
    const from = L[i], to = L[(i + 1) % L.length]
    let spanDeg = (to.deg - from.deg) % 360
    if (spanDeg < 0) spanDeg += 360            // clockwise span 0..360
    const start = polar(from.deg + PAD)
    const end   = polar(to.deg   - PAD)
    const largeArc = spanDeg > 180 ? 1 : 0     // wrap-around edge takes the long way
    out.push({
      key: from.n.id + '->' + to.n.id,
      d: `M ${start.x} ${start.y} A ${R} ${R} 0 ${largeArc} 1 ${end.x} ${end.y}`
    })
  }
  return out
})

// ── file dots: gather every node's local files, resolve hash + owner ──────────
const fileDots = ref([])

async function loadFiles() {
  if (props.nodes.length === 0) { fileDots.value = []; return }
  const names = new Set()
  await Promise.all(props.nodes.map(async n => {
    try { (await getNodeLocalFiles(n.ip, n.port)).forEach(f => names.add(f)) } catch { /* node unreachable */ }
  }))
  const dots = []
  await Promise.all([...names].map(async f => {
    try {
      const r = await getFileHash(f)            // { filename, hash, ownerId? }
      if (r && r.hash != null) dots.push({ filename: f, hash: r.hash, ownerId: r.ownerId })
    } catch { /* skip */ }
  }))
  fileDots.value = dots
}

// Re-resolve whenever the set of nodes changes (add / remove / kill).
watch(() => props.nodes.map(n => n.id).join(','), loadFiles, { immediate: true })
</script>

<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; }
.topo-meta { font-size: 10px; color: #4a6080; letter-spacing: 1px; text-transform: none; }

.topo-empty { font-size: 11px; color: #4a6080; padding: 20px 0; text-align: center; }

.topo-svg { width: 100%; max-width: 440px; height: auto; display: block; margin: 4px auto 0; }

.ring { fill: none; stroke: #1e2d4a; stroke-width: 2; }
.scale-text { fill: #4a6080; font-size: 10px; }

.edge { stroke: #3a4d6f; stroke-width: 1.6; opacity: 0.8; transition: all 0.4s ease; }

.file-dot { stroke: #0a0e1a; stroke-width: 1; transition: cx 0.4s ease, cy 0.4s ease; }

.node-g { cursor: pointer; transition: transform 0.4s ease; }
.node-marker {
  fill: #0d1221;
  stroke-width: 3;
  transition: stroke 0.2s ease;
}
.node-g:hover .node-marker { fill: #15203a; }
.node-g.selected .node-marker { fill: #0f1e38; }
.node-name { fill: #c8d8f0; font-size: 12px; font-weight: 600; }
.node-id   { fill: #4a6080; font-size: 10px; font-family: monospace; }

.node-g.crashing .node-name { fill: #e74c3c; }
.node-g.crashing .node-marker { animation: topo-pulse 0.8s ease-in-out infinite; }
@keyframes topo-pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.35; } }

.topo-legend {
  display: flex; flex-wrap: wrap; gap: 16px; justify-content: center;
  margin-top: 12px; padding-top: 10px; border-top: 1px solid #131d30;
  font-size: 10px; color: #4a6080;
}
.topo-legend .lg {
  display: inline-block; width: 10px; height: 10px; margin-right: 5px; vertical-align: middle;
}
.topo-legend .ring-dot  { border: 2px solid #4a6080; border-radius: 50%; background: #0d1221; }
.topo-legend .arrow-dot { width: 0; height: 0; border-left: 8px solid #3a4d6f;
                          border-top: 4px solid transparent; border-bottom: 4px solid transparent; }
.topo-legend .file-dot-lg { background: #4a9eff; border-radius: 50%; }
</style>
