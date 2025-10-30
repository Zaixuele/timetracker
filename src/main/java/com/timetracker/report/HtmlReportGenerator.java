package com.timetracker.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.timetracker.aggregation.MinuteRecord;
import com.timetracker.aggregation.MinuteStatus;
import com.timetracker.app.ResolvedApplication;
import com.timetracker.config.ReportConfig;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class HtmlReportGenerator implements ReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(HtmlReportGenerator.class);

    private static final DateTimeFormatter DATE_DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILE_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReportConfig config;
    private final ObjectMapper objectMapper;
    private final String chartJs;

    public HtmlReportGenerator(ReportConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.objectMapper = new ObjectMapper()
                .registerModule(new Jdk8Module())
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.chartJs = loadBundledChartJs();
    }

    @Override
    public void generateDailyReport(LocalDate date, List<MinuteRecord> records) throws IOException {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(records, "records");

        Path reportDir = Path.of(config.rootDir()).toAbsolutePath();
        Files.createDirectories(reportDir);

        DailyDataset dataset = buildDataset(date, records);
        String htmlContent = buildHtml(dataset);
        Path htmlPath = reportDir.resolve("daily_report_" + date.format(FILE_SUFFIX) + ".html");
        Files.writeString(htmlPath, htmlContent, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);

        Path jsonPath = reportDir.resolve("data_" + date.format(FILE_SUFFIX) + ".json");
        Files.writeString(jsonPath, objectMapper.writeValueAsString(dataset), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);

        log.info("Generated daily report for {} at {}", date, htmlPath);
    }

    private DailyDataset buildDataset(LocalDate date, List<MinuteRecord> records) {
        Map<String, AppAggregate> aggregates = new LinkedHashMap<>();
        int idleMinutes = 0;
        int idleSeconds = 0;

        for (MinuteRecord record : records) {
            if (!date.equals(record.date())) {
                continue;
            }
            if (record.status() == MinuteStatus.IDLE) {
                idleMinutes += 1;
                idleSeconds += record.idleSeconds();
            } else {
                record.application().ifPresent(app -> {
                    AppAggregate agg = aggregates.computeIfAbsent(app.id(), id -> new AppAggregate(
                            app.id(),
                            app.displayName(),
                            app.executablePath()
                    ));
                    agg.minutes += 1;
                    agg.activeSeconds += record.activeSeconds();
                });
            }
        }

        int totalMinutes = idleMinutes + aggregates.values().stream()
                .mapToInt(a -> a.minutes)
                .sum();

        List<AppAggregate> sortedAggregates = aggregates.values().stream()
                .sorted(Comparator.comparingInt((AppAggregate a) -> a.minutes).reversed()
                        .thenComparing(a -> a.displayName.toLowerCase(Locale.ROOT)))
                .toList();

        Optional<AppAggregate> topApp = sortedAggregates.stream().findFirst();

        List<AppAggregate> pieEntries = new ArrayList<>(sortedAggregates);
        if (idleMinutes > 0) {
            pieEntries.add(AppAggregate.idleAggregate(idleMinutes, idleSeconds));
        }

        List<ChartEntry> pieChart = pieEntries.stream()
                .map(AppAggregate::toChartEntry)
                .toList();

        List<AppAggregate> barEntries = sortedAggregates;
        if (Boolean.TRUE.equals(config.includeIdleInBar()) && idleMinutes > 0) {
            barEntries = new ArrayList<>(sortedAggregates);
            barEntries.add(AppAggregate.idleAggregate(idleMinutes, idleSeconds));
        }
        List<ChartEntry> barChart = buildBarSeries(barEntries, config.topN());

        List<TimelineEntry> timeline = records.stream()
                .filter(record -> date.equals(record.date()))
                .sorted(Comparator.comparing(MinuteRecord::minute))
                .map(TimelineEntry::fromMinuteRecord)
                .toList();

        Summary summary = new Summary(
                date.format(DATE_DISPLAY),
                totalMinutes,
                totalMinutes - idleMinutes,
                idleMinutes,
                topApp.map(AppAggregate::toUsageSummary)
        );

        return new DailyDataset(summary, pieChart, barChart, timeline);
    }

    private List<ChartEntry> buildBarSeries(List<AppAggregate> aggregates, int topN) {
        List<AppAggregate> sorted = aggregates.stream()
                .sorted(Comparator.comparingInt((AppAggregate a) -> a.minutes).reversed())
                .toList();
        List<AppAggregate> limited = sorted.stream().limit(topN).collect(Collectors.toCollection(ArrayList::new));
        if (sorted.size() > topN) {
            int othersMinutes = sorted.stream().skip(topN).mapToInt(a -> a.minutes).sum();
            int othersActiveSeconds = sorted.stream().skip(topN).mapToInt(a -> a.activeSeconds).sum();
            limited.add(new AppAggregate("others", "Others", "")
                    .withMinutes(othersMinutes)
                    .withActiveSeconds(othersActiveSeconds));
        }
        return limited.stream().map(AppAggregate::toChartEntry).toList();
    }

    private String buildHtml(DailyDataset dataset) throws JsonProcessingException {
        String pieData = objectMapper.writeValueAsString(dataset.pieChart());
        String barData = objectMapper.writeValueAsString(dataset.barChart());
        String timelineData = objectMapper.writeValueAsString(dataset.timeline());
        String summaryJson = objectMapper.writeValueAsString(dataset.summary());

        StringBuilder html = new StringBuilder();
        html.append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>TimeTracker Daily Report</title>
                    <style>
                        body { font-family: "Segoe UI", Arial, sans-serif; margin: 0; padding: 0; background-color: #f5f6fa; color: #2c3e50; }
                        header { background: #2c3e50; color: white; padding: 20px 40px; }
                        header h1 { margin: 0; font-size: 24px; }
                        main { padding: 30px 40px; }
                        section { margin-bottom: 40px; background: white; border-radius: 12px; padding: 24px; box-shadow: 0 10px 25px rgba(0,0,0,0.05); }
                        h2 { margin-top: 0; font-size: 20px; }
                        .summary-grid { display: flex; gap: 20px; flex-wrap: wrap; }
                        .summary-card { flex: 1 1 200px; background: linear-gradient(135deg, #74ebd5 0%, #ACB6E5 100%); color: #1c2833; padding: 16px; border-radius: 10px; }
                        .summary-card h3 { margin: 0 0 8px 0; font-size: 16px; }
                        .summary-card p { margin: 0; font-size: 28px; font-weight: bold; }
                        canvas { max-width: 100%; height: 400px; }
                        footer { text-align: center; padding: 16px; color: #7f8c8d; font-size: 12px; }
                        .timeline { max-height: 320px; overflow-y: auto; border: 1px solid #e1e4e8; border-radius: 8px; }
                        .timeline-item { padding: 8px 12px; border-bottom: 1px solid #ecf0f1; display: flex; justify-content: space-between; }
                        .timeline-item:last-child { border-bottom: none; }
                        .timeline-item.idle { background-color: #f9f9f9; color: #7f8c8d; }
                        .timeline-time { font-weight: 600; }
                        .timeline-label { font-weight: 500; }
                    </style>
                </head>
                <body>
                """);

        html.append("<header>\n")
                .append("    <h1>Daily Report · ").append(dataset.summary().date()).append("</h1>\n")
                .append("</header>\n")
                .append("<main>\n");

        html.append("    <section>\n")
                .append("        <h2>Summary</h2>\n")
                .append("        <div class=\"summary-grid\">\n")
                .append("            <div class=\"summary-card\"><h3>Total Minutes</h3><p>")
                .append(dataset.summary().totalMinutes()).append("</p></div>\n")
                .append("            <div class=\"summary-card\"><h3>Focus Minutes</h3><p>")
                .append(dataset.summary().activeMinutes()).append("</p></div>\n")
                .append("            <div class=\"summary-card\"><h3>Idle Minutes</h3><p>")
                .append(dataset.summary().idleMinutes()).append("</p></div>\n");

        dataset.summary().topApp().ifPresent(top ->
                html.append("            <div class=\"summary-card\"><h3>Top Application</h3><p>")
                        .append(top.displayName()).append(" · ").append(top.minutes()).append("m</p></div>\n"));

        html.append("        </div>\n")
                .append("    </section>\n");

        html.append("    <section>\n")
                .append("        <h2>Usage Breakdown</h2>\n")
                .append("        <div><canvas id=\"pieChart\"></canvas></div>\n")
                .append("        <div style=\"margin-top: 32px;\"><canvas id=\"barChart\"></canvas></div>\n")
                .append("    </section>\n");

        html.append("    <section>\n")
                .append("        <h2>Timeline</h2>\n")
                .append("        <div class=\"timeline\" id=\"timeline\"></div>\n")
                .append("    </section>\n");

        html.append("</main>\n")
                .append("<footer>Generated by TimeTracker • ")
                .append(dataset.summary().date())
                .append("</footer>\n");

        html.append("<script>\n")
                .append(chartJs)
                .append("\n</script>\n");

        html.append("<script>\n")
                .append("const pieData = ").append(pieData).append(";\n")
                .append("const barData = ").append(barData).append(";\n")
                .append("const timelineData = ").append(timelineData).append(";\n")
                .append("const summary = ").append(summaryJson).append(";\n");

        html.append("""
                const colors = ["#4E79A7","#F28E2B","#E15759","#76B7B2","#59A14F","#EDC948","#B07AA1","#FF9DA7","#9C755F","#BAB0AC","#5F9ED1","#F1CE63"];
                const ctxPie = document.getElementById('pieChart');
                const ctxBar = document.getElementById('barChart');

                new Chart(ctxPie, {
                    type: 'doughnut',
                    data: {
                        labels: pieData.map(item => item.label),
                        datasets: [{
                            data: pieData.map(item => item.minutes),
                            backgroundColor: pieData.map((_, idx) => colors[idx % colors.length]),
                            borderWidth: 1
                        }]
                    },
                    options: {
                        plugins: {
                            legend: { position: 'bottom' },
                            tooltip: {
                                callbacks: {
                                    label: (context) => {
                                        const minutes = context.parsed;
                                        const label = context.label || '';
                                        return `${label}: ${minutes} min`;
                                    }
                                }
                            }
                        }
                    }
                });

                new Chart(ctxBar, {
                    type: 'bar',
                    data: {
                        labels: barData.map(item => item.label),
                        datasets: [{
                            label: 'Minutes',
                            data: barData.map(item => item.minutes),
                            backgroundColor: barData.map((_, idx) => colors[idx % colors.length]),
                            borderRadius: 6
                        }]
                    },
                    options: {
                        indexAxis: 'y',
                        scales: {
                            x: { beginAtZero: true, title: { display: true, text: 'Minutes' } }
                        }
                    }
                });

                const timelineContainer = document.getElementById('timeline');
                timelineData.forEach(item => {
                    const row = document.createElement('div');
                    row.className = 'timeline-item ' + (item.status === 'IDLE' ? 'idle' : '');
                    const time = document.createElement('span');
                    time.className = 'timeline-time';
                    time.textContent = item.minute;
                    const label = document.createElement('span');
                    label.className = 'timeline-label';
                    label.textContent = item.label + (item.minutes ? ` (${item.minutes}m)` : '');
                    row.appendChild(time);
                    row.appendChild(label);
                    timelineContainer.appendChild(row);
                });
                </script>
                """);

        html.append("</body>\n</html>");
        return html.toString();
    }

    private String loadBundledChartJs() {
        try (var stream = getClass().getClassLoader().getResourceAsStream("report/chart.umd.min.js")) {
            if (stream == null) {
                Path fallback = Path.of("src/main/resources/report/chart.umd.min.js");
                if (Files.exists(fallback)) {
                    return Files.readString(fallback, StandardCharsets.UTF_8);
                }
                throw new IllegalStateException("Missing bundled Chart.js resource");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load Chart.js", ex);
        }
    }

    private record DailyDataset(
            Summary summary,
            List<ChartEntry> pieChart,
            List<ChartEntry> barChart,
            List<TimelineEntry> timeline
    ) {
    }

    private record Summary(
            String date,
            int totalMinutes,
            int activeMinutes,
            int idleMinutes,
            Optional<TopApp> topApp
    ) {
    }

    private record TopApp(String displayName, int minutes) {
    }

    private record ChartEntry(String label, int minutes) {
    }

    private record TimelineEntry(String minute, String label, String status, int minutes) {

        static TimelineEntry fromMinuteRecord(MinuteRecord record) {
            String label = record.status() == MinuteStatus.IDLE
                    ? "Idle"
                    : record.application().map(ResolvedApplication::displayName).orElse("Unknown");
            return new TimelineEntry(
                    record.minute().toString(),
                    label,
                    record.status().name(),
                    record.status() == MinuteStatus.ACTIVE ? 1 : 0
            );
        }
    }

    private static final class AppAggregate {
        private final String id;
        private final String displayName;
        private final String exePath;
        private int minutes;
        private int activeSeconds;

        private AppAggregate(String id, String displayName, String exePath) {
            this.id = id;
            this.displayName = StringUtils.defaultIfBlank(displayName, "Unknown");
            this.exePath = exePath;
        }

        private AppAggregate withMinutes(int minutes) {
            this.minutes = minutes;
            return this;
        }

        private AppAggregate withActiveSeconds(int seconds) {
            this.activeSeconds = seconds;
            return this;
        }

        private static AppAggregate idleAggregate(int minutes, int idleSeconds) {
            AppAggregate agg = new AppAggregate("idle", "Idle", "");
            agg.minutes = minutes;
            agg.activeSeconds = idleSeconds;
            return agg;
        }

        private ChartEntry toChartEntry() {
            return new ChartEntry(displayName, minutes);
        }

        private TopApp toUsageSummary() {
            return new TopApp(displayName, minutes);
        }
    }
}
