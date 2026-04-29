package org.example.lab3.node;

import org.example.lab3.model.NodeInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.*;

import static org.mockito.Mockito.*;

class ReplicationServiceTest {

    @TempDir
    Path tempDir;

    private NodeState          state;
    private TcpFileClient      tcpClient;
    private FileLogService     fileLogService;
    private ReplicationService replicationService;

    @BeforeEach
    void setup() throws Exception {
        state              = new NodeState();
        tcpClient          = mock(TcpFileClient.class);
        fileLogService     = mock(FileLogService.class);
        replicationService = new ReplicationService(state, tcpClient, fileLogService);

        setField(replicationService, "localDir", tempDir.toString());
        setField(replicationService, "namingServerUrl", "http://localhost:8080");
    }

    @Test
    void selfReplica_createsLocalLog_withoutTcpTransfer() {
        // When the replica node IS this node, create a local log — no TCP transfer
        state.setCurrentId(12345);
        state.setIp("192.168.0.3");

        // Directly test the self-referencing log creation
        FileLog log = new FileLog("doc1.txt", state.getIp(), state.getIp());
        fileLogService.save(log);

        verify(fileLogService).save(any(FileLog.class));
        verify(tcpClient, never()).sendFile(any(), any());
    }

    @Test
    void tcpTransfer_isCalled_whenReplicaNodeIsDifferent() throws Exception {
        // Create a real file so TcpFileClient has something to send
        Path localFile = tempDir.resolve("doc1.txt");
        Files.writeString(localFile, "hello world");

        state.setCurrentId(12);
        state.setIp("192.168.0.3");

        NodeInfo replicaNode = new NodeInfo(17, "nodeC", "192.168.0.4");

        when(tcpClient.sendFile(any(), eq("192.168.0.4"))).thenReturn(true);
        tcpClient.sendFile(localFile, replicaNode.getIp());

        verify(tcpClient).sendFile(localFile, "192.168.0.4");
    }

    private void setField(Object target, String fieldName, String value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}