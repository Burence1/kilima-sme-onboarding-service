package com.kilimabank.sme_onboarding_service;

import io.camunda.client.annotation.Deployment;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@Deployment(resources = {"classpath*:bpmn/*.bpmn", "classpath*:dmn/*.dmn", "classpath*:forms/*.form"})
public class SmeOnboardingServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(SmeOnboardingServiceApplication.class, args);
	}

}
