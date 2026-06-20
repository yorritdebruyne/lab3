package org.example.lab3.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests the GUI upload endpoint: a valid upload is stored in local/ and
 * handed to the existing replication pipeline; bad input is rejected; and a
 * path-traversal filename is neutralised to a safe name inside local/.
 */
class NodeControllerUploadTest {

    @TempDir
    Path tempDir;

    private NodeState          state;
    private ReplicationService replicationService;
    private NodeController      controller;

    @BeforeEach
    void setup() throws Exception {
        state = new NodeState();
        state.setName("nodeA");
        replicationService = mock(ReplicationService.class);
        FileRegistry fileRegistry = new FileRegistry();
        FileLogService fileLogService = mock(FileLogService.class);

        controller = new NodeController(state, fileLogService, fileRegistry, null, null, replicationService, null);
        setField(controller, "localDir", tempDir.resolve("local").toString());
        setField(controller, "replicasDir", tempDir.resolve("replicas").toString());
    }

    @Test
    void validUpload_isStoredLocally_andReplicated() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc1.txt", "text/plain", "hello world".getBytes());

        var response = controller.upload(file);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(Files.exists(tempDir.resolve("local").resolve("doc1.txt")),
                "uploaded file should land in local/");
        verify(replicationService).replicate("doc1.txt"); // reuses the existing pipeline
    }

    @Test
    void emptyUpload_isRejected_andNotReplicated() {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);

        var response = controller.upload(empty);

        assertEquals(400, response.getStatusCode().value());
        verify(replicationService, never()).replicate(anyString());
    }

    @Test
    void pathTraversalFilename_isNeutralisedToBaseName() throws Exception {
        MockMultipartFile evil = new MockMultipartFile(
                "file", "../../evil.txt", "text/plain", "x".getBytes());

        var response = controller.upload(evil);

        assertEquals(200, response.getStatusCode().value());
        // Stored as the base name INSIDE local/, never outside it.
        assertTrue(Files.exists(tempDir.resolve("local").resolve("evil.txt")));
        assertFalse(Files.exists(tempDir.resolve("evil.txt")), "must not escape local/");
        verify(replicationService).replicate("evil.txt");
    }

    private void setField(Object target, String name, String value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
