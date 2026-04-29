package org.example.lab3.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests only the Lab 5 endpoints added to NodeController.
 * The Lab 4 endpoints are already covered by NodeControllerTest.
 */
class NodeControllerReplicationTest {

    @TempDir
    Path tempDir;

    private NodeState      state;
    private FileLogService fileLogService;
    private NodeController controller;

    @BeforeEach
    void setup() throws Exception {
        state          = new NodeState();
        fileLogService = mock(FileLogService.class);
        controller     = new NodeController(state, fileLogService);

        // Point directories at our temp directory
        setField(controller, "replicasDir", tempDir.resolve("replicas").toString());
        setField(controller, "localDir",    tempDir.resolve("local").toString());

        Files.createDirectories(tempDir.resolve("replicas"));
        Files.createDirectories(tempDir.resolve("local"));
    }

    @Test
    void deleteReplica_removesFileAndLog() throws Exception {
        Path replicaFile = tempDir.resolve("replicas").resolve("doc1.txt");
        Files.writeString(replicaFile, "fake content");
        assertTrue(Files.exists(replicaFile));

        var response = controller.deleteReplica("doc1.txt");

        assertFalse(Files.exists(replicaFile));
        verify(fileLogService).delete("doc1.txt");
        assertEquals(204, response.getStatusCode().value());
    }

    @Test
    void deleteReplica_doesNotThrow_whenFileDoesNotExist() {
        var response = controller.deleteReplica("nonexistent.txt");
        assertEquals(204, response.getStatusCode().value());
    }

    @Test
    void listLocalFiles_returnsFilesInLocalFolder() throws Exception {
        Files.writeString(tempDir.resolve("local").resolve("doc1.txt"), "content");
        Files.writeString(tempDir.resolve("local").resolve("image.png"), "content");

        var response = controller.listLocalFiles();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertTrue(response.getBody().contains("doc1.txt"));
        assertTrue(response.getBody().contains("image.png"));
    }

    @Test
    void listLocalFiles_returnsEmptyList_whenFolderIsEmpty() {
        var response = controller.listLocalFiles();
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty());
    }

    private void setField(Object target, String fieldName, String value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}