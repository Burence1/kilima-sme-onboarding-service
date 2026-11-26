package com.kilimabank.sme_onboarding_service.corebanking;


import com.kilimabank.sme_onboarding_service.corebanking.CoreBankingModel.Account;
import com.kilimabank.sme_onboarding_service.corebanking.CoreBankingModel.CreateAccountRequest;
import com.kilimabank.sme_onboarding_service.corebanking.CoreBankingModel.CreateCustomerRequest;
import com.kilimabank.sme_onboarding_service.corebanking.CoreBankingModel.Customer;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class CoreBankingClient {

  private final RestClient restClient;

  public CoreBankingClient(CoreBankingProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    this.restClient = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
  }

  public Optional<Customer> findCustomer(String registrationNumber) {
    return send("GET /customers", true,
        () -> restClient
                .get()
                .uri(b -> b.path("/customers").queryParam("registrationNumber", registrationNumber).build())
                .exchange(
                    (request, response) -> {
                      int status = response.getStatusCode().value();
                      if (status == 404) {
                        return Optional.<Customer>empty();
                      }
                      if (status == 200) {
                        return Optional.ofNullable(response.bodyTo(Customer.class));
                      }
                      throw toException(status, response, true);
                    }));
  }

  public Customer createCustomer(CreateCustomerRequest body) {
    return send(
        "POST /customers",
        false,
        () -> restClient
                .post()
                .uri("/customers")
                .body(body)
                .exchange(
                    (request, response) -> {
                      int status = response.getStatusCode().value();
                      if (status == 200 || status == 201) {
                        return response.bodyTo(Customer.class);
                      }
                      throw toException(status, response, false);
                    }));
  }

  public Account createAccount(CreateAccountRequest body) {
    return send("POST /accounts", false,
        () -> restClient
                .post()
                .uri("/accounts")
                .body(body)
                .exchange(
                    (request, response) -> {
                      int status = response.getStatusCode().value();
                      if (status == 200 || status == 201) {
                        return response.bodyTo(Account.class);
                      }
                      throw toException(status, response, false);
                    }));
  }

  private <T> T send(String operation, boolean safeToRepeat, Supplier<T> httpCall) {
    try {
      return httpCall.get();
    } catch (ResourceAccessException e) {
      if (neverReachedServer(e)) {
        throw new CoreBankingException.NotProcessed(
            operation + ": could not connect", 0, null, e);
      }
      if (safeToRepeat) {
        throw new CoreBankingException.NotProcessed(
            operation + ": no response (" + e.getMessage() + ")", 0, null, e);
      }
      throw new CoreBankingException.OutcomeUnknown(
          operation + ": sent but no response, it may or may not have been processed", e);
    }
  }

  private static boolean neverReachedServer(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException || t instanceof UnknownHostException) {
        return true;
      }
    }
    return false;
  }

  private static CoreBankingException toException(
      int status, ClientHttpResponse response, boolean safeToRepeat) throws IOException {
    return switch (status) {
      case 429 -> new CoreBankingException.NotProcessed(
          "Rate limited by core banking (429)", 429, retryAfter(response), null);
      case 503 -> new CoreBankingException.NotProcessed(
          "Core banking unavailable (503)", 503, retryAfter(response), null);
      case 422 -> new CoreBankingException.ValidationFailed(body(response));
      case 500, 502, 504 -> safeToRepeat
          ? new CoreBankingException.NotProcessed(
          "Core banking error (" + status + ")", status, null, null)
          : new CoreBankingException.OutcomeUnknown(
              "Core banking error (" + status + ") after the request was sent", null);
      default -> new CoreBankingException.Unexpected(
          "Unexpected core banking response " + status + ": " + body(response));
    };
  }

  private static Duration retryAfter(ClientHttpResponse response) {
    String value = response.getHeaders().getFirst("Retry-After");
    if (value == null) {
      return null;
    }
    try {
      return Duration.ofSeconds(Long.parseLong(value.trim()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String body(ClientHttpResponse response) throws IOException {
    return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
  }
}
