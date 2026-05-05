package org.example.lab3.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Field;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration-style tests for SyncAgent.
 *
 * We test the SyncAgent's two core behaviours without actually starting
 * a real background thread:
 *
 *   1. File scanning — does it pick up files from the local/ folder
 *      and register them in the FileRegistry?
 *
 *   2. Sync push — does it build a correct AgentPayload and dispatch it
 *      to the next node?
 *
 * We use reflection to call the private methods directly so we can test
 * them in isolation without the 5-second sleep between cycles.
 */
class SyncAgentIntegrationTest {

    @TempDir
    Path tempDir;

    private NodeState       state;
    private FileRegistry    fileRegistry;
    private AgentDispatcher dispatcher;
    private NodeIpLookup    ipLookup;
    private SyncAgent       syncAgent;

    @BeforeEach
    void setup() throws Exception {
        state        = new NodeState();
        fileRegistry = new FileRegistry();
        dispatcher   = mock(AgentDispatcher.class);
        ipLookup     = mock(NodeIpLookup.class);

        syncAgent = new SyncAgent(state, fileRegistry, dispatcher, ipLookup);

        // Point the localDir at our temp directory
        setField(syncAgent, "localDir", tempDir.toString());
        setField(syncAgent, "syncIntervalMs", 5000L);
    }

    @Test
    void scanLocalFiles_registersFilesInRegistry() throws Exception {
        // Create two files in the temp local directory
        Files.writeString(tempDir.resolve("doc1.txt"), "content");
        Files.writeString(tempDir.resolve("image1.png"), "content");

        // Call the private scanLocalFiles method directly
        invokePrivate(syncAgent, "scanLocalFiles");

        // Both files should now be in the registry
        assertEquals(2, fileRegistry.size());
        assertFalse(fileRegistry.isLocked("doc1.txt"));
        assertFalse(fileRegistry.isLocked("image1.png"));
    }

    @Test
    void scanLocalFiles_doesNotOverwriteExistingLockedEntry() throws Exception {
        // Pre-lock a file in the registry
        fileRegistry.addFile("doc1.txt");
        fileRegistry.lock("doc1.txt");

        // Create the file on disk and scan
        Files.writeString(tempDir.resolve("doc1.txt"), "content");
        invokePrivate(syncAgent, "scanLocalFiles");

        // The lock should still be in place
        assertTrue(fileRegistry.isLocked("doc1.txt"),
                "Scanning should not overwrite an existing locked entry");
    }

    @Test
    void pushToNextNode_dispatchesSyncPayload() throws Exception {
        state.setCurrentId(12);
        state.setNextId(17);
        when(ipLookup.getIpForId(17)).thenReturn("192.168.0.4");

        fileRegistry.addFile("doc1.txt");

        // Call pushToNextNode directly
        invokePrivate(syncAgent, "pushToNextNode");

        // AgentDispatcher should have been called with a SYNC payload
        verify(dispatcher).dispatch(eq("192.168.0.4"), argThat(payload ->
                "SYNC".equals(payload.getType())
                        && payload.getFileList().size() == 1
                        && "doc1.txt".equals(payload.getFileList().get(0).getFilename())
        ));
    }

    @Test
    void pushToNextNode_skipsDispatch_whenAloneInRing() throws Exception {
        // next == current means we are alone — no point syncing with ourselves
        state.setCurrentId(12);
        state.setNextId(12);

        invokePrivate(syncAgent, "pushToNextNode");

        verify(dispatcher, never()).dispatch(any(), any());
    }

    @Test
    void pushToNextNode_skipsDispatch_whenNextIpIsNull() throws Exception {
        state.setCurrentId(12);
        state.setNextId(17);
        when(ipLookup.getIpForId(17)).thenReturn(null); // IP not found

        invokePrivate(syncAgent, "pushToNextNode");

        verify(dispatcher, never()).dispatch(any(), any());
    }

    // ── helpers ──────────────────────────────────────────────

    /** Calls a private no-arg method on the target object via reflection */
    private void invokePrivate(Object target, String methodName) throws Exception {
        var method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    /** Sets a private field value via reflection */
    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}