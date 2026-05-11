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
import io.gravitee.reporter.otellogs.mapper.OtelLogRecord;
import io.gravitee.reporter.otellogs.writer.OtelLogWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class OtelLogsReporter
  extends AbstractService<Reporter>
  implements Reporter {

  private static final Logger log = LoggerFactory.getLogger(
    OtelLogsReporter.class
  );

  @Autowired
  private OtelLogsReporterConfiguration cfg;

  @Autowired
  private OtelLogWriter writer;

  @Autowired
  private MetricsToLogRecordMapper metricsMapper;

  @Autowired
  private LogToLogRecordMapper logMapper;

  @Autowired
  private EndpointStatusToLogRecordMapper endpointMapper;

  @Autowired
  private MessageMetricsToLogRecordMapper messageMapper;

  @Override
  protected void doStart() throws Exception {
    super.doStart();
    if (!cfg.isEnabled()) {
      log.info("OTel logs reporter is disabled");
      return;
    }
    if ("gcloud".equalsIgnoreCase(cfg.getExporter())) {
      log.info(
        "OTel logs reporter started — exporter=gcloud project={}",
        cfg.getGcloudProjectId()
      );
    } else {
      log.info(
        "OTel logs reporter started — exporter=otlp endpoint={}",
        cfg.getEndpoint()
      );
    }
  }

  @Override
  protected void doStop() throws Exception {
    writer.flush();
    writer.close();
    super.doStop();
  }

  @Override
  public boolean canHandle(Reportable reportable) {
    if (!cfg.isEnabled()) return false;
    if (reportable instanceof Metrics) return true;
    if (reportable instanceof EndpointStatus) return cfg.isReportHealthChecks();
    if (reportable instanceof Log) return cfg.isReportLogs();
    if (
      reportable instanceof MessageMetrics
    ) return cfg.isReportMessageMetrics();
    return false;
  }

  @Override
  public void report(Reportable reportable) {
    if (!cfg.isEnabled()) return;
    try {
      OtelLogRecord record = null;
      if (reportable instanceof Metrics m) {
        record = metricsMapper.map(m);
      } else if (
        reportable instanceof EndpointStatus es && cfg.isReportHealthChecks()
      ) {
        record = endpointMapper.map(es);
      } else if (reportable instanceof Log l && cfg.isReportLogs()) {
        record = logMapper.map(l);
      } else if (
        reportable instanceof MessageMetrics mm && cfg.isReportMessageMetrics()
      ) {
        record = messageMapper.map(mm);
      }
      if (record != null) {
        writer.emit(record);
      }
    } catch (Exception e) {
      log.warn("Unexpected error while reporting OTel log — skipping", e);
    }
  }
}
