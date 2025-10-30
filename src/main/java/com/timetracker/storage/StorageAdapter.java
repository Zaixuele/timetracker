package com.timetracker.storage;

import com.timetracker.aggregation.MinuteRecord;

public interface StorageAdapter extends AutoCloseable {

    void persist(MinuteRecord record) throws StorageException;

    void flush() throws StorageException;

    @Override
    default void close() throws StorageException {
        flush();
    }
}
