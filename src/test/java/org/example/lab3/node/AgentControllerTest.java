package org.example.lab3.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for AgentController — the REST endpoint that receives agents.
 *
 * We test the routing logic directly without starting a real HTTP server.
 * The key things to verify:
 *   - SYNC payloads trigger a FileRegistry merge
 *   - FAILURE payloads trigger FailureAgent.execute()
 *   - FAILURE payloads are forwarded to the next node (unless back at start)
 *   - FAILURE payloads terminate when back at the start node
 *
 * We use Thread.sleep() after calling receiveAgent() because the controller
 * runs the logic in a background thread and returns immediately.
 */
class AgentControllerTest {

    private NodeState       state;
    private FileRegistry    fileRegistry;
    private FailureAgent    failureAgent;
    private AgentDispatcher dispatcher;
    private NodeIpLookup    ipLookup;
    private AgentController controller;

    @BeforeEach
    void setup() {
        state        = new NodeState();
        fileRegistry = new FileRegistry();
        failureAgent = mock(FailureAgent.class);
        dispatcher   = mock(AgentDispatcher.class);
        ipLookup     = mock(NodeIpLookup.class);

        controller = new AgentController(
                state, fileRegistry, failureAgent, dispatcher, ipLookup);
    }

    // ── SYNC agent ────────────────────────────────────────────

    @Test
    void syncPayload_returns200() {
        AgentPayload payload = AgentPayload.forSync(List.of());
        var response = controller.receiveAgent(payload);
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void syncPayload_mergesFileListIntoRegistry() throws InterruptedException {
        // Incoming SYNC payload has a file we don't know about
        List<FileEntry> incoming = List.of(new FileEntry("doc1.txt", false));
        AgentPayload payload = AgentPayload.forSync(incoming);

        controller.receiveAgent(payload);
        Thread.sleep(200); // wait for background thread to finish

        // FileRegistry should now contain the incoming file
        assertEquals(1, fileRegistry.size());
        assertFalse(fileRegistry.isLocked("doc1.txt"));
    }

    @Test
    void syncPayload_propagatesLock() throws InterruptedException {
        // We know about the file (unlocked); incoming says it's locked
        fileRegistry.addFile("doc1.txt");

        List<FileEntry> incoming = List.of(new FileEntry("doc1.txt", true));
        AgentPayload payload = AgentPayload.forSync(incoming);

        controller.receiveAgent(payload);
        Thread.sleep(200);

        assertTrue(fileRegistry.isLocked("doc1.txt"),
                "Lock from SYNC payload should propagate into our registry");
    }

    @Test
    void syncPayload_isNeverForwarded() throws InterruptedException {
        // SYNC agents never travel — each node has its own SyncAgent
        AgentPayload payload = AgentPayload.forSync(List.of());

        controller.receiveAgent(payload);
        Thread.sleep(200);

        // AgentDispatcher should never have been called
        verify(dispatcher, never()).dispatch(any(), any());
    }

    // ── FAILURE agent ─────────────────────────────────────────

    @Test
    void failurePayload_returns200() {
        state.setCurrentId(12);
        state.setNextId(17);
        when(ipLookup.getIpForId(17)).thenReturn("192.168.0.4");

        AgentPayload payload = AgentPayload.forFailure(5, 28);
        var response = controller.receiveAgent(payload);
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void failurePayload_callsFailureAgentExecute() throws InterruptedException {
        state.setCurrentId(12);
        state.setNextId(17);
        when(ipLookup.getIpForId(17)).thenReturn("192.168.0.4");

        AgentPayload payload = AgentPayload.forFailure(5, 28);
        controller.receiveAgent(payload);
        Thread.sleep(200);

        // FailureAgent.execute() should have been called with our payload
        verify(failureAgent).execute(payload);
    }

    @Test
    void failurePayload_isForwarded_whenNotBackAtStart() throws InterruptedException {
        // We are node 12, start was node 28 → not back at start → forward
        state.setCurrentId(12);
        state.setNextId(17);
        when(ipLookup.getIpForId(17)).thenReturn("192.168.0.4");

        AgentPayload payload = AgentPayload.forFailure(5, 28);
        controller.receiveAgent(payload);
        Thread.sleep(200);

        // Should have forwarded to the next node
        verify(dispatcher).dispatch(eq("192.168.0.4"), eq(payload));
    }

    @Test
    void failurePayload_terminates_whenBackAtStartNode() throws InterruptedException {
        // We ARE the start node → the agent has completed the full ring → stop
        state.setCurrentId(28); // we are the start node
        state.setNextId(5);
        when(ipLookup.getIpForId(5)).thenReturn("192.168.0.2");

        AgentPayload payload = AgentPayload.forFailure(5, 28); // startNodeId = 28 = us
        controller.receiveAgent(payload);
        Thread.sleep(200);

        // FailureAgent still runs on this node
        verify(failureAgent).execute(payload);
        // But should NOT be forwarded further
        verify(dispatcher, never()).dispatch(any(), any());
    }

    @Test
    void failurePayload_stops_whenAloneInRing() throws InterruptedException {
        // next == current means we are alone → no forwarding
        state.setCurrentId(12);
        state.setNextId(12); // alone

        AgentPayload payload = AgentPayload.forFailure(5, 28);
        controller.receiveAgent(payload);
        Thread.sleep(200);

        verify(dispatcher, never()).dispatch(any(), any());
    }
}