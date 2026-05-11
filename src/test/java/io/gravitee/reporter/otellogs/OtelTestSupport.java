/*
 * Copyright © 2026 Fluent Health (https://fluentinhealth.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.reporter.otellogs;

import io.gravitee.common.http.HttpMethod;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.reporter.api.common.Request;
import io.gravitee.reporter.api.common.Response;
import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;
import java.time.Instant;
import java.util.Map;

public final class OtelTestSupport {

  private OtelTestSupport() {}

  /** Epoch millis for 2026-01-15T12:00:00Z — used across all test fixtures. */
  public static final long FIXTURE_TIMESTAMP_MS = Instant.parse(
    "2026-01-15T12:00:00Z"
  ).toEpochMilli();

  public static OtelLogsReporterConfiguration config() {
    var cfg = new OtelLogsReporterConfiguration();
    cfg.setEnabled(true);
    cfg.setEndpoint("http://localhost:4317");
    cfg.setInsecure(true);
    cfg.setCorrelationHeader("X-Request-ID");
    cfg.setBatchSize(512);
    cfg.setScheduledDelayMs(5000);
    cfg.setReportHealthChecks(true);
    cfg.setReportLogs(false);
    cfg.setReportMessageMetrics(true);
    return cfg;
  }

  public static Metrics metrics(int status) {
    Metrics m = new Metrics();
    m.setApiId("api-123");
    m.setApiName("Test API");
    m.setHttpMethod(HttpMethod.GET);
    m.setUri("/api/v1/users/42");
    m.setStatus(status);
    m.setRequestContentLength(128L);
    m.setResponseContentLength(512L);
    m.setGatewayResponseTimeMs(42L);
    m.setEndpoint("https://backend.example.com/api/users/42");
    m.setSubscriptionId("sub-abc");
    m.setPlanId("plan-gold");
    m.setApplicationId("app-001");
    m.setTimestamp(FIXTURE_TIMESTAMP_MS);
    return m;
  }

  /**
   * Creates a Metrics instance with request headers attached via the embedded Log.
   * The MetricsToLogRecordMapper reads headers from m.getLog().getEntrypointRequest().getHeaders().
   */
  public static Metrics metricsWithHeaders(
    int status,
    Map<String, String> headers
  ) {
    Metrics m = metrics(status);
    HttpHeaders httpHeaders = HttpHeaders.create();
    headers.forEach(httpHeaders::set);
    Request entrypointRequest = new Request();
    entrypointRequest.setMethod(HttpMethod.GET);
    entrypointRequest.setUri("/api/v1/users/42");
    entrypointRequest.setHeaders(httpHeaders);
    Log log = Log.builder()
      .apiId("api-123")
      .apiName("Test API")
      .entrypointRequest(entrypointRequest)
      .build();
    m.setLog(log);
    return m;
  }

  public static EndpointStatus endpointStatus(boolean available) {
    EndpointStatus s = EndpointStatus.forEndpoint(
      "api-123",
      "Test API",
      "https://backend.example.com/health"
    )
      .on(FIXTURE_TIMESTAMP_MS)
      .build();
    s.setAvailable(available);
    s.setResponseTime(100L);
    return s;
  }

  /**
   * Creates a v4 Log instance. Since Log v4 stores request metadata on the entrypointRequest
   * and response status on entrypointResponse, we construct those sub-objects.
   */
  public static Log log(int status) {
    HttpHeaders requestHeaders = HttpHeaders.create();
    requestHeaders.set("Content-Type", "application/json");
    requestHeaders.set("X-Request-ID", "test-req-id");

    Request entrypointRequest = new Request();
    entrypointRequest.setMethod(HttpMethod.POST);
    entrypointRequest.setUri("/api/v1/data");
    entrypointRequest.setHeaders(requestHeaders);

    HttpHeaders responseHeaders = HttpHeaders.create();
    responseHeaders.set("Content-Type", "application/json");

    Response entrypointResponse = new Response(status);
    entrypointResponse.setHeaders(responseHeaders);

    return Log.builder()
      .apiId("api-123")
      .apiName("Test API")
      .entrypointRequest(entrypointRequest)
      .entrypointResponse(entrypointResponse)
      .build();
  }

  public static MessageMetrics messageMetrics() {
    return MessageMetrics.builder()
      .apiId("api-123")
      .apiName("Test API")
      .count(10L)
      .errorCount(1L)
      .contentLength(2048L)
      .build();
  }
}
