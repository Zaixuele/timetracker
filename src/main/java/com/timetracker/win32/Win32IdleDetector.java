package com.timetracker.win32;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;
import com.timetracker.sampling.IdleDetector;
import com.timetracker.sampling.SamplingException;

import java.time.Duration;

/**
 * Reports the duration since the last user input using GetLastInputInfo.
 */
public class Win32IdleDetector implements IdleDetector {

    @Override
    public Duration timeSinceLastInput() throws SamplingException {
        WinUser.LASTINPUTINFO lastInputInfo = new WinUser.LASTINPUTINFO();
        lastInputInfo.cbSize = lastInputInfo.size();

        boolean success = User32.INSTANCE.GetLastInputInfo(lastInputInfo);
        if (!success) {
            int error = Kernel32.INSTANCE.GetLastError();
            throw new SamplingException("GetLastInputInfo failed with error " + error);
        }

        long tickCount = Kernel32.INSTANCE.GetTickCount64();
        long lastInputTick = Integer.toUnsignedLong(lastInputInfo.dwTime);

        long delta = tickCount - lastInputTick;
        return Duration.ofMillis(delta);
    }
}
