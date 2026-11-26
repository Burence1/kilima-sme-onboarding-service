package com.kilimabank.sme_onboarding_service.process;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.kilimabank.sme_onboarding_service.process.TestData.SCREENING_MESSAGE;
import static com.kilimabank.sme_onboarding_service.process.TestData.id;
import static com.kilimabank.sme_onboarding_service.process.TestData.publishScreeningResult;
import static com.kilimabank.sme_onboarding_service.process.TestData.startApplication;
import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static io.camunda.process.test.api.CamundaAssert.assertThatUserTask;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byKey;
import static io.camunda.process.test.api.assertions.UserTaskSelectors.byElementId;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.kilimabank.sme_onboarding_service.utils.CommonUtils;
import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.wiremock.spring.EnableWireMock;

@SpringBootTest(
    properties = {
      "camunda.client.worker.defaults.enabled=false",
      "camunda.client.worker.override.core-banking-create-customer.enabled=true",
      "camunda.client.worker.override.core-banking-create-account.enabled=true",
      "kilima.core-banking.base-url=http://localhost:${wiremock.server.port}"
    })
@CamundaSpringProcessTest
@EnableWireMock
class CoreBankingFailureProcessTest {

  @TestConfiguration
  static class DaytimeClock {
    @Bean
    @Primary
    Clock daytimeClock() {
      return Clock.fixed(Instant.parse("2026-11-20T07:00:00Z"), ZoneOffset.UTC);
    }
  }

  @Autowired private CamundaClient client;
  @Autowired private CamundaProcessTestContext processTestContext;

  @BeforeEach
  void setUp() {
    processTestContext
        .mockJobWorker(CommonUtils.JOB_REGISTRY_LOOKUP)
        .thenComplete(Map.of("registryVerified", true, "registryStatus", "ACTIVE"));
    processTestContext
        .mockJobWorker(CommonUtils.JOB_SCREENING_REQUEST)
        .thenComplete(Map.of("screeningRequestId", "SCR-1"));
    processTestContext.mockJobWorker(CommonUtils.JOB_SEND_WELCOME_EMAIL).thenComplete();
    processTestContext.mockJobWorker(CommonUtils.JOB_SEND_REJECTION_EMAIL).thenComplete();
  }

  private static void coreBankingCreatesCustomer(String applicationId) {
    String registrationNumber = "PVT-" + applicationId;
    stubFor(
        get(urlPathEqualTo("/customers"))
            .withQueryParam("registrationNumber", equalTo(registrationNumber))
            .willReturn(aResponse().withStatus(404)));
    stubFor(
        post("/customers")
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"customerId\":\"CUS-"
                            + applicationId
                            + "\",\"registrationNumber\":\""
                            + registrationNumber
                            + "\",\"externalReference\":\""
                            + applicationId
                            + "\"}")));
  }

  @Test
  void rejectedAccountLeadsToClosingTheCustomerWeCreated() {
    coreBankingCreatesCustomer(id("APP-10"));
    stubFor(
        post("/accounts")
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withBody("{\"error\":\"Product not allowed for this legal form\"}")));

    long instance = startApplication(client, id("APP-10"), "KE");
    assertThatProcessInstance(byKey(instance)).isWaitingForMessage(SCREENING_MESSAGE, id("APP-10"));
    publishScreeningResult(client, id("APP-10"), false);

    assertThatUserTask(byElementId("Task_ResolveAccountValidation")).isCreated();
    assertThatProcessInstance(byKey(instance))
        .hasVariable("customerId", "CUS-" + id("APP-10"))
        .hasVariable("customerCreatedByProcess", true)
        .hasVariable(
            "accountValidationError", "{\"error\":\"Product not allowed for this legal form\"}");

    processTestContext.completeUserTask(
        byElementId("Task_ResolveAccountValidation"),
        Map.of("accountResolution", "CLOSE_CUSTOMER"));
    processTestContext.completeUserTask(byElementId("Task_CloseCustomer"));

    assertThatProcessInstance(byKey(instance))
        .isCompleted()
        .hasCompletedElements("Task_CloseCustomer", "Task_SendRejection", "End_Rejected");
    verify(1, postRequestedFor(urlEqualTo("/customers")));
  }

  @Test
  void retryAfterFixingTheDataReusesTheSameCustomer() {
    coreBankingCreatesCustomer(id("APP-11"));

    stubFor(
        post("/accounts")
            .inScenario("account")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(422).withBody("{\"error\":\"Missing tax PIN\"}"))
            .willSetStateTo("fixed"));
    stubFor(
        post("/accounts")
            .inScenario("account")
            .whenScenarioStateIs("fixed")
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"accountNumber\":\"0100011\"}")));

    long instance = startApplication(client, id("APP-11"), "KE");
    assertThatProcessInstance(byKey(instance)).isWaitingForMessage(SCREENING_MESSAGE, id("APP-11"));
    publishScreeningResult(client, id("APP-11"), false);

    processTestContext.completeUserTask(
        byElementId("Task_ResolveAccountValidation"), Map.of("accountResolution", "RETRY"));

    assertThatProcessInstance(byKey(instance))
        .isCompleted()
        .hasCompletedElements("End_AccountOpened")
        .hasVariable("accountNumber", "0100011");

    verify(1, postRequestedFor(urlEqualTo("/customers")));
    verify(2, postRequestedFor(urlEqualTo("/accounts")));
  }
}
