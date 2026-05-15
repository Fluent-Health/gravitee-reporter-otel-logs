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
package io.gravitee.reporter.otellogs.spring;

import io.gravitee.reporter.otellogs.config.LegacyConfigWarning;
import io.gravitee.reporter.otellogs.config.LogsConfiguration;
import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;
import io.gravitee.reporter.otellogs.config.ResourceConfiguration;
import io.gravitee.reporter.otellogs.config.TracesConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class OtelLogsReporterSpringConfiguration {

  @Bean
  public LogsConfiguration logsConfiguration() {
    return new LogsConfiguration();
  }

  @Bean
  public TracesConfiguration tracesConfiguration() {
    return new TracesConfiguration();
  }

  @Bean
  public ResourceConfiguration resourceConfiguration() {
    return new ResourceConfiguration();
  }

  @Bean
  public OtelLogsReporterConfiguration otelLogsReporterConfiguration(
    LogsConfiguration logs,
    TracesConfiguration traces,
    ResourceConfiguration resource
  ) {
    return new OtelLogsReporterConfiguration(logs, traces, resource);
  }

  @Bean
  public LegacyConfigWarning legacyConfigWarning(Environment env) {
    LegacyConfigWarning warn = new LegacyConfigWarning(env);
    warn.run();
    return warn;
  }
}
