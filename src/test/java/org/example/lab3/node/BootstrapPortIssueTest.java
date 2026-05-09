//package org.example.lab3.node;
//
//import org.example.lab3.service.HashService;
//import org.junit.jupiter.api.Test;
//import org.springframework.web.client.RestTemplate;
//import java.lang.reflect.Field;
//import java.lang.reflect.Method;
//import static org.mockito.Mockito.*;
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * This test detects the port-mismatch bug where nodes on the same IP
// * overwrite their own state because they target the wrong REST port.
// */
//class BootstrapPortIssueTest {
//
//    @Test
//    void reproducePortMismatchBug() throws Exception {
//        // 1. Setup NodeA (local node) simulating port 8081
//        NodeState state = new NodeState();
//        state.setCurrentId(17185); // hash of "nodeA"
//
//        MulticastReceiver receiver = new MulticastReceiver(state, new HashService());
//        setField(receiver, "peerPort", 8081);
//
//        // 2. Mock RestTemplate to see where the call goes
//        RestTemplate mockRestTemplate = mock(RestTemplate.class);
//        setField(receiver, "restTemplate", mockRestTemplate);
//
//        // 3. Simulate receiving a multicast from nodeBeta (127.0.0.1)
//        // Even if nodeBeta is on 8082, the current method signature
//        // doesn't even accept a port parameter!
//        String targetIp = "127.0.0.1";
//
//        // Invoke the private sendNeighbourUpdate method via reflection
//        Method method = MulticastReceiver.class.getDeclaredMethod(
//                "sendNeighbourUpdate", String.class, int.class, int.class);
//        method.setAccessible(true);
//        method.invoke(receiver, targetIp, 24948, 24948);
//
//        // 4. DETECTION LOGIC:
//        // If the bug exists, the call will go to 127.0.0.1:8081 (NodeA itself)
//        // instead of the intended NodeBeta port.
//
//        try {
//            // Use (Object[]) any() to match the varargs parameter of RestTemplate.put
//            verify(mockRestTemplate).put(startsWith("http://127.0.0.1:8081"), any(), (Object[]) any());
//            System.out.println("BUG DETECTED: NodeA (8081) sent a REST update to ITSELF at port 8081.");
//        } catch (AssertionError e) {
//            fail("The node did not call itself at port 8081. Mockito error: " + e.getMessage());
//        }
//    }
//
//    @Test
//    void detectIncompatibleMessageFormat() {
//        // Detects why adding the port to the multicast message breaks existing nodes
//        String messageWithPort = "nodeBeta:127.0.0.1:8082";
//        String[] parts = messageWithPort.split(":");
//
//        // The logic in MulticastReceiver:105 and MulticastListener:83 uses:
//        // if (parts.length != 2) continue;
//        assertTrue(parts.length > 2, "A message with port info has 3 parts.");
//        assertNotEquals(2, parts.length, "Existing logic will REJECT messages that include a port.");
//    }
//
//    // Helper to set private @Value fields
//    private void setField(Object target, String fieldName, Object value) throws Exception {
//        Field field = target.getClass().getDeclaredField(fieldName);
//        field.setAccessible(true);
//        field.set(target, value);
//    }
//}