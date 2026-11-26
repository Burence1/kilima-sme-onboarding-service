package com.kilimabank.sme_onboarding_service.notification;

public interface IEmailService {

  void send(String idempotencyKey, String to, String subject, String body);
}
