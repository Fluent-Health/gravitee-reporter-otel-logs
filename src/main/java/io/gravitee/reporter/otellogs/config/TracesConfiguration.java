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

public class TracesConfiguration {

  @Value("${reporters.otellogs.traces.enabled:false}")
  private boolean enabled;

  @Value("${reporters.otellogs.traces.exporter:otlp}")
  private String exporter;

  @Value(
    "${reporters.otellogs.traces.endpoint:https://telemetry.googleapis.com/v1/traces}"
  )
  private String endpoint;

  // none | gcp-adc | static
  @Value("${reporters.otellogs.traces.authMode:gcp-adc}")
  private String authMode;

  private Map<String, String> headers = Collections.emptyMap();

  @Value("${reporters.otellogs.traces.batchSize:512}")
  private int batchSize;

  @Value("${reporters.otellogs.traces.scheduledDelayMs:5000}")
  private int scheduledDelayMs;

  // parent-based-always-on | always-on | ratio
  @Value("${reporters.otellogs.traces.sampler:parent-based-always-on}")
  private String sampler;

  @Value("${reporters.otellogs.traces.sampleRatio:1.0}")
  private double sampleRatio;

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

  public String getSampler() {
    return sampler;
  }

  public void setSampler(String v) {
    this.sampler = v;
  }

  public double getSampleRatio() {
    return sampleRatio;
  }

  public void setSampleRatio(double v) {
    this.sampleRatio = v;
  }
}
