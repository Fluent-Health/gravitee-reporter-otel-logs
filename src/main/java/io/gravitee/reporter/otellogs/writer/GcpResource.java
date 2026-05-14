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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cloud Logging MonitoredResource — identifies "where" a log entry came from so
 * the GCL UI can render cluster/pod/namespace badges, link entries to the GKE
 * workload page, and apply namespace-scoped retention.
 *
 * <p>{@link #detect} probes the GCE metadata server and Kubernetes service-account
 * files at plugin init. When the gateway runs on GKE the detection lights up
 * automatically; when it runs anywhere else, {@link #global} is the safe fallback.
 */
public record GcpResource(String type, Map<String, String> labels) {
  private static final Logger log = LoggerFactory.getLogger(GcpResource.class);

  private static final String METADATA_URL =
    "http://metadata.google.internal/computeMetadata/v1/";
  private static final Path K8S_NAMESPACE_FILE = Path.of(
    "/var/run/secrets/kubernetes.io/serviceaccount/namespace"
  );
  private static final Duration METADATA_TIMEOUT = Duration.ofMillis(500);
  private static final Duration DETECTION_BUDGET = Duration.ofSeconds(2);

  public static GcpResource global() {
    return new GcpResource("global", Map.of());
  }

  /**
   * Detects the most specific MonitoredResource visible to this process:
   *
   * <ul>
   *   <li>{@code k8s_container} when the pod spec exposes {@code K8S_CONTAINER_NAME}
   *   <li>{@code k8s_pod} when cluster/namespace/pod metadata is reachable on GKE
   *   <li>{@code global} otherwise (running outside GKE, metadata server unreachable, etc.)
   * </ul>
   *
   * <p>Bounded by a {@value #DETECTION_BUDGET}-second budget so plugin startup
   * never blocks on a stuck metadata-server call.
   */
  public static GcpResource detect(String projectId) {
    try {
      return CompletableFuture.supplyAsync(() -> detectInternal(projectId)).get(
        DETECTION_BUDGET.toMillis(),
        TimeUnit.MILLISECONDS
      );
    } catch (Exception e) {
      log.debug("GCP resource detection exceeded budget, using 'global'");
      return global();
    }
  }

  private static GcpResource detectInternal(String projectId) {
    String namespace = readK8sNamespace();
    String hostname = System.getenv("HOSTNAME");
    String cluster = fetchMetadata("instance/attributes/cluster-name");
    String location = fetchMetadata("instance/attributes/cluster-location");

    if (
      namespace == null ||
      hostname == null ||
      cluster == null ||
      location == null
    ) {
      log.debug(
        "GCP resource detection incomplete (namespace={}, hostname={}, cluster={}, location={}), using 'global'",
        namespace,
        hostname,
        cluster,
        location
      );
      return global();
    }

    var labels = new LinkedHashMap<String, String>();
    labels.put("project_id", projectId);
    labels.put("location", location);
    labels.put("cluster_name", cluster);
    labels.put("namespace_name", namespace);
    labels.put("pod_name", hostname);

    String containerName = System.getenv("K8S_CONTAINER_NAME");
    if (containerName != null && !containerName.isBlank()) {
      labels.put("container_name", containerName);
      return new GcpResource("k8s_container", labels);
    }
    return new GcpResource("k8s_pod", labels);
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
    // Dedicated short-timeout client so a stalled connect off-GCP can't block startup.
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
