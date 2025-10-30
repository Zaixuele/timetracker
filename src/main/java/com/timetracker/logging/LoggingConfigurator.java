package com.timetracker.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.FileSize;
import com.timetracker.config.LoggingConfig;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class LoggingConfigurator {

    private static final String FILE_APPENDER_NAME = "FILE";

    private LoggingConfigurator() {
    }

    public static void apply(LoggingConfig config) throws IOException {
        Objects.requireNonNull(config, "config");
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        synchronized (context) {
            configureRootLevel(context, config);
            configureFileAppender(context, config);
        }
    }

    private static void configureRootLevel(LoggerContext context, LoggingConfig config) {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.valueOf(config.level()));
    }

    private static void configureFileAppender(LoggerContext context, LoggingConfig config) throws IOException {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(FILE_APPENDER_NAME);

        Path logPath = Path.of(config.file()).toAbsolutePath();
        if (logPath.getParent() != null) {
            Files.createDirectories(logPath.getParent());
        }

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n");
        encoder.start();

        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        appender.setName(FILE_APPENDER_NAME);
        appender.setContext(context);
        appender.setFile(logPath.toString());
        appender.setEncoder(encoder);

        int rotationCount = config.rotationCount() == null ? 5 : config.rotationCount();
        int maxSizeMb = config.maxSizeMB() == null ? 5 : config.maxSizeMB();

        FixedWindowRollingPolicy rollingPolicy = new FixedWindowRollingPolicy();
        rollingPolicy.setContext(context);
        rollingPolicy.setParent(appender);
        rollingPolicy.setFileNamePattern(logPath + ".%i");
        rollingPolicy.setMinIndex(1);
        rollingPolicy.setMaxIndex(Math.max(1, rotationCount));
        rollingPolicy.start();

        SizeBasedTriggeringPolicy<ILoggingEvent> triggeringPolicy = new SizeBasedTriggeringPolicy<>();
        triggeringPolicy.setContext(context);
        triggeringPolicy.setMaxFileSize(FileSize.valueOf(maxSizeMb + "MB"));
        triggeringPolicy.start();

        appender.setRollingPolicy(rollingPolicy);
        appender.setTriggeringPolicy(triggeringPolicy);
        appender.start();

        root.addAppender(appender);
    }
}
