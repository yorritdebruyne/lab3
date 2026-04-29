package org.example.lab3.node;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileLogTest {

    @Test
    void constructor_setsAllFields() {
        FileLog log = new FileLog("doc1.txt", "192.168.0.2", "192.168.0.3");
        assertEquals("doc1.txt", log.getFilename());
        assertEquals("192.168.0.2", log.getDownloadLocation());
        assertTrue(log.getReplicaLocations().contains("192.168.0.3"));
    }

    @Test
    void addReplicaLocation_addsNewIp() {
        FileLog log = new FileLog("doc1.txt", "192.168.0.2", "192.168.0.3");
        log.addReplicaLocation("192.168.0.4");
        assertEquals(2, log.getReplicaLocations().size());
        assertTrue(log.getReplicaLocations().contains("192.168.0.4"));
    }

    @Test
    void addReplicaLocation_doesNotAddDuplicate() {
        // Adding the same IP twice should not create a duplicate entry
        FileLog log = new FileLog("doc1.txt", "192.168.0.2", "192.168.0.3");
        log.addReplicaLocation("192.168.0.3");
        assertEquals(1, log.getReplicaLocations().size());
    }

    @Test
    void removeReplicaLocation_removesCorrectIp() {
        FileLog log = new FileLog("doc1.txt", "192.168.0.2", "192.168.0.3");
        log.addReplicaLocation("192.168.0.4");
        log.removeReplicaLocation("192.168.0.3");
        assertFalse(log.getReplicaLocations().contains("192.168.0.3"));
        assertTrue(log.getReplicaLocations().contains("192.168.0.4"));
    }

    @Test
    void emptyConstructor_worksForJacksonDeserialization() {
        // Jackson needs a no-arg constructor to deserialize JSON
        FileLog log = new FileLog();
        log.setFilename("image.png");
        log.setDownloadLocation("192.168.0.5");
        assertEquals("image.png", log.getFilename());
        assertEquals("192.168.0.5", log.getDownloadLocation());
        assertTrue(log.getReplicaLocations().isEmpty());
    }
}