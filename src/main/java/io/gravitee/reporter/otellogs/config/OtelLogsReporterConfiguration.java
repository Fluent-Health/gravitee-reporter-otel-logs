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

  @Value("${reporters.otellogs.correlationHeader:X-Request-ID}")
  private String correlationHeader;

  private final LogsConfiguration logs;
  private final TracesConfiguration traces;
  private final ResourceConfiguration resource;

  public OtelLogsReporterConfiguration(
    LogsConfiguration logs,
    TracesConfiguration traces,
    ResourceConfiguration resource
  ) {
    this.logs = logs;
    this.traces = traces;
    this.resource = resource;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getCorrelationHeader() {
    return correlationHeader;
  }

  public LogsConfiguration getLogs() {
    return logs;
  }

  public TracesConfiguration getTraces() {
    return traces;
  }

  public ResourceConfiguration getResource() {
    return resource;
  }

  // Setters used by tests
  public void setEnabled(boolean v) {
    this.enabled = v;
  }

  public void setCorrelationHeader(String v) {
    this.correlationHeader = v;
  }
}
