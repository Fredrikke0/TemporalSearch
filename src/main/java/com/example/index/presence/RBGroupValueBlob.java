package com.example.index.presence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.roaringbitmap.longlong.LongIterator;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

/**
 * Encodes a combined value blob for rb_* group-key indexes:
 * - Header: version, offsets
 * - Presence bitmap bytes (portable serialization of Roaring64NavigableMap)
 * - Doc TOC and per-doc blocks mapping sentences to value IDs.
 */
public final class RBGroupValueBlob {
    public static final int VERSION = 1;

    private final RBPresenceIndex presenceIndex;
    // Map docId -> DocBlock
    private final Map<Integer, DocBlock> docBlocks;

    public RBGroupValueBlob(RBPresenceIndex presenceIndex, Map<Integer, DocBlock> docBlocks) {
        this.presenceIndex = presenceIndex;
        this.docBlocks = docBlocks;
    }

    public RBPresenceIndex getPresenceIndex() { return presenceIndex; }
    public Map<Integer, DocBlock> getDocBlocks() { return docBlocks; }

    public byte[] toBytes() throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            // Header: version
            dos.writeInt(VERSION);

            // Serialize presence
            byte[] presenceBytes = presenceIndex.toBytes();
            dos.writeInt(presenceBytes.length);
            dos.write(presenceBytes);

            // Write number of docs
            dos.writeInt(docBlocks.size());

            // Write TOC: docId and block length. We will buffer blocks first to know lengths.
            List<Integer> docIds = new ArrayList<>(docBlocks.keySet());
            java.util.Collections.sort(docIds);

            // Buffer all blocks
            List<byte[]> blocks = new ArrayList<>(docIds.size());
            for (int docId : docIds) {
                byte[] blockBytes = serializeDocBlock(docBlocks.get(docId));
                blocks.add(blockBytes);
            }

            // Write TOC entries
            for (int i = 0; i < docIds.size(); i++) {
                dos.writeInt(docIds.get(i));
                dos.writeInt(blocks.get(i).length);
            }

            // Write blocks
            for (byte[] b : blocks) {
                dos.write(b);
            }

            dos.flush();
            return baos.toByteArray();
        }
    }

    public static RBGroupValueBlob fromBytes(byte[] bytes) throws IOException {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = dis.readInt();
            if (version != VERSION) throw new IOException("Unsupported RBGroupValueBlob version: " + version);

            int presenceLen = dis.readInt();
            if (presenceLen <= 0) throw new IOException("Invalid presence length: " + presenceLen);
            byte[] presenceBytes = dis.readNBytes(presenceLen);
            RBPresenceIndex presence = RBPresenceIndex.fromBytes(presenceBytes);

            int numDocs = dis.readInt();
            if (numDocs < 0) throw new IOException("Invalid numDocs: " + numDocs);

            int[] docIds = new int[numDocs];
            int[] lengths = new int[numDocs];
            for (int i = 0; i < numDocs; i++) {
                docIds[i] = dis.readInt();
                lengths[i] = dis.readInt();
                if (lengths[i] < 0) throw new IOException("Invalid block length: " + lengths[i]);
            }

            Map<Integer, DocBlock> blocks = new HashMap<>();
            for (int i = 0; i < numDocs; i++) {
                byte[] blockBytes = dis.readNBytes(lengths[i]);
                DocBlock block = deserializeDocBlock(blockBytes);
                blocks.put(docIds[i], block);
            }

            return new RBGroupValueBlob(presence, blocks);
        }
    }

    private static byte[] serializeDocBlock(DocBlock block) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            // S
            dos.writeInt(block.sentIds.length);
            // sentIds
            for (int s : block.sentIds) dos.writeShort(s & 0xFFFF);
            // offsets
            dos.writeInt(block.offsets.length);
            for (int off : block.offsets) dos.writeInt(off);
            // values
            dos.writeInt(block.values.length);
            for (int v : block.values) dos.writeInt(v);
            dos.flush();
            return baos.toByteArray();
        }
    }

    private static DocBlock deserializeDocBlock(byte[] bytes) throws IOException {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int S = dis.readInt();
            if (S < 0) throw new IOException("Invalid S: " + S);
            int[] sentIds = new int[S];
            for (int i = 0; i < S; i++) sentIds[i] = dis.readUnsignedShort();
            int offsetsLen = dis.readInt();
            if (offsetsLen != S + 1) throw new IOException("Invalid offsets length: " + offsetsLen + ", expected " + (S+1));
            int[] offsets = new int[offsetsLen];
            for (int i = 0; i < offsetsLen; i++) offsets[i] = dis.readInt();
            int valuesLen = dis.readInt();
            if (valuesLen < 0) throw new IOException("Invalid values length: " + valuesLen);
            int[] values = new int[valuesLen];
            for (int i = 0; i < valuesLen; i++) values[i] = dis.readInt();
            return new DocBlock(sentIds, offsets, values);
        }
    }

    public static final class DocBlock {
        public final int[] sentIds;      // ascending u16
        public final int[] offsets;      // S+1 prefix sums
        public final int[] values;       // concatenated synonym IDs (or entity IDs)

        public DocBlock(int[] sentIds, int[] offsets, int[] values) {
            this.sentIds = sentIds;
            this.offsets = offsets;
            this.values = values;
        }

        public List<Integer> getValuesForSentenceIndex(int i) {
            int start = offsets[i];
            int end = offsets[i+1];
            List<Integer> out = new ArrayList<>(end - start);
            for (int p = start; p < end; p++) out.add(values[p]);
            return out;
        }
    }

    public static Map<Integer, DocBlock> buildDocBlocksFromPresenceAndValues(RBPresenceIndex presence, Map<Integer, Map<Integer, List<Integer>>> docSentToValueIds) {
        // presence provides (docId,sentId) pairs; docSentToValueIds maps docId->(sentId->list of value ids)
        Map<Integer, DocBlock> out = new HashMap<>();
        Roaring64NavigableMap bm = presence.getBitmap();
        Map<Integer, List<Integer>> docToSentList = new HashMap<>();
        LongIterator it = bm.getLongIterator();
        while (it.hasNext()) {
            long pair = it.next();
            int docId = (int)(pair >>> 16);
            int sentId = (int)(pair & 0xFFFFL);
            docToSentList.computeIfAbsent(docId, k -> new ArrayList<>()).add(sentId);
        }
        for (Map.Entry<Integer, List<Integer>> e : docToSentList.entrySet()) {
            int docId = e.getKey();
            List<Integer> sents = e.getValue();
            java.util.Collections.sort(sents);
            int S = sents.size();
            int[] sentIds = new int[S];
            int[] offsets = new int[S+1];
            List<Integer> values = new ArrayList<>();
            Map<Integer, List<Integer>> sentToVals = docSentToValueIds.getOrDefault(docId, java.util.Collections.emptyMap());
            for (int i = 0; i < S; i++) {
                int sid = sents.get(i);
                sentIds[i] = sid;
                List<Integer> vals = sentToVals.getOrDefault(sid, java.util.Collections.emptyList());
                offsets[i] = values.size();
                values.addAll(vals);
            }
            offsets[S] = values.size();
            int[] valArr = values.stream().mapToInt(Integer::intValue).toArray();
            out.put(docId, new DocBlock(sentIds, offsets, valArr));
        }
        return out;
    }
}



