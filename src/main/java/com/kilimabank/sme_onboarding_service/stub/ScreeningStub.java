package com.kilimabank.sme_onboarding_service.stub;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@Profile("stub")
@Slf4j
@RequestMapping("/stub/screening")
public class ScreeningStub {


  public record ScreeningRequest(String applicationId, String callbackUrl) {}

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final RestClient restClient = RestClient.create();
  private final Duration callbackDelay;

  public ScreeningStub(@Value("${stub.screening.callback-delay:10s}") Duration callbackDelay) {
    this.callbackDelay = callbackDelay;
  }

  @PostMapping("/screenings")
  public ResponseEntity<?> request(@RequestBody ScreeningRequest request) {
    String screeningRequestId = "SCR-" + UUID.randomUUID();
    boolean match = request.applicationId().contains("MATCH");
    scheduler.schedule(() -> callBack(request, screeningRequestId, match), callbackDelay.toMillis(), TimeUnit.MILLISECONDS);

    return ResponseEntity.accepted().body(Map.of("screeningRequestId", screeningRequestId));
  }

  private void callBack(ScreeningRequest request, String screeningRequestId, boolean match) {
    try {
      restClient
          .post()
          .uri(request.callbackUrl())
          .body(
              Map.of(
                  "applicationId", request.applicationId(),
                  "screeningRequestId", screeningRequestId,
                  "match", match,
                  "reference", "REF-" + screeningRequestId.substring(4, 12)))
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException e) {
      log.warn("Screening callback for {} failed: {}", request.applicationId(), e.getMessage());
    }
  }
}
