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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gravitee.reporter.otellogs.config.TracesConfiguration;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.Test;

class OtelTraceWriterTest {

  @Test
  void emits_span_through_processor_to_exporter() {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    TracesConfiguration tc = new TracesConfiguration();
    tc.setBatchSize(1);
    tc.setScheduledDelayMs(50);
    tc.setSampler("always-on");
    OtelTraceWriter w = new OtelTraceWriter(exporter, tc, Resource.empty());

    w
      .tracer()
      .spanBuilder("test")
      .setSpanKind(SpanKind.SERVER)
      .startSpan()
      .end();
    w.flush();

    assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    w.close();
  }

  @Test
  void invalid_batch_size_throws() {
    TracesConfiguration tc = new TracesConfiguration();
    tc.setBatchSize(0);
    tc.setScheduledDelayMs(1);
    tc.setSampler("always-on");
    assertThatThrownBy(() ->
      new OtelTraceWriter(InMemorySpanExporter.create(), tc, Resource.empty())
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("batchSize");
  }

  @Test
  void unknown_sampler_throws() {
    TracesConfiguration tc = new TracesConfiguration();
    tc.setBatchSize(1);
    tc.setScheduledDelayMs(1);
    tc.setSampler("not-a-sampler");
    assertThatThrownBy(() ->
      new OtelTraceWriter(InMemorySpanExporter.create(), tc, Resource.empty())
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("sampler");
  }
}
