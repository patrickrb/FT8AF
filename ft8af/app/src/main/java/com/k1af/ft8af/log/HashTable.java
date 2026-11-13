package com.k1af.ft8af.log;
/**
 * Two-key ("row" + "column") map used to record which SWL QSOs have already been
 * logged, so the same C1-&gt;C2 contact is not written to the database on every
 * decode cycle. A plain HashMap cannot key on two callsigns, so this delegates to
 * Google guava's {@link com.google.common.collect.HashBasedTable}.
 *
 * <p>Historically this class was a stub whose {@code contains(...)} always returned
 * {@code false} and whose {@code put(...)} was a no-op. That silently disabled SWL
 * QSO deduplication ({@link SWLQsoList#findSwlQso} keys off it): every repeat of a
 * closing marker re-logged the same QSO. Delegating to a real table restores the
 * dedup.
 *
 * <p>The callers relied on the stub tolerating null keys. guava tables reject null
 * keys/values, but an SWL callsign can be absent, and the detector runs on the
 * decode thread, so a lookup/mutation on a null key is treated as
 * "not present"/no-op instead of throwing.
 *
 * BG7YOZ
 * 2023-03-20
 *
 */

import androidx.annotation.Nullable;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

@SuppressWarnings({"unchecked", "rawtypes"})
public class HashTable implements Table {
    // Raw Table so the delegated return types erase to the same raw types the raw
    // Table interface this class implements expects (e.g. Set<Cell>, Map).
    //
    // findSwlQso runs on the decode thread, and concurrent decode passes can
    // overlap (MainViewModel: slot N's late/deep pass vs slot N+1's early pass,
    // #398), so contains/put/remove can be called from two threads at once.
    // HashBasedTable is not thread-safe, so wrap it in a synchronized view to
    // keep concurrent mutation from corrupting the backing HashMap.
    private final Table delegate = Tables.synchronizedTable(HashBasedTable.create());

    @Override
    public boolean contains(@Nullable Object rowKey, @Nullable Object columnKey) {
        if (rowKey == null || columnKey == null) return false;
        return delegate.contains(rowKey, columnKey);
    }

    @Override
    public boolean containsRow(@Nullable Object rowKey) {
        return rowKey != null && delegate.containsRow(rowKey);
    }

    @Override
    public boolean containsColumn(@Nullable Object columnKey) {
        return columnKey != null && delegate.containsColumn(columnKey);
    }

    @Override
    public boolean containsValue(@Nullable Object value) {
        return value != null && delegate.containsValue(value);
    }

    @Nullable
    @Override
    public Object get(@Nullable Object rowKey, @Nullable Object columnKey) {
        if (rowKey == null || columnKey == null) return null;
        return delegate.get(rowKey, columnKey);
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Nullable
    @Override
    public Object put(Object rowKey, Object columnKey, Object value) {
        if (rowKey == null || columnKey == null || value == null) return null;
        return delegate.put(rowKey, columnKey, value);
    }

    @Override
    public void putAll(Table table) {
        delegate.putAll(table);
    }

    @Nullable
    @Override
    public Object remove(@Nullable Object rowKey, @Nullable Object columnKey) {
        if (rowKey == null || columnKey == null) return null;
        return delegate.remove(rowKey, columnKey);
    }

    @Override
    public Map row(Object rowKey) {
        if (rowKey == null) return Collections.emptyMap();
        return delegate.row(rowKey);
    }

    @Override
    public Map column(Object columnKey) {
        if (columnKey == null) return Collections.emptyMap();
        return delegate.column(columnKey);
    }

    @Override
    public Set<Cell> cellSet() {
        return delegate.cellSet();
    }

    @Override
    public Set rowKeySet() {
        return delegate.rowKeySet();
    }

    @Override
    public Set columnKeySet() {
        return delegate.columnKeySet();
    }

    @Override
    public Collection values() {
        return delegate.values();
    }

    @Override
    public Map rowMap() {
        return delegate.rowMap();
    }

    @Override
    public Map columnMap() {
        return delegate.columnMap();
    }
}
