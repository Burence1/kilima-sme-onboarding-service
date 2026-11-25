package com.kilimabank.sme_onboarding_service.screening;

import com.kilimabank.sme_onboarding_service.utils.CommonUtils;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.command.ClientHttpException;
import io.camunda.client.api.command.ClientStatusException;
import io.grpc.Status;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequestMapping("/api/screening")
public class CallbackController {


  public record ScreeningResult(
      String applicationId, String screeningRequestId, boolean match, String reference) {}

  private final CamundaClient camundaClient;

  public CallbackController(CamundaClient camundaClient) {
    this.camundaClient = camundaClient;
  }

  @PostMapping("/callback")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void onResult(@RequestBody ScreeningResult result) {
    try {
      camundaClient
          .newPublishMessageCommand()
          .messageName(CommonUtils.MESSAGE_SCREENING_COMPLETED)
          .correlationKey(result.applicationId())
          .messageId(result.screeningRequestId())
          .timeToLive(Duration.ofHours(1))
          .variables(
              Map.of("screeningMatch", result.match(), "screeningReference", result.reference()))
          .send()
          .join();

      log.info("Published screening result for application {} (match: {})", result.applicationId(), result.match());
    } catch (ClientStatusException e) {
      if (e.getStatusCode() != Status.Code.ALREADY_EXISTS) {
        throw e;
      }
      log.info("Duplicate screening callback {} ignored", result.screeningRequestId());
    } catch (ClientHttpException e) {
      if (e.code() != 409) {
        throw e;
      }
      log.info("Duplicate screening callback {} ignored", result.screeningRequestId());
    }
  }
}
