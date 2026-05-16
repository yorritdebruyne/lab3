package org.example.lab3.controller;

import org.example.lab3.model.ReplicaRequest;
import org.example.lab3.model.AddNodeRequest;
import org.example.lab3.model.FileOwnerResponse;
import org.example.lab3.model.NeighbourResponse;
import org.example.lab3.model.NodeInfo;
import org.example.lab3.service.NodeRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;

@Profile("naming-server")
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class NamingServerController {

    private final NodeRegistry registry;

    public NamingServerController(NodeRegistry registry) {
        this.registry = registry;
    }

    /**
     * Registers a new node. Now passes the port from the request body
     * so the naming server stores the correct port per node.
     */
    @PostMapping("/nodes")
    public ResponseEntity<NodeInfo> addNode(@RequestBody AddNodeRequest req) {
        NodeInfo node = registry.addNode(req.getName(), req.getIp(), req.getPort(), req.getTcpPort());
        return ResponseEntity.ok(node);
    }

    @DeleteMapping("/nodes/{name}")
    public ResponseEntity<Void> removeNode(@PathVariable String name) {
        registry.removeNode(name);
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns full NodeInfo (including port) for a given ring ID.
     * Used by NodeIpLookup to get the "ip:port" address of a neighbour.
     */
    @GetMapping("/nodes/{id}")
    public ResponseEntity<NodeInfo> getNode(@PathVariable int id) {
        NodeInfo node = registry.getNode(id);
        if (node == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(node);
    }

    /**
     * Returns all nodes in the registry.
     * Used by the GUI (Lab 7) to display the node list.
     */
    @GetMapping("/nodes")
    public ResponseEntity<Collection<NodeInfo>> getAllNodes() {
        return ResponseEntity.ok(registry.getAllNodes());
    }

    /**
     * Returns the owner node for a given filename.
     * Now includes the port in the response so callers can build
     * correct URLs for localhost multi-node testing.
     */
    @GetMapping("/files/owner")
    public ResponseEntity<FileOwnerResponse> getFileOwner(@RequestParam String filename) {
        NodeInfo owner = registry.findOwnerForFile(filename);
        if (owner == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(
                new FileOwnerResponse(owner.getId(), owner.getIp(), owner.getPort())
        );
    }

    /**
     * Returns the previous and next node of a given node ID.
     * Used during failure recovery to stitch the ring back together.
     */
    @GetMapping("/nodes/neighbours/{id}")
    public ResponseEntity<NeighbourResponse> getNeighbours(@PathVariable int id) {
        NeighbourResponse neighbours = registry.getNeighbours(id);
        if (neighbours == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(neighbours);
    }

    /**
     * Returns which node should store the replica of a given file.
     */
    @PostMapping("/files/replicate")
    public ResponseEntity<NodeInfo> getReplicaNode(@RequestBody ReplicaRequest req) {
        NodeInfo replicaNode = registry.findOwnerForFile(req.getFilename());
        if (replicaNode == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(replicaNode);
    }
}