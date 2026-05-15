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

import io.gravitee.common.component.Lifecycle;
import io.gravitee.common.service.AbstractService;
import io.gravitee.reporter.api.Reportable;
import io.gravitee.reporter.api.Reporter;
import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;
import io.gravitee.reporter.otellogs.mapper.EndpointStatusToLogRecordMapper;
import io.gravitee.reporter.otellogs.mapper.LogToLogRecordMapper;
import io.gravitee.reporter.otellogs.mapper.MessageMetricsToLogRecordMapper;
import io.gravitee.reporter.otellogs.mapper.MetricsToLogRecordMapper;
import io.gravitee.reporter.otellogs.mapper.MetricsToSpanMapper;
import io.gravitee.reporter.otellogs.mapper.OtelLogRecord;
import io.gravitee.reporter.otellogs.mapper.TraceContextResolver;
import io.gravitee.reporter.otellogs.writer.CustomOtlpHttpLogRecordExporter;
import io.gravitee.reporter.otellogs.writer.CustomOtlpHttpSpanExporter;
import io.gravitee.reporter.otellogs.writer.GclLogRecordExporter;
import io.gravitee.reporter.otellogs.writer.GcpEnvironment;
import io.gravitee.reporter.otellogs.writer.GcpOtelResource;
import io.gravitee.reporter.otellogs.writer.OtelLogWriter;
import io.gravitee.reporter.otellogs.writer.OtelTraceWriter;
import io.gravitee.reporter.otellogs.writer.OtlpAuthHeaders;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Gravitee APIM Reporter plugin that emits structured log records via the OTel Logs SDK.
 * Handles both HTTP request metrics (V4) and full request logs.
 */
public class OtelLogsReporter
  extends AbstractService<Reporter>
  implements Reporter {

  private static final Logger log = LoggerFactory.getLogger(
    OtelLogsReporter.class
  );

  private final OtelLogsReporterConfiguration cfg;

  private final MetricsToLogRecordMapper metricsMapper;
  private final LogToLogRecordMapper logMapper;
  private final EndpointStatusToLogRecordMapper endpointMapper;
  private final MessageMetricsToLogRecordMapper messageMapper;

  private OtelLogWriter writer;
  private OtelTraceWriter traceWriter;
  private MetricsToSpanMapper spanMapper;

  @Autowired
  public OtelLogsReporter(OtelLogsReporterConfiguration cfg) {
    log.info("OTelLogsReporter constructor called with cfg: {}", cfg);
    this.cfg = cfg;
    this.metricsMapper = new MetricsToLogRecordMapper(
      cfg,
      cfg.getLogs().isReportPayloads(),
      cfg.getLogs().isReportHeaders()
    );
    this.logMapper = new LogToLogRecordMapper(
      cfg.getLogs().isReportPayloads(),
      cfg.getLogs().isReportHeaders()
    );
    this.endpointMapper = new EndpointStatusToLogRecordMapper();
    this.messageMapper = new MessageMetricsToLogRecordMapper();
  }

  @Override
  protected void doStart() throws Exception {
    log.info(
      "OtelLogsReporter.doStart() — reporter.enabled={}, logs.enabled={}, traces.enabled={}",
      cfg.isEnabled(),
      cfg.getLogs().isEnabled(),
      cfg.getTraces().isEnabled()
    );
    if (!cfg.isEnabled()) return;
    super.doStart();

    if (cfg.getLogs().isEnabled() && this.writer == null) {
      LogRecordExporter exporter = buildLogExporter();
      this.writer = new OtelLogWriter(
        exporter,
        cfg.getLogs().getBatchSize(),
        cfg.getLogs().getScheduledDelayMs()
      );
      // Degenerate config: logs are enabled but every per-request source is
      // suppressed. Operator probably forgot to set reportRequestLogs=true.
      if (
        !cfg.getLogs().isReportRequestSummary() &&
        !cfg.getLogs().isReportRequestLogs()
      ) {
        log.warn(
          "logs.enabled=true but both reportRequestSummary=false and reportRequestLogs=false — no per-request log records will be emitted"
        );
      }
    }

    if (cfg.getTraces().isEnabled() && this.traceWriter == null) {
      var env = cfg.getResource().isGcpAutoDetect()
        ? GcpEnvironment.detect()
        : GcpEnvironment.empty();
      var otelResource = GcpOtelResource.build(cfg.getResource(), env);
      var spanExporter = buildSpanExporter();
      this.traceWriter = new OtelTraceWriter(
        spanExporter,
        cfg.getTraces(),
        otelResource
      );
      this.spanMapper = new MetricsToSpanMapper(
        traceWriter.tracer(),
        new TraceContextResolver(cfg.getCorrelationHeader())
      );
      log.info(
        "OTel traces pipeline started — endpoint={} authMode={}",
        cfg.getTraces().getEndpoint(),
        cfg.getTraces().getAuthMode()
      );
    }
  }

  private LogRecordExporter buildLogExporter() {
    String exporter = cfg.getLogs().getExporter();
    if ("none".equalsIgnoreCase(exporter)) {
      throw new IllegalStateException(
        "logs.exporter=none but logs.enabled=true — disable logs or pick gcloud|otlp"
      );
    }
    if ("gcloud".equalsIgnoreCase(exporter)) {
      String projectId = cfg.getResource().getGcpProjectId();
      if (projectId == null || projectId.isBlank()) {
        throw new IllegalStateException(
          "reporters.otellogs.resource.gcp.projectId is required when logs.exporter=gcloud"
        );
      }
      return new GclLogRecordExporter(projectId, cfg.getLogs().getLogName());
    }
    // Default: Custom OTLP HTTP — uses built-in HttpClient to avoid SPI issues in Gravitee
    return new CustomOtlpHttpLogRecordExporter(
      cfg.getLogs().getEndpoint(),
      buildAuthHeaders(cfg.getLogs().getAuthMode(), cfg.getLogs().getHeaders())
    );
  }

  private SpanExporter buildSpanExporter() {
    var traces = cfg.getTraces();
    return new CustomOtlpHttpSpanExporter(
      traces.getEndpoint(),
      buildAuthHeaders(traces.getAuthMode(), traces.getHeaders())
    );
  }

  private Supplier<Map<String, String>> buildAuthHeaders(
    String authMode,
    Map<String, String> staticHeaders
  ) {
    return switch (authMode) {
      case "gcp-adc" -> OtlpAuthHeaders.gcpAdc();
      case "static" -> OtlpAuthHeaders.staticHeaders(staticHeaders);
      case "none" -> OtlpAuthHeaders.none();
      default -> throw new IllegalStateException(
        "Unknown authMode '" + authMode + "' — expected none|gcp-adc|static"
      );
    };
  }

  @Override
  protected void doStop() throws Exception {
    if (writer != null) {
      writer.flush();
      writer.close();
    }
    if (traceWriter != null) {
      traceWriter.flush();
      traceWriter.close();
    }
    super.doStop();
    log.info("OTel logs reporter stopped");
  }

  @Override
  public void report(Reportable reportable) {
    if (lifecycleState() != Lifecycle.State.STARTED || !cfg.isEnabled()) return;

    log.debug("Received reportable: {}", reportable.getClass().getSimpleName());

    try {
      if (reportable instanceof Metrics metrics && spanMapper != null) {
        spanMapper.map(metrics);
      }

      if (writer == null) return;

      OtelLogRecord record = switch (reportable) {
        case Metrics m when (
          cfg.getLogs().isReportRequestSummary()
        ) -> metricsMapper.map(m);
        case Log l when cfg.getLogs().isReportRequestLogs() -> logMapper.map(l);
        case EndpointStatus es when (
          cfg.getLogs().isReportHealthChecks()
        ) -> endpointMapper.map(es);
        case MessageMetrics mm when (
          cfg.getLogs().isReportMessageMetrics()
        ) -> messageMapper.map(mm);
        default -> null;
      };

      if (record != null) {
        log.debug("Mapped reportable to OtelLogRecord, emitting...");
        writer.emit(record);
      } else {
        log.debug("Reportable was mapped to null (ignored by config)");
      }
    } catch (Exception e) {
      log.warn(
        "Unexpected error while reporting {}: {}",
        reportable.getClass().getSimpleName(),
        e.getMessage()
      );
    }
  }

  @Override
  public boolean canHandle(Reportable reportable) {
    if (!cfg.isEnabled()) return false;
    boolean logsOn = cfg.getLogs().isEnabled();
    boolean tracesOn = cfg.getTraces().isEnabled();
    return switch (reportable) {
      case Metrics ignored -> logsOn || tracesOn;
      case EndpointStatus ignored -> logsOn &&
      cfg.getLogs().isReportHealthChecks();
      case Log ignored -> logsOn && cfg.getLogs().isReportRequestLogs();
      case MessageMetrics ignored -> logsOn &&
      cfg.getLogs().isReportMessageMetrics();
      default -> false;
    };
  }
}
