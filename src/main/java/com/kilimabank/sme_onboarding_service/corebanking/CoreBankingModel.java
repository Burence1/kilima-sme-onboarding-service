package com.kilimabank.sme_onboarding_service.corebanking;

public final class CoreBankingModel {

  private CoreBankingModel() {}

  public record Customer(String customerId, String registrationNumber, String externalReference) {}

  public record CreateCustomerRequest(
      String registrationNumber, String legalName, String country, String externalReference) {}

  public record CreateAccountRequest(
      String customerId, String productCode, String currency, String externalReference) {}

  public record Account(String accountNumber) {}
}
