package com.kilimabank.sme_onboarding_service.corebanking;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kilima.core-banking")
public record CoreBankingProperties(
    String baseUrl,
    Duration connectTimeout,
    Duration readTimeout,
    int callsPerMinute,
    int maxRetries,
    MaintenanceWindowProperties maintenanceWindow) {

  public record MaintenanceWindowProperties(String zone, String start, String end) {}
}
