package com.kilimabank.sme_onboarding_service.stub;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("stub")
@RequestMapping("/stub/registry")
public class RegistryStub {

  @GetMapping("/businesses/{registrationNumber}")
  public ResponseEntity<?> find(@PathVariable String registrationNumber) throws InterruptedException {
    if (ThreadLocalRandom.current().nextInt(100) < 2) {
      Thread.sleep(Duration.ofSeconds(5));
    }
    if (registrationNumber.contains("NOTFOUND")) {
      return ResponseEntity.notFound().build();
    }
    String status = registrationNumber.contains("INACTIVE") ? "DEREGISTERED" : "ACTIVE";

    return ResponseEntity.ok(Map.of("registrationNumber", registrationNumber, "name", "Stub Ltd", "status", status));
  }
}
