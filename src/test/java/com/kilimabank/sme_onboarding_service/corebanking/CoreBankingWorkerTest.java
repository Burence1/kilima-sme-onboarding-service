package com.kilimabank.sme_onboarding_service.corebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kilimabank.sme_onboarding_service.corebanking.CoreBankingModel.Account;
import com.kilimabank.sme_onboarding_service.corebanking.CoreBankingModel.Customer;
import com.kilimabank.sme_onboarding_service.utils.CommonUtils;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.exception.BpmnError;
import io.camunda.client.exception.JobError;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoreBankingWorkerTest {

  private static final ZoneId EAT = ZoneId.of("Africa/Nairobi");

  private static final Instant DAYTIME = Instant.parse("2026-11-20T07:00:00Z");

  private static final Instant NIGHT = Instant.parse("2026-11-19T21:30:00Z");

  private CoreBankingClient client;
  private ActivatedJob job;

  @BeforeEach
  void setUp() {
    client = mock(CoreBankingClient.class);
    job = mock(ActivatedJob.class);
    when(job.getRetries()).thenReturn(10);
  }

  private CoreBankingWorker workerAt(Instant now, int callsPerMinute) {
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    MaintenanceWindow window =
        new MaintenanceWindow(clock, EAT, LocalTime.of(0, 0), LocalTime.of(2, 0));
    CallRateLimiter limiter = new CallRateLimiter(clock, callsPerMinute);
    CoreBankingProperties properties =
        new CoreBankingProperties(
            "http://unused", Duration.ofSeconds(1), Duration.ofSeconds(1), callsPerMinute, 10, null);
    return new CoreBankingWorker(client, limiter, window, properties);
  }

  private CoreBankingWorker worker() {
    return workerAt(DAYTIME, 18);
  }

  @Test
  void reusesCustomerCreatedByEarlierAttemptInsteadOfCreatingADuplicate() {
    when(client.findCustomer("PVT-123"))
        .thenReturn(Optional.of(new Customer("CUS-1", "PVT-123", "APP-1")));

    Map<String, Object> result = worker().createCustomer(job, "APP-1", "PVT-123", "Acme Ltd", "KE");

    assertThat(result).containsEntry("customerId", "CUS-1");
    assertThat(result).containsEntry("customerCreatedByProcess", true);
    verify(client, never()).createCustomer(any());
  }

  @Test
  void marksAPreExistingBankCustomerAsNotCreatedByUs() {
    when(client.findCustomer("PVT-123"))
        .thenReturn(Optional.of(new Customer("CUS-9", "PVT-123", "OLD-APPLICATION")));

    Map<String, Object> result = worker().createCustomer(job, "APP-1", "PVT-123", "Acme Ltd", "KE");

    assertThat(result).containsEntry("customerCreatedByProcess", false);
    verify(client, never()).createCustomer(any());
  }

  @Test
  void createsCustomerWhenLookupFindsNone() {
    when(client.findCustomer("PVT-123")).thenReturn(Optional.empty());
    when(client.createCustomer(any())).thenReturn(new Customer("CUS-2", "PVT-123", "APP-1"));

    Map<String, Object> result = worker().createCustomer(job, "APP-1", "PVT-123", "Acme Ltd", "KE");

    assertThat(result).containsEntry("customerId", "CUS-2");
  }

  @Test
  void customerTimeoutIsRetriedBecauseTheNextAttemptLooksUpFirst() {
    when(client.findCustomer("PVT-123")).thenReturn(Optional.empty());
    when(client.createCustomer(any()))
        .thenThrow(new CoreBankingException.OutcomeUnknown("read timeout", null));

    JobError error =
        catchThrowableOfType(
            JobError.class,
            () -> worker().createCustomer(job, "APP-1", "PVT-123", "Acme Ltd", "KE"));

    assertThat(error.getRetries()).isEqualTo(9);
  }

  @Test
  void accountValidationErrorBecomesBpmnError() {
    when(client.createAccount(any()))
        .thenThrow(new CoreBankingException.ValidationFailed("{\"error\":\"bad product\"}"));

    BpmnError error =
        catchThrowableOfType(BpmnError.class, () -> worker().createAccount(job, "APP-1", "CUS-1", "KE"));

    assertThat(error.getErrorCode()).isEqualTo(CommonUtils.ERROR_ACCOUNT_VALIDATION_FAILED);
    assertThat(error.getVariables())
        .isEqualTo(Map.of("accountValidationError", "{\"error\":\"bad product\"}"));
  }

  @Test
  void accountTimeoutIsNeverRetriedBlindly() {
    when(client.createAccount(any()))
        .thenThrow(new CoreBankingException.OutcomeUnknown("read timeout", null));

    BpmnError error =
        catchThrowableOfType(BpmnError.class, () -> worker().createAccount(job, "APP-1", "CUS-1", "KE"));

    assertThat(error.getErrorCode()).isEqualTo(CommonUtils.ERROR_ACCOUNT_OUTCOME_UNKNOWN);
  }

  @Test
  void unavailableOutsideTheWindowUsesUpARetryWithBackoff() {
    when(client.createAccount(any()))
        .thenThrow(new CoreBankingException.NotProcessed("503", 503, null, null));

    JobError error =
        catchThrowableOfType(JobError.class, () -> worker().createAccount(job, "APP-1", "CUS-1", "KE"));

    assertThat(error.getRetries()).isEqualTo(9);
    assertThat(error.getRetryBackoff().apply(9)).isEqualTo(Duration.ofSeconds(15));
  }

  @Test
  void respectsRetryAfterWhenItIsLongerThanOurBackoff() {
    when(client.createAccount(any()))
        .thenThrow(new CoreBankingException.NotProcessed("429", 429, Duration.ofSeconds(90), null));

    JobError error =
        catchThrowableOfType(JobError.class, () -> worker().createAccount(job, "APP-1", "CUS-1", "KE"));

    assertThat(error.getRetries()).isEqualTo(9);
    assertThat(error.getRetryBackoff().apply(9)).isEqualTo(Duration.ofSeconds(90));
  }

  @Test
  void unexpectedResponseRaisesAnIncidentStraightAway() {
    when(client.createAccount(any()))
        .thenThrow(new CoreBankingException.Unexpected("401 Unauthorized"));

    JobError error =
        catchThrowableOfType(JobError.class, () -> worker().createAccount(job, "APP-1", "CUS-1", "KE"));

    assertThat(error.getRetries()).isZero();
  }

  @Test
  void backoffGrowsExponentiallyAndIsCapped() {
    CoreBankingWorker worker = worker();
    assertThat(worker.backoffFor(9)).isEqualTo(Duration.ofSeconds(15));
    assertThat(worker.backoffFor(8)).isEqualTo(Duration.ofSeconds(30));
    assertThat(worker.backoffFor(7)).isEqualTo(Duration.ofMinutes(1));
    assertThat(worker.backoffFor(0)).isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void doesNotCallCoreBankingDuringTheMaintenanceWindowAndKeepsTheRetryCount() {
    JobError error =
        catchThrowableOfType(
            JobError.class, () -> workerAt(NIGHT, 18).createAccount(job, "APP-1", "CUS-1", "KE"));

    verifyNoInteractions(client);
    assertThat(error.getRetries()).isEqualTo(10);

    assertThat(error.getRetryBackoff().apply(10))
        .isBetween(Duration.ofMinutes(90), Duration.ofMinutes(95));
  }

  @Test
  void waitsForCapacityWhenOurOwnRateLimitIsReached() {
    when(client.createAccount(any())).thenReturn(new Account("0100001"));
    CoreBankingWorker worker = workerAt(DAYTIME, 1);

    worker.createAccount(job, "APP-1", "CUS-1", "KE");
    JobError error =
        catchThrowableOfType(JobError.class, () -> worker.createAccount(job, "APP-2", "CUS-2", "KE"));

    assertThat(error.getRetries()).isEqualTo(10);
    assertThat(error.getRetryBackoff().apply(10))
        .isBetween(Duration.ofSeconds(60), Duration.ofSeconds(65));
  }
}
