package org.example.lab3.node;

import org.example.lab3.model.NodeInfo;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * PingScheduler periodically checks whether our neighbours are still alive.
 *
 * Every N milliseconds (configured by ping.interval.ms) it calls
 * GET /node/ping on both the previous and next neighbour.
 *
 * If the call SUCCEEDS (HTTP 200 "pong") → neighbour is alive, do nothing.
 * If the call FAILS (exception) → neighbour has crashed, hand off to FailureHandler.
 *
 * === Port handling ===
 *
 * NodeIpLookup now returns "ip:port" (e.g. "127.0.0.1:8082") so this class
 * no longer needs its own peerPort field. The correct port for each neighbour
 * comes directly from the naming server, fixing localhost multi-node testing.
 */
@Service
@Profile("node")
@EnableScheduling
public class PingScheduler {

    private final NodeState      state;
    private final FailureHandler failureHandler;
    private final NodeIpLookup   ipLookup;
    // 3-second timeout: a dead container should fail fast, not block the scheduler thread
    private final RestTemplate   restTemplate = buildRestTemplate(3000);

    public PingScheduler(NodeState state, FailureHandler failureHandler, NodeIpLookup ipLookup) {
        this.state          = state;
        this.failureHandler = failureHandler;
        this.ipLookup       = ipLookup;
    }

    @Scheduled(fixedRateString = "${ping.interval.ms:5000}")
    public void pingNeighbours() {
        if (state.getCurrentId() == -1) return;

        pingOne(state.getPrevId(), "prev");
        pingOne(state.getNextId(), "next");
    }

    private void pingOne(int neighbourId, String label) {
        if (neighbourId == state.getCurrentId()) return;

        NodeInfo node = ipLookup.getNodeForId(neighbourId);
        if (node == null) {
            System.err.println("[PingScheduler] " + label + " node (id=" + neighbourId
                    + ") not found in registry — starting failure recovery.");
            failureHandler.handleFailure(neighbourId, null);
            return;
        }

        String addr = node.getIp() + ":" + node.getPort();
        try {
            restTemplate.getForObject("http://" + addr + "/node/ping", String.class);
        } catch (Exception e) {
            System.err.println("[PingScheduler] " + label + " node (id=" + neighbourId
                    + ", addr=" + addr + ") is unreachable — starting failure recovery.");
            // Pass the name so FailureHandler can remove the dead node from the naming server
            failureHandler.handleFailure(neighbourId, node.getName());
        }
    }

    private static RestTemplate buildRestTemplate(int timeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }
}