package org.example.lab3.node;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NodeStateTest {

    @Test
    void defaultValues_areMinusOne() {
        NodeState state = new NodeState();
        assertEquals(-1, state.getCurrentId());
        assertEquals(-1, state.getPrevId());
        assertEquals(-1, state.getNextId());
    }

    @Test
    void setAndGet_currentId() {
        NodeState state = new NodeState();
        state.setCurrentId(1234);
        assertEquals(1234, state.getCurrentId());
    }

    @Test
    void setAndGet_prevAndNext() {
        NodeState state = new NodeState();
        state.setPrevId(100);
        state.setNextId(200);
        assertEquals(100, state.getPrevId());
        assertEquals(200, state.getNextId());
    }

    @Test
    void aloneInRing_prevAndNextEqualSelf() {
        // When a node is alone, prev == current == next.
        // This is the convention used by BootstrapService when count == 0.
        NodeState state = new NodeState();
        state.setCurrentId(500);
        state.setPrevId(500);
        state.setNextId(500);

        assertEquals(state.getCurrentId(), state.getPrevId());
        assertEquals(state.getCurrentId(), state.getNextId());
    }
}
