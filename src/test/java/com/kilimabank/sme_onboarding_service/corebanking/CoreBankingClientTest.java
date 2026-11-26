package com.kilimabank.sme_onboarding_service.corebanking;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.kilimabank.sme_onboarding_service.corebanking.CoreBankingModel.CreateAccountRequest;
import com.kilimabank.sme_onboarding_service.corebanking.CoreBankingModel.CreateCustomerRequest;
import com.kilimabank.sme_onboarding_service.utils.CommonUtils;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class CoreBankingClientTest {

  @RegisterExtension
  static WireMockExtension coreBanking =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  private CoreBankingClient client(String baseUrl) {
    return new CoreBankingClient(
        new CoreBankingProperties(
            baseUrl, Duration.ofMillis(500), Duration.ofMillis(500), 18, 10, null));
  }

  private CoreBankingClient client() {
    return client(coreBanking.baseUrl());
  }

  private static final CreateAccountRequest ACCOUNT =
      new CreateAccountRequest("CUS-1", CommonUtils.SME_PRODUCT_CODE, "KES", "APP-1");

  @Test
  void lookupReturnsEmptyOn404() {
    coreBanking.stubFor(get(urlPathEqualTo("/customers")).willReturn(aResponse().withStatus(404)));

    assertThat(client().findCustomer("PVT-123")).isEmpty();
  }

  @Test
  void lookupReturnsTheCustomer() {
    coreBanking.stubFor(
        get(urlPathEqualTo("/customers"))
            .willReturn(
                okJson(
                    "{\"customerId\":\"CUS-1\",\"registrationNumber\":\"PVT-123\","
                        + "\"externalReference\":\"APP-1\"}")));

    assertThat(client().findCustomer("PVT-123"))
        .contains(new CoreBankingModel.Customer("CUS-1", "PVT-123", "APP-1"));
  }

  @Test
  void validationErrorKeepsTheDetails() {
    coreBanking.stubFor(
        post("/accounts").willReturn(aResponse().withStatus(422).withBody("bad product")));

    CoreBankingException.ValidationFailed error =
        catchThrowableOfType(
            CoreBankingException.ValidationFailed.class, () -> client().createAccount(ACCOUNT));

    assertThat(error.details()).isEqualTo("bad product");
  }

  @Test
  void rateLimitIsNotProcessedAndCarriesRetryAfter() {
    coreBanking.stubFor(
        post("/accounts").willReturn(aResponse().withStatus(429).withHeader("Retry-After", "30")));

    CoreBankingException.NotProcessed error =
        catchThrowableOfType(
            CoreBankingException.NotProcessed.class, () -> client().createAccount(ACCOUNT));

    assertThat(error.isRateLimited()).isTrue();
    assertThat(error.retryAfter()).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void maintenance503IsNotProcessed() {
    coreBanking.stubFor(post("/accounts").willReturn(aResponse().withStatus(503)));

    CoreBankingException.NotProcessed error =
        catchThrowableOfType(
            CoreBankingException.NotProcessed.class, () -> client().createAccount(ACCOUNT));

    assertThat(error.isUnavailable()).isTrue();
  }

  @Test
  void readTimeoutOnPostMeansTheOutcomeIsUnknown() {
    coreBanking.stubFor(
        post("/accounts").willReturn(okJson("{\"accountNumber\":\"01\"}").withFixedDelay(2_000)));

    assertThatThrownBy(() -> client().createAccount(ACCOUNT))
        .isInstanceOf(CoreBankingException.OutcomeUnknown.class);
  }

  @Test
  void serverErrorOnPostMeansTheOutcomeIsUnknown() {
    coreBanking.stubFor(post("/customers").willReturn(aResponse().withStatus(500)));

    assertThatThrownBy(
        () -> client().createCustomer(new CreateCustomerRequest("PVT-1", "Acme", "KE", "APP-1")))
        .isInstanceOf(CoreBankingException.OutcomeUnknown.class);
  }

  @Test
  void readTimeoutOnLookupIsSafeToRetry() {
    coreBanking.stubFor(
        get(urlPathEqualTo("/customers")).willReturn(aResponse().withStatus(404).withFixedDelay(2_000)));

    assertThatThrownBy(() -> client().findCustomer("PVT-123"))
        .isInstanceOf(CoreBankingException.NotProcessed.class);
  }

  @Test
  void connectionRefusedIsNotProcessed() {
    assertThatThrownBy(
        () -> client("http://localhost:1").createAccount(ACCOUNT))
        .isInstanceOf(CoreBankingException.NotProcessed.class);
  }
}
