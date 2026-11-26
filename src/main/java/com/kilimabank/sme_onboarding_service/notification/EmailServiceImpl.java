package com.kilimabank.sme_onboarding_service.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmailServiceImpl implements IEmailService {

  @Override
  public void send(String idempotencyKey, String to, String subject, String body) {
    log.info("EMAIL [{}] to={} subject=\"{}\"\n{}", idempotencyKey, to, subject, body);
  }

}
