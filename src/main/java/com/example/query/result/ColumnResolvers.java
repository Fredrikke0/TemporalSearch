package com.example.query.result;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.core.PostingList;
import com.example.query.executor.Bindings;
import com.example.query.executor.CellResult;
import com.example.query.model.SelectedColumn;
import com.example.query.model.SelectedCount;
import com.example.query.model.SelectedSnippet;
import com.example.query.model.SelectedStructural;
import com.example.query.model.SelectedVariable;
import com.example.query.sqlite.SqliteAccessor;

/**
 * Factory for {@link ColumnResolver} implementations.
 * Each static method returns a resolver for a specific column type.
 */
public final class ColumnResolvers {
    private static final Logger logger = LoggerFactory.getLogger(ColumnResolvers.class);

    private ColumnResolvers() {
    }

    /** Resolves TITLE from the SQLite metadata table, cached per docId. */
    public static ColumnResolver title() {
        return (cellKey, result, bindings, source, cache) -> {
            int docId = PostingList.docIdFromCellKey(cellKey);
            String cacheKey = "title_" + docId;
            return cache.computeIfAbsent(cacheKey, k -> {
                String val = SqliteAccessor.getInstance().getMetadata(source, docId, "title");
                return val != null ? val : "";
            });
        };
    }

    /** Resolves TIMESTAMP from the SQLite metadata table, cached per docId. */
    public static ColumnResolver timestamp() {
        return (cellKey, result, bindings, source, cache) -> {
            int docId = PostingList.docIdFromCellKey(cellKey);
            String cacheKey = "timestamp_" + docId;
            return cache.computeIfAbsent(cacheKey, k -> {
                String timestampStr = SqliteAccessor.getInstance().getMetadata(source, docId, "timestamp");
                if (timestampStr != null && !timestampStr.isEmpty()) {
                    try {
                        return LocalDateTime.parse(timestampStr).toLocalDate();
                    } catch (Exception e) {
                        logger.warn("Failed to parse timestamp '{}' for docId {}", timestampStr, docId, e);
                    }
                }
                return null;
            });
        };
    }

    /** Extracts the document ID from the cell key. */
    public static ColumnResolver documentId() {
        return (cellKey, result, bindings, source, cache) -> PostingList.docIdFromCellKey(cellKey);
    }

    /** Extracts the sentence ID from the cell key. */
    public static ColumnResolver sentenceId() {
        return (cellKey, result, bindings, source, cache) -> {
            int sentId = PostingList.sentIdFromCellKey(cellKey);
            return sentId >= 0 ? sentId : null;
        };
    }

    /**
     * Resolves a variable value by scanning the bindings for the qualified variable
     * name.
     */
    public static ColumnResolver variable(String qualifiedName) {
        return (cellKey, result, bindings, source, cache) -> {
            Bindings b = result.bindings();
            if (b == null || bindings == null || bindings.isEmpty()) {
                return null;
            }
            for (int bi : bindings) {
                String varName = b.variableNameAt(bi);
                if (qualifiedName.equals(varName)) {
                    Object val = b.valueAt(bi);
                    return val != null ? val.toString() : null;
                }
            }
            return null;
        };
    }

    /**
     * Resolves a snippet: fetches doc text from SQLite, extracts a window around
     * the variable's position.
     */
    public static ColumnResolver snippet(String qualifiedVarName, int windowSize) {
        return (cellKey, result, bindings, source, cache) -> {
            int docId = PostingList.docIdFromCellKey(cellKey);
            String textCacheKey = "docText_" + source + "_" + docId;
            String docText = (String) cache.get(textCacheKey);
            if (docText == null) {
                docText = SqliteAccessor.getInstance().getDocumentText(source, docId);
                if (docText != null) {
                    cache.put(textCacheKey, docText);
                } else {
                    return "[Error: Document text not available for snippet]";
                }
            }

            // Try to find the variable's occurrence position from bindings
            Bindings b = result.bindings();
            int matchStart = 0;
            int matchEnd = Math.min(100, docText.length());

            if (b != null && bindings != null) {
                for (int bi : bindings) {
                    if (qualifiedVarName.equals(b.variableNameAt(bi))) {
                        // Without exact char offsets in bindings, use start of doc
                        break;
                    }
                }
            }

            int snippetStart = Math.max(0, matchStart - windowSize);
            int snippetEnd = Math.min(docText.length(), matchEnd + windowSize);
            if (snippetStart >= snippetEnd) {
                snippetStart = Math.max(0, matchStart - 5);
                snippetEnd = Math.min(docText.length(), matchEnd + 5);
            }

            String textContent = docText.substring(snippetStart, snippetEnd);
            textContent = textContent.replaceAll("\\R", " ");
            String prefix = (snippetStart > 0) ? "..." : "";
            String suffix = (snippetEnd < docText.length()) ? "..." : "";

            return prefix + textContent.trim() + suffix;
        };
    }

    /**
     * Dispatches a {@link SelectedColumn} to the appropriate resolver.
     * COUNT columns return a no-op resolver (they are computed via aggregation).
     */
    public static ColumnResolver fromSelectedColumn(SelectedColumn sc) {
        return switch (sc) {
            case SelectedStructural s -> switch (s.field()) {
                case TITLE -> title();
                case TIMESTAMP -> timestamp();
                case DOCUMENT_ID -> documentId();
                case SENTENCE_ID -> sentenceId();
                default -> (ck, r, bi, src, cache) -> null;
            };
            case SelectedVariable sv -> variable(sv.qualifiedName());
            case SelectedSnippet ss -> snippet(ss.qualifiedVariableName(), ss.windowSize());
            case SelectedCount __ -> (ck, r, bi, src, cache) -> null; // via aggregation
        };
    }
}
