package com.timetracker.lifecycle;

public interface TimeTrackerApplication extends AutoCloseable {

    void start() throws Exception;

    void pause();

    void resume();

    void stop() throws Exception;
}
