package com.kilimabank.sme_onboarding_service.stub;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("stub")
@RequestMapping("/stub/core-banking")
public class CoreBankingStub {

  public record Customer(String customerId, String registrationNumber, String externalReference) {}

  public record CreateCustomerRequest(String registrationNumber, String legalName, String country, String externalReference) {}

  public record CreateAccountRequest(String customerId, String productCode, String currency, String externalReference) {}

  private final Map<String, Customer> customersByRegistration = new ConcurrentHashMap<>();
  private final Map<String, Customer> customersById = new ConcurrentHashMap<>();
  private final AtomicInteger sequence = new AtomicInteger(1000);
  private final Deque<Long> calls = new ArrayDeque<>();
  private final boolean forceMaintenance;

  public CoreBankingStub(@Value("${stub.core-banking.force-maintenance:false}") boolean forceMaintenance) {
    this.forceMaintenance = forceMaintenance;
  }

  @GetMapping("/customers")
  public ResponseEntity<?> findCustomer(@RequestParam String registrationNumber) {
    ResponseEntity<?> refused = refuseIfUnavailable();
    if (refused != null) {
      return refused;
    }
    Customer customer = customersByRegistration.get(registrationNumber);
    return customer == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(customer);
  }

  @PostMapping("/customers")
  public ResponseEntity<?> createCustomer(@RequestBody CreateCustomerRequest request) {
    ResponseEntity<?> refused = refuseIfUnavailable();
    if (refused != null) {
      return refused;
    }
    if (request.registrationNumber().contains("CUST422")) {
      return ResponseEntity.unprocessableContent()
          .body(Map.of("error", "Registration number format is invalid"));
    }
    Customer customer = new Customer("CUS-" + sequence.incrementAndGet(), request.registrationNumber(), request.externalReference());
    customersByRegistration.put(request.registrationNumber(), customer);
    customersById.put(customer.customerId(), customer);

    return ResponseEntity.status(201).body(customer);
  }

  @PostMapping("/accounts")
  public ResponseEntity<?> createAccount(@RequestBody CreateAccountRequest request) throws InterruptedException {

    ResponseEntity<?> refused = refuseIfUnavailable();
    if (refused != null) {
      return refused;
    }
    Customer customer = customersById.get(request.customerId());
    if (customer == null) {
      return ResponseEntity.unprocessableContent().body(Map.of("error", "Unknown customer"));
    }
    if (customer.registrationNumber().contains("ACC422")) {
      return ResponseEntity.unprocessableContent()
          .body(Map.of("error", "Product SME_CURRENT is not allowed for this legal form"));
    }
    String accountNumber = "01" + sequence.incrementAndGet() + "0001";
    if (customer.registrationNumber().contains("ACCTIMEOUT")) {
      Thread.sleep(Duration.ofSeconds(15));
    }
    return ResponseEntity.status(201).body(Map.of("accountNumber", accountNumber));
  }

  private synchronized ResponseEntity<?> refuseIfUnavailable() {
    LocalTime eat = ZonedDateTime.now(ZoneId.of("Africa/Nairobi")).toLocalTime();
    if (forceMaintenance || eat.isBefore(LocalTime.of(2, 0))) {
      return ResponseEntity.status(503).header("Retry-After", "600").build();
    }
    long now = System.currentTimeMillis();
    while (!calls.isEmpty() && calls.peekFirst() <= now - 60_000) {
      calls.pollFirst();
    }
    if (calls.size() >= 20) {
      return ResponseEntity.status(429).header("Retry-After", "30").build();
    }
    calls.addLast(now);
    return null;
  }
}
