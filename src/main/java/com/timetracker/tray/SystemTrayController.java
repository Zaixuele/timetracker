package com.timetracker.tray;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class SystemTrayController implements TrayController {

    private static final DateTimeFormatter REPORT_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    private volatile Path reportDirectory;
    private volatile Path dataDirectory;
    private final Path configFile;

    private TrayIcon trayIcon;
    private TrayActions actions;
    private MenuItem toggleTrackingItem;
    private MenuItem openReportItem;
    private MenuItem openDataItem;
    private MenuItem openConfigItem;
    private MenuItem exitItem;
    private final AtomicReference<TrayStatus> statusRef = new AtomicReference<>();

    public SystemTrayController(Path reportDirectory, Path dataDirectory, Path configFile) {
        this.reportDirectory = Objects.requireNonNull(reportDirectory, "reportDirectory");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.configFile = Objects.requireNonNull(configFile, "configFile");
    }

    @Override
    public void init(TrayActions actions) throws TrayException {
        Objects.requireNonNull(actions, "actions");
        if (!SystemTray.isSupported()) {
            throw new TrayException("System tray not supported on this platform.");
        }
        this.actions = actions;
        EventQueue.invokeLater(() -> {
            try {
                setupTray(actions);
            } catch (AWTException e) {
                throw new RuntimeException("Failed to initialise system tray", e);
            }
        });
    }

    private void setupTray(TrayActions actions) throws AWTException {
        PopupMenu menu = new PopupMenu();

        toggleTrackingItem = new MenuItem();
        toggleTrackingItem.addActionListener(e -> actions.onToggleTracking().run());
        menu.add(toggleTrackingItem);
        updateToggleLabel(actions.isTrackingActive().getAsBoolean());

        openReportItem = new MenuItem("Open Today's Report");
        openReportItem.addActionListener(this::openTodayReport);
        menu.add(openReportItem);

        openDataItem = new MenuItem("Open Data Directory");
        openDataItem.addActionListener(e -> openPath(dataDirectory));
        menu.add(openDataItem);

        openConfigItem = new MenuItem("Open Config File");
        openConfigItem.addActionListener(e -> openPath(configFile));
        menu.add(openConfigItem);

        menu.addSeparator();

        exitItem = new MenuItem("Exit");
        exitItem.addActionListener(e -> actions.onExit().run());
        menu.add(exitItem);

        trayIcon = new TrayIcon(createIconImage(Color.decode("#4E79A7")));
        trayIcon.setImageAutoSize(true);
        trayIcon.setToolTip("TimeTracker");
        trayIcon.setPopupMenu(menu);
        trayIcon.addActionListener(e -> trayIcon.displayMessage("TimeTracker",
                statusRef.get() != null ? statusRef.get().currentAppDisplay() : "Tracking",
                TrayIcon.MessageType.INFO));

        SystemTray.getSystemTray().add(trayIcon);
    }

    @Override
    public void updateStatus(TrayStatus status) {
        statusRef.set(status);
        if (trayIcon == null) {
            return;
        }
        EventQueue.invokeLater(() -> {
            trayIcon.setToolTip(buildTooltip(status));
            updateToggleLabel(status.trackingActive());
        });
    }

    private String buildTooltip(TrayStatus status) {
        StringBuilder sb = new StringBuilder();
        sb.append(status.trackingActive() ? "Tracking: " : "Paused: ")
                .append(status.currentAppDisplay());
        status.topApplication().ifPresent(top -> sb.append("\nTop Today: ")
                .append(top.displayName())
                .append(" ")
                .append(top.minutes())
                .append(" min"));
        return sb.toString();
    }

    @Override
    public void displayMessage(String caption, String text, TrayMessageType type) {
        if (trayIcon == null) {
            return;
        }
        TrayIcon.MessageType awtType = toAwtType(type);
        EventQueue.invokeLater(() -> trayIcon.displayMessage(caption, text, awtType));
    }

    private TrayIcon.MessageType toAwtType(TrayMessageType type) {
        return switch (type) {
            case INFO -> TrayIcon.MessageType.INFO;
            case WARNING -> TrayIcon.MessageType.WARNING;
            case ERROR -> TrayIcon.MessageType.ERROR;
        };
    }

    @Override
    public void shutdown() {
        if (trayIcon != null) {
            EventQueue.invokeLater(() -> SystemTray.getSystemTray().remove(trayIcon));
        }
    }

    @Override
    public void updatePaths(Path reportDirectory, Path dataDirectory) {
        if (reportDirectory != null) {
            this.reportDirectory = reportDirectory;
        }
        if (dataDirectory != null) {
            this.dataDirectory = dataDirectory;
        }
    }

    private void openTodayReport(ActionEvent event) {
        LocalDate today = LocalDate.now();
        Path reportPath = reportDirectory.resolve("daily_report_" + today.format(REPORT_SUFFIX) + ".html");
        if (!Files.exists(reportPath)) {
            displayMessage("TimeTracker", "Report not generated yet for today.", TrayMessageType.INFO);
            return;
        }
        openPath(reportPath);
    }

    private void openPath(Path path) {
        try {
            ensurePathExists(path);
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(path.toFile());
                } else if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(path.toUri());
                }
            }
        } catch (IOException ex) {
            displayMessage("TimeTracker", "Failed to open: " + path, TrayMessageType.ERROR);
        }
    }

    private Image createIconImage(Color baseColor) {
        int size = 16;
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(baseColor);
        g.fillRoundRect(0, 0, size, size, 4, 4);
        g.setColor(Color.WHITE);
        g.drawString("T", 4, 12);
        g.dispose();
        return image;
    }

    private void updateToggleLabel(boolean trackingActive) {
        if (toggleTrackingItem != null) {
            toggleTrackingItem.setLabel(trackingActive ? "Pause Tracking" : "Resume Tracking");
        }
    }

    private void ensurePathExists(Path path) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        Path parent = path.getParent();
        if (parent != null && Files.notExists(parent)) {
            Files.createDirectories(parent);
        }
        if (Files.notExists(path)) {
            if (path.toString().toLowerCase(Locale.ROOT).endsWith(".html")
                    || path.toString().toLowerCase(Locale.ROOT).endsWith(".json")
                    || path.toString().toLowerCase(Locale.ROOT).endsWith(".log")
                    || path.toString().toLowerCase(Locale.ROOT).endsWith(".db")
                    || path.toString().contains(".")) {
                Files.createFile(path);
            } else {
                Files.createDirectory(path);
            }
        }
    }
}
