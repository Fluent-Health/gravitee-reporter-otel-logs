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
package io.gravitee.reporter.otellogs.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.google.api.gax.paging.Page;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.Logging.EntryListOption;
import com.google.cloud.logging.LoggingOptions;
import io.gravitee.reporter.otellogs.mapper.OtelLogRecord;
import io.gravitee.reporter.otellogs.writer.OtelLogWriter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test that writes an OTel log record to Google Cloud Logging via
 * OTLP gRPC and verifies it arrives. Requires Application Default Credentials:
 * in CI via Workload Identity Federation, locally via `gcloud auth application-default login`.
 *
 * Run with: mvn verify -Pgcloud-integration-test
 */
@Tag("integration")
@Tag("gcloud-integration")
class GclLogsReporterIT {

  private static Logging logging;
  private static String projectId;
  private static OtelLogWriter writer;

  @BeforeAll
  static void setUp() {
    logging = LoggingOptions.getDefaultInstance().getService();
    projectId = LoggingOptions.getDefaultInstance().getProjectId();
    assertThat(projectId)
      .as(
        "GCP project ID must be discoverable via ADC or GOOGLE_CLOUD_PROJECT env var"
      )
      .isNotNull()
      .isNotBlank();

    var exporter = OtlpGrpcLogRecordExporter.builder()
      .setEndpoint("https://logging.googleapis.com")
      .build();
    // Single-item batches and minimal delay to flush immediately in tests
    writer = new OtelLogWriter(exporter, 1, 100);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (writer != null) {
      writer.flush();
      writer.close();
    }
    if (logging != null) {
      logging.close();
    }
  }

  @Test
  void metricsLogRecordArrivesInGoogleCloudLogging() {
    // Use a unique 32-hex traceId so this test run's entry can be found unambiguously.
    String traceId = UUID.randomUUID().toString().replace("-", "");
    // Provide a spanId so OtelLogWriter sets the OTel span context, which GCL maps to the trace field.
    String spanId = traceId.substring(0, 16);

    var record = new OtelLogRecord(
      traceId,
      spanId,
      null,
      null,
      Severity.INFO,
      System.currentTimeMillis() * 1_000_000L,
      "GET /gcloud-it-probe → 200",
      Attributes.builder()
        .put(AttributeKey.stringKey("api.name"), "GCL IT Test")
        .put(AttributeKey.stringKey("api.id"), "gcloud-it")
        .put(AttributeKey.longKey("http.status"), 200L)
        .build()
    );

    writer.emit(record);
    writer.flush();

    // GCL maps the OTel traceId to the LogEntry trace field as
    // "projects/{project}/traces/{traceId}".
    String filter = String.format(
      "trace=\"projects/%s/traces/%s\"",
      projectId,
      traceId
    );

    await("log entry with traceId=" + traceId + " to appear in GCL")
      .atMost(Duration.ofSeconds(90))
      .pollInterval(Duration.ofSeconds(5))
      .conditionEvaluationListener(condition ->
        System.out.printf(
          "Polling GCL (elapsed %.0fs / 90s)%n",
          condition.getElapsedTimeInMS() / 1000.0
        )
      )
      .until(() -> {
        Page<LogEntry> page = logging.listLogEntries(
          EntryListOption.filter(filter)
        );
        return page.iterateAll().iterator().hasNext();
      });

    Page<LogEntry> page = logging.listLogEntries(
      EntryListOption.filter(filter)
    );
    LogEntry entry = page.iterateAll().iterator().next();
    assertThat(entry).isNotNull();
    assertThat(entry.getLogName()).contains("projects/" + projectId);
  }
}
