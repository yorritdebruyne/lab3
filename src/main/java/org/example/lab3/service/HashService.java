package org.example.lab3.service;

import org.springframework.stereotype.Service;

@Service
public class HashService {
    private static final int MAX = 2_147_483_647;
    private static final int MIN = -2_147_483_647;
    private static final int RANGE = 32_768;

    public int hashToRing(String input){
        int hc = input.hashCode();
        long shifted = (long) hc + MAX;
        long denom = (long) MAX + Math.abs(MIN);
        long result = shifted * RANGE / denom;
        return (int) result;
    }
}
