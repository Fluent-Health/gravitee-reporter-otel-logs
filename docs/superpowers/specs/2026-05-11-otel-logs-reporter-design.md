# OTel Logs Reporter — Design Spec

**Date:** 2026-05-11
**Project:** `gravitee-reporter-otel-logs`
**Copyright:** Fluent Health (https://fluentinhealth.com)

---

## Purpose

A Gravitee APIM reporter plugin that emits structured per-request log entries via the OpenTelemetry Logs SDK, exported via OTLP gRPC to Google Cloud Logging. Companion to `gravitee-reporter-prometheus` (metrics) and sibling to `gravitee-reporter-gcloud`. The OTel transport ensures W3C trace context fields (`trace_id`, `span_id`) are first-class citizens in every log record, enabling full cross-service traceability in Grafana via Grafana Alloy → Loki.

---

## Architecture

### Export pipeline

```
Gravitee APIM (JVM)
  └─ OtelLogsReporter (Reporter interface)
       └─ mapper/* → OtelLogWriter
            └─ SdkLoggerProvider
                 └─ BatchLogRecordProcessor
                      └─ OtlpGrpcLogRecordExporter
                           └─ logging.googleapis.com:443  (OTLP gRPC)
                                └─ Google Cloud Logging
                                     └─ Grafana Alloy (in-cluster)
                                          └─ Grafana Cloud Loki
```

### Key decisions

- **OTel SDK owns batching and retry.** The `BatchLogRecordProcessor` provides the same semantics as `GCloudLogWriter`'s blocking queue, without reimplementing them. `OtelLogWriter` is a thin wrapper around `SdkLoggerProvider`.
- **GCL is the single export target.** Alloy (already deployed for Prometheus scraping) bridges GCL → Loki. No dual-export in the plugin.
- **Workload Identity auth.** On GKE, the OTel gRPC channel picks up Application Default Credentials automatically. No credentials file in plugin config.

---

## Package Structure

```
src/main/java/io/gravitee/reporter/otellogs/
├── OtelLogsReporter.java                        ← implements Reporter, AbstractService
├── config/
│   └── OtelLogsReporterConfiguration.java       ← @Value bindings for reporters.otellogs.*
├── mapper/
│   ├── MetricsToLogRecordMapper.java
│   ├── LogToLogRecordMapper.java
│   ├── EndpointStatusToLogRecordMapper.java
│   ├── MessageMetricsToLogRecordMapper.java
│   └── OtelLabels.java                          ← null-safe attribute builder
├── spring/
│   └── OtelLogsReporterSpringConfiguration.java ← @Configuration, bean wiring
└── writer/
    └── OtelLogWriter.java                       ← holds SdkLoggerProvider, calls emit()

src/main/resources/
├── plugin.properties
├── gravitee.json
└── assembly/plugin-assembly.xml
```

---

## OTel Log Record Schema

Every log record uses the following OTel SDK fields:

| OTel field | Source | Notes |
|---|---|---|
| `traceId` | `X-Request-ID` or `traceparent` header | Extracted by mapper; Loki indexes natively |
| `spanId` | `traceparent` span component | Optional; omitted when absent |
| `severityNumber` | HTTP status code | 5xx → ERROR, 4xx → WARN, else INFO |
| `timestamp` | Event timestamp | |
| `body` | `"{METHOD} {sanitized_path} → {status}"` | Human-readable summary line |
| attributes | All remaining fields | Key-value pairs, see below |

### Attributes by reportable type

**Metrics (HTTP request):**
```
api.name, api.id, api.type
http.method, http.status, http.latency_ms
upstream.endpoint
context.application, context.plan, context.subscription
entrypoint.request.content_length, entrypoint.response.content_length
gateway.proxy_latency_ms, gateway.api_latency_ms
error.message (when present)
```

**Log (request/response metadata — no payload values, no header values):**
```
api.name, api.id
http.method, http.status
log.request.headers_count, log.response.headers_count   ← count only, not values
log.request.content_length, log.response.content_length ← size only, not body
```

**EndpointStatus (health check transitions):**
```
api.name, api.id
endpoint.name, endpoint.url
endpoint.status (UP / DOWN / TRANSITIONALLY_DOWN)
endpoint.response_time_ms
```

**MessageMetrics (async/event-driven APIs):**
```
api.name, api.id
message.count, message.error_count
message.content_length   ← Gravitee v4 MessageMetrics exposes a single contentLength field;
                           no separate bytes_in/bytes_out breakdown available in the API
```

### Compliance constraints

- **No payload values logged** — ever. Only sizes/counts.
- No PII or PHI in any attribute.
- `Log` type (`reportLogs`) is disabled by default.

### Loki label strategy

Low-cardinality attributes (`api.name`, `http.method`, `severity`) become Loki stream labels — configured in Grafana Alloy, not in the plugin. High-cardinality fields (`trace_id`, `http.status`, `latency_ms`) remain as log-line metadata queryable via LogQL's `| json` or `| logfmt`.

---

## Configuration

### `gravitee.yml` shape

```yaml
reporters:
  otellogs:
    enabled: true
    endpoint: "https://logging.googleapis.com"   # OTLP gRPC target; use http:// scheme for plaintext (local/test)
    correlationHeader: "X-Request-ID"            # header to extract trace_id from
    batchSize: 512
    scheduledDelayMs: 5000
    reportHealthChecks: true
    reportLogs: false
    reportMessageMetrics: true
    # No Sentry config needed — sentry-trace header extraction is automatic
```

### `OtelLogsReporterConfiguration` properties

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | Activates the reporter |
| `endpoint` | required | OTLP gRPC endpoint URL. Use `http://` scheme to disable TLS (test/local only) — the OTLP exporter selects plaintext automatically based on scheme |
| `correlationHeader` | `"X-Request-ID"` | Request header whose value is extracted from `Metrics.request().headers()` and set as OTel `traceId`; falls back to W3C `traceparent` if absent |
| `batchSize` | `512` | `BatchLogRecordProcessor` max batch size |
| `scheduledDelayMs` | `5000` | Flush interval in milliseconds |
| `reportHealthChecks` | `true` | Enable `EndpointStatus` logging |
| `reportLogs` | `false` | Enable `Log` type logging |
| `reportMessageMetrics` | `true` | Enable `MessageMetrics` logging |

---

## Sentry Correlation

The plugin does **not** bundle the Sentry SDK — a separate Sentry plugin already handles log forwarding. This plugin only needs to ensure OTel log records carry the attributes that Sentry uses for trace correlation.

### Header extraction

The mapper reads the `sentry-trace` request header (standard Sentry format: `{traceId}-{spanId}-{sampled}`) when present and sets:

| OTel attribute | Source |
|---|---|
| `sentry.trace_id` | first component of `sentry-trace` header |
| `sentry.span_id` | second component of `sentry-trace` header |

No config required — extraction is always attempted and silently skipped when the header is absent. This allows pivoting from any Sentry issue → matching Loki log line by `sentry.trace_id`, and vice versa.

---

## Interoperability with Gravitee Native OTel

Gravitee APIM 4.6+ has built-in OTel tracing (`services.opentelemetry` in gravitee.yml) that exports **spans** via OTLP. This plugin exports **log records** via OTLP. They are complementary:

- Native OTel → spans in Cloud Trace (policy-level detail, microsecond timing)
- This plugin → log records in GCL → Loki (per-request aggregate: status, latency, upstream)

**Shared trace ID:** When Gravitee's native OTel is active it propagates a W3C `traceparent` on each request. This plugin's mapper reads the same `traceparent` header as the fallback trace ID source — so both the span in Cloud Trace and the log record in Loki carry the identical trace ID, enabling cross-signal correlation in Grafana.

No special configuration is needed to make them coexist. The two systems operate on separate SDK instances.

---

## Integration Test Strategy

### Container topology

```
[Test JVM]
  └─ OtelLogsReporter (pointed at collector)
       └─ OTLP gRPC → [OTel Collector container :4317]
                             └─ loki exporter → [Loki container :3100]
                                                      ↑
                                              [Test assertions via HTTP API]
```

### OTel Collector config (embedded in test resources)

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: "0.0.0.0:4317"

exporters:
  loki:
    endpoint: "http://loki:3100/loki/api/v1/push"
    labels:
      attributes:
        api.name: ""
        severity: ""

service:
  pipelines:
    logs:
      receivers: [otlp]
      exporters: [loki]
```

### Test cases

| # | Scenario | Assertion |
|---|---|---|
| 1 | `Metrics` event with `X-Request-ID: abc123` | Loki returns log line with `trace_id=abc123` |
| 2 | `Metrics` event with W3C `traceparent` header | Both `traceId` and `spanId` are set in Loki |
| 3 | `Metrics` event with HTTP 500 | Log line has `severity=ERROR` |
| 4 | `EndpointStatus` DOWN event | Log line appears in Loki, `endpoint.status=DOWN` |
| 5 | `reportLogs=false` + `Log` event sent | No log line appears in Loki |
| 6 | `sentry-trace` header present | `sentry.trace_id` and `sentry.span_id` attributes appear on OTel log record |

### Unit tests

Each mapper class gets a dedicated unit test asserting the exact attribute map and OTel field values for a given input object. No containers.

---

## Maven Dependencies (bundled in plugin ZIP)

```xml
<!-- OTel SDK -->
io.opentelemetry:opentelemetry-sdk-logs
io.opentelemetry:opentelemetry-sdk-common

<!-- OTLP gRPC exporter -->
io.opentelemetry:opentelemetry-exporter-otlp-logs-grpc
io.opentelemetry:opentelemetry-exporter-otlp-grpc-common

<!-- gRPC transport (needed by exporter) -->
io.grpc:grpc-netty-shaded
io.grpc:grpc-stub
io.grpc:grpc-protobuf

```

All bundled (not provided) — the Gravitee gateway runtime does not supply OTel SDK.

---

## Plugin Descriptor

**`plugin.properties`:**
```properties
id=otellogs
name=${project.name}
version=${project.version}
description=${project.description}
class=io.gravitee.reporter.otellogs.OtelLogsReporter
type=reporter
```

---

## OtelLogWriter Lifecycle

```
doStart()  → build SdkLoggerProvider (endpoint, batch config, insecure flag)
           → acquire Logger instance from provider

report()   → mapper produces attribute map + trace fields
           → writer calls logger.logRecordBuilder()
                .setTraceId(...)
                .setSpanId(...)
                .setSeverity(...)
                .setBody(...)
                .setAllAttributes(...)
                .emit()

doStop()   → sdkLoggerProvider.shutdown()
              (flushes BatchLogRecordProcessor before closing gRPC channel)
```

No manual queue management. No flush thread. No retry loop. The OTel SDK handles all of this.
