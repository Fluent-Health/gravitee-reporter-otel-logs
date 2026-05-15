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

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Emits a one-time WARN for each legacy flat property key that is still in use.
 * The new SpEL fallbacks in LogsConfiguration / ResourceConfiguration still honour
 * the legacy values; this class only nags operators to migrate.
 */
public class LegacyConfigWarning implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(
    LegacyConfigWarning.class
  );

  // ordered for deterministic warning output
  private static final Map<String, String> RENAMES = new LinkedHashMap<>();

  static {
    RENAMES.put(
      "reporters.otellogs.exporter",
      "reporters.otellogs.logs.exporter"
    );
    RENAMES.put(
      "reporters.otellogs.endpoint",
      "reporters.otellogs.logs.endpoint"
    );
    RENAMES.put(
      "reporters.otellogs.batchSize",
      "reporters.otellogs.logs.batchSize"
    );
    RENAMES.put(
      "reporters.otellogs.scheduledDelayMs",
      "reporters.otellogs.logs.scheduledDelayMs"
    );
    RENAMES.put(
      "reporters.otellogs.reportHealthChecks",
      "reporters.otellogs.logs.reportHealthChecks"
    );
    RENAMES.put(
      "reporters.otellogs.reportLogs",
      "reporters.otellogs.logs.reportRequestLogs"
    );
    RENAMES.put(
      "reporters.otellogs.reportMessageMetrics",
      "reporters.otellogs.logs.reportMessageMetrics"
    );
    RENAMES.put(
      "reporters.otellogs.gcloud.projectId",
      "reporters.otellogs.resource.gcp.projectId"
    );
    RENAMES.put(
      "reporters.otellogs.gcloud.logName",
      "reporters.otellogs.logs.logName"
    );
    // gcloud.credentialsFile is removed silently — see spec rationale.
  }

  private final Environment env;

  public LegacyConfigWarning(Environment env) {
    this.env = env;
  }

  @Override
  public void run() {
    RENAMES.forEach((legacy, replacement) -> {
      if (env.containsProperty(legacy)) {
        log.warn(
          "Deprecated config key '{}' will be removed next release — please use '{}' instead",
          legacy,
          replacement
        );
      }
    });
  }
}
