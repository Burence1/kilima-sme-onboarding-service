package com.kilimabank.sme_onboarding_service.process;

import com.kilimabank.sme_onboarding_service.utils.CommonUtils;
import io.camunda.client.CamundaClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class TestData {

  static final String PROCESS_ID = "sme-onboarding";
  static final String SUBMITTED_MESSAGE = "ApplicationSubmitted";
  static final String SCREENING_MESSAGE = CommonUtils.MESSAGE_SCREENING_COMPLETED;

  private static final String RUN = Long.toString(System.currentTimeMillis(), 36);

  private TestData() {}

  static String id(String name) {
    return name + "-" + RUN;
  }

  static void submitApplication(CamundaClient client, String applicationId, String country) {
    client
        .newPublishMessageCommand()
        .messageName(SUBMITTED_MESSAGE)
        .correlationKey(applicationId)
        .messageId(applicationId)
        .timeToLive(Duration.ofDays(30))
        .variables(
            Map.of(
                "applicationId", applicationId,
                "country", country,
                "registrationNumber", "PVT-" + applicationId,
                "businessName", "TechWorld Traders Ltd",
                "legalForm", "LIMITED_COMPANY",
                "contactEmail", "owner@techworld.com",
                "documentRefs", List.of("doc-store://" + applicationId + "/certificate")))
        .send()
        .join();
  }

  static long startApplication(CamundaClient client, String applicationId, String country) {
    submitApplication(client, applicationId, country);
    return findInstanceKey(client, applicationId);
  }

  static long findInstanceKey(CamundaClient client, String applicationId) {
    for (int attempt = 0; attempt < 50; attempt++) {
      var result =
          client
              .newProcessInstanceSearchRequest()
              .filter(
                  f ->
                      f.processDefinitionId(PROCESS_ID)

                          .variables(Map.of("applicationId", "\"" + applicationId + "\"")))
              .send()
              .join();
      if (!result.items().isEmpty()) {
        return result.items().get(0).getProcessInstanceKey();
      }
      sleep(Duration.ofMillis(200));
    }
    throw new AssertionError("No process instance found for application " + applicationId);
  }

  static void publishScreeningResult(CamundaClient client, String applicationId, boolean match) {
    client
        .newPublishMessageCommand()
        .messageName(SCREENING_MESSAGE)
        .correlationKey(applicationId)
        .variables(Map.of("screeningMatch", match, "screeningReference", "REF-" + applicationId))
        .send()
        .join();
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
