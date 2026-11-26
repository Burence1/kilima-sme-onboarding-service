package com.kilimabank.sme_onboarding_service.utils;

public final class CommonUtils {

  private CommonUtils() {}

  public static final String JOB_REGISTRY_LOOKUP = "registry-lookup";
  public static final String JOB_SCREENING_REQUEST = "screening-request";
  public static final String JOB_CREATE_CUSTOMER = "core-banking-create-customer";
  public static final String JOB_CREATE_ACCOUNT = "core-banking-create-account";
  public static final String JOB_SEND_WELCOME_EMAIL = "send-welcome-email";
  public static final String JOB_SEND_REJECTION_EMAIL = "send-rejection-email";
  public static final String JOB_COMPLIANCE_ESCALATION = "compliance-escalation";

  public static final String ERROR_REGISTRY_NOT_FOUND = "REGISTRY_NOT_FOUND";
  public static final String ERROR_ACCOUNT_VALIDATION_FAILED = "ACCOUNT_VALIDATION_FAILED";
  public static final String ERROR_ACCOUNT_OUTCOME_UNKNOWN = "ACCOUNT_OUTCOME_UNKNOWN";

  public static final String MESSAGE_SCREENING_COMPLETED = "ScreeningCompleted";

  public static final String SME_PRODUCT_CODE = "SME_CURRENT";
}
