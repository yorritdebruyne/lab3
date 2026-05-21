package org.example.lab3.node;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AgentPayload — the JSON body that travels between nodes.
 *
 * Verifies that the factory methods create the correct payloads
 * and that all fields are set properly.
 */
class AgentPayloadTest {

    @Test
    void forSync_setsTypeToSync() {
        AgentPayload payload = AgentPayload.forSync(List.of());
        assertEquals("SYNC", payload.getType());
    }

    @Test
    void forSync_carriesFileList() {
        List<FileEntry> entries = List.of(
                new FileEntry("doc1.txt", false),
                new FileEntry("image1.png", true)
        );
        AgentPayload payload = AgentPayload.forSync(entries);

        assertEquals(2, payload.getFileList().size());
        assertEquals("doc1.txt",   payload.getFileList().get(0).getFilename());
        assertEquals("image1.png", payload.getFileList().get(1).getFilename());
    }

    @Test
    void forSync_failingNodeId_isMinusOne() {
        // SYNC payloads don't carry a failing node ID
        AgentPayload payload = AgentPayload.forSync(List.of());
        assertEquals(-1, payload.getFailingNodeId());
    }

    @Test
    void forFailure_setsTypeToFailure() {
        AgentPayload payload = AgentPayload.forFailure(42);
        assertEquals("FAILURE", payload.getType());
    }

    @Test
    void forFailure_setsFailingNodeId() {
        AgentPayload payload = AgentPayload.forFailure(42);
        assertEquals(42, payload.getFailingNodeId());
    }

    @Test
    void forFailure_visitedNodeIds_isEmptyByDefault() {
        // No nodes visited yet when agent is first created
        AgentPayload payload = AgentPayload.forFailure(42);
        assertNotNull(payload.getVisitedNodeIds());
        assertTrue(payload.getVisitedNodeIds().isEmpty());
    }

    @Test
    void forFailure_fileList_isEmptyByDefault() {
        // FAILURE payloads don't carry a file list
        AgentPayload payload = AgentPayload.forFailure(42);
        assertNotNull(payload.getFileList());
        assertTrue(payload.getFileList().isEmpty());
    }

    @Test
    void defaultConstructor_allFieldsAreDefaults() {
        // The no-arg constructor (needed by Jackson) should produce safe defaults
        AgentPayload payload = new AgentPayload();
        assertNull(payload.getType());
        assertEquals(-1, payload.getFailingNodeId());
        assertNotNull(payload.getVisitedNodeIds());
        assertNotNull(payload.getFileList());
    }
}
