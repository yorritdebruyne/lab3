// api.js
// Central module for all REST calls made by the Vue frontend.
//
// NAMING_SERVER resolves dynamically from the browser's current origin:
//   - Locally:  http://localhost:8080  (Spring Boot serves the frontend directly)
//   - On VM:    http://143.129.43.114  (Nginx on port 80 proxies everything)
// This means no hardcoded URLs — the same build works in both environments.
const NAMING_SERVER = window.location.origin

// ── Naming server calls ──────────────────────────────────────────────────────
// These go to /api/... on the naming server. Whether running locally or through
// Nginx, NAMING_SERVER already points to the right place.

export async function getAllNodes() {
    const res = await fetch(`${NAMING_SERVER}/api/nodes`)
    return res.json()
}

export async function removeNode(name) {
    await fetch(`${NAMING_SERVER}/api/nodes/${name}`, { method: 'DELETE' })
}

// Tells the naming server to start a pre-created Docker container for a node.
// The naming server uses the Docker socket API internally to start the container.
// The node then bootstraps itself and joins the ring automatically.
// Pre-condition: the container must exist — run 'sudo docker compose create node-d' once on the VM.
export async function launchNode(serviceName) {
    const res = await fetch(`${NAMING_SERVER}/api/nodes/launch/${serviceName}`, {
        method: 'POST'
    })
    return res.json()
}

export async function stopNode(serviceName) {
    await fetch(`${NAMING_SERVER}/api/nodes/stop/${serviceName}`, { method: 'POST' })
}

// Hard-kills the container with SIGKILL (no graceful shutdown) — a real crash,
// so the ring must detect the failure and the FailureAgent recovers. Used by the
// GUI "kill" button (distinct from stopNode, which lets the node leave gracefully).
export async function killNode(serviceName) {
    await fetch(`${NAMING_SERVER}/api/nodes/kill/${serviceName}`, { method: 'POST' })
}

export async function getFileOwner(filename) {
    const res = await fetch(`${NAMING_SERVER}/api/files/owner?filename=${filename}`)
    return res.json()
}

// Returns { filename, hash, ownerId?, ownerIp?, ownerPort? } in a single call.
// The hash is computed server-side by HashService, so it always matches the
// value the ownership algorithm uses. Owner fields are absent if the ring is empty.
export async function getFileHash(filename) {
    const res = await fetch(`${NAMING_SERVER}/api/files/hash?filename=${encodeURIComponent(filename)}`)
    return res.json()
}

// ── Node calls — routed through Nginx reverse proxy ──────────────────────────
// BEFORE (broken on VM): fetch(`http://${ip}:${port}/node/state`)
//   → browser tries to open port 8081 directly → blocked by university firewall
//
// AFTER (works on VM):   fetch(`${NAMING_SERVER}/proxy/${port}/node/state`)
//   → goes to port 80 → Nginx receives it → strips /proxy/${port} prefix
//   → forwards /node/state to the correct container on the internal Docker network
//
// The "ip" parameter is kept in the signature so Vue components don't need to
// change — but it is no longer used. Nginx routes purely by port number.

export async function getNodeState(ip, port) {
    const res = await fetch(`${NAMING_SERVER}/proxy/${port}/node/state`)
    return res.json()
}

export async function getNodeLocalFiles(ip, port) {
    const res = await fetch(`${NAMING_SERVER}/proxy/${port}/node/files`)
    return res.json()
}

export async function getNodeFileList(ip, port) {
    const res = await fetch(`${NAMING_SERVER}/proxy/${port}/node/filelist`)
    return res.json()
}

// Filenames ACTUALLY held in this node's replicas/ folder (files it owns) —
// not the gossiped registry. Used by the "Replica Files" panel.
export async function getNodeReplicaFiles(ip, port) {
    const res = await fetch(`${NAMING_SERVER}/proxy/${port}/node/replicas`)
    return res.json()
}

// Uploads a file to a specific node's local/ folder (routed through Nginx).
// The node then replicates it to the hash-determined owner via the existing
// pipeline. Returns { ok, body } so the caller can show success/error.
export async function uploadFileToNode(port, file) {
    const form = new FormData()
    form.append('file', file)
    const res = await fetch(`${NAMING_SERVER}/proxy/${port}/node/upload`, {
        method: 'POST',
        body: form
    })
    let body = {}
    try { body = await res.json() } catch { /* non-JSON error body */ }
    return { ok: res.ok, status: res.status, body }
}