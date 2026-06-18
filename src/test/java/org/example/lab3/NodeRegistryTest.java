package org.example.lab3;
import org.example.lab3.model.NodeInfo;
import org.example.lab3.service.HashService;
import org.example.lab3.service.NodeRegistry;
import org.example.lab3.storage.NodeRegistryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeRegistryTest {

    private NodeRegistry registry;

    @BeforeEach
    void setup() {
        HashService hashService = new HashService();
        NodeRegistryStorage storage = new NodeRegistryStorage() {
            @Override
            public List<NodeInfo> load() {
                return List.of();
            }

            @Override
            public void save(Collection<NodeInfo> nodes) {
                // no-op for tests
            }
        };
        registry = new NodeRegistry(hashService, storage, null);
    }

    @Test
    void addNode_uniqueName_succeeds() {
        NodeInfo n = registry.addNode("node1", "192.168.0.2");
        assertNotNull(n);
        assertEquals("node1", n.getName());
        assertEquals("192.168.0.2", n.getIp());
    }

    @Test
    void addNode_duplicateName_updatesEntry() {
        // Re-registering the same name should upsert, not throw —
        // this handles naming-server restart with stale nodes.json
        registry.addNode("node1", "192.168.0.2", 8081, 9001);
        NodeInfo updated = registry.addNode("node1", "192.168.0.3", 8082, 9002);
        assertEquals("192.168.0.3", updated.getIp());
        assertEquals(8082, updated.getPort());
        assertEquals(9002, updated.getTcpPort());
    }

    @Test
    void findOwnerForFile_basicRingLogic() {
        NodeInfo n1 = registry.addNode("nodeA", "10.0.0.1");
        NodeInfo n2 = registry.addNode("nodeB", "10.0.0.2");
        NodeInfo n3 = registry.addNode("nodeC", "10.0.0.3");

        NodeInfo owner = registry.findOwnerForFile("doc1.txt");
        assertNotNull(owner);
        assertTrue(List.of(n1.getId(), n2.getId(), n3.getId()).contains(owner.getId()));
    }

    @Test
    void findOwnerForFile_noNodes_returnsNull() {
        NodeRegistry emptyRegistry = new NodeRegistry(new HashService(), new NodeRegistryStorage() {
            @Override
            public List<NodeInfo> load() { return List.of(); }
            @Override
            public void save(Collection<NodeInfo> nodes) {}
        }, null);

        assertNull(emptyRegistry.findOwnerForFile("file.txt"));
    }
}
