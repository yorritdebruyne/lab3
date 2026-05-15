<template>
  <div class="node-list">
    <div
        v-for="node in nodes"
        :key="node.id"
        class="node-item"
        :class="{ selected: node.id === selectedId }"
        @click="$emit('select', node)"
    >
      <div class="node-item-left">
        <span class="node-status-dot online"></span>
        <div class="node-info">
          <span class="node-name">{{ node.name }}</span>
          <span class="node-meta">ID {{ node.id }}</span>
        </div>
      </div>
      <div class="node-item-right">
        <span class="node-ip">:{{ node.port }}</span>
        <button
            class="btn-remove"
            @click.stop="$emit('remove', node)"
            title="Remove node"
        >✕</button>
      </div>
    </div>

    <div v-if="nodes.length === 0" class="no-nodes">
      No nodes registered
    </div>
  </div>
</template>

<script setup>
defineProps({
  nodes: { type: Array, default: () => [] },
  selectedId: { type: Number, default: null }
})
defineEmits(['select', 'remove'])
</script>

<style scoped>
.node-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.node-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
  border: 1px solid transparent;
  transition: all 0.15s;
}
.node-item:hover { background: #131d30; border-color: #1e2d4a; }
.node-item.selected { background: #0f1e38; border-color: #4a9eff; }

.node-item-left { display: flex; align-items: center; gap: 10px; }
.node-item-right { display: flex; align-items: center; gap: 6px; }

.node-status-dot {
  width: 7px; height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}
.node-status-dot.online { background: #2ecc71; box-shadow: 0 0 5px #2ecc71; }

.node-info { display: flex; flex-direction: column; gap: 2px; }
.node-name { font-size: 13px; color: #c8d8f0; font-weight: 600; }
.node-meta { font-size: 10px; color: #4a6080; }

.node-ip { font-size: 10px; color: #4a6080; }

.btn-remove {
  background: none;
  border: none;
  color: #4a6080;
  cursor: pointer;
  font-size: 11px;
  width: 20px; height: 20px;
  border-radius: 4px;
  transition: all 0.15s;
  display: flex; align-items: center; justify-content: center;
}
.btn-remove:hover { background: #3d1515; color: #e74c3c; }

.no-nodes {
  text-align: center;
  padding: 32px 16px;
  font-size: 11px;
  color: #2a3d5a;
  letter-spacing: 1px;
}
</style>