package org.example.lab3.controller;

import org.example.lab3.model.AddNodeRequest;
import org.example.lab3.model.FileOwnerResponse;
import org.example.lab3.model.NodeInfo;
import org.example.lab3.service.NodeRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class NamingServerController {

    private final NodeRegistry registry;

    public NamingServerController(NodeRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/nodes")
    public ResponseEntity<NodeInfo> addNode(@RequestBody AddNodeRequest req) {
        NodeInfo node = registry.addNode(req.getName(), req.getIp());
        return ResponseEntity.ok(node);
    }

    @DeleteMapping("/nodes/{name}")
    public ResponseEntity<Void> removeNode(@PathVariable String name) {
        registry.removeNode(name);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/files/owner")
    public ResponseEntity<FileOwnerResponse> getFileOwner(@RequestParam String filename) {
        NodeInfo owner = registry.findOwnerForFile(filename);
        if (owner == null) {
            return ResponseEntity.notFound().build();
        }
        FileOwnerResponse resp = new FileOwnerResponse(owner.getId(), owner.getIp());
        return ResponseEntity.ok(resp);
    }
}
