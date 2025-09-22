package com.example.index.presence;

import java.io.IOException;

/**
 * Minimal adapter API for presence bitmaps over (docId, sentId) pairs.
 */
public interface PositionPresenceIndex {
    void add(int docId, int sentId);
    boolean contains(int docId, int sentId);
    PositionPresenceIndex and(PositionPresenceIndex other);
    PositionPresenceIndex or(PositionPresenceIndex other);
    PositionPresenceIndex andNot(PositionPresenceIndex other);
    org.roaringbitmap.RoaringBitmap toDocBitmap();
    byte[] toBytes() throws IOException;
}


