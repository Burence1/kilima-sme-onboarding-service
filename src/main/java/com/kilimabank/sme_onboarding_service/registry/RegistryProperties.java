package com.kilimabank.sme_onboarding_service.registry;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kilima.registry")
public record RegistryProperties(
    String baseUrl, Duration connectTimeout, Duration readTimeout) {}

