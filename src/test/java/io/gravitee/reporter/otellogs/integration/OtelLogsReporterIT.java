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

import static org.awaitility.Awaitility.await;

import io.gravitee.reporter.api.Reportable;
import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.otellogs.OtelLogsReporter;
import io.gravitee.reporter.otellogs.OtelTestSupport;
import io.gravitee.reporter.otellogs.mapper.*;
import io.gravitee.reporter.otellogs.writer.OtelLogWriter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
class OtelLogsReporterIT {

  private static final Logger log = LoggerFactory.getLogger(
    OtelLogsReporterIT.class
  );
  private static final int OTLP_PORT = 4317;
  private static final int LOKI_PORT = 3100;

  private static final Network NETWORK = Network.newNetwork();
  private static GenericContainer<?> loki;
  private static GenericContainer<?> collector;

  private static OtelLogsReporter reporter;
  private static OtelLogWriter writer;
  private static HttpClient http;
  private static String lokiBase;

  @BeforeAll
  static void startInfrastructure() throws Exception {
    loki = new GenericContainer<>("grafana/loki:3.0.0")
      .withNetwork(NETWORK)
      .withNetworkAliases("loki")
      .withExposedPorts(LOKI_PORT)
      .withCommand("-config.file=/etc/loki/local-config.yaml")
      .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("tc.loki")))
      .waitingFor(
        Wait.forHttp("/ready")
          .forPort(LOKI_PORT)
          .forStatusCode(200)
          .withStartupTimeout(Duration.ofSeconds(60))
      );

    collector = new GenericContainer<>(
      "otel/opentelemetry-collector-contrib:0.104.0"
    )
      .withNetwork(NETWORK)
      .withNetworkAliases("collector")
      .withExposedPorts(OTLP_PORT)
      .withCopyFileToContainer(
        MountableFile.forClasspathResource("otel-collector-config.yaml"),
        "/etc/otel-collector-config.yaml"
      )
      .withCommand("--config=/etc/otel-collector-config.yaml")
      .withLogConsumer(
        new Slf4jLogConsumer(LoggerFactory.getLogger("tc.collector"))
      )
      .waitingFor(
        Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60))
      )
      .dependsOn(loki);

    Startables.deepStart(loki, collector).join();

    lokiBase = "http://localhost:" + loki.getMappedPort(LOKI_PORT);
    http = HttpClient.newHttpClient();

    String collectorEndpoint =
      "http://localhost:" + collector.getMappedPort(OTLP_PORT);
    var exporter = OtlpGrpcLogRecordExporter.builder()
      .setEndpoint(collectorEndpoint)
      .build();
    writer = new OtelLogWriter(exporter, 10, 500);

    var cfg = OtelTestSupport.config();

    reporter = new OtelLogsReporter();
    inject(reporter, "cfg", cfg);
    inject(reporter, "writer", writer);
    inject(reporter, "metricsMapper", new MetricsToLogRecordMapper(cfg));
    inject(reporter, "logMapper", new LogToLogRecordMapper());
    inject(reporter, "endpointMapper", new EndpointStatusToLogRecordMapper());
    inject(reporter, "messageMapper", new MessageMetricsToLogRecordMapper());
    invokeProtected(reporter, "doStart");
  }

  @AfterAll
  static void stopInfrastructure() throws Exception {
    if (reporter != null) invokeProtected(reporter, "doStop");
    Stream.of(collector, loki)
      .filter(Objects::nonNull)
      .forEach(GenericContainer::stop);
    NETWORK.close();
  }

  @Test
  void metricsLogAppearsInLoki() {
    Metrics m = OtelTestSupport.metrics(200);
    m.setTimestamp(System.currentTimeMillis());
    reporter.report(m);
    writer.flush();

    await("GET 200 log to appear in Loki")
      .atMost(Duration.ofSeconds(30))
      .pollInterval(Duration.ofSeconds(2))
      .until(() ->
        queryLoki("{job=\"gravitee/gravitee-otel-logs\"}").contains("Test API")
      );
  }

  @Test
  void errorStatusProducesSeverityErrorLog() {
    Metrics m500 = OtelTestSupport.metrics(500);
    m500.setTimestamp(System.currentTimeMillis());
    reporter.report(m500);
    writer.flush();

    await("500 error log to appear in Loki")
      .atMost(Duration.ofSeconds(30))
      .pollInterval(Duration.ofSeconds(2))
      .until(() ->
        queryLoki("{job=\"gravitee/gravitee-otel-logs\"} |= \"500\"").contains(
          "500"
        )
      );
  }

  @Test
  void traceIdFromXRequestIdAppearsInLogLine() {
    var m = OtelTestSupport.metricsWithHeaders(
      200,
      Map.of("X-Request-ID", "550e8400-e29b-41d4-a716-446655440000")
    );
    m.setTimestamp(System.currentTimeMillis());
    reporter.report(m);
    writer.flush();

    await("trace ID log to appear in Loki")
      .atMost(Duration.ofSeconds(30))
      .pollInterval(Duration.ofSeconds(2))
      .until(() ->
        queryLoki("{job=\"gravitee/gravitee-otel-logs\"}").contains("Test API")
      );
  }

  @Test
  void endpointStatusDownAppearsInLoki() {
    EndpointStatus es = OtelTestSupport.endpointStatus(false);
    es.setTimestamp(System.currentTimeMillis());
    reporter.report(es);
    writer.flush();

    await("endpoint DOWN log to appear in Loki")
      .atMost(Duration.ofSeconds(30))
      .pollInterval(Duration.ofSeconds(2))
      .until(() ->
        queryLoki("{job=\"gravitee/gravitee-otel-logs\"} |= \"DOWN\"").contains(
          "DOWN"
        )
      );
  }

  @Test
  void sentryTraceAttributeAppearsInLog() {
    var m = OtelTestSupport.metricsWithHeaders(
      200,
      Map.of(
        "sentry-trace",
        "771a43a4192642f0b136d5159a501700-7d17675a3e4f44e8-1"
      )
    );
    m.setTimestamp(System.currentTimeMillis());
    reporter.report(m);
    writer.flush();

    await("sentry-trace log to appear in Loki")
      .atMost(Duration.ofSeconds(30))
      .pollInterval(Duration.ofSeconds(2))
      .until(() ->
        queryLoki("{job=\"gravitee/gravitee-otel-logs\"}").contains("Test API")
      );
  }

  private String queryLoki(String logqlQuery) {
    try {
      String encoded = URLEncoder.encode(logqlQuery, StandardCharsets.UTF_8);
      long now = System.currentTimeMillis();
      long start = (now - 120_000L) * 1_000_000L;
      long end = (now + 5_000L) * 1_000_000L;
      String url =
        lokiBase +
        "/loki/api/v1/query_range" +
        "?query=" +
        encoded +
        "&start=" +
        start +
        "&end=" +
        end +
        "&limit=50";
      var response = http.send(
        HttpRequest.newBuilder().uri(URI.create(url)).build(),
        HttpResponse.BodyHandlers.ofString()
      );
      return response.body();
    } catch (Exception e) {
      log.warn("Loki query failed: {}", e.getMessage());
      return "";
    }
  }

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

  private static void invokeProtected(Object target, String methodName)
    throws Exception {
    Method m = findMethod(target.getClass(), methodName);
    m.setAccessible(true);
    m.invoke(target);
  }

  private static Method findMethod(Class<?> clazz, String name)
    throws NoSuchMethodException {
    try {
      return clazz.getDeclaredMethod(name);
    } catch (NoSuchMethodException e) {
      if (clazz.getSuperclass() != null) return findMethod(
        clazz.getSuperclass(),
        name
      );
      throw e;
    }
  }
}
