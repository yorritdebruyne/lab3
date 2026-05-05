package org.example.lab3.node;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FileEntry — the data model for a single file in the registry.
 *
 * These are pure unit tests with no Spring context needed.
 * FileEntry is just a plain Java object so we test it directly.
 */
class FileEntryTest {

    @Test
    void defaultConstructor_filenameIsNull_lockedIsFalse() {
        // The no-arg constructor (needed by Jackson) should produce
        // an empty object — no filename, not locked
        FileEntry entry = new FileEntry();
        assertNull(entry.getFilename());
        assertFalse(entry.isLocked());
    }

    @Test
    void filenameConstructor_setsFilename_andStartsUnlocked() {
        // Files should always start unlocked when first registered
        FileEntry entry = new FileEntry("doc1.txt");
        assertEquals("doc1.txt", entry.getFilename());
        assertFalse(entry.isLocked());
    }

    @Test
    void fullConstructor_setsFilenameAndLockState() {
        // Used when reconstructing from a neighbour's list that already has a lock
        FileEntry entry = new FileEntry("image5.png", true);
        assertEquals("image5.png", entry.getFilename());
        assertTrue(entry.isLocked());
    }

    @Test
    void setLocked_true_marksFileAsLocked() {
        FileEntry entry = new FileEntry("doc1.txt");
        entry.setLocked(true);
        assertTrue(entry.isLocked());
    }

    @Test
    void setLocked_false_marksFileAsUnlocked() {
        // Start locked, then release the lock
        FileEntry entry = new FileEntry("doc1.txt", true);
        entry.setLocked(false);
        assertFalse(entry.isLocked());
    }

    @Test
    void toString_containsFilenameAndLockState() {
        FileEntry entry = new FileEntry("doc1.txt", true);
        String result = entry.toString();
        assertTrue(result.contains("doc1.txt"));
        assertTrue(result.contains("true"));
    }
}