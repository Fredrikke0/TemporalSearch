package com.example.logging;

import java.util.Random;

/**
 * Utility class for controlling log volume through sampling.
 * Helps prevent excessive logging in high-throughput scenarios.
 */
public class LogSampler {
    private static final double DEFAULT_SAMPLE_RATE = 0.001; // Log 0.1% of documents by default
    private final Random random;
    private final double sampleRate;

    public LogSampler() {
        this(DEFAULT_SAMPLE_RATE);
    }

    public LogSampler(double sampleRate) {
        if (sampleRate <= 0 || sampleRate > 1) {
            throw new IllegalArgumentException("Sample rate must be between 0 and 1");
        }
        this.sampleRate = sampleRate;
        this.random = new Random();
    }

    /**
     * Determines whether the current operation should be logged based on sampling rate.
     *
     * @return true if the operation should be logged, false otherwise
     */
    public boolean shouldLog() {
        return random.nextDouble() < sampleRate;
    }

    /**
     * Gets the current sampling rate.
     *
     * @return the sampling rate as a double between 0 and 1
     */
    public double getSampleRate() {
        return sampleRate;
    }
}