package org.example.lab3.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileLogServiceTest {

    // @TempDir creates a temporary directory deleted after each test
    @TempDir
    Path tempDir;

    private FileLogService fileLogService;

    @BeforeEach
    void setup() throws Exception {
        fileLogService = new FileLogService();
        // Inject the temp directory as the logs dir using reflection
        // (@Value cannot be set in unit tests without a Spring context)
        Field field = FileLogService.class.getDeclaredField("logsDir");
        field.setAccessible(true);
        field.set(fileLogService, tempDir.toString());
    }

    @Test
    void save_and_load_roundTrip() {
        FileLog original = new FileLog("doc1.txt", "192.168.0.2", "192.168.0.3");
        fileLogService.save(original);

        FileLog loaded = fileLogService.load("doc1.txt");

        assertNotNull(loaded);
        assertEquals("doc1.txt", loaded.getFilename());
        assertEquals("192.168.0.2", loaded.getDownloadLocation());
        assertTrue(loaded.getReplicaLocations().contains("192.168.0.3"));
    }

    @Test
    void load_returnsNull_whenFileDoesNotExist() {
        FileLog result = fileLogService.load("nonexistent.txt");
        assertNull(result);
    }

    @Test
    void exists_returnsFalse_beforeSave() {
        assertFalse(fileLogService.exists("doc1.txt"));
    }

    @Test
    void exists_returnsTrue_afterSave() {
        fileLogService.save(new FileLog("doc1.txt", "192.168.0.2", "192.168.0.3"));
        assertTrue(fileLogService.exists("doc1.txt"));
    }

    @Test
    void delete_removesLogFile() {
        fileLogService.save(new FileLog("doc1.txt", "192.168.0.2", "192.168.0.3"));
        assertTrue(fileLogService.exists("doc1.txt"));

        fileLogService.delete("doc1.txt");

        assertFalse(fileLogService.exists("doc1.txt"));
    }

    @Test
    void delete_doesNotThrow_whenFileDoesNotExist() {
        assertDoesNotThrow(() -> fileLogService.delete("nonexistent.txt"));
    }

    @Test
    void save_overwrites_existingLog() {
        fileLogService.save(new FileLog("doc1.txt", "192.168.0.2", "192.168.0.3"));

        fileLogService.save(new FileLog("doc1.txt", "192.168.0.9", "192.168.0.3"));

        FileLog loaded = fileLogService.load("doc1.txt");
        assertNotNull(loaded);
        assertEquals("192.168.0.9", loaded.getDownloadLocation());
    }
}