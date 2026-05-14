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

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GcpResourceTest {

  @Test
  void globalSerializesWithoutLabels() {
    assertThat(GcpResource.global().toJson()).isEqualTo(
      "{\"type\":\"global\"}"
    );
  }

  @Test
  void k8sPodSerializesAllLabels() {
    var labels = new LinkedHashMap<String, String>();
    labels.put("project_id", "fh-dev-svc");
    labels.put("location", "us-central1-a");
    labels.put("cluster_name", "gravitee-cluster");
    labels.put("namespace_name", "gravitee");
    labels.put("pod_name", "gateway-7f9b8d6c4-x2k9p");

    var resource = new GcpResource("k8s_pod", labels);

    assertThat(resource.toJson()).isEqualTo(
      "{\"type\":\"k8s_pod\",\"labels\":{" +
        "\"project_id\":\"fh-dev-svc\"," +
        "\"location\":\"us-central1-a\"," +
        "\"cluster_name\":\"gravitee-cluster\"," +
        "\"namespace_name\":\"gravitee\"," +
        "\"pod_name\":\"gateway-7f9b8d6c4-x2k9p\"" +
        "}}"
    );
  }

  @Test
  void escapesQuotesAndBackslashesInLabels() {
    var resource = new GcpResource(
      "k8s_pod",
      Map.of("pod_name", "weird\"name\\with\\specials")
    );
    assertThat(resource.toJson()).contains(
      "\"pod_name\":\"weird\\\"name\\\\with\\\\specials\""
    );
  }
}
