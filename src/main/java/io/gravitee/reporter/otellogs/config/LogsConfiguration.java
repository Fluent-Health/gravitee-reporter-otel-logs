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

import java.util.Collections;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;

public class LogsConfiguration {

  @Value("${reporters.otellogs.logs.enabled:true}")
  private boolean enabled;

  // gcloud | otlp | none — falls back to the old flat key.
  @Value(
    "${reporters.otellogs.logs.exporter:${reporters.otellogs.exporter:otlp}}"
  )
  private String exporter;

  // gcloud-only
  @Value(
    "${reporters.otellogs.logs.logName:${reporters.otellogs.gcloud.logName:gravitee-api-gateway}}"
  )
  private String logName;

  // otlp-only
  @Value(
    "${reporters.otellogs.logs.endpoint:${reporters.otellogs.endpoint:http://localhost:4317}}"
  )
  private String endpoint;

  // otlp-only: none | gcp-adc | static
  @Value("${reporters.otellogs.logs.authMode:none}")
  private String authMode;

  // otlp-only, only when authMode=static — left as Map; injection
  // happens via @Value if customers set it as a flat list of properties,
  // otherwise stays empty.
  private Map<String, String> headers = Collections.emptyMap();

  @Value(
    "${reporters.otellogs.logs.batchSize:${reporters.otellogs.batchSize:512}}"
  )
  private int batchSize;

  @Value(
    "${reporters.otellogs.logs.scheduledDelayMs:${reporters.otellogs.scheduledDelayMs:5000}}"
  )
  private int scheduledDelayMs;

  @Value(
    "${reporters.otellogs.logs.reportHealthChecks:${reporters.otellogs.reportHealthChecks:true}}"
  )
  private boolean reportHealthChecks;

  // Renamed: old reportLogs → reportRequestLogs.
  @Value(
    "${reporters.otellogs.logs.reportRequestLogs:${reporters.otellogs.reportLogs:false}}"
  )
  private boolean reportRequestLogs;

  @Value(
    "${reporters.otellogs.logs.reportMessageMetrics:${reporters.otellogs.reportMessageMetrics:true}}"
  )
  private boolean reportMessageMetrics;

  // Forward request/response bodies inside Log events. Default-deny: bodies
  // are only emitted when this flag AND reportRequestLogs are both true.
  // Whatever bodies arrive here have already been filtered upstream by the
  // API-level logging config — this flag does NOT bypass that filter.
  @Value("${reporters.otellogs.logs.reportPayloads:false}")
  private boolean reportPayloads;

  public boolean isEnabled() {
    return enabled;
  }

  // Setters used by tests (Spring uses @Value injection, not these)
  public void setEnabled(boolean v) {
    this.enabled = v;
  }

  public String getExporter() {
    return exporter;
  }

  public void setExporter(String v) {
    this.exporter = v;
  }

  public String getLogName() {
    return logName;
  }

  public void setLogName(String v) {
    this.logName = v;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String v) {
    this.endpoint = v;
  }

  public String getAuthMode() {
    return authMode;
  }

  public void setAuthMode(String v) {
    this.authMode = v;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public void setHeaders(Map<String, String> v) {
    this.headers = v == null ? Map.of() : Map.copyOf(v);
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int v) {
    this.batchSize = v;
  }

  public int getScheduledDelayMs() {
    return scheduledDelayMs;
  }

  public void setScheduledDelayMs(int v) {
    this.scheduledDelayMs = v;
  }

  public boolean isReportHealthChecks() {
    return reportHealthChecks;
  }

  public void setReportHealthChecks(boolean v) {
    this.reportHealthChecks = v;
  }

  public boolean isReportRequestLogs() {
    return reportRequestLogs;
  }

  public void setReportRequestLogs(boolean v) {
    this.reportRequestLogs = v;
  }

  public boolean isReportMessageMetrics() {
    return reportMessageMetrics;
  }

  public void setReportMessageMetrics(boolean v) {
    this.reportMessageMetrics = v;
  }

  public boolean isReportPayloads() {
    return reportPayloads;
  }

  public void setReportPayloads(boolean v) {
    this.reportPayloads = v;
  }
}
