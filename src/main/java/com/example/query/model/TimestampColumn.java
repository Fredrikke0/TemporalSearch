package com.example.query.model;

import com.example.core.IndexAccessInterface;
import com.example.query.binding.MatchDetail;
import com.example.query.sqlite.SqliteAccessor;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;
import tech.tablesaw.columns.Column;

import java.util.Map;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a TIMESTAMP function in the SELECT clause of a query.
 * This column displays the timestamp of when the document was indexed.
 */
public class TimestampColumn implements SelectColumn {
    private static final Logger logger = LoggerFactory.getLogger(TimestampColumn.class);
    
    /**
     * Creates a new timestamp column.
     */
    public TimestampColumn() {
        // No configuration needed
    }
    
    @Override
    public String getColumnName() {
        return "timestamp";
    }
    
    @Override
    public Column<?> createColumn() {
        Column<?> col = StringColumn.create(getColumnName());
        logger.debug("TimestampColumn creating column named '{}' of type {}", getColumnName(), col.type());
        return col;
    }
    
    @Override
    public void populateColumn(Table table, int rowIndex, List<?> detailsForUnit, 
                               String source,
                               Map<String, IndexAccessInterface> indexes) {
        StringColumn column = (StringColumn) table.column(getColumnName());
        
        // Get documentId from the first detail in the list
        if (detailsForUnit == null || detailsForUnit.isEmpty()) {
            logger.warn("Received empty detail list for row {} in TimestampColumn.", rowIndex);
            column.setMissing(rowIndex);
            return;
        }
        // Cast to List<MatchDetail>
        MatchDetail detail = (MatchDetail) detailsForUnit.get(0);
        int documentId = detail.getDocumentId();

        logger.debug("Getting timestamp for document {} from source {}", documentId, source);
        
        // Get timestamp using the SqliteAccessor singleton and the passed source
        String value = SqliteAccessor.getInstance().getMetadata(source, documentId, "timestamp");
        
        column.set(rowIndex, value != null ? value : "");
    }
    
    @Override
    public String toString() {
        return "TIMESTAMP";
    }
} 