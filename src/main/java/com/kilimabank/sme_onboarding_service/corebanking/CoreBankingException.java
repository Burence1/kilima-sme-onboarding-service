package com.kilimabank.sme_onboarding_service.corebanking;

import java.time.Duration;

public abstract sealed class CoreBankingException extends RuntimeException {

  protected CoreBankingException(String message, Throwable cause) {
    super(message, cause);
  }

  public static final class NotProcessed extends CoreBankingException {
    private final int httpStatus;
    private final Duration retryAfter;

    public NotProcessed(String message, int httpStatus, Duration retryAfter, Throwable cause) {
      super(message, cause);
      this.httpStatus = httpStatus;
      this.retryAfter = retryAfter;
    }

    public int httpStatus() {
      return httpStatus;
    }

    public Duration retryAfter() {
      return retryAfter;
    }

    public boolean isRateLimited() {
      return httpStatus == 429;
    }

    public boolean isUnavailable() {
      return httpStatus == 503;
    }
  }

  public static final class OutcomeUnknown extends CoreBankingException {
    public OutcomeUnknown(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static final class ValidationFailed extends CoreBankingException {
    private final String details;

    public ValidationFailed(String details) {
      super("Core banking rejected the request (422): " + details, null);
      this.details = details;
    }

    public String details() {
      return details;
    }
  }

  public static final class Unexpected extends CoreBankingException {
    public Unexpected(String message) {
      super(message, null);
    }
  }
}
