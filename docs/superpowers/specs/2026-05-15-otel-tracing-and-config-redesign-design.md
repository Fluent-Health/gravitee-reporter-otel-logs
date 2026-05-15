# OTel Tracing + Config Redesign — Design Spec

**Date:** 2026-05-15
**Project:** `gravitee-reporter-otel-logs`
**Copyright:** Fluent Health (https://fluentinhealth.com)

---

## Purpose

Add a **trace signal** to the plugin alongside the existing log signal, exported via OTLP only (no GCP-specific tracer SDK). Cloud Trace ingests OTLP directly at `telemetry.googleapis.com/v1/traces`, so a generic OTLP exporter — paired with an ADC-refreshing header supplier — is sufficient. Adding a second signal also exposes that the current flat configuration cannot cleanly describe two pipelines with potentially different destinations, auth modes, and batching knobs, so the configuration is reorganized into signal-grouped subtrees with shared resource attributes at the top level.

End-to-end goal: a request that flows Frontend → APIM → Backend produces one span in Cloud Trace (the APIM hop, parented by the frontend's `traceparent` if present) and a log entry in GCL with the same `trace_id`, so Logs Explorer and the Cloud Trace UI cross-reference cleanly.

---

## Scope

**In scope**

- New trace pipeline: one span per Gravitee `Metrics` event, built from the recorded start/end timestamps after the request completes.
- OTLP/HTTP span export with optional ADC bearer-token auth refreshed per export.
- Configuration redesign into `logs:`, `traces:`, and shared `resource:` subtrees.
- Shared GCP environment detection feeding both the existing GCL `MonitoredResource` and a new OTel `Resource`.
- Backward-compatible reads of existing flat properties for the logs pipeline, with deprecation warnings, for one release.

**Out of scope** (deliberate YAGNI)

- Live in-process spans for per-policy timings — finished spans built from `Metrics` are enough.
- gRPC OTLP transport — HTTP suffices for `telemetry.googleapis.com` and standard Collectors.
- A metrics signal — already covered by the sibling Prometheus reporter.
- Customer-supplied sampler implementations beyond `parent-based-always-on`, `always-on`, `ratio`.

---

## Configuration Shape

```yaml
reporters:
  otellogs:
    enabled: true
    correlationHeader: X-Request-ID      # shared: extracted once per request

    resource:                            # shared: applied to both signals
      serviceName: gravitee-api-gateway
      serviceNamespace: apim
      gcp:
        projectId:                       # null → auto-detect from metadata server
        autoDetect: true                 # populates k8s_container / gce_instance attrs

    logs:
      enabled: true
      exporter: gcloud                   # gcloud | otlp | none
      logName: gravitee-api-gateway      # gcloud-only
      endpoint: http://localhost:4317    # otlp-only
      authMode: none                     # none | gcp-adc | static  (otlp-only)
      headers: {}                        # otlp-only, when authMode=static
      batchSize: 512
      scheduledDelayMs: 5000
      reportHealthChecks: true
      reportRequestLogs: false           # renamed from reportLogs for clarity
      reportMessageMetrics: true

    traces:
      enabled: false                     # off by default; opt-in
      exporter: otlp                     # otlp only
      endpoint: https://telemetry.googleapis.com/v1/traces
      authMode: gcp-adc                  # none | gcp-adc | static
      headers: {}
      batchSize: 512
      scheduledDelayMs: 5000
      sampler: parent-based-always-on    # parent-based-always-on | always-on | ratio
      sampleRatio: 1.0                   # only used when sampler=ratio
```

### Rationale

- **Signal-grouped** mirrors the OTel SDK split (Logger pipeline vs. Tracer pipeline) and isolates the knobs that legitimately differ. A change to traces batching can never accidentally affect logs.
- **Shared `resource:` and `correlationHeader:`** at the top level encode the invariant that both signals describe the same process and the same request. One place to set service name, one place to set the header.
- **`authMode` enum** generalises auth. Three small values cover today's needs (none for Collector, gcp-adc for `telemetry.googleapis.com`, static for customer-managed bearer/basic) without speculative depth.
- **`exporter: none`** disables a single pipeline cleanly without touching the top-level `enabled` flag — useful for tests, dry runs, gradual rollout.
- **`logs.exporter: gcloud`** preserves the existing native GCL `entries:write` path. Traces have no equivalent native path, so `traces.exporter` accepts only `otlp`.
- **ADC-only for GCP auth.** Both the GCL native exporter and the OTLP exporter (when `authMode: gcp-adc`) authenticate via Application Default Credentials. The previous `credentialsFile` option is removed — on GKE this means Workload Identity, locally it means `gcloud auth application-default login`, in CI it means a mounted service-account key resolved by `GOOGLE_APPLICATION_CREDENTIALS`. ADC already covers all three transparently, so a dedicated config field was redundant and gave a second way to misconfigure.

### Backward compatibility

For one release, the existing flat property names are read as fallbacks into the `logs:` subtree when `logs.*` is not set:

| Legacy key | Maps to |
|---|---|
| `reporters.otellogs.exporter` | `reporters.otellogs.logs.exporter` |
| `reporters.otellogs.endpoint` | `reporters.otellogs.logs.endpoint` |
| `reporters.otellogs.batchSize` | `reporters.otellogs.logs.batchSize` |
| `reporters.otellogs.scheduledDelayMs` | `reporters.otellogs.logs.scheduledDelayMs` |
| `reporters.otellogs.reportHealthChecks` | `reporters.otellogs.logs.reportHealthChecks` |
| `reporters.otellogs.reportLogs` | `reporters.otellogs.logs.reportRequestLogs` |
| `reporters.otellogs.reportMessageMetrics` | `reporters.otellogs.logs.reportMessageMetrics` |
| `reporters.otellogs.gcloud.projectId` | `reporters.otellogs.resource.gcp.projectId` |
| `reporters.otellogs.gcloud.logName` | `reporters.otellogs.logs.logName` |

`reporters.otellogs.gcloud.credentialsFile` is removed with no replacement; if set, the bridge logs a single `WARN` at startup that the property is unsupported and ADC will be used instead.

Each legacy key in use logs a single `WARN` at startup naming the new key. Trace configuration has no legacy alias — it is only addressable under `traces:`.

---

## Architecture

### Combined pipeline

```
Gravitee APIM (JVM)
  └─ OtelLogsReporter (Reporter interface)
       ├─ mapper/MetricsToLogRecordMapper      → OtelLogWriter   → SdkLoggerProvider
       │                                                              └─ BatchLogRecordProcessor
       │                                                                   └─ LogRecordExporter (gcloud | otlp)
       │                                                                        └─ GCL or OTLP endpoint
       └─ mapper/MetricsToSpanMapper           → OtelTraceWriter → SdkTracerProvider
                                                                       └─ BatchSpanProcessor
                                                                            └─ OtlpHttpSpanExporter
                                                                                 └─ telemetry.googleapis.com/v1/traces
                                                                                      └─ Cloud Trace
```

### Span construction strategy

Gravitee `Metrics` events arrive after request completion. The mapper builds a **finished span** with explicit start and end timestamps rather than starting a live span:

```java
long endNanos   = metrics.timestamp() * 1_000_000L;
long startNanos = endNanos - metrics.apiResponseTimeMs() * 1_000_000L;

Span span = tracer.spanBuilder("gravitee.gateway.request")
    .setParent(parentContextFrom(metrics))
    .setSpanKind(SpanKind.SERVER)
    .setStartTimestamp(startNanos, TimeUnit.NANOSECONDS)
    .startSpan();

span.setAllAttributes(spanAttributes(metrics));    // OTel HTTP semconv: http.request.method, http.response.status_code, http.route, url.scheme + Gravitee-specific: gravitee.api.name, gravitee.upstream.endpoint
if (metrics.status() >= 500) {
    span.setStatus(StatusCode.ERROR, "HTTP " + metrics.status());
}
span.end(endNanos, TimeUnit.NANOSECONDS);
```

`parentContextFrom(metrics)` resolves in this order:

1. Valid W3C `traceparent` header → use it directly; the APIM span becomes a child.
2. `X-Request-ID` (or whichever `correlationHeader` is configured) → derive a deterministic 16-byte trace ID (SHA-256 of the header value, first 16 bytes); the APIM span becomes the root with this trace ID.
3. Neither present → generate a fresh trace ID; APIM span is the root.

The same resolution feeds `MetricsToLogRecordMapper` so the log entry's `trace_id` matches.

**One span per request.** The `reportMessageMetrics` toggle stays logs-only — emitting a span per stream message would explode trace storage and provide little signal.

**Attribute naming.** Span attributes follow OTel HTTP semantic conventions (`http.request.method`, `http.response.status_code`, `http.route`, `url.scheme`, `network.protocol.name`) so Cloud Trace renders the span natively. Gravitee-specific fields are namespaced (`gravitee.api.name`, `gravitee.upstream.endpoint`). This is intentionally distinct from the log-side attribute names (`http.method`, `http.status`, `http.latency_ms`), which are shaped to map directly onto GCL's `httpRequest` LogEntry field in `GclLogRecordExporter`. The two namespaces serve different rendering targets and should not be unified.

### OTLP auth helper

The OTel Java OTLP HTTP exporter accepts a `Supplier<Map<String,String>>` for headers, refreshed per export. ADC integration:

```java
public final class OtlpAuthHeaders {
    public static Supplier<Map<String,String>> gcpAdc() {
        GoogleCredentials creds;
        try {
            creds = GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load ADC for OTLP auth", e);
        }
        return () -> {
            try { creds.refreshIfExpired(); }
            catch (IOException e) { throw new UncheckedIOException(e); }
            return Map.of("Authorization", "Bearer " + creds.getAccessToken().getTokenValue());
        };
    }

    public static Supplier<Map<String,String>> staticHeaders(Map<String,String> headers) {
        Map<String,String> snapshot = Map.copyOf(headers);
        return () -> snapshot;
    }

    public static Supplier<Map<String,String>> none() {
        return Map::of;
    }
}
```

Used by both the traces OTLP exporter and (when `logs.exporter: otlp` with `authMode: gcp-adc`) the logs OTLP exporter.

### Resource attribute strategy

GCP environment detection happens once, in `GcpEnvironment.detect()`. Two adapters consume it:

- **`GcpResource`** (existing) — builds the GCL `MonitoredResource` shape (`type` + `labels`). Used only by `GclLogRecordExporter`.
- **`GcpOtelResource`** (new) — builds an OTel `io.opentelemetry.sdk.resources.Resource` with:
  - `service.name`, `service.namespace` from `resource.serviceName` / `resource.serviceNamespace`
  - `gcp.project_id` — **required** for Cloud Trace OTLP ingestion to route to the right project
  - `k8s.namespace.name`, `k8s.pod.name`, `k8s.container.name` when on GKE
  - `host.name` as a fallback when nothing else is detected

The same `Resource` is shared by the `SdkTracerProvider` and (when `logs.exporter: otlp`) the `SdkLoggerProvider`. The native GCL exporter keeps using `GcpResource` because the wire shapes differ.

### Failure isolation

- Each pipeline owns its own batch processor and exporter. A failing OTLP endpoint logs `WARN` and drops the batch; logs are unaffected and vice versa.
- ADC failure at startup throws once with a clear message naming the missing credentials. Refresh failures during export are logged at `WARN` and the batch is dropped.
- `traces.enabled: false` means no `SdkTracerProvider` is constructed — zero runtime cost.

---

## Package Structure

```
src/main/java/io/gravitee/reporter/otellogs/
├── OtelLogsReporter.java                       # wires both pipelines
├── config/
│   ├── OtelLogsReporterConfiguration.java      # top-level + delegates
│   ├── LogsConfiguration.java                  # logs.* subtree
│   ├── TracesConfiguration.java                # traces.* subtree (NEW)
│   ├── ResourceConfiguration.java              # resource.* subtree (NEW)
│   └── LegacyConfigBridge.java                 # one-release fallback reader (NEW)
├── writer/
│   ├── OtelLogWriter.java                      # constructed from LogsConfiguration
│   ├── OtelTraceWriter.java                    # NEW — analog for spans
│   ├── GclLogRecordExporter.java               # unchanged
│   ├── CustomOtlpHttpLogRecordExporter.java    # unchanged
│   ├── OtlpAuthHeaders.java                    # NEW — shared ADC supplier
│   ├── GcpEnvironment.java                     # NEW — shared detection
│   ├── GcpResource.java                        # unchanged (GCL MonitoredResource)
│   └── GcpOtelResource.java                    # NEW — OTel Resource
├── mapper/
│   ├── MetricsToLogRecordMapper.java           # extracts trace_id via shared resolver
│   ├── MetricsToSpanMapper.java                # NEW
│   ├── TraceContextResolver.java               # NEW — traceparent | header | generate
│   ├── EndpointStatusToLogRecordMapper.java    # unchanged
│   ├── MessageMetricsToLogRecordMapper.java    # unchanged
│   ├── OtelLogRecord.java                      # unchanged
│   └── OtelLabels.java                         # unchanged
└── spring/
    └── OtelLogsReporterSpringConfiguration.java
```

---

## Testing

- **Unit — `MetricsToSpanMapper`**: presence/absence of `traceparent`, malformed `traceparent`, missing latency, status 200/4xx/5xx → span status, span kind `SERVER`, attribute set.
- **Unit — `TraceContextResolver`**: traceparent precedence over correlation header; deterministic trace ID from header; fresh ID when neither present; same input ⇒ same trace ID across log and span mappers.
- **Unit — `OtlpAuthHeaders`**: ADC token refresh between calls, header shape, refresh failure surfaces as `UncheckedIOException`.
- **Unit — `GcpOtelResource`**: required attributes under GKE, GCE, and unknown environments; `gcp.project_id` always set when projectId resolved.
- **Unit — `LegacyConfigBridge`**: each legacy key maps to the right new key; explicit new-key value wins over legacy; deprecation warning logged exactly once per legacy key in use; `gcloud.credentialsFile` is ignored with a single "unsupported, ADC will be used" warning.
- **Integration — extend `OtelLogsReporterIT`**: mock OTLP receiver captures spans; assert one span per request, parent linkage from inbound `traceparent`, span's `trace_id` equals the linked log entry's `trace_id`, span attributes include HTTP method/status/route.

---

## Migration Path

1. Land the new config classes and `LegacyConfigBridge` in one PR; behavior unchanged because traces default off and logs read either shape.
2. Land the trace pipeline (writer, mapper, resolver, OTel resource) in a follow-up PR; traces still default off.
3. Update deployment values to set `traces.enabled: true` and `traces.endpoint: https://telemetry.googleapis.com/v1/traces` per environment.
4. After one release cycle of deprecation warnings, remove `LegacyConfigBridge`.

---

## Open Questions

None blocking. Deferred:

- Whether to emit span events for per-policy timings within a request — revisit once the baseline span pipeline is in production and we know what Cloud Trace cardinality looks like.
- Whether to expose a custom sampler interface for tenant-specific sampling — wait for a concrete request before adding the seam.
