package com.timetracker.config;

public interface ConfigListener {
    void onConfigReload(AppConfig newConfig);
}
