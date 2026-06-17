# System Y — Distributed File System

A ring-based distributed file system built for the **Distributed Systems** course
(6th semester Applied Engineering, University of Antwerp).

The project is delivered as a **single Spring Boot JAR** that runs in one of two
roles, selected by Spring profile:

| Profile | Role | Default port |
|---------|------|--------------|
| `naming-server` | Central registry: maps files/nodes onto the ring, resolves owners | 8080 |
| `node` | A ring node: stores files, replicates, runs agents | 8081+ (configurable) |

It covers the full lab sequence:

- **Lab 3** — Naming Server (consistent hashing + ownership resolution)
- **Lab 4** — Node lifecycle (discovery, bootstrap, shutdown, failure detection)
- **Lab 5** — File replication (TCP transfer + file logs)
- **Lab 6** — Mobile agents (SyncAgent, FailureAgent)
- **Lab 7** — Vue GUI + Nginx reverse proxy + Docker deployment

---

## 1. Architecture overview

```
                        ┌───────────────────┐
   browser ──► nginx ──►│  naming-server     │  consistent-hashing registry
   (port 80)            │  :8080             │  + serves Vue GUI
                        └───────┬───────────┘
                                │ REST (register / resolve owner / neighbours)
        ┌───────────────────────┼───────────────────────┐
        ▼                       ▼                        ▼
   ┌─────────┐  next      ┌─────────┐  next       ┌─────────┐
   │ nodeA   │──────────► │ nodeBeta│───────────► │nodeGamma│──┐
   │ :8081   │ ◄──────────│ :8082   │ ◄───────────│ :8083   │  │ ring
   └─────────┘  prev      └─────────┘  prev       └─────────┘  │
        ▲                                                       │
        └───────────────────────────────────────────────────────┘
   TCP file replication on ports 9001/9002/9003
```

- **Ring**: every node and filename is hashed into `[0, 32768]`. Nodes form a
  logical ring ordered by ID; each node knows its `prev` and `next` neighbour.
- **Ownership**: the owner of a file is the node with the largest ID *smaller than*
  the file hash; if none is smaller, the node with the largest ID overall owns it.
- **Replication**: a file's owner stores the authoritative copy and keeps a log of
  where replicas live; replicas are pushed to neighbours over TCP.
- **Agents**: lightweight payloads that travel the ring — one keeps file registries
  in sync, one repairs ownership after a crash.

---

## 2. Package structure

```
src/main/java/org/example/lab3/
├── controller/
│   ├── NamingServerController.java   @Profile("naming-server") — REST registry
│   └── NodeLaunchController.java     @Profile("naming-server") — start/stop node containers via Docker socket
├── model/                            AddNodeRequest, NodeInfo, NeighbourResponse,
│                                     FileOwnerResponse, ReplicaRequest (all carry a port field)
├── network/
│   └── MulticastListener.java        @Profile("naming-server") — receives node discovery multicasts
├── service/
│   ├── HashService.java              shared (NO @Profile) — hashCode → [0,32768]
│   ├── NodeRegistry.java             @Profile("naming-server") — ring map + ownership algorithm
│   └── NodeRegistryStorage.java      @Profile("naming-server") — JSON persistence (nodes.json)
└── node/                             all @Profile("node")
    ├── BootstrapService.java         discovery + join the ring on startup
    ├── ShutdownService.java          graceful leave (relink neighbours)
    ├── ReplicationService.java       / ReplicationShutdownService.java — replica logic
    ├── PingScheduler.java            heartbeat to next neighbour
    ├── FailureHandler.java           reacts to a dead neighbour
    ├── FailureAgent.java / SyncAgent.java     mobile agents
    ├── AgentController.java / AgentDispatcher.java / AgentPayload.java   agent transport
    ├── FileRegistry.java / FileEntry.java     in-memory file table (filename + lock)
    ├── FileLog.java / FileLogService.java     per-file log (origin + replica locations)
    ├── FileScanner.java / FolderWatcher.java  watch local/ for added/removed files
    ├── TcpFileServer.java / TcpFileClient.java  TCP replica transfer
    ├── MulticastReceiver.java        node-side discovery handling
    ├── NodeController.java           node REST API (/node/**)
    ├── NodeState.java                currentId / prevId / nextId / ip / name
    └── NodeIpLookup.java             resolves a node ID to "ip:port"
```

---

## 3. How it works (by lab)

### Lab 3 — Naming Server & consistent hashing
`HashService` maps Java's `hashCode()` range onto `[0, 32768]`. `NodeRegistry`
keeps the (ID → node) map and implements ownership: given a file hash, find all
nodes with a smaller ID; the owner is the largest of those, or the largest node
overall when none is smaller. The map is persisted to `nodes.json` so it survives
a restart.

### Lab 4 — Node lifecycle
- **Discovery (multicast)**: a node sends a UDP multicast `name:ip:port` to
  `230.0.0.0:4446`. The naming server registers it and replies on port `4447` with
  the current node count. (`BootstrapService`, `MulticastReceiver`, `MulticastListener`.)
- **Bootstrap**: the node computes its neighbours and notifies them via
  `PUT /node/prev` and `PUT /node/next`.
- **Shutdown**: `ShutdownService` relinks the departing node's neighbours to each
  other and deregisters from the naming server.
- **Failure detection**: `PingScheduler` heartbeats `next`; on failure
  `FailureHandler` removes the dead node from the naming server and triggers repair.

### Lab 5 — Replication
Each node has three folders in its workspace: `local/` (files it owns),
`replicas/` (replicas it holds for others), and `logs/` (per-file `FileLog` JSON).
`FileScanner` replicates everything in `local/` on startup; `FolderWatcher` polls
`local/` every 5s for changes. Files transfer node-to-node over TCP
(`TcpFileServer`/`TcpFileClient`). A `FileLog` records the download location
(origin) and the list of replica locations.

### Lab 6 — Mobile agents
- **SyncAgent** runs forever on every node, pushing its `FileRegistry` to its
  `next` neighbour every 5s so file knowledge converges around the ring.
- **FailureAgent** is dispatched when a node crashes; it travels the ring repairing
  file ownership, and terminates when it has visited every node (tracked via a
  `visitedNodeIds` list, so traversal is idempotent and always halts).
- Agents move as JSON `AgentPayload`s via `POST /agent/receive`, routed by
  `AgentController` / `AgentDispatcher`.

### Lab 7 — GUI, reverse proxy, deployment
A Vue 3 + Vite single-page app (`frontend/`) served as static files. It lists
nodes, shows node detail (prev/next, local + replica files), adds/removes nodes,
and resolves file owners. On the VM it is reached only through **Nginx** on port 80,
which proxies `/` and `/api/**` to the naming server and `/proxy/{port}/**` to the
matching node container. "Add Node" starts a pre-created Docker container through
the Docker socket API (`NodeLaunchController`). Everything is orchestrated by
`docker-compose.yml`.

---

## 4. REST API

### Naming server — base `http://<host>:8080` (or via Nginx on `:80`)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/nodes` | Register a node (body: `AddNodeRequest`); rejects duplicate names |
| DELETE | `/api/nodes/{name}` | Remove a node by name |
| GET | `/api/nodes` | All nodes (used by the GUI) |
| GET | `/api/nodes/{id}` | `NodeInfo` by ring ID → `{ id, name, ip, port }` |
| GET | `/api/nodes/neighbours/{id}` | `prev` + `next` IDs of a node |
| GET | `/api/files/owner?filename=X` | Owner node for a file (404 if no nodes) |
| POST | `/api/files/replicate` | Resolve the replica node for a file |
| POST | `/api/nodes/launch/{serviceName}` | Start a stopped node container (Docker socket) |
| POST | `/api/nodes/stop/{serviceName}` | Stop a node container |

### Node — base `http://<host>:{port}` (or via Nginx at `/proxy/{port}/...`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/node/ping` | Heartbeat → `pong` |
| GET | `/node/state` | `{ currentId, prevId, nextId, ip, name }` |
| PUT | `/node/prev` / `/node/next` | Update a neighbour (body: `{ id }`) |
| GET | `/node/files` | Filenames in `local/` |
| GET | `/node/filelist` | Full `FileRegistry` (`{ filename, locked }` list) |
| PUT | `/node/lock/{filename}` / `/node/unlock/{filename}` | Lock / unlock a file |
| DELETE | `/node/replica/{filename}` | Delete a held replica + its log |
| POST | `/agent/receive` | Receive an `AgentPayload` (SYNC / FAILURE) |

All controllers use `@CrossOrigin(origins = "*")` so the GUI can call them.

---

## 5. Build & run (localhost)

**Prerequisites:** JDK 25 (loom-ea build), Maven wrapper included.

```powershell
# Activate Java 25 in each new PowerShell window (sets JAVA_HOME)
. .\start.ps1

# Build
.\mvnw.cmd clean package -DskipTests
```

```powershell
# Window 1 — naming server
java -jar target\projectDS-0.0.1-SNAPSHOT.jar --spring.profiles.active=naming-server

# Window 2 — nodeA
cd nodeA-workspace
java -jar ..\target\projectDS-0.0.1-SNAPSHOT.jar `
  --spring.profiles.active=node --node.name=nodeA --node.ip=127.0.0.1 `
  --server.port=8081 --namingserver.url=http://localhost:8080 `
  --node.peer.port=8081 --node.tcp.port=9001

# Window 3 — nodeBeta  (use nodeBeta, NOT nodeB — see note below)
cd nodeB-workspace
java -jar ..\target\projectDS-0.0.1-SNAPSHOT.jar `
  --spring.profiles.active=node --node.name=nodeBeta --node.ip=127.0.0.1 `
  --server.port=8082 --namingserver.url=http://localhost:8080 `
  --node.peer.port=8082 --node.tcp.port=9002
```

> **Note:** on localhost every node shares `127.0.0.1`, so nodes are distinguished
> by port. `NodeIpLookup` returns `"ip:port"` and all REST URLs are built from it.

### Frontend (dev)
```powershell
cd frontend
npm install
npm run dev        # Vite dev server; build with `npm run build`
```
The frontend reads `window.location.origin`, so the same build works locally
(`:8080`) and on the VM (Nginx `:80`) with no hardcoded URLs.

---

## 6. Docker deployment (VM)

`docker-compose.yml` runs five services on a private `ring-net`: `naming-server`,
`node-a`, `node-b`, `node-c`, and `nginx` (the only public endpoint, port 80).
`node-d`/`node-e`/`node-f` are pre-created but stopped, ready to be launched from
the GUI.

```bash
# On the VM (143.129.43.114)
bash /projectDS/demo-start.sh     # builds/starts core services, creates spare nodes, reloads nginx
bash /projectDS/demo-stop.sh      # tear down
```

Then open **http://143.129.43.114** in a browser.

```bash
# Manual build + run
sudo docker compose build
sudo docker compose up -d
```

---

## 7. Testing

```powershell
.\mvnw.cmd test
```
56 tests, 0 failures (as of Lab 5). Controller tests cover the naming server,
node endpoints, replication, and agent routing.

---

## 8. Known issues / gotchas

1. **Hash collision** — `"nodeA"` and `"nodeB"` both hash to `17185`. Use
   `nodeBeta` (24948) and `nodeGamma` (19764) as the 2nd/3rd test nodes.
2. **Bootstrap multicast race** — a node may receive its own multicast before the
   naming server's count reply; the `NumberFormatException` is caught and the ring
   still forms via `MulticastReceiver`'s REST calls.
3. **Count reply timeout (3s)** — not always received in time; non-fatal for the
   same reason as above.
4. **TCP replication** uses the plain IP (extracted from `"ip:port"`) since
   `TcpFileServer` has its own port; only REST calls use the `ip:port` form.

---

## 9. Tech stack

- Java 25 (loom-ea), Spring Boot 4.0.5, Spring Web MVC
- Jackson 3.x (`tools.jackson`, **not** `com.fasterxml.jackson`)
- Vue 3 + Vite (frontend)
- Nginx (reverse proxy), Docker + Docker Compose
- UDP multicast (discovery), raw TCP sockets (file replication)

See [`Claude.md`](Claude.md) for deep implementation notes and the **demo
expansion backlog**.
