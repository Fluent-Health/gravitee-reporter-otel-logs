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

class GcpEnvironmentTest {

  @Test
  void empty_when_not_on_gcp() {
    GcpEnvironment env = GcpEnvironment.empty();
    assertThat(env.isOnGke()).isFalse();
    assertThat(env.k8sNamespace()).isNull();
    assertThat(env.cluster()).isNull();
    assertThat(env.location()).isNull();
    assertThat(env.podName()).isNull();
    assertThat(env.containerName()).isNull();
  }

  @Test
  void k8s_container_when_all_fields_present() {
    GcpEnvironment env = new GcpEnvironment(
      "default",
      "pod-1",
      "container-a",
      "us-c1",
      "demo-cluster"
    );
    assertThat(env.isOnGke()).isTrue();
    assertThat(env.isK8sContainer()).isTrue();
  }

  @Test
  void k8s_pod_when_container_missing() {
    GcpEnvironment env = new GcpEnvironment(
      "default",
      "pod-1",
      null,
      "us-c1",
      "demo-cluster"
    );
    assertThat(env.isOnGke()).isTrue();
    assertThat(env.isK8sContainer()).isFalse();
  }
}
