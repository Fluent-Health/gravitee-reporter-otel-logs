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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.MountableFile;

/**
 * End-to-end test that stands up the full Gravitee APIM stack (Gateway, Management
 * API, MongoDB, mock backend) with the OTel Logs reporter plugin installed and an
 * OTel Collector receiving OTLP HTTP.
 *
 * <p>The collector is configured with the {@code debug} exporter at {@code
 * verbosity: detailed} (see {@code otel-collector-config.yaml}). The test asserts
 * on the collector's stdout, which captures every received log record. No storage
 * backend (Loki, Tempo, GCL) is required — the test only verifies that the gateway
 * plugin produces and ships OTLP correctly.
 */
@Tag("integration")
@Tag("e2e")
class OtelLogsReporterIT {

  private static final Logger log = LoggerFactory.getLogger(
    OtelLogsReporterIT.class
  );
  private static final int OTLP_PORT = 4318;

  private static final Network NETWORK = Network.newNetwork();
  private static final String MONGO_URI =
    "mongodb://mongodb:27017/gravitee" +
    "?serverSelectionTimeoutMS=5000&connectTimeoutMS=5000&socketTimeoutMS=5000";

  private static MongoDBContainer mongodb;
  private static GenericContainer<?> managementApi;
  private static GenericContainer<?> gateway;
  private static GenericContainer<?> httpbin;
  private static GenericContainer<?> collector;

  /** Captures the collector's stdout so the test can assert on emitted log records. */
  private static final StringBuffer collectorOut = new StringBuffer();

  private static HttpClient http;
  private static String gatewayBase;
  private static ManagementApiHelper mgmtHelper;

  @BeforeAll
  static void startInfrastructure() throws Exception {
    String pluginVersion = System.getProperty(
      "project.version",
      "1.0.0-SNAPSHOT"
    );
    Path pluginZip = Paths.get(
      "target/gravitee-reporter-otellogs-" + pluginVersion + ".zip"
    );
    assertThat(pluginZip)
      .as(
        "Plugin ZIP not found at %s — run 'mvn package -DskipTests' first",
        pluginZip.toAbsolutePath()
      )
      .exists();

    // 1. OTel Collector — sink for the gateway plugin's OTLP HTTP export.
    //    The `debug` exporter writes received log records to stdout, which we
    //    capture below for assertions.
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
      .withLogConsumer(frame -> collectorOut.append(frame.getUtf8String()))
      .waitingFor(Wait.forLogMessage(".*Everything is ready.*", 1));

    // 2. MongoDB — backing store for the Management API.
    mongodb = new MongoDBContainer("mongo:7.0")
      .withNetwork(NETWORK)
      .withNetworkAliases("mongodb");

    // 3. Management API.
    managementApi = new GenericContainer<>(
      "graviteeio/apim-management-api:4.9.13"
    )
      .withNetwork(NETWORK)
      .withNetworkAliases("management-api")
      .withExposedPorts(8083, 18083)
      .withEnv("gravitee_management_mongodb_uri", MONGO_URI)
      .withEnv("gravitee_reporters_elasticsearch_enabled", "false")
      .withEnv("gravitee_analytics_type", "none")
      .withEnv("gravitee_services_core_http_enabled", "true")
      .withEnv("gravitee_services_core_http_port", "18083")
      .withEnv("gravitee_services_core_http_host", "0.0.0.0")
      .withEnv("gravitee_services_core_http_authentication_type", "none")
      .dependsOn(mongodb)
      .withLogConsumer(
        new Slf4jLogConsumer(LoggerFactory.getLogger("tc.management-api"))
      )
      .waitingFor(
        Wait.forHttp("/_node/health").forPort(18083).forStatusCode(200)
      );

    // 4. Mock backend the gateway proxies to.
    httpbin = new GenericContainer<>("mccutchen/go-httpbin")
      .withNetwork(NETWORK)
      .withNetworkAliases("httpbin")
      .withExposedPorts(8080)
      .withLogConsumer(
        new Slf4jLogConsumer(LoggerFactory.getLogger("tc.httpbin"))
      )
      .waitingFor(Wait.forHttp("/get").forPort(8080).forStatusCode(200));

    // 5. Gateway with the plugin installed and pointed at the collector.
    gateway = new GenericContainer<>("graviteeio/apim-gateway:4.9.13")
      .withCreateContainerCmdModifier(cmd -> cmd.withUser("root"))
      .withNetwork(NETWORK)
      .withNetworkAliases("gateway")
      .withExposedPorts(8082, 18082)
      .withCopyFileToContainer(
        MountableFile.forHostPath(pluginZip.toAbsolutePath().toString()),
        "/opt/graviteeio-gateway/plugins-ext/gravitee-reporter-otellogs.zip"
      )
      .withEnv("gravitee_management_mongodb_uri", MONGO_URI)
      .withEnv("gravitee_ratelimit_mongodb_uri", MONGO_URI)
      .withEnv("gravitee_reporters_elasticsearch_enabled", "false")
      .withEnv("gravitee_plugins_path_0", "/opt/graviteeio-gateway/plugins")
      .withEnv("gravitee_plugins_path_1", "/opt/graviteeio-gateway/plugins-ext")
      .withEnv("gravitee_services_core_http_enabled", "true")
      .withEnv("gravitee_services_core_http_port", "18082")
      .withEnv("gravitee_services_core_http_host", "0.0.0.0")
      .withEnv("gravitee_services_core_http_authentication_type", "none")
      .withEnv("gravitee_services_bridge_http_enabled", "false")
      .withEnv("gravitee_services_healthcheck_enabled", "false")
      // Reporter config
      .withEnv("gravitee_reporters_otellogs_enabled", "true")
      .withEnv("gravitee_reporters_otellogs_reportLogs", "true")
      .withEnv("gravitee_reporters_otellogs_exporter", "otlp")
      .withEnv("gravitee_reporters_otellogs_endpoint", "http://collector:4318")
      .withEnv("gravitee_reporters_otellogs_batchSize", "1")
      .withEnv("gravitee_reporters_otellogs_scheduledDelayMs", "100")
      .dependsOn(managementApi, collector)
      .withLogConsumer(
        new Slf4jLogConsumer(LoggerFactory.getLogger("tc.gateway"))
      )
      .waitingFor(
        Wait.forHttp("/_node/health").forPort(18082).forStatusCode(200)
      );

    Startables.deepStart(gateway, httpbin).join();

    gatewayBase = "http://localhost:" + gateway.getMappedPort(8082);
    String mgmtBase = "http://localhost:" + managementApi.getMappedPort(8083);
    http = HttpClient.newHttpClient();
    mgmtHelper = new ManagementApiHelper(mgmtBase);

    // Create and deploy an API on the gateway.
    mgmtHelper.createAndDeployApi(
      "OTel E2E Test",
      "/otel-e2e",
      "http://httpbin:8080/get"
    );

    // Wait for the gateway to sync the deployed API.
    await("gateway to serve API")
      .atMost(Duration.ofSeconds(90))
      .pollInterval(Duration.ofSeconds(3))
      .until(
        () ->
          http
            .send(
              HttpRequest.newBuilder()
                .uri(URI.create(gatewayBase + "/otel-e2e"))
                .build(),
              HttpResponse.BodyHandlers.discarding()
            )
            .statusCode() !=
          404
      );
  }

  @AfterAll
  static void stopInfrastructure() {
    Stream.of(gateway, httpbin, managementApi, mongodb, collector)
      .filter(Objects::nonNull)
      .forEach(GenericContainer::stop);
    NETWORK.close();
  }

  @Test
  void requestLogAppearsInCollectorViaGateway() throws Exception {
    // Snapshot output length so we only consider lines emitted by this request.
    int before = collectorOut.length();

    var response = http.send(
      HttpRequest.newBuilder()
        .uri(URI.create(gatewayBase + "/otel-e2e"))
        .header("X-Log", "true")
        .build(),
      HttpResponse.BodyHandlers.discarding()
    );
    assertThat(response.statusCode()).isEqualTo(200);

    // The debug exporter at verbosity=detailed prints the LogRecord body, which
    // contains the request path "otel-e2e". Tail the captured stdout.
    await("log record to appear in collector output")
      .atMost(Duration.ofSeconds(30))
      .pollInterval(Duration.ofMillis(500))
      .until(() -> {
        String tail = collectorOut.substring(before);
        if (tail.contains("otel-e2e")) {
          log.info("Found expected log line in collector output");
          return true;
        }
        return false;
      });
  }
}
