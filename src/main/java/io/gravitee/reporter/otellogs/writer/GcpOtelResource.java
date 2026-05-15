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

import io.gravitee.reporter.otellogs.config.ResourceConfiguration;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.resources.Resource;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Builds an OTel {@link Resource} from {@link ResourceConfiguration} + a detected
 * {@link GcpEnvironment}. Sets standard OTel semconv keys plus {@code gcp.project_id}
 * which Cloud Trace requires to route OTLP traces.
 */
public final class GcpOtelResource {

  private GcpOtelResource() {}

  public static Resource build(ResourceConfiguration rc, GcpEnvironment env) {
    AttributesBuilder a = Attributes.builder();
    a.put(AttributeKey.stringKey("service.name"), rc.getServiceName());
    a.put(
      AttributeKey.stringKey("service.namespace"),
      rc.getServiceNamespace()
    );
    if (rc.getGcpProjectId() != null && !rc.getGcpProjectId().isBlank()) {
      a.put(AttributeKey.stringKey("gcp.project_id"), rc.getGcpProjectId());
    }
    if (rc.isGcpAutoDetect() && env.isOnGke()) {
      a.put(AttributeKey.stringKey("k8s.namespace.name"), env.k8sNamespace());
      a.put(AttributeKey.stringKey("k8s.pod.name"), env.podName());
      if (env.isK8sContainer()) {
        a.put(
          AttributeKey.stringKey("k8s.container.name"),
          env.containerName()
        );
      }
      a.put(AttributeKey.stringKey("cloud.region"), env.location());
      a.put(AttributeKey.stringKey("k8s.cluster.name"), env.cluster());
    }
    a.put(AttributeKey.stringKey("host.name"), resolveHostName());
    return Resource.create(a.build());
  }

  private static String resolveHostName() {
    String fromEnv = System.getenv("HOSTNAME");
    if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "unknown";
    }
  }
}
