package com.timetracker.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.timetracker.sampling.AppIdentity;
import com.timetracker.sampling.ForegroundSample;
import com.timetracker.sampling.ForegroundSampler;
import com.timetracker.sampling.SamplingException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Foreground sampler backed by Win32 APIs (User32 + Kernel32).
 */
public class Win32ForegroundSampler implements ForegroundSampler {

    private static final int WINDOW_TITLE_MAX_CHARS = 1024;

    private final boolean captureWindowTitle;

    public Win32ForegroundSampler(boolean captureWindowTitle) {
        this.captureWindowTitle = captureWindowTitle;
    }

    @Override
    public ForegroundSample sample() throws SamplingException {
        Instant now = Instant.now();

        HWND foregroundWindow = User32.INSTANCE.GetForegroundWindow();
        if (foregroundWindow == null || Pointer.nativeValue(foregroundWindow.getPointer()) == 0) {
            return new ForegroundSample(now, Optional.empty(), Optional.empty());
        }

        int processId = getProcessId(foregroundWindow);
        if (processId <= 0) {
            return new ForegroundSample(now, Optional.empty(), Optional.empty());
        }

        String exePath = queryExecutablePath(processId)
                .map(this::normalizePath)
                .orElse(null);

        if (exePath == null || exePath.isBlank()) {
            return new ForegroundSample(now, Optional.empty(), Optional.empty());
        }

        String displayName = deriveDisplayName(exePath);

        Optional<String> windowTitle = captureWindowTitle
                ? Optional.ofNullable(readWindowTitle(foregroundWindow)).filter(s -> !s.isBlank())
                : Optional.empty();

        AppIdentity app = new AppIdentity(exePath, displayName, processId);
        return new ForegroundSample(now, Optional.of(app), windowTitle);
    }

    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return rawPath;
        }
        try {
            return Path.of(rawPath).toAbsolutePath().normalize().toString();
        } catch (Exception ex) {
            return rawPath;
        }
    }

    private int getProcessId(HWND hwnd) throws SamplingException {
        IntByReference processId = new IntByReference();
        int threadId = User32.INSTANCE.GetWindowThreadProcessId(hwnd, processId);
        if (threadId == 0) {
            int error = Kernel32.INSTANCE.GetLastError();
            throw new SamplingException("GetWindowThreadProcessId failed with error " + error);
        }
        return processId.getValue();
    }

    private Optional<String> queryExecutablePath(int processId) throws SamplingException {
        WinNT.HANDLE processHandle = Kernel32.INSTANCE.OpenProcess(
                WinNT.PROCESS_QUERY_LIMITED_INFORMATION | WinNT.PROCESS_VM_READ,
                false,
                processId);

        if (processHandle == null || Pointer.nativeValue(processHandle.getPointer()) == 0) {
            // Fallback to more permissive flag set; some processes may deny VM_READ
            processHandle = Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, processId);
        }

        if (processHandle == null || Pointer.nativeValue(processHandle.getPointer()) == 0) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(Kernel32Util.QueryFullProcessImageName(processHandle, 0));
        } catch (RuntimeException ex) {
            throw new SamplingException("QueryFullProcessImageName failed", ex);
        } finally {
            Kernel32.INSTANCE.CloseHandle(processHandle);
        }
    }

    private String deriveDisplayName(String exePath) {
        Path path = Path.of(exePath);
        Path fileName = path.getFileName();
        if (fileName == null) {
            return exePath;
        }
        String baseName = fileName.toString();
        int dotIndex = baseName.lastIndexOf('.');
        String withoutExtension = dotIndex > 0 ? baseName.substring(0, dotIndex) : baseName;
        return capitalize(withoutExtension);
    }

    private String capitalize(String input) {
        if (input.isEmpty()) {
            return input;
        }
        return input.substring(0, 1).toUpperCase(Locale.ROOT) + input.substring(1);
    }

    private String readWindowTitle(HWND hwnd) {
        char[] buffer = new char[WINDOW_TITLE_MAX_CHARS];
        int length = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.length);
        if (length <= 0) {
            return null;
        }
        return new String(buffer, 0, length);
    }
}
