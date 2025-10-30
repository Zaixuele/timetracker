package com.timetracker.report;

import com.timetracker.aggregation.MinuteRecord;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

public interface ReportGenerator {

    void generateDailyReport(LocalDate date, List<MinuteRecord> records) throws IOException;
}
