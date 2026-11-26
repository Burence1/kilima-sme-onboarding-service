package com.kilimabank.sme_onboarding_service.screening;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kilima.screening")
public record ScreeningProperties(String baseUrl, String callbackUrl) {}
