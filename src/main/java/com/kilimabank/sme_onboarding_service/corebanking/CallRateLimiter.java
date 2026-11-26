package com.kilimabank.sme_onboarding_service.corebanking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CallRateLimiter {

  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final Clock clock;
  private final int callsPerMinute;
  private final Deque<Instant> recentCalls = new ArrayDeque<>();

  @Autowired
  public CallRateLimiter(Clock clock, CoreBankingProperties properties) {
    this(clock, properties.callsPerMinute());
  }

  CallRateLimiter(Clock clock, int callsPerMinute) {
    this.clock = clock;
    this.callsPerMinute = callsPerMinute;
  }

  public synchronized Optional<Duration> tryAcquire() {
    Instant now = clock.instant();
    while (!recentCalls.isEmpty() && !recentCalls.peekFirst().isAfter(now.minus(WINDOW))) {
      recentCalls.pollFirst();
    }
    if (recentCalls.size() < callsPerMinute) {
      recentCalls.addLast(now);
      return Optional.empty();
    }
    Instant oldest = recentCalls.peekFirst();
    return Optional.of(Duration.between(now, oldest.plus(WINDOW)));
  }
}
