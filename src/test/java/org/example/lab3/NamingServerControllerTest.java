package org.example.lab3;

import org.example.lab3.controller.NamingServerController;
import org.example.lab3.model.AddNodeRequest;
import org.example.lab3.model.NodeInfo;
import org.example.lab3.service.NodeRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NamingServerControllerUnitTest {

    @Test
    void addNode_directCall() {
        NodeRegistry registry = mock(NodeRegistry.class);
        NamingServerController controller = new NamingServerController(registry);

        NodeInfo node = new NodeInfo(100, "node1", "1.2.3.4");
        when(registry.addNode("node1", "1.2.3.4")).thenReturn(node);

        AddNodeRequest req = new AddNodeRequest();
        req.setName("node1");
        req.setIp("1.2.3.4");

        var response = controller.addNode(req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("node1", response.getBody().getName());
    }

    @Test
    void getFileOwner_directCall() {
        NodeRegistry registry = mock(NodeRegistry.class);
        NamingServerController controller = new NamingServerController(registry);

        NodeInfo owner = new NodeInfo(777, "nodeX", "10.0.0.7");
        when(registry.findOwnerForFile("file.txt")).thenReturn(owner);

        var response = controller.getFileOwner("file.txt");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(777, response.getBody().getNodeId());
        assertEquals("10.0.0.7", response.getBody().getIp());
    }
}

