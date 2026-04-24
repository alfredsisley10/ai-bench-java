package com.omnibank.batch.api;

import java.time.Duration;
import java.util.List;

public record BatchRunResult(
        String jobName,
        boolean success,
        int itemsProcessed,
        Duration elapsed,
        List<String> errors
) {}
