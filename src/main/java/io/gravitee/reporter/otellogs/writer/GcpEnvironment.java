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
package io.gravitee.reporter.otellogs.writer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detected GCP / GKE environment for a single JVM. Shared by GcpResource (GCL
 * MonitoredResource shape) and GcpOtelResource (OTel Resource shape).
 */
public record GcpEnvironment(
  String k8sNamespace,
  String podName,
  String containerName,
  String location,
  String cluster
) {
  private static final Logger log = LoggerFactory.getLogger(
    GcpEnvironment.class
  );

  private static final String METADATA_URL =
    "http://metadata.google.internal/computeMetadata/v1/";
  private static final Path K8S_NAMESPACE_FILE = Path.of(
    "/var/run/secrets/kubernetes.io/serviceaccount/namespace"
  );
  private static final Duration METADATA_TIMEOUT = Duration.ofMillis(500);
  private static final Duration DETECTION_BUDGET = Duration.ofSeconds(2);

  public static GcpEnvironment empty() {
    return new GcpEnvironment(null, null, null, null, null);
  }

  public boolean isOnGke() {
    return (
      k8sNamespace != null &&
      podName != null &&
      cluster != null &&
      location != null
    );
  }

  public boolean isK8sContainer() {
    return isOnGke() && containerName != null && !containerName.isBlank();
  }

  /** Probes the GCE metadata server + Kubernetes downward API, bounded by a 2s budget. */
  public static GcpEnvironment detect() {
    try {
      return CompletableFuture.supplyAsync(GcpEnvironment::detectInternal).get(
        DETECTION_BUDGET.toMillis(),
        TimeUnit.MILLISECONDS
      );
    } catch (Exception e) {
      log.debug(
        "GCP environment detection exceeded budget; treating as off-GCP"
      );
      return empty();
    }
  }

  private static GcpEnvironment detectInternal() {
    String namespace = readK8sNamespace();
    String hostname = System.getenv("HOSTNAME");
    String cluster = fetchMetadata("instance/attributes/cluster-name");
    String location = fetchMetadata("instance/attributes/cluster-location");
    String container = System.getenv("K8S_CONTAINER_NAME");
    return new GcpEnvironment(
      namespace,
      hostname,
      container,
      location,
      cluster
    );
  }

  private static String readK8sNamespace() {
    try {
      if (!Files.exists(K8S_NAMESPACE_FILE)) return null;
      String value = Files.readString(K8S_NAMESPACE_FILE).trim();
      return value.isEmpty() ? null : value;
    } catch (IOException e) {
      return null;
    }
  }

  private static String fetchMetadata(String path) {
    HttpClient http = HttpClient.newBuilder()
      .connectTimeout(METADATA_TIMEOUT)
      .build();
    try {
      var req = HttpRequest.newBuilder()
        .uri(URI.create(METADATA_URL + path))
        .header("Metadata-Flavor", "Google")
        .timeout(METADATA_TIMEOUT)
        .GET()
        .build();
      var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() == 200) {
        String body = resp.body().trim();
        return body.isEmpty() ? null : body;
      }
    } catch (Exception e) {
      // expected when running outside GCE
    }
    return null;
  }
}
