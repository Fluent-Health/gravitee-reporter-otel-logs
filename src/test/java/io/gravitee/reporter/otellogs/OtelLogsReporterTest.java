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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.gravitee.node.api.monitor.Monitor;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.otellogs.config.LogsConfiguration;
import io.gravitee.reporter.otellogs.config.OtelLogsReporterConfiguration;
import io.gravitee.reporter.otellogs.config.TracesConfiguration;
import io.gravitee.reporter.otellogs.mapper.*;
import io.gravitee.reporter.otellogs.writer.OtelLogWriter;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OtelLogsReporterTest {

  @Mock
  private OtelLogsReporterConfiguration cfg;

  @Mock
  private LogsConfiguration logsCfg;

  @Mock
  private TracesConfiguration tracesCfg;

  @Mock
  private OtelLogWriter writer;

  @Mock
  private MetricsToLogRecordMapper metricsMapper;

  @Mock
  private LogToLogRecordMapper logMapper;

  @Mock
  private EndpointStatusToLogRecordMapper endpointMapper;

  @Mock
  private MessageMetricsToLogRecordMapper messageMapper;

  private OtelLogsReporter reporter;

  @BeforeEach
  void setUp() throws Exception {
    when(cfg.isEnabled()).thenReturn(true);
    when(cfg.getLogs()).thenReturn(logsCfg);
    when(cfg.getTraces()).thenReturn(tracesCfg);
    when(tracesCfg.isEnabled()).thenReturn(false);
    when(logsCfg.isEnabled()).thenReturn(true);
    when(logsCfg.isReportHealthChecks()).thenReturn(true);
    when(logsCfg.isReportRequestLogs()).thenReturn(false);
    when(logsCfg.isReportMessageMetrics()).thenReturn(true);
    when(logsCfg.isReportRequestSummary()).thenReturn(true);

    reporter = new OtelLogsReporter(cfg);
    // The constructor instantiates real mappers; we overwrite them with mocks for the test.
    inject(reporter, "writer", writer);
    inject(reporter, "metricsMapper", metricsMapper);
    inject(reporter, "logMapper", logMapper);
    inject(reporter, "endpointMapper", endpointMapper);
    inject(reporter, "messageMapper", messageMapper);

    reporter.start();
  }

  // ===== canHandle =====

  @Test
  void metricsAreAlwaysHandledWhenEnabled() {
    assertThat(reporter.canHandle(OtelTestSupport.metrics(200))).isTrue();
  }

  @Test
  void endpointStatusHandledWhenReportHealthChecksEnabled() {
    assertThat(
      reporter.canHandle(OtelTestSupport.endpointStatus(true))
    ).isTrue();
  }

  @Test
  void endpointStatusNotHandledWhenReportHealthChecksDisabled() {
    when(logsCfg.isReportHealthChecks()).thenReturn(false);
    assertThat(
      reporter.canHandle(OtelTestSupport.endpointStatus(true))
    ).isFalse();
  }

  @Test
  void logNotHandledWhenReportLogsDisabled() {
    assertThat(reporter.canHandle(OtelTestSupport.log(200))).isFalse();
  }

  @Test
  void logHandledWhenReportLogsEnabled() {
    when(logsCfg.isReportRequestLogs()).thenReturn(true);
    assertThat(reporter.canHandle(OtelTestSupport.log(200))).isTrue();
  }

  @Test
  void messageMetricsHandledWhenEnabled() {
    assertThat(reporter.canHandle(OtelTestSupport.messageMetrics())).isTrue();
  }

  @Test
  void messageMetricsNotHandledWhenDisabled() {
    when(logsCfg.isReportMessageMetrics()).thenReturn(false);
    assertThat(reporter.canHandle(OtelTestSupport.messageMetrics())).isFalse();
  }

  @Test
  void monitorIsNeverHandled() {
    assertThat(reporter.canHandle(mock(Monitor.class))).isFalse();
  }

  @Test
  void disabledReporterHandlesNothing() {
    when(cfg.isEnabled()).thenReturn(false);
    assertThat(reporter.canHandle(OtelTestSupport.metrics(200))).isFalse();
    assertThat(
      reporter.canHandle(OtelTestSupport.endpointStatus(true))
    ).isFalse();
  }

  // ===== report =====

  @Test
  void metricsAreMappedAndEmitted() {
    var m = OtelTestSupport.metrics(200);
    var record = new OtelLogRecord(
      null,
      null,
      null,
      null,
      io.opentelemetry.api.logs.Severity.INFO,
      0L,
      "body",
      io.opentelemetry.api.common.Attributes.empty()
    );
    when(metricsMapper.map(m)).thenReturn(record);
    reporter.report(m);
    verify(writer).emit(record);
  }

  @Test
  void endpointStatusIsMappedAndEmitted() {
    var es = OtelTestSupport.endpointStatus(false);
    var record = new OtelLogRecord(
      null,
      null,
      null,
      null,
      io.opentelemetry.api.logs.Severity.ERROR,
      0L,
      "body",
      io.opentelemetry.api.common.Attributes.empty()
    );
    when(endpointMapper.map(es)).thenReturn(record);
    reporter.report(es);
    verify(writer).emit(record);
  }

  @Test
  void logNotEmittedWhenReportLogsDisabled() {
    reporter.report(OtelTestSupport.log(200));
    verify(logMapper, never()).map(any());
    verify(writer, never()).emit(any());
  }

  @Test
  void logIsMappedAndEmittedWhenReportLogsEnabled() {
    when(logsCfg.isReportRequestLogs()).thenReturn(true);
    var l = OtelTestSupport.log(200);
    var record = new OtelLogRecord(
      null,
      null,
      null,
      null,
      io.opentelemetry.api.logs.Severity.INFO,
      0L,
      "body",
      io.opentelemetry.api.common.Attributes.empty()
    );
    when(logMapper.map(l)).thenReturn(record);
    reporter.report(l);
    verify(writer).emit(record);
  }

  @Test
  void metricsRecordSuppressedWhenReportRequestSummaryDisabled() {
    when(logsCfg.isReportRequestSummary()).thenReturn(false);
    reporter.report(OtelTestSupport.metrics(200));
    verify(metricsMapper, never()).map(any());
    verify(writer, never()).emit(any());
  }

  @Test
  void onlyLogRecordEmittedWhenSummarySuppressedAndRequestLogsEnabled() {
    // Combined config: detailed Log-derived record only.
    when(logsCfg.isReportRequestSummary()).thenReturn(false);
    when(logsCfg.isReportRequestLogs()).thenReturn(true);

    var l = OtelTestSupport.log(200);
    var record = new OtelLogRecord(
      null,
      null,
      null,
      null,
      io.opentelemetry.api.logs.Severity.INFO,
      0L,
      "body",
      io.opentelemetry.api.common.Attributes.empty()
    );
    when(logMapper.map(l)).thenReturn(record);

    // Metrics event: suppressed
    reporter.report(OtelTestSupport.metrics(200));
    verify(metricsMapper, never()).map(any());
    // Log event: emitted
    reporter.report(l);
    verify(writer).emit(record);
  }

  @Test
  void messageMetricsAreMappedAndEmitted() {
    var mm = OtelTestSupport.messageMetrics();
    var record = new OtelLogRecord(
      null,
      null,
      null,
      null,
      io.opentelemetry.api.logs.Severity.INFO,
      0L,
      "body",
      io.opentelemetry.api.common.Attributes.empty()
    );
    when(messageMapper.map(mm)).thenReturn(record);
    reporter.report(mm);
    verify(writer).emit(record);
  }

  @Test
  void doStopCallsFlushAndClose() throws Exception {
    reporter.doStop();
    verify(writer).flush();
    verify(writer).close();
  }

  @Test
  void disabledReporterDoesNotEmit() {
    when(cfg.isEnabled()).thenReturn(false);
    reporter.report(OtelTestSupport.metrics(200));
    verify(writer, never()).emit(any());
  }

  // ===== helpers =====

  private static void inject(Object target, String fieldName, Object value)
    throws Exception {
    Field f = findField(target.getClass(), fieldName);
    f.setAccessible(true);
    f.set(target, value);
  }

  private static Field findField(Class<?> clazz, String name)
    throws NoSuchFieldException {
    try {
      return clazz.getDeclaredField(name);
    } catch (NoSuchFieldException e) {
      if (clazz.getSuperclass() != null) return findField(
        clazz.getSuperclass(),
        name
      );
      throw e;
    }
  }
}
