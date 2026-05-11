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

import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;
import io.gravitee.reporter.otellogs.mapper.EndpointStatusToLogRecordMapper;
import io.gravitee.reporter.otellogs.mapper.LogToLogRecordMapper;
import io.gravitee.reporter.otellogs.mapper.MessageMetricsToLogRecordMapper;
import io.gravitee.reporter.otellogs.mapper.MetricsToLogRecordMapper;
import io.gravitee.reporter.otellogs.writer.GclLogRecordExporter;
import io.gravitee.reporter.otellogs.writer.OtelLogWriter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the OTel logs reporter plugin.
 * <p>
 * Note: {@link io.gravitee.reporter.otellogs.OtelLogsReporter} is not declared as a {@code @Bean}
 * here — Gravitee's plugin manager instantiates and autowires it from this context automatically,
 * using the class name declared in {@code plugin.properties}.
 */
@Configuration
public class OtelLogsReporterSpringConfiguration {

  @Bean
  public OtelLogsReporterConfiguration otelLogsReporterConfiguration() {
    return new OtelLogsReporterConfiguration();
  }

  @Bean
  public OtelLogWriter otelLogWriter(OtelLogsReporterConfiguration cfg) {
    LogRecordExporter exporter = buildExporter(cfg);
    return new OtelLogWriter(
      exporter,
      cfg.getBatchSize(),
      cfg.getScheduledDelayMs()
    );
  }

  private LogRecordExporter buildExporter(OtelLogsReporterConfiguration cfg) {
    if ("gcloud".equalsIgnoreCase(cfg.getExporter())) {
      String projectId = cfg.getGcloudProjectId();
      if (projectId == null || projectId.isBlank()) {
        throw new IllegalStateException(
          "reporters.otel-logs.gcloud.projectId is required when exporter=gcloud"
        );
      }
      return new GclLogRecordExporter(
        projectId,
        cfg.getGcloudLogName(),
        cfg.getGcloudCredentialsFile()
      );
    }
    // Default: OTLP gRPC — uses plaintext when endpoint scheme is http://
    return OtlpGrpcLogRecordExporter.builder()
      .setEndpoint(cfg.getEndpoint())
      .build();
  }

  @Bean
  public MetricsToLogRecordMapper metricsToLogRecordMapper(
    OtelLogsReporterConfiguration cfg
  ) {
    return new MetricsToLogRecordMapper(cfg);
  }

  @Bean
  public LogToLogRecordMapper logToLogRecordMapper() {
    return new LogToLogRecordMapper();
  }

  @Bean
  public EndpointStatusToLogRecordMapper endpointStatusToLogRecordMapper() {
    return new EndpointStatusToLogRecordMapper();
  }

  @Bean
  public MessageMetricsToLogRecordMapper messageMetricsToLogRecordMapper() {
    return new MessageMetricsToLogRecordMapper();
  }
}
