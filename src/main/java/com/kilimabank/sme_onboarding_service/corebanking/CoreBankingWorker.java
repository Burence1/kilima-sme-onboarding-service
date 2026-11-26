package com.kilimabank.sme_onboarding_service.corebanking;

import com.kilimabank.sme_onboarding_service.utils.CommonUtils;
import com.kilimabank.sme_onboarding_service.corebanking.CoreBankingModel.Account;
import com.kilimabank.sme_onboarding_service.corebanking.CoreBankingModel.CreateAccountRequest;
import com.kilimabank.sme_onboarding_service.corebanking.CoreBankingModel.CreateCustomerRequest;
import com.kilimabank.sme_onboarding_service.corebanking.CoreBankingModel.Customer;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.exception.CamundaError;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class CoreBankingWorker {


  private static final Duration FIRST_BACKOFF = Duration.ofSeconds(15);
  private static final Duration MAX_BACKOFF = Duration.ofMinutes(15);

  private final CoreBankingClient client;
  private final CallRateLimiter rateLimiter;
  private final MaintenanceWindow maintenanceWindow;
  private final int maxRetries;

  public CoreBankingWorker(CoreBankingClient client, CallRateLimiter rateLimiter, MaintenanceWindow maintenanceWindow, CoreBankingProperties properties) {
    this.client = client;
    this.rateLimiter = rateLimiter;
    this.maintenanceWindow = maintenanceWindow;
    this.maxRetries = properties.maxRetries();
  }

  @JobWorker(type = CommonUtils.JOB_CREATE_CUSTOMER, fetchVariables = {"applicationId", "registrationNumber", "businessName", "country"},
      maxJobsActive = 4, timeout = 60_000)
  public Map<String, Object> createCustomer(
      ActivatedJob job, @Variable("applicationId") String applicationId, @Variable("registrationNumber") String registrationNumber,
      @Variable("businessName") String businessName, @Variable("country") String country) {
    try {
      waitForCapacity(job);
      Optional<Customer> existing = client.findCustomer(registrationNumber);
      if (existing.isPresent()) {
        Customer customer = existing.get();
        boolean ours = applicationId.equals(customer.externalReference());
        log.info("Application {}: customer {} already exists ({}), reusing it", applicationId, customer.customerId(), ours ? "created by an earlier attempt" : "existing bank customer");

        return Map.of("customerId", customer.customerId(), "customerCreatedByProcess", ours);
      }

      waitForCapacity(job);
      Customer created = client.createCustomer(new CreateCustomerRequest(registrationNumber, businessName, country, applicationId));
      log.info("Application {}: created customer {}", applicationId, created.customerId());

      return Map.of("customerId", created.customerId(), "customerCreatedByProcess", true);

    } catch (CoreBankingException.OutcomeUnknown e) {
      throw retryLater(job, e);
    } catch (CoreBankingException.NotProcessed e) {
      throw retryLater(job, e);
    } catch (CoreBankingException.ValidationFailed | CoreBankingException.Unexpected e) {
      throw CamundaError.jobError(e.getMessage(), null, 0, Duration.ZERO, e);
    }
  }

  @JobWorker(
      type = CommonUtils.JOB_CREATE_ACCOUNT, fetchVariables = {"applicationId", "customerId", "country"}, maxJobsActive = 4, timeout = 60_000)
  public Map<String, Object> createAccount(
      ActivatedJob job, @Variable("applicationId") String applicationId, @Variable("customerId") String customerId, @Variable("country") String country) {
    try {
      waitForCapacity(job);
      Account account = client.createAccount(new CreateAccountRequest(customerId, CommonUtils.SME_PRODUCT_CODE, currencyFor(country), applicationId));

      log.info("Application {}: opened account {}", applicationId, account.accountNumber());
      return Map.of("accountNumber", account.accountNumber());

    } catch (CoreBankingException.ValidationFailed e) {
      throw CamundaError.bpmnError(CommonUtils.ERROR_ACCOUNT_VALIDATION_FAILED, e.getMessage(), Map.of("accountValidationError", e.details()));
    } catch (CoreBankingException.OutcomeUnknown e) {
      throw CamundaError.bpmnError(CommonUtils.ERROR_ACCOUNT_OUTCOME_UNKNOWN, e.getMessage());
    } catch (CoreBankingException.NotProcessed e) {
      throw retryLater(job, e);
    } catch (CoreBankingException.Unexpected e) {
      throw CamundaError.jobError(e.getMessage(), null, 0, Duration.ZERO, e);
    }
  }

  private void waitForCapacity(ActivatedJob job) {
    if (maintenanceWindow.isActive()) {
      Duration wait = maintenanceWindow.remaining().plus(randomDelayUpTo(Duration.ofMinutes(5)));
      throw CamundaError.jobError("Core banking maintenance window, trying again in " + wait, null, job.getRetries(), wait);
    }

    Optional<Duration> wait = rateLimiter.tryAcquire();
    if (wait.isPresent()) {
      Duration backoff = wait.get().plus(randomDelayUpTo(Duration.ofSeconds(5)));
      throw CamundaError.jobError("Core banking rate limit reached, trying again in " + backoff, null, job.getRetries(), backoff);
    }
  }

  private RuntimeException retryLater(ActivatedJob job, CoreBankingException e) {
    if (e instanceof CoreBankingException.NotProcessed notProcessed && notProcessed.isUnavailable() && maintenanceWindow.isActive()) {
      Duration wait = maintenanceWindow.remaining().plus(randomDelayUpTo(Duration.ofMinutes(5)));
      return CamundaError.jobError(e.getMessage(), null, job.getRetries(), wait, e);
    }

    int remaining = job.getRetries() - 1;
    Duration backoff = backoffFor(remaining);
    if (e instanceof CoreBankingException.NotProcessed notProcessed && notProcessed.retryAfter() != null && notProcessed.retryAfter().compareTo(backoff) > 0) {
      backoff = notProcessed.retryAfter();
    }
    log.warn("Job {} failed ({}), {} retries left, next try in {}", job.getKey(), e.getMessage(), remaining, backoff);
    return CamundaError.jobError(e.getMessage(), null, remaining, backoff, e);
  }

  Duration backoffFor(int retriesLeft) {
    int attempt = Math.max(0, maxRetries - retriesLeft - 1);
    Duration backoff = FIRST_BACKOFF.multipliedBy(1L << Math.min(attempt, 10));
    return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
  }

  private static Duration randomDelayUpTo(Duration max) {
    return Duration.ofMillis(ThreadLocalRandom.current().nextLong(max.toMillis() + 1));
  }

  static String currencyFor(String country) {
    return switch (country) {
      case "KE" -> "KES";
      case "TZ" -> "TZS";
      case "UG" -> "UGX";
      default -> throw new IllegalArgumentException("Unsupported country: " + country);
    };
  }
}
