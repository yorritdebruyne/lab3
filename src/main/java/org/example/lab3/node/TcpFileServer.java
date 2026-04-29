package org.example.lab3.node;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.*;

/**
 * TcpFileServer listens on a TCP port for incoming file transfers.
 *
 * This runs on the REPLICA NODE — the node that will store the copy.
 *
 * Protocol (simple, length-prefixed):
 *   1. Client sends: filename length (4 bytes, int)
 *   2. Client sends: filename bytes (UTF-8)
 *   3. Client sends: sender IP length (4 bytes, int)
 *   4. Client sends: sender IP bytes (UTF-8)
 *   5. Client sends: file content length (8 bytes, long)
 *   6. Client sends: file content bytes
 *
 * After receiving:
 *   - File is saved to replicas/{filename}
 *   - A FileLog is created in logs/{filename}.json
 */
@Service
@Profile("node")
public class TcpFileServer {

    @Value("${node.tcp.port:9000}")
    private int tcpPort;

    @Value("${node.replicas.dir:replicas}")
    private String replicasDir;

    private final NodeState      state;
    private final FileLogService fileLogService;

    public TcpFileServer(NodeState state, FileLogService fileLogService) {
        this.state          = state;
        this.fileLogService = fileLogService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startServer() {
        Thread t = new Thread(this::listenLoop, "tcp-file-server");
        t.setDaemon(true);
        t.start();
    }

    private void listenLoop() {
        try {
            Files.createDirectories(Paths.get(replicasDir));
            ServerSocket serverSocket = new ServerSocket(tcpPort);
            System.out.println("[TcpFileServer] Listening on port " + tcpPort);

            while (true) {
                Socket client = serverSocket.accept();
                new Thread(() -> handleConnection(client), "tcp-receive").start();
            }
        } catch (Exception e) {
            System.err.println("[TcpFileServer] Fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleConnection(Socket socket) {
        try (DataInputStream in = new DataInputStream(socket.getInputStream())) {

            // Read filename
            int nameLen = in.readInt();
            byte[] nameBytes = new byte[nameLen];
            in.readFully(nameBytes);
            String filename = new String(nameBytes, "UTF-8");

            // Read sender IP (= download location)
            int ipLen = in.readInt();
            byte[] ipBytes = new byte[ipLen];
            in.readFully(ipBytes);
            String senderIp = new String(ipBytes, "UTF-8");

            // Read file content
            long fileLen = in.readLong();
            byte[] fileData = new byte[(int) fileLen];
            in.readFully(fileData);

            // Save to replicas/
            Path dest = Paths.get(replicasDir, filename);
            Files.write(dest, fileData);
            System.out.println("[TcpFileServer] Received: " + filename
                    + " (" + fileLen + " bytes) from " + senderIp);

            // Create FileLog — this node is the owner/replica
            FileLog log = new FileLog(filename, senderIp, state.getIp());
            fileLogService.save(log);

        } catch (Exception e) {
            System.err.println("[TcpFileServer] Error receiving file: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }
}