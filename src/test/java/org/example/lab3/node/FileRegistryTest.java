package org.example.lab3.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FileRegistry — the system-wide file catalogue.
 *
 * Tests cover:
 *   - Adding files
 *   - Locking and unlocking
 *   - The merge logic (most important — this is how sync works)
 *   - Thread safety is not tested here (integration test territory)
 */
class FileRegistryTest {

    private FileRegistry registry;

    @BeforeEach
    void setup() {
        registry = new FileRegistry();
    }

    // ── addFile ──────────────────────────────────────────────

    @Test
    void addFile_registersNewFile_asUnlocked() {
        registry.addFile("doc1.txt");

        List<FileEntry> all = registry.getAll();
        assertEquals(1, all.size());
        assertEquals("doc1.txt", all.get(0).getFilename());
        assertFalse(all.get(0).isLocked());
    }

    @Test
    void addFile_doesNotOverwriteExistingEntry() {
        // If a file is already locked and addFile is called again,
        // the lock should NOT be reset to false
        registry.addFile("doc1.txt");
        registry.lock("doc1.txt");          // lock it
        registry.addFile("doc1.txt");       // try to add again

        assertTrue(registry.isLocked("doc1.txt"),
                "Existing locked entry should not be overwritten by addFile");
    }

    @Test
    void addFile_multipleDifferentFiles_allRegistered() {
        registry.addFile("doc1.txt");
        registry.addFile("image1.png");
        registry.addFile("doc2.pdf");

        assertEquals(3, registry.size());
    }

    // ── lock / unlock ─────────────────────────────────────────

    @Test
    void lock_setsLockedTrue() {
        registry.addFile("doc1.txt");
        registry.lock("doc1.txt");
        assertTrue(registry.isLocked("doc1.txt"));
    }

    @Test
    void unlock_setsLockedFalse() {
        registry.addFile("doc1.txt");
        registry.lock("doc1.txt");
        registry.unlock("doc1.txt");
        assertFalse(registry.isLocked("doc1.txt"));
    }

    @Test
    void lock_addsFileIfNotPresent() {
        // lock() should register the file automatically if it isn't in the registry yet
        assertFalse(registry.isLocked("newfile.txt")); // not in registry
        registry.lock("newfile.txt");                  // should add AND lock
        assertTrue(registry.isLocked("newfile.txt"));
        assertEquals(1, registry.size());
    }

    @Test
    void unlock_doesNothing_whenFileNotInRegistry() {
        // Unlocking a file that doesn't exist should not throw
        assertDoesNotThrow(() -> registry.unlock("nonexistent.txt"));
        assertEquals(0, registry.size());
    }

    @Test
    void isLocked_returnsFalse_forUnknownFile() {
        // A file not in the registry is not locked
        assertFalse(registry.isLocked("unknown.txt"));
    }

    // ── merge ─────────────────────────────────────────────────
    // merge() only discovers new filenames — it never updates lock state.
    // Lock and unlock changes propagate immediately via PUT /node/lock and
    // PUT /node/unlock (broadcast to all neighbours), not through SYNC.
    // This prevents a stale SYNC cycle from overwriting a freshly-set lock.

    @Test
    void merge_addsNewFilesFromIncomingList() {
        // Our registry is empty; incoming list has a file we don't know about
        List<FileEntry> incoming = List.of(new FileEntry("image1.png", false));
        registry.merge(incoming);

        assertEquals(1, registry.size());
        assertFalse(registry.isLocked("image1.png"));
    }

    @Test
    void merge_doesNotOverwriteLock_whenIncomingIsLocked() {
        // We know about a file (unlocked); neighbour sends it as locked via SYNC.
        // merge() must NOT change our lock state — locks come via broadcast only.
        registry.addFile("doc1.txt"); // start unlocked

        List<FileEntry> incoming = List.of(new FileEntry("doc1.txt", true));
        registry.merge(incoming);

        assertFalse(registry.isLocked("doc1.txt"),
                "SYNC merge must not propagate locks — use PUT /node/lock instead");
    }

    @Test
    void merge_doesNotOverwriteLock_whenLocalIsLocked() {
        // We have a file locked; neighbour sends it as unlocked via SYNC.
        // merge() must NOT release our lock — unlocks come via broadcast only.
        registry.addFile("doc1.txt");
        registry.lock("doc1.txt"); // currently locked

        List<FileEntry> incoming = List.of(new FileEntry("doc1.txt", false));
        registry.merge(incoming);

        assertTrue(registry.isLocked("doc1.txt"),
                "SYNC merge must not release locks — use PUT /node/unlock instead");
    }

    @Test
    void merge_doesNotChangeLock_whenBothAlreadyMatch() {
        // Both sides agree the file is unlocked → nothing should change
        registry.addFile("doc1.txt"); // unlocked

        List<FileEntry> incoming = List.of(new FileEntry("doc1.txt", false));
        registry.merge(incoming);

        assertFalse(registry.isLocked("doc1.txt"));
        assertEquals(1, registry.size()); // still only one entry
    }

    @Test
    void merge_handlesEmptyIncomingList_gracefully() {
        registry.addFile("doc1.txt");
        assertDoesNotThrow(() -> registry.merge(List.of()));
        assertEquals(1, registry.size()); // our file is still there
    }

    @Test
    void merge_mixedList_addsNewFilesAndIgnoresLockUpdates() {
        // Start with one known file (unlocked)
        registry.addFile("doc1.txt");

        // Incoming has: doc1.txt (locked in neighbour) + a new file image1.png
        List<FileEntry> incoming = List.of(
                new FileEntry("doc1.txt",  true),
                new FileEntry("image1.png", false)
        );
        registry.merge(incoming);

        assertEquals(2, registry.size());
        assertFalse(registry.isLocked("doc1.txt"),   "existing lock state must not change");
        assertFalse(registry.isLocked("image1.png"), "new file added as unlocked");
    }

    // ── getAll ────────────────────────────────────────────────

    @Test
    void getAll_returnsAllEntries() {
        registry.addFile("a.txt");
        registry.addFile("b.txt");
        registry.addFile("c.txt");

        List<FileEntry> all = registry.getAll();
        assertEquals(3, all.size());
    }

    @Test
    void getAll_returnsDefensiveCopy() {
        // Modifying the returned list should not affect the registry
        registry.addFile("doc1.txt");
        List<FileEntry> all = registry.getAll();
        all.clear();

        assertEquals(1, registry.size(),
                "Clearing the returned list should not affect the registry");
    }
}