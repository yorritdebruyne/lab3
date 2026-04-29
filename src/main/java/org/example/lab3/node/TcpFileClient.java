package org.example.lab3.node;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;

/**
 * TcpFileClient sends a file to another node's TcpFileServer over TCP.
 *
 * Protocol matches TcpFileServer exactly (length-prefixed):
 *   1. Send filename length (4 bytes)
 *   2. Send filename (UTF-8)
 *   3. Send sender IP length (4 bytes)
 *   4. Send sender IP (UTF-8)
 *   5. Send file content length (8 bytes)
 *   6. Send file content
 */
@Service
@Profile("node")
public class TcpFileClient {

    @Value("${node.tcp.port:9000}")
    private int tcpPort;

    private final NodeState state;

    public TcpFileClient(NodeState state) {
        this.state = state;
    }

    /**
     * Sends a file to the target IP.
     *
     * @param filePath  Path to the file to send
     * @param targetIp  IP address of the receiving node
     * @return true if successful, false on error
     */
    public boolean sendFile(Path filePath, String targetIp) {
        String filename = filePath.getFileName().toString();

        try (Socket socket = new Socket(targetIp, tcpPort);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            // Send filename
            byte[] nameBytes = filename.getBytes("UTF-8");
            out.writeInt(nameBytes.length);
            out.write(nameBytes);

            // Send our IP (receiver uses this as the download location)
            byte[] ipBytes = state.getIp().getBytes("UTF-8");
            out.writeInt(ipBytes.length);
            out.write(ipBytes);

            // Send file content
            byte[] fileData = Files.readAllBytes(filePath);
            out.writeLong(fileData.length);
            out.write(fileData);
            out.flush();

            System.out.println("[TcpFileClient] Sent: " + filename
                    + " (" + fileData.length + " bytes) to " + targetIp);
            return true;

        } catch (Exception e) {
            System.err.println("[TcpFileClient] Failed to send " + filename
                    + " to " + targetIp + ": " + e.getMessage());
            return false;
        }
    }
}