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

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.reporter.otellogs.config.ResourceConfiguration;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;

class GcpOtelResourceTest {

  @Test
  void sets_service_attributes_always() {
    ResourceConfiguration rc = new ResourceConfiguration();
    rc.setServiceName("gravitee-api-gateway");
    rc.setServiceNamespace("apim");
    rc.setGcpProjectId("my-project");
    rc.setGcpAutoDetect(false);

    Resource r = GcpOtelResource.build(rc, GcpEnvironment.empty());

    assertThat(r.getAttribute(stringKey("service.name"))).isEqualTo(
      "gravitee-api-gateway"
    );
    assertThat(r.getAttribute(stringKey("service.namespace"))).isEqualTo(
      "apim"
    );
    assertThat(r.getAttribute(stringKey("gcp.project_id"))).isEqualTo(
      "my-project"
    );
  }

  @Test
  void sets_k8s_attributes_when_on_gke() {
    ResourceConfiguration rc = new ResourceConfiguration();
    rc.setServiceName("svc");
    rc.setServiceNamespace("apim");
    rc.setGcpProjectId("p");
    rc.setGcpAutoDetect(true);
    GcpEnvironment env = new GcpEnvironment(
      "ns-a",
      "pod-1",
      "container-a",
      "us-c1",
      "demo-cluster"
    );

    Resource r = GcpOtelResource.build(rc, env);

    assertThat(r.getAttribute(stringKey("k8s.namespace.name"))).isEqualTo(
      "ns-a"
    );
    assertThat(r.getAttribute(stringKey("k8s.pod.name"))).isEqualTo("pod-1");
    assertThat(r.getAttribute(stringKey("k8s.container.name"))).isEqualTo(
      "container-a"
    );
    assertThat(r.getAttribute(stringKey("cloud.region"))).isEqualTo("us-c1");
  }

  @Test
  void skips_k8s_attrs_when_autoDetect_off() {
    ResourceConfiguration rc = new ResourceConfiguration();
    rc.setServiceName("svc");
    rc.setServiceNamespace("apim");
    rc.setGcpProjectId("p");
    rc.setGcpAutoDetect(false);
    GcpEnvironment env = new GcpEnvironment(
      "ns-a",
      "pod-1",
      "container-a",
      "us-c1",
      "demo-cluster"
    );

    Resource r = GcpOtelResource.build(rc, env);

    assertThat(r.getAttribute(stringKey("k8s.namespace.name"))).isNull();
    assertThat(r.getAttribute(stringKey("k8s.pod.name"))).isNull();
  }

  @Test
  void host_name_fallback_when_off_gke() {
    ResourceConfiguration rc = new ResourceConfiguration();
    rc.setServiceName("svc");
    rc.setServiceNamespace("apim");
    rc.setGcpProjectId("p");
    rc.setGcpAutoDetect(true);

    Resource r = GcpOtelResource.build(rc, GcpEnvironment.empty());

    // host.name resolves from HOSTNAME env var or InetAddress; just assert non-null.
    assertThat(r.getAttribute(stringKey("host.name"))).isNotNull();
  }
}
