package com.kilimabank.sme_onboarding_service.registry;

import com.kilimabank.sme_onboarding_service.utils.CommonUtils;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.exception.CamundaError;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RegistryWorker {


  public record Business(String registrationNumber, String name, String status) {}

  private final RestClient restClient;

  public RegistryWorker(RegistryProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    this.restClient = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
  }

  @JobWorker(type = CommonUtils.JOB_REGISTRY_LOOKUP, fetchVariables = {"registrationNumber"}, timeout = 30_000)
  public Map<String, Object> lookUp(ActivatedJob job, @Variable("registrationNumber") String registrationNumber) {
    Optional<Business> business;
    try {
      business = find(registrationNumber);
    } catch (RuntimeException e) {
      int remaining = job.getRetries() - 1;
      Duration backoff = Duration.ofSeconds(5L << Math.max(0, 4 - remaining));
      throw CamundaError.jobError("Registry lookup failed: " + e.getMessage(), null, remaining, backoff, e);
    }
    if (business.isEmpty()) {
      throw CamundaError.bpmnError(CommonUtils.ERROR_REGISTRY_NOT_FOUND, "No business with registration number " + registrationNumber);
    }
    String status = business.get().status();
    return Map.of("registryVerified", "ACTIVE".equals(status), "registryStatus", status);
  }

  private Optional<Business> find(String registrationNumber) {
    return restClient
        .get()
        .uri("/businesses/{registrationNumber}", registrationNumber)
        .exchange(
            (request, response) -> {
              int status = response.getStatusCode().value();
              if (status == 404) {
                return Optional.<Business>empty();
              }
              if (status == 200) {
                return Optional.ofNullable(response.bodyTo(Business.class));
              }
              throw new IllegalStateException("Registry answered HTTP " + status);
            });
  }
}
