//const NAMING_SERVER = 'http://localhost:8080'
const NAMING_SERVER = window.location.origin

export async function getAllNodes() {
    const res = await fetch(`${NAMING_SERVER}/api/nodes`)
    return res.json()
}

export async function getNodeState(ip, port) {
    const res = await fetch(`http://${ip}:${port}/node/state`)
    return res.json()
}

export async function getNodeLocalFiles(ip, port) {
    const res = await fetch(`http://${ip}:${port}/node/files`)
    return res.json()
}

export async function getNodeFileList(ip, port) {
    const res = await fetch(`http://${ip}:${port}/node/filelist`)
    return res.json()
}

export async function removeNode(name) {
    await fetch(`${NAMING_SERVER}/api/nodes/${name}`, { method: 'DELETE' })
}

export async function getFileOwner(filename) {
    const res = await fetch(`${NAMING_SERVER}/api/files/owner?filename=${filename}`)
    return res.json()
}