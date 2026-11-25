package com.kilimabank.sme_onboarding_service.screening;

import com.kilimabank.sme_onboarding_service.utils.CommonUtils;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ScreeningWorker {

  public record ScreeningRequest(String applicationId, String callbackUrl) {}

  public record ScreeningAccepted(String screeningRequestId) {}

  private final RestClient restClient;
  private final ScreeningProperties properties;

  public ScreeningWorker(ScreeningProperties properties) {
    this.properties = properties;
    this.restClient = RestClient.builder().baseUrl(properties.baseUrl()).build();
  }

  @JobWorker(type = CommonUtils.JOB_SCREENING_REQUEST, fetchVariables = {"applicationId"}, timeout = 30_000)
  public Map<String, Object> requestScreening(@Variable("applicationId") String applicationId) {
    ScreeningAccepted accepted =
        restClient
            .post()
            .uri("/screenings")
            .body(new ScreeningRequest(applicationId, properties.callbackUrl()))
            .retrieve()
            .body(ScreeningAccepted.class);
    return Map.of("screeningRequestId", accepted.screeningRequestId());
  }
}
