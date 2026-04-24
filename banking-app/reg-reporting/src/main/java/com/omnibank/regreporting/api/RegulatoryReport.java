package com.omnibank.regreporting.api;

import java.time.LocalDate;

public interface RegulatoryReport {

    String reportId();

    LocalDate periodStart();

    LocalDate periodEnd();

    byte[] render();
}
