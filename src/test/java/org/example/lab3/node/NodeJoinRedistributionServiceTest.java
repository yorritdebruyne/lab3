package org.example.lab3.node;

import org.example.lab3.model.NodeInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests the JOIN redistribution logic: an owned replica whose owner is now a
 * DIFFERENT node must be transferred over TCP and then removed locally, while a
 * replica still owned by this node must be left untouched.
 *
 * The naming-server lookup is stubbed by overriding lookupOwner(), so no live
 * server is needed (same spirit as the other replication unit tests).
 */
class NodeJoinRedistributionServiceTest {

    @TempDir
    Path tempDir;

    private static final int SELF_ID  = 100;
    private static final int OTHER_ID = 200;

    private NodeState      state;
    private TcpFileClient  tcpClient;
    private FileLogService fileLogService;
    private NodeJoinRedistributionService service;

    @BeforeEach
    void setup() throws Exception {
        state          = new NodeState();
        state.setCurrentId(SELF_ID);
        state.setIp("10.0.0.1");

        tcpClient      = mock(TcpFileClient.class);
        fileLogService = mock(FileLogService.class);

        // Stub the naming-server owner lookup:
        //   moveme.txt → owned by a DIFFERENT node (must be handed off)
        //   keepme.txt → still owned by us           (must be kept)
        service = new NodeJoinRedistributionService(state, tcpClient, fileLogService, null) {
            @Override
            protected NodeInfo lookupOwner(String filename) {
                if (filename.equals("moveme.txt")) {
                    return new NodeInfo(OTHER_ID, "nodeOther", "10.0.0.9"); // tcpPort defaults to 9000
                }
                return new NodeInfo(SELF_ID, "self", "10.0.0.1");
            }
        };

        setField(service, "replicasDir", tempDir.resolve("replicas").toString());
        setField(service, "namingServerUrl", "http://localhost:8080");
        Files.createDirectories(tempDir.resolve("replicas"));
    }

    @Test
    void redistribute_handsOffMovedFile_andKeepsOwnFile() throws Exception {
        Path moveme = tempDir.resolve("replicas").resolve("moveme.txt");
        Path keepme = tempDir.resolve("replicas").resolve("keepme.txt");
        Files.writeString(moveme, "to the new owner");
        Files.writeString(keepme, "still mine");

        when(tcpClient.sendFile(any(), eq("10.0.0.9"), eq(9000))).thenReturn(true);

        service.redistribute();

        // The moved file is sent to the new owner and removed locally (file + log).
        verify(tcpClient).sendFile(any(), eq("10.0.0.9"), eq(9000));
        verify(fileLogService).delete("moveme.txt");
        assertFalse(Files.exists(moveme), "moved replica should be deleted locally");

        // The file we still own is left completely alone.
        verify(tcpClient, never()).sendFile(any(), eq("10.0.0.1"), anyInt());
        verify(fileLogService, never()).delete("keepme.txt");
        assertTrue(Files.exists(keepme), "still-owned replica must stay");
    }

    @Test
    void redistribute_keepsSelfOwnedBackup_thatBelongsOffItsOwner() throws Exception {
        // A replica we hold for a file whose OWNER is a different node (10.0.0.9),
        // but whose origin (download location) IS that same owner — i.e. the owner
        // also holds the original locally, so this backup is meant to live OFF the
        // owner (we are the owner's previous node). It must NOT be shipped back.
        Path backup = tempDir.resolve("replicas").resolve("mybackup.txt");
        Files.writeString(backup, "owner's own file, kept on its prev");

        when(fileLogService.load("mybackup.txt"))
                .thenReturn(new FileLog("mybackup.txt", "10.0.0.9", "10.0.0.1"));

        service = selfOwnedBackupService();
        setField(service, "replicasDir", tempDir.resolve("replicas").toString());
        setField(service, "namingServerUrl", "http://localhost:8080");

        service.redistribute();

        // Not transferred anywhere, not deleted — the off-owner backup stays put.
        verify(tcpClient, never()).sendFile(any(), anyString(), anyInt());
        verify(fileLogService, never()).delete("mybackup.txt");
        assertTrue(Files.exists(backup), "self-owned backup must stay on the owner's prev node");
    }

    // Variant whose owner lookup returns the OTHER node for mybackup.txt.
    private NodeJoinRedistributionService selfOwnedBackupService() {
        return new NodeJoinRedistributionService(state, tcpClient, fileLogService, null) {
            @Override
            protected NodeInfo lookupOwner(String filename) {
                return new NodeInfo(OTHER_ID, "nodeOther", "10.0.0.9");
            }
        };
    }

    @Test
    void redistribute_keepsFile_whenTransferFails() throws Exception {
        Path moveme = tempDir.resolve("replicas").resolve("moveme.txt");
        Files.writeString(moveme, "owner moved but TCP will fail");

        when(tcpClient.sendFile(any(), eq("10.0.0.9"), eq(9000))).thenReturn(false);

        service.redistribute();

        // Transfer failed → do NOT delete the local copy (no data loss).
        verify(fileLogService, never()).delete("moveme.txt");
        assertTrue(Files.exists(moveme), "failed transfer must keep the local replica");
    }

    private void setField(Object target, String fieldName, String value) throws Exception {
        Field field = target.getClass().getSuperclass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
