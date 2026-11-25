package com.kilimabank.sme_onboarding_service.corebanking;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceWindow {

  private final Clock clock;
  private final ZoneId zone;
  private final LocalTime start;
  private final LocalTime end;

  @Autowired
  public MaintenanceWindow(Clock clock, CoreBankingProperties properties) {
    this(
        clock, ZoneId.of(properties.maintenanceWindow().zone()), LocalTime.parse(properties.maintenanceWindow().start()), LocalTime.parse(properties.maintenanceWindow().end()));
  }

  MaintenanceWindow(Clock clock, ZoneId zone, LocalTime start, LocalTime end) {
    this.clock = clock;
    this.zone = zone;
    this.start = start;
    this.end = end;
  }

  public boolean isActive() {
    LocalTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone).toLocalTime();
    return !now.isBefore(start) && now.isBefore(end);
  }

  public Duration remaining() {
    if (!isActive()) {
      return Duration.ZERO;
    }
    ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
    ZonedDateTime windowEnd = now.with(end);
    return Duration.between(now, windowEnd);
  }
}
