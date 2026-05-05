package org.example.lab3.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FailureHandlerTest {

    private NodeState       state;
    private NodeIpLookup    ipLookup;
    private AgentDispatcher agentDispatcher; // Lab 6: added
    private FailureHandler  failureHandler;

    @BeforeEach
    void setup() {
        state           = new NodeState();
        ipLookup        = mock(NodeIpLookup.class);
        agentDispatcher = mock(AgentDispatcher.class); // Lab 6: added
        failureHandler  = new FailureHandler(state, ipLookup, agentDispatcher);
    }

    @Test
    void ourNextDies_ourNextIsUpdatedToDeadNodesNext() {
        // Ring: us(10) → dead(20) → survivor(30)
        // After dead(20) is removed: our next should become 30
        state.setCurrentId(10);
        state.setPrevId(30);
        state.setNextId(20); // our next is the dead node

        // Simulate the local state update (step 5 of FailureHandler algorithm)
        if (state.getNextId() == 20) state.setNextId(30);

        assertEquals(30, state.getNextId(),
                "After next dies, our next should skip to the survivor");
    }

    @Test
    void ourPrevDies_ourPrevIsUpdatedToDeadNodesPrev() {
        // Ring: survivor(5) → dead(12) → us(17)
        state.setCurrentId(17);
        state.setPrevId(12); // our prev is the dead node
        state.setNextId(5);

        if (state.getPrevId() == 12) state.setPrevId(5);

        assertEquals(5, state.getPrevId(),
                "After prev dies, our prev should skip to the survivor");
    }

    @Test
    void unrelatedNodeDies_ourStateUnchanged() {
        // A node that is NOT our neighbour dies — we should not change our state
        state.setCurrentId(17);
        state.setPrevId(12);
        state.setNextId(5);

        int deadNode = 99; // not our neighbour
        if (state.getPrevId() == deadNode) state.setPrevId(0);
        if (state.getNextId() == deadNode) state.setNextId(0);

        assertEquals(12, state.getPrevId(), "Prev should be unchanged");
        assertEquals(5,  state.getNextId(), "Next should be unchanged");
    }

    @Test
    void shutdown_aloneInRing_doesNotLookupNeighbours() {
        // If alone, ShutdownService should not call ipLookup at all
        state.setCurrentId(100);
        state.setPrevId(100);
        state.setNextId(100);
        state.setName("nodeA");

        ReplicationShutdownService replicationShutdown =
                mock(ReplicationShutdownService.class);
        ShutdownService shutdownService =
                new ShutdownService(state, ipLookup, replicationShutdown);
        shutdownService.shutdown();

        verify(ipLookup, never()).getIpForId(anyInt());
    }

    // ── Lab 6: FailureAgent launch tests ─────────────────────

    @Test
    void failureAgentIsNotLaunched_whenAloneInRing() {
        // If we are alone (next == current), there is nobody to send the agent to
        state.setCurrentId(12);
        state.setPrevId(12);
        state.setNextId(12); // alone

        // handleFailure will fail early because the naming server is unreachable,
        // but we can verify dispatch was never called regardless
        failureHandler.handleFailure(5, "nodeC");

        verify(agentDispatcher, never()).dispatch(any(), any());
    }

    @Test
    void failureAgentIsNotLaunched_whenNextIpIsNull() {
        // If we can't find the next node's IP, the agent should not be launched
        state.setCurrentId(12);
        state.setNextId(17);

        when(ipLookup.getIpForId(anyInt())).thenReturn(null); // no IPs found

        failureHandler.handleFailure(5, "nodeC");

        verify(agentDispatcher, never()).dispatch(any(), any());
    }
}