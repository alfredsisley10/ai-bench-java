package com.omnibank.batch.api;

import java.time.LocalDate;

public interface BatchJobs {

    BatchRunResult runEndOfDay(LocalDate businessDate);

    BatchRunResult runInterestAccrual(LocalDate asOf);

    BatchRunResult runStatementCycle(LocalDate cycleDate);
}
