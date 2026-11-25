package com.kilimabank.sme_onboarding_service.notification;

import com.kilimabank.sme_onboarding_service.utils.CommonUtils;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NotificationWorkers {

  private final IEmailService emailService;
  private final String headOfComplianceEmail;

  public NotificationWorkers(IEmailService emailService,
      @Value("${kilima.compliance.head-email:compliance@kilimabank.com}") String headOfComplianceEmail) {
    this.emailService = emailService;
    this.headOfComplianceEmail = headOfComplianceEmail;
  }

  @JobWorker(type = CommonUtils.JOB_SEND_WELCOME_EMAIL, fetchVariables = {"applicationId", "contactEmail", "businessName", "accountNumber"})
  public void sendSuccessNotification(
      @Variable("applicationId") String applicationId, @Variable("contactEmail") String contactEmail,
      @Variable("businessName") String businessName, @Variable("accountNumber") String accountNumber) {
    emailService.send(applicationId + ":welcome", contactEmail, "Your Kilima Bank business account is open",
        "Dear " + businessName + ",\n\nYour account " + accountNumber + " is ready to use.");
  }

  @JobWorker(type = CommonUtils.JOB_SEND_REJECTION_EMAIL, fetchVariables = {"applicationId", "contactEmail", "businessName"})
  public void sendRejectionNotification(
      @Variable("applicationId") String applicationId, @Variable("contactEmail") String contactEmail, @Variable("businessName") String businessName) {
    emailService.send(applicationId + ":rejection", contactEmail, "Your Kilima Bank business account application", "Dear " + businessName
        + ",\n\nWe are unable to open your account at this time. A relationship manager will" + " contact you.");
  }

  @JobWorker(type = CommonUtils.JOB_COMPLIANCE_ESCALATION, fetchVariables = {"applicationId"})
  public void sendEscalationNotification(@Variable("applicationId") String applicationId) {
    emailService.send(applicationId + ":escalation", headOfComplianceEmail,
        "Overdue compliance review: application " + applicationId, "The enhanced due diligence review for application "
        + applicationId + " has been open for 48 hours.");
  }
}
