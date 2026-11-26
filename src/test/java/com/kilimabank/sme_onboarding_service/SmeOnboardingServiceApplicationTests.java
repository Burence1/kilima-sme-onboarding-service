package com.kilimabank.sme_onboarding_service;

import io.camunda.process.test.api.CamundaSpringProcessTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"camunda.client.worker.defaults.enabled=false"})
@CamundaSpringProcessTest
class SmeOnboardingServiceApplicationTests {

  @Test
  void contextLoads() {}
}
