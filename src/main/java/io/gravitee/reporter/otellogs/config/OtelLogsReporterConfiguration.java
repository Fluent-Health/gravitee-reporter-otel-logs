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

  @Value("${reporters.otellogs.enabled:true}")
  private boolean enabled;

  @Value("${reporters.otellogs.exporter:otlp}")
  private String exporter;

  @Value("${reporters.otellogs.endpoint:http://localhost:4317}")
  private String endpoint;

  @Value("${reporters.otellogs.correlationHeader:X-Request-ID}")
  private String correlationHeader;

  @Value("${reporters.otellogs.batchSize:512}")
  private int batchSize;

  @Value("${reporters.otellogs.scheduledDelayMs:5000}")
  private int scheduledDelayMs;

  @Value("${reporters.otellogs.reportHealthChecks:true}")
  private boolean reportHealthChecks;

  @Value("${reporters.otellogs.reportLogs:false}")
  private boolean reportLogs;

  @Value("${reporters.otellogs.reportMessageMetrics:true}")
  private boolean reportMessageMetrics;

  @Value("${reporters.otellogs.gcloud.projectId:#{null}}")
  private String gcloudProjectId;

  @Value("${reporters.otellogs.gcloud.logName:gravitee-api-gateway}")
  private String gcloudLogName;

  @Value("${reporters.otellogs.gcloud.credentialsFile:#{null}}")
  private String gcloudCredentialsFile;

  public boolean isEnabled() {
    return enabled;
  }

  public String getExporter() {
    return exporter;
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

  public String getGcloudProjectId() {
    return gcloudProjectId;
  }

  public String getGcloudLogName() {
    return gcloudLogName;
  }

  public String getGcloudCredentialsFile() {
    return gcloudCredentialsFile;
  }

  // Setters used by tests (Spring uses @Value injection, not these)
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public void setExporter(String exporter) {
    this.exporter = exporter;
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

  public void setGcloudProjectId(String gcloudProjectId) {
    this.gcloudProjectId = gcloudProjectId;
  }

  public void setGcloudLogName(String gcloudLogName) {
    this.gcloudLogName = gcloudLogName;
  }

  public void setGcloudCredentialsFile(String gcloudCredentialsFile) {
    this.gcloudCredentialsFile = gcloudCredentialsFile;
  }
}
