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
111 tests, 0 failures. Tests cover the naming server, node endpoints,
replication, join redistribution, the upload endpoint, and agent routing.

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

---

## 10. Expansions

Beyond the base assignment, the project adds a live **monitoring & operations GUI**
and several **resilience improvements** to the core replication logic. The network
algorithm (hashing, ownership, replication, agents) is unchanged — these build on
top of it.

### 10.1 Live monitoring & visualization
- **Ring topology** (`Topology.vue`) — a real-time SVG ring: each node is placed at
  the angle of its ring ID, files appear as dots coloured by their owner, edges are
  drawn as arcs, and the **SyncAgent** is animated as a pulse that travels along the
  ring to the next node.
- **Hash Inspector** — type a filename to see its ring hash and the owner the naming
  server computes for it (`GET /api/files/hash`), so the ownership rule is visible.
- **Load Distribution** chart — files owned per node (read from each node's actual
  `replicas/` folder), showing how evenly the hash spreads ownership.
- **Live event feed** — the naming server streams `JOIN` / `LEAVE` / `REPLICATE` /
  `REDISTRIBUTE` / `FAILURE` / `DELETE` events to the browser over Server-Sent Events
  (`GET /api/events`; `EventService`, `EventController`, node-side `EventPublisher`).
- **Agent visualization** — both the SyncAgent and the **FailureAgent** appear as
  moving pulses driven by the real hops the nodes report, so the agents' paths around
  the ring are visible during a crash.

### 10.2 Interactive cluster control
- **Add node** — starts a pre-created container through the Docker socket API.
- **Graceful leave vs. hard crash** — *Remove/Stop* sends SIGTERM (runs the graceful
  `ShutdownService`); the **Kill** button sends SIGKILL (`POST /api/nodes/kill`) to
  simulate a real crash, so the ring must detect the failure and recover on its own.

### 10.3 GUI file management
- **Upload** (`POST /node/upload`) — drag-drop a file onto a node; it is stored in
  that node's `local/` and replicated through the normal pipeline.
- **Download** (`GET /node/file/{filename}`) — save any file a node holds (from
  `local/` or `replicas/`) to your computer.
- **Delete** (`DELETE /node/local/{filename}`) — remove a file from the system: the
  original plus the owner's replica.
- **Send to another node** — fetch a file from one node and re-upload it to a target
  node, reusing the existing upload path (no new transfer logic).

### 10.4 Resilience & correctness optimizations
These keep storage consistent through churn (joins, graceful leaves, crashes,
rejoins) with no data loss. They reuse the authoritative ownership lookup and the
existing TCP transfer — no new placement logic.

- **Join redistribution** (`NodeJoinRedistributionService`) — when a newcomer slots
  into the ring, the previous owner hands over the files that now hash to the
  newcomer, using a safe **transfer-then-delete** (a copy is never dropped before the
  new owner has it).
- **Anti-entropy reconcile sweep** (`ReconcileScheduler`, every 10 s) — each node
  sheds any replica it no longer owns **and** re-pushes its `local/` files to their
  current owner, so every node's `replicas/` folder self-converges to exactly what it
  owns after any churn or leftover state.
- **Crash self-heal** — after an owner crashes, the surviving origins re-replicate
  their files to the new owner within one sweep, restoring the replicas a crash would
  otherwise orphan.
- **Own-file replication to the previous node** — when a node is the owner of its
  *own* local file, the replica is placed on its **previous neighbour** (rather than a
  self-referencing log), so every file — including a node's own — has an off-node copy
  that survives that node crashing. The redistribution and reconcile passes recognise
  these backups (download location == owner) and leave them in place instead of
  shipping them back onto the owner.
- **Deletion propagation fix** — `FolderWatcher` notifies the owner on the *owner's*
  HTTP port (each node runs on its own port), so deleting a local file correctly
  removes its replica on the other node.
