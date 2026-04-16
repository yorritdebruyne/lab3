package org.example.lab3;

import org.example.lab3.service.HashService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class HashServiceTest {
    private final HashService hashService = new HashService();

    @Test
    void hashToRing_isWithinBounds(){
        for(int i = 0; i < 1000; i++){
            int h = hashService.hashToRing("node-" + i);
            assertTrue(h >= 0 && h <= 32_768, "Hash out of bounds: " + h);
        }
    }

    @Test
    void hashToRing_sameInputSameOutput(){
        int h1 = hashService.hashToRing("node-1");
        int h2 = hashService.hashToRing("node-1");
        assertEquals(h1,h2);
    }
}
