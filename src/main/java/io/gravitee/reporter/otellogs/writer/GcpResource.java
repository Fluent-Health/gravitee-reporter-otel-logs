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

import java.util.LinkedHashMap;
import java.util.Map;
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
   * <p>Bounded by a 2-second budget so plugin startup never blocks on a stuck
   * metadata-server call.
   */
  public static GcpResource detect(String projectId) {
    return fromEnvironment(projectId, GcpEnvironment.detect());
  }

  static GcpResource fromEnvironment(String projectId, GcpEnvironment env) {
    if (!env.isOnGke()) {
      log.debug("GCP environment incomplete, using 'global'");
      return global();
    }
    var labels = new LinkedHashMap<String, String>();
    labels.put("project_id", projectId);
    labels.put("location", env.location());
    labels.put("cluster_name", env.cluster());
    labels.put("namespace_name", env.k8sNamespace());
    labels.put("pod_name", env.podName());
    if (env.isK8sContainer()) {
      labels.put("container_name", env.containerName());
      return new GcpResource("k8s_container", labels);
    }
    return new GcpResource("k8s_pod", labels);
  }
}
