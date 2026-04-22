package org.example.lab3.node;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the ring-position logic from MulticastReceiver.
 * These two helper methods are the most critical logic in the whole lab —
 * they decide which node becomes the new neighbour of a joining node.
 */
class MulticastReceiverLogicTest {

    // Copy of the two helper methods from MulticastReceiver
    private boolean isBetweenNext(int myId, int newId, int myNextId) {
        if (myId < myNextId) {
            return myId < newId && newId < myNextId;
        } else {
            return newId > myId || newId < myNextId;
        }
    }

    private boolean isBetweenPrev(int myPrevId, int newId, int myId) {
        if (myPrevId < myId) {
            return myPrevId < newId && newId < myId;
        } else {
            return newId < myId || newId > myPrevId;
        }
    }

    @Test
    void caseA_newNodeBetweenMeAndNext_straightCase() {
        assertTrue(isBetweenNext(5, 7, 12), "7 should fall between 5 and 12");
    }

    @Test
    void caseA_newNodeNotBetweenMeAndNext() {
        assertFalse(isBetweenNext(5, 3, 12), "3 should NOT fall between 5 and 12");
    }

    @Test
    void caseA_wrapAround_iAmLargestNode() {
        // me=17, next=5 (wraps), new=20
        assertTrue(isBetweenNext(17, 20, 5), "20 should fall after 17 in wrap-around ring");
    }

    @Test
    void caseA_wrapAround_newNodeBeforeWrapPoint() {
        // me=17, next=5 (wraps), new=3
        assertTrue(isBetweenNext(17, 3, 5), "3 should fall before wrap point 5");
    }

    @Test
    void caseB_newNodeBetweenPrevAndMe_straightCase() {
        assertTrue(isBetweenPrev(5, 7, 12), "7 should fall between prev=5 and me=12");
    }

    @Test
    void caseB_newNodeNotBetweenPrevAndMe() {
        assertFalse(isBetweenPrev(5, 15, 12), "15 should NOT fall between prev=5 and me=12");
    }

    @Test
    void caseB_wrapAround_iAmSmallestNode() {
        // me=5, prev=17 (wrap), new=20
        assertTrue(isBetweenPrev(17, 20, 5), "20 should fall between prev=17 and wrap");
    }

    // === Slide example 2 exact numbers: nodes 5, 12, 17 — new node 7 joins ===

    @Test
    void slideExample2_node1_caseA() {
        // node1 (me=5, next=12): should detect new node (7) as its new next
        assertTrue(isBetweenNext(5, 7, 12),
                "Slide example 2: node1 should detect new node as its new next");
    }

    @Test
    void slideExample2_node2_caseB() {
        // node2 (me=12, prev=5): should detect new node (7) as its new prev
        assertTrue(isBetweenPrev(5, 7, 12),
                "Slide example 2: node2 should detect new node as its new prev");
    }

    @Test
    void slideExample2_node3_noMatch() {
        // node3 (me=17, prev=12, next=5 wrap): new node 7 is NOT its neighbour
        assertFalse(isBetweenNext(17, 7, 5),  "node3 Case A should not match");
        assertFalse(isBetweenPrev(12, 7, 17), "node3 Case B should not match");
    }
}