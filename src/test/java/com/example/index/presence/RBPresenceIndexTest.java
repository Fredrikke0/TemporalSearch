package com.example.index.presence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

class RBPresenceIndexTest {
    @Test
    void addContainsAndSerializationRoundTrip() throws Exception {
        RBPresenceIndex idx = new RBPresenceIndex();
        idx.add(10, 3);
        idx.add(10, 7);
        idx.add(42, 1);

        assertTrue(idx.contains(10, 3));
        assertTrue(idx.contains(10, 7));
        assertTrue(idx.contains(42, 1));
        assertFalse(idx.contains(42, 2));

        byte[] bytes = idx.toBytes();
        RBPresenceIndex restored = RBPresenceIndex.fromBytes(bytes);

        assertTrue(restored.contains(10, 3));
        assertTrue(restored.contains(10, 7));
        assertTrue(restored.contains(42, 1));
        assertFalse(restored.contains(42, 2));
    }

    @Test
    void booleanOperations() {
        RBPresenceIndex a = new RBPresenceIndex();
        a.add(1, 1); a.add(1, 2); a.add(2, 1);

        RBPresenceIndex b = new RBPresenceIndex();
        b.add(1, 2); b.add(3, 3);

        RBPresenceIndex and = (RBPresenceIndex) a.and(b);
        assertFalse(and.contains(1, 1));
        assertTrue(and.contains(1, 2));
        assertFalse(and.contains(2, 1));

        RBPresenceIndex or = (RBPresenceIndex) a.or(b);
        assertTrue(or.contains(1, 1));
        assertTrue(or.contains(1, 2));
        assertTrue(or.contains(2, 1));
        assertTrue(or.contains(3, 3));

        RBPresenceIndex andNot = (RBPresenceIndex) a.andNot(b);
        assertTrue(andNot.contains(1, 1));
        assertFalse(andNot.contains(1, 2));
        assertTrue(andNot.contains(2, 1));
    }

    @Test
    void toDocBitmapProjectsHighBits() {
        RBPresenceIndex idx = new RBPresenceIndex();
        idx.add(7, 1);
        idx.add(7, 2);
        idx.add(9, 5);
        RoaringBitmap docs = idx.toDocBitmap();
        assertTrue(docs.contains(7));
        assertTrue(docs.contains(9));
        assertFalse(docs.contains(8));
    }
}


