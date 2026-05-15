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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GcpResourceTest {

  @Test
  void globalIsTypeGlobalWithEmptyLabels() {
    var r = GcpResource.global();
    assertThat(r.type()).isEqualTo("global");
    assertThat(r.labels()).isEmpty();
  }

  @Test
  void fromEnvironment_returnsGlobal_whenEnvIsEmpty() {
    var r = GcpResource.fromEnvironment("my-project", GcpEnvironment.empty());
    assertThat(r.type()).isEqualTo("global");
    assertThat(r.labels()).isEmpty();
  }

  @Test
  void fromEnvironment_returnsK8sContainer_whenContainerPresent() {
    var env = new GcpEnvironment(
      "default",
      "pod-abc",
      "my-container",
      "us-central1",
      "my-cluster"
    );
    var r = GcpResource.fromEnvironment("my-project", env);
    assertThat(r.type()).isEqualTo("k8s_container");
    assertThat(r.labels())
      .containsEntry("project_id", "my-project")
      .containsEntry("location", "us-central1")
      .containsEntry("cluster_name", "my-cluster")
      .containsEntry("namespace_name", "default")
      .containsEntry("pod_name", "pod-abc")
      .containsEntry("container_name", "my-container");
  }

  @Test
  void fromEnvironment_returnsK8sPod_whenContainerMissing() {
    var env = new GcpEnvironment(
      "default",
      "pod-abc",
      null,
      "us-central1",
      "my-cluster"
    );
    var r = GcpResource.fromEnvironment("my-project", env);
    assertThat(r.type()).isEqualTo("k8s_pod");
    assertThat(r.labels())
      .containsEntry("project_id", "my-project")
      .containsEntry("pod_name", "pod-abc")
      .doesNotContainKey("container_name");
  }
}
