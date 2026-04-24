package com.omnibank.regreporting.api;

import java.time.LocalDate;
import java.util.List;

public interface RegulatoryReportingService {

    RegulatoryReport generateCallReport(LocalDate quarterEnd);

    RegulatoryReport generateHmdaLar(LocalDate yearEnd);

    List<RegulatoryReport> dueByCalendar(LocalDate asOf);
}
