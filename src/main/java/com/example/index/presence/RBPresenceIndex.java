package com.example.index.presence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.roaringbitmap.longlong.LongIterator;

/**
 * Roaring-based implementation backed by Roaring64NavigableMap using portable serialization.
 */
public final class RBPresenceIndex implements PositionPresenceIndex {
    static {
        // Ensure portable serialization per design
        Roaring64NavigableMap.SERIALIZATION_MODE = Roaring64NavigableMap.SERIALIZATION_MODE_PORTABLE;
    }
    private final Roaring64NavigableMap bitmap;

    public RBPresenceIndex() {
        this.bitmap = new Roaring64NavigableMap();
    }

    private RBPresenceIndex(Roaring64NavigableMap bm) {
        this.bitmap = bm;
    }

    public static long pair(int docId, int sentId) {
        return ((docId & 0xFFFFFFFFL) << 16) | (sentId & 0xFFFFL);
    }

    @Override
    public void add(int docId, int sentId) {
        if (sentId < 0 || sentId > 0xFFFF) {
            throw new IllegalArgumentException("sentId out of range [0,65535]: " + sentId);
        }
        bitmap.add(pair(docId, sentId));
    }

    @Override
    public boolean contains(int docId, int sentId) {
        if (sentId < 0 || sentId > 0xFFFF) return false;
        return bitmap.contains(pair(docId, sentId));
    }

    @Override
    public PositionPresenceIndex and(PositionPresenceIndex other) {
        Objects.requireNonNull(other, "other");
        if (!(other instanceof RBPresenceIndex o)) {
            throw new IllegalArgumentException("Mismatched implementation");
        }
        Roaring64NavigableMap result = new Roaring64NavigableMap();
        result.or(this.bitmap);
        result.and(o.bitmap);
        return new RBPresenceIndex(result);
    }

    @Override
    public PositionPresenceIndex or(PositionPresenceIndex other) {
        Objects.requireNonNull(other, "other");
        if (!(other instanceof RBPresenceIndex o)) {
            throw new IllegalArgumentException("Mismatched implementation");
        }
        Roaring64NavigableMap result = new Roaring64NavigableMap();
        result.or(this.bitmap);
        result.or(o.bitmap);
        return new RBPresenceIndex(result);
    }

    @Override
    public PositionPresenceIndex andNot(PositionPresenceIndex other) {
        Objects.requireNonNull(other, "other");
        if (!(other instanceof RBPresenceIndex o)) {
            throw new IllegalArgumentException("Mismatched implementation");
        }
        Roaring64NavigableMap result = new Roaring64NavigableMap();
        result.or(this.bitmap);
        result.andNot(o.bitmap);
        return new RBPresenceIndex(result);
    }

    @Override
    public RoaringBitmap toDocBitmap() {
        RoaringBitmap docs = new RoaringBitmap();
        LongIterator it = bitmap.getLongIterator();
        int currentDocId = Integer.MIN_VALUE;
        while (it.hasNext()) {
            long value = it.next();
            int docId = (int) (value >>> 16);
            if (docId != currentDocId) {
                docs.add(docId);
                currentDocId = docId;
            }
        }
        return docs;
    }

    @Override
    public byte[] toBytes() throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            bitmap.serialize(dos);
            dos.flush();
            return baos.toByteArray();
        }
    }

    public static RBPresenceIndex fromBytes(byte[] bytes) throws IOException {
        Roaring64NavigableMap bm = new Roaring64NavigableMap();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             DataInputStream dis = new DataInputStream(bais)) {
            bm.deserialize(dis);
        }
        return new RBPresenceIndex(bm);
    }

    public Roaring64NavigableMap getBitmap() {
        return bitmap;
    }
}


