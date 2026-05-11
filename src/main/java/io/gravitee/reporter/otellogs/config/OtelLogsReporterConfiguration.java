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
package io.gravitee.reporter.otellogs.config;

import org.springframework.beans.factory.annotation.Value;

public class OtelLogsReporterConfiguration {

  @Value("${reporters.otel-logs.enabled:true}")
  private boolean enabled;

  @Value("${reporters.otel-logs.endpoint:https://logging.googleapis.com}")
  private String endpoint;

  @Value("${reporters.otel-logs.correlationHeader:X-Request-ID}")
  private String correlationHeader;

  @Value("${reporters.otel-logs.batchSize:512}")
  private int batchSize;

  @Value("${reporters.otel-logs.scheduledDelayMs:5000}")
  private int scheduledDelayMs;

  @Value("${reporters.otel-logs.reportHealthChecks:true}")
  private boolean reportHealthChecks;

  @Value("${reporters.otel-logs.reportLogs:false}")
  private boolean reportLogs;

  @Value("${reporters.otel-logs.reportMessageMetrics:true}")
  private boolean reportMessageMetrics;

  public boolean isEnabled() {
    return enabled;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public String getCorrelationHeader() {
    return correlationHeader;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public int getScheduledDelayMs() {
    return scheduledDelayMs;
  }

  public boolean isReportHealthChecks() {
    return reportHealthChecks;
  }

  public boolean isReportLogs() {
    return reportLogs;
  }

  public boolean isReportMessageMetrics() {
    return reportMessageMetrics;
  }

  // Setters used by tests (Spring uses @Value injection, not these)
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public void setCorrelationHeader(String correlationHeader) {
    this.correlationHeader = correlationHeader;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public void setScheduledDelayMs(int scheduledDelayMs) {
    this.scheduledDelayMs = scheduledDelayMs;
  }

  public void setReportHealthChecks(boolean reportHealthChecks) {
    this.reportHealthChecks = reportHealthChecks;
  }

  public void setReportLogs(boolean reportLogs) {
    this.reportLogs = reportLogs;
  }

  public void setReportMessageMetrics(boolean reportMessageMetrics) {
    this.reportMessageMetrics = reportMessageMetrics;
  }
}
