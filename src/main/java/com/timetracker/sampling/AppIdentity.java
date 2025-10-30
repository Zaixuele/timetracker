package com.timetracker.sampling;

import java.nio.file.Path;

public record AppIdentity(
        String executablePath,
        String displayName,
        int processId
) {

    public String executableDirectory() {
        return Path.of(executablePath).getParent() != null
                ? Path.of(executablePath).getParent().toString()
                : "";
    }
}
