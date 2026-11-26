package com.kilimabank.sme_onboarding_service.process;

import static com.kilimabank.sme_onboarding_service.process.TestData.SCREENING_MESSAGE;
import static com.kilimabank.sme_onboarding_service.process.TestData.id;
import static com.kilimabank.sme_onboarding_service.process.TestData.publishScreeningResult;
import static com.kilimabank.sme_onboarding_service.process.TestData.startApplication;
import static com.kilimabank.sme_onboarding_service.process.TestData.submitApplication;
import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static io.camunda.process.test.api.CamundaAssert.assertThatUserTask;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byKey;
import static io.camunda.process.test.api.assertions.UserTaskSelectors.byElementId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kilimabank.sme_onboarding_service.utils.CommonUtils;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.command.ClientException;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"camunda.client.worker.defaults.enabled=false"})
@CamundaSpringProcessTest
class SmeOnboardingProcessTest {

  @Autowired private CamundaClient client;
  @Autowired private CamundaProcessTestContext processTestContext;

  @BeforeEach
  void mockWorkersThatBehaveTheSameInEveryTest() {
    processTestContext
        .mockJobWorker(CommonUtils.JOB_REGISTRY_LOOKUP)
        .thenComplete(Map.of("registryVerified", true, "registryStatus", "ACTIVE"));
    processTestContext
        .mockJobWorker(CommonUtils.JOB_SCREENING_REQUEST)
        .thenComplete(Map.of("screeningRequestId", "SCR-1"));
    processTestContext
        .mockJobWorker(CommonUtils.JOB_CREATE_CUSTOMER)
        .thenComplete(Map.of("customerId", "CUS-1", "customerCreatedByProcess", true));
    processTestContext.mockJobWorker(CommonUtils.JOB_SEND_WELCOME_EMAIL).thenComplete();
    processTestContext.mockJobWorker(CommonUtils.JOB_SEND_REJECTION_EMAIL).thenComplete();
    processTestContext.mockJobWorker(CommonUtils.JOB_COMPLIANCE_ESCALATION).thenComplete();
  }

  @Test
  void happyPathOpensTheAccount() {
    processTestContext
        .mockJobWorker(CommonUtils.JOB_CREATE_ACCOUNT)
        .thenComplete(Map.of("accountNumber", "0100001"));

    long instance = startApplication(client, id("APP-1"), "KE");

    assertThatProcessInstance(byKey(instance)).isWaitingForMessage(SCREENING_MESSAGE, id("APP-1"));
    publishScreeningResult(client, id("APP-1"), false);

    assertThatProcessInstance(byKey(instance))
        .isCompleted()
        .hasCompletedElementsInOrder(
            "Task_RegistryLookup",
            "Task_RequestScreening",
            "Task_AwaitScreening",
            "Task_DueDiligence",
            "Task_CreateCustomer",
            "Task_CreateAccount",
            "Task_SendWelcome",
            "End_AccountOpened")
        .hasNotActivatedElements("Task_ComplianceReview")
        .hasVariable("dueDiligenceLevel", "standard")
        .hasVariable("accountNumber", "0100001");
  }

  @Test
  void screeningMatchGoesToComplianceReviewAndEscalatesAfter48Hours() {
    processTestContext
        .mockJobWorker(CommonUtils.JOB_CREATE_ACCOUNT)
        .thenComplete(Map.of("accountNumber", "0100002"));

    long instance = startApplication(client, id("APP-2"), "KE");
    assertThatProcessInstance(byKey(instance)).isWaitingForMessage(SCREENING_MESSAGE, id("APP-2"));
    publishScreeningResult(client, id("APP-2"), true);

    assertThatProcessInstance(byKey(instance))
        .hasVariable("dueDiligenceLevel", "enhanced")
        .hasActiveElements("Task_ComplianceReview");
    assertThatUserTask(byElementId("Task_ComplianceReview")).isCreated();

    processTestContext.increaseTime(Duration.ofHours(48));
    assertThatProcessInstance(byKey(instance))
        .hasCompletedElements("Task_EscalateToHead", "End_Escalated")
        .hasActiveElements("Task_ComplianceReview");

    processTestContext.completeUserTask(
        byElementId("Task_ComplianceReview"), Map.of("complianceDecision", "APPROVED"));

    assertThatProcessInstance(byKey(instance)).isCompleted().hasCompletedElements("End_AccountOpened");
  }

  @Test
  void complianceRejectionSendsTheRejectionEmail() {
    long instance = startApplication(client, id("APP-3"), "KE");
    assertThatProcessInstance(byKey(instance)).isWaitingForMessage(SCREENING_MESSAGE, id("APP-3"));
    publishScreeningResult(client, id("APP-3"), true);

    processTestContext.completeUserTask(
        byElementId("Task_ComplianceReview"), Map.of("complianceDecision", "REJECTED"));

    assertThatProcessInstance(byKey(instance))
        .isCompleted()
        .hasCompletedElements("Task_SendRejection", "End_Rejected")
        .hasNotActivatedElements("Task_CreateCustomer");
  }

  @Test
  void accountWithUnknownOutcomeIsCheckedByAPersonBeforeAnyRetry() {
    processTestContext
        .mockJobWorker(CommonUtils.JOB_CREATE_ACCOUNT)
        .thenThrowBpmnError(CommonUtils.ERROR_ACCOUNT_OUTCOME_UNKNOWN);

    long instance = startApplication(client, id("APP-4"), "KE");
    assertThatProcessInstance(byKey(instance)).isWaitingForMessage(SCREENING_MESSAGE, id("APP-4"));
    publishScreeningResult(client, id("APP-4"), false);

    assertThatUserTask(byElementId("Task_VerifyAccount")).isCreated();

    processTestContext.completeUserTask(
        byElementId("Task_VerifyAccount"), Map.of("accountNumber", "0100004"));

    assertThatProcessInstance(byKey(instance))
        .isCompleted()
        .hasCompletedElements("Task_SendWelcome", "End_AccountOpened")
        .hasVariable("accountNumber", "0100004");
  }

  @Test
  void tanzanianApplicationIsCheckedByBranchStaff() {
    processTestContext
        .mockJobWorker(CommonUtils.JOB_CREATE_ACCOUNT)
        .thenComplete(Map.of("accountNumber", "0100005"));

    long instance = startApplication(client, id("APP-5"), "TZ");

    assertThatUserTask(byElementId("Task_ManualRegistryCheck"))
        .isCreated()
        .hasCandidateGroup("branch-ops-tz");
    processTestContext.completeUserTask(
        byElementId("Task_ManualRegistryCheck"), Map.of("registryVerified", true));
    assertThatProcessInstance(byKey(instance)).isWaitingForMessage(SCREENING_MESSAGE, id("APP-5"));
    publishScreeningResult(client, id("APP-5"), false);

    assertThatProcessInstance(byKey(instance))
        .isCompleted()
        .hasNotActivatedElements("Task_RegistryLookup")
        .hasCompletedElements("End_AccountOpened");
  }
  @Test
  void duplicateSubmissionIsRejectedAndNeverStartsASecondInstance() {
    processTestContext
        .mockJobWorker(CommonUtils.JOB_CREATE_ACCOUNT)
        .thenComplete(Map.of("accountNumber", "0100006"));

    long instance = startApplication(client, id("APP-6"), "KE");

    assertThatThrownBy(() -> submitApplication(client, id("APP-6"), "KE"))
        .isInstanceOf(ClientException.class)
        .hasMessageContaining("ALREADY_EXISTS");

    assertThatProcessInstance(byKey(instance)).isWaitingForMessage(SCREENING_MESSAGE, id("APP-6"));
    long instancesForApplication =
        client
            .newProcessInstanceSearchRequest()
            .filter(
                f ->
                    f.processDefinitionId(TestData.PROCESS_ID)
                        .variables(Map.of("applicationId", "\"" + id("APP-6") + "\"")))
            .send()
            .join()
            .items()
            .size();
    assertThat(instancesForApplication).isEqualTo(1);
  }
}
