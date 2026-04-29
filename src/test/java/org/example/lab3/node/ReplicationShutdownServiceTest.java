package org.example.lab3.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.*;

import static org.mockito.Mockito.*;

class ReplicationShutdownServiceTest {

    @TempDir
    Path tempDir;

    private NodeState                  state;
    private NodeIpLookup               ipLookup;
    private TcpFileClient              tcpClient;
    private FileLogService             fileLogService;
    private ReplicationShutdownService shutdownService;

    @BeforeEach
    void setup() throws Exception {
        state           = new NodeState();
        ipLookup        = mock(NodeIpLookup.class);
        tcpClient       = mock(TcpFileClient.class);
        fileLogService  = mock(FileLogService.class);
        shutdownService = new ReplicationShutdownService(
                state, ipLookup, tcpClient, fileLogService);

        setField(shutdownService, "replicasDir", tempDir.resolve("replicas").toString());
        setField(shutdownService, "localDir",    tempDir.resolve("local").toString());
        setField(shutdownService, "namingServerUrl", "http://localhost:8080");
        setField(shutdownService, "serverPort", 8081);

        Files.createDirectories(tempDir.resolve("replicas"));
        Files.createDirectories(tempDir.resolve("local"));
    }

    @Test
    void noReplicaFiles_doesNotCallTcpClient() {
        // Empty replicas/ folder → no TCP transfers at all
        state.setCurrentId(12);
        state.setPrevId(5);
        state.setNextId(17);

        shutdownService.transferReplicasOnShutdown();

        verify(tcpClient, never()).sendFile(any(), any());
    }

    @Test
    void withReplicaFiles_lookupsAreCalledForPrevNode() throws Exception {
        Files.writeString(tempDir.resolve("replicas").resolve("doc1.txt"), "content");

        state.setCurrentId(12);
        state.setPrevId(5);
        state.setNextId(17);
        state.setIp("192.168.0.3");

        when(ipLookup.getIpForId(5)).thenReturn(null); // prevent real HTTP call
        when(fileLogService.load("doc1.txt")).thenReturn(
                new FileLog("doc1.txt", "192.168.0.5", "192.168.0.3"));

        shutdownService.transferReplicasOnShutdown();

        // ipLookup should have been called for prevId=5
        verify(ipLookup).getIpForId(5);
    }

    @Test
    void prevNodeIpIsNull_doesNotCallTcpClient() throws Exception {
        // If we can't find the prev node's IP, skip gracefully — no crash
        Files.writeString(tempDir.resolve("replicas").resolve("doc1.txt"), "content");

        state.setCurrentId(12);
        state.setPrevId(5);

        when(ipLookup.getIpForId(5)).thenReturn(null);

        shutdownService.transferReplicasOnShutdown();

        verify(tcpClient, never()).sendFile(any(), any());
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}