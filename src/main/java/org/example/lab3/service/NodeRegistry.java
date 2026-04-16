package org.example.lab3.service;

import org.example.lab3.model.NodeInfo;
import org.example.lab3.storage.NodeRegistryStorage;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class NodeRegistry {

    private final NavigableMap<Integer, NodeInfo> nodes = new TreeMap<>();
    private final HashService hashService;
    private final NodeRegistryStorage storage;

    public NodeRegistry(HashService hashService, NodeRegistryStorage storage) {
        this.hashService = hashService;
        this.storage = storage;

        // Load from disk
        storage.load().forEach(n -> nodes.put(n.getId(), n));
    }

    public synchronized NodeInfo addNode(String name, String ip) {
        boolean exists = nodes.values().stream()
                .anyMatch(n -> n.getName().equals(name));

        if (exists) {
            throw new IllegalArgumentException("Node name already exists");
        }

        int id = hashService.hashToRing(name);
        NodeInfo node = new NodeInfo(id, name, ip);

        nodes.put(id, node);
        storage.save(nodes.values());
        return node;
    }

    public synchronized void removeNode(String name) {
        Optional<Integer> key = nodes.entrySet().stream()
                .filter(e -> e.getValue().getName().equals(name))
                .map(Map.Entry::getKey)
                .findFirst();

        key.ifPresent(k -> {
            nodes.remove(k);
            storage.save(nodes.values());
        });
    }

    public synchronized NodeInfo findOwnerForFile(String filename) {
        if (nodes.isEmpty()) {
            return null;
        }
        int fileHash = hashService.hashToRing(filename);
        SortedMap<Integer, NodeInfo> head = nodes.headMap(fileHash);

        if (!head.isEmpty()) {
            return nodes.get(head.lastKey());
        } else {
            return nodes.get(nodes.lastKey());
        }
    }
}
