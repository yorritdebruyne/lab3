package org.example.lab3.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Allows the GUI to start a pre-created Docker container for a ring node.
 *
 * How it works:
 *   1. The naming server has the Docker socket mounted at /var/run/docker.sock
 *   2. Docker exposes a REST API over that socket
 *   3. We call POST /containers/{name}/start via curl to start the container
 *
 * Pre-condition: the container must already exist in a stopped state.
 *   Run once on the VM before the demo:  sudo docker compose create node-d
 *
 * After a graceful shutdown (via GUI "Remove"), the container stops but is NOT
 * deleted, so it can be re-launched in the same session without any VM commands.
 */
@RestController
@RequestMapping("/api/nodes")
@Profile("naming-server")
@CrossOrigin(origins = "*")
public class NodeLaunchController {

    private static final String DOCKER_SOCKET = "/var/run/docker.sock";

    /**
     * Starts a stopped Docker Compose container for the given service name.
     *
     * POST /api/nodes/launch/{serviceName}
     *   serviceName: the Docker Compose service name (e.g. "node-d")
     *
     * Docker Compose names containers as: {project}-{service}-{replica}
     * Our project folder is "projectds", so node-d becomes "projectds-node-d-1".
     *
     * Docker API responses:
     *   204 = container started successfully
     *   304 = container was already running
     *   404 = container does not exist (needs 'docker compose create node-d')
     */
    @PostMapping("/launch/{serviceName}")
    public ResponseEntity<Map<String, String>> launchNode(@PathVariable String serviceName) {
        String containerName = "projectds-" + serviceName + "-1";
        try {
            String statusCode = dockerPost("/containers/" + containerName + "/start");

            return switch (statusCode) {
                case "204" -> ResponseEntity.ok(Map.of(
                    "status", "started",
                    "container", containerName
                ));
                case "304" -> {
                    // Container still running — stop it so BootstrapService re-runs fresh.
                    dockerPost("/containers/" + containerName + "/stop?t=3");
                    dockerPost("/containers/" + containerName + "/start");
                    yield ResponseEntity.ok(Map.of(
                        "status", "restarted",
                        "container", containerName
                    ));
                }
                case "404" -> ResponseEntity.status(404).body(Map.of(
                    "error", "Container not found. Run: sudo docker compose create " + serviceName,
                    "container", containerName
                ));
                default -> ResponseEntity.status(500).body(Map.of(
                    "error", "Docker returned HTTP " + statusCode,
                    "container", containerName
                ));
            };
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/stop/{serviceName}")
    public ResponseEntity<Map<String, String>> stopNode(@PathVariable String serviceName) {
        String containerName = "projectds-" + serviceName + "-1";
        try {
            dockerPost("/containers/" + containerName + "/stop?t=3");
            return ResponseEntity.ok(Map.of("status", "stopped", "container", containerName));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private String dockerPost(String path) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "curl", "--unix-socket", DOCKER_SOCKET,
            "-s", "-o", "/dev/null", "-w", "%{http_code}",
            "-X", "POST",
            "http://localhost" + path
        );
        pb.redirectErrorStream(false);
        Process process = pb.start();
        String code = new String(process.getInputStream().readAllBytes()).trim();
        process.waitFor();
        return code;
    }
}
