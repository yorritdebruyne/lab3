package org.example.lab3.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NodeControllerTest {

    private NodeState      state;
    private NodeController controller;

    @BeforeEach
    void setup() {
        state      = new NodeState();
        // FileLogService has no dependencies so we can just instantiate it directly
        FileLogService fileLogService = new FileLogService();
        controller = new NodeController(state, fileLogService);
    }

    @Test
    void updatePrev_changesStateCorrectly() {
        state.setCurrentId(12);
        state.setPrevId(5);

        NodeController.NeighbourUpdate req = new NodeController.NeighbourUpdate();
        req.setId(7);

        var response = controller.updatePrev(req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(7, state.getPrevId(), "prevId should be updated to 7");
    }

    @Test
    void updateNext_changesStateCorrectly() {
        state.setCurrentId(5);
        state.setNextId(12);

        NodeController.NeighbourUpdate req = new NodeController.NeighbourUpdate();
        req.setId(7);

        var response = controller.updateNext(req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(7, state.getNextId(), "nextId should be updated to 7");
    }

    @Test
    void ping_returns200AndPong() {
        var response = controller.ping();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("pong", response.getBody());
    }

    @Test
    void getState_returnsCurrentState() {
        state.setCurrentId(42);
        state.setPrevId(10);
        state.setNextId(80);

        var response = controller.getState();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(42, response.getBody().getCurrentId());
    }
}