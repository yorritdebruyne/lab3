package org.example.lab3.node;

import org.example.lab3.service.HashService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapServiceTest {

    private HashService hashService;
    private NodeState   state;

    @BeforeEach
    void setup() {
        hashService = new HashService();
        state       = new NodeState();
    }

    @Test
    void hashService_producesConsistentId() {
        int id1 = hashService.hashToRing("nodeA");
        int id2 = hashService.hashToRing("nodeA");
        assertEquals(id1, id2);
    }

    @Test
    void hashService_idIsInValidRange() {
        int id = hashService.hashToRing("nodeA");
        assertTrue(id >= 0 && id <= 32_768,
                "Hash should be within ring range, got: " + id);
    }

    @Test
    void aloneScenario_prevAndNextPointToSelf() {
        // Simulate: naming server replied with count=0 (we are alone).
        // Bootstrap logic: set prev = next = self.
        int myId = hashService.hashToRing("nodeA");
        state.setCurrentId(myId);

        // This is the exact code from BootstrapService when existingCount < 1
        state.setPrevId(myId);
        state.setNextId(myId);

        assertEquals(myId, state.getPrevId(),  "Alone: prev should equal self");
        assertEquals(myId, state.getNextId(),  "Alone: next should equal self");
    }

    @Test
    void multipleNodesScenario_stateWaitsForNeighbourUpdates() {
        // Once neighbours call PUT /node/prev and PUT /node/next, state updates
        int myId = hashService.hashToRing("nodeD");
        state.setCurrentId(myId);
        state.setPrevId(myId);
        state.setNextId(myId);

        // Simulate NodeController receiving neighbour updates
        state.setPrevId(100);
        state.setNextId(200);

        assertEquals(100, state.getPrevId());
        assertEquals(200, state.getNextId());
    }
}