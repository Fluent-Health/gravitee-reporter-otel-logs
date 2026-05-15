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
package io.gravitee.reporter.otellogs.config;

import org.springframework.beans.factory.annotation.Value;

public class ResourceConfiguration {

  @Value("${reporters.otellogs.resource.serviceName:gravitee-api-gateway}")
  private String serviceName;

  @Value("${reporters.otellogs.resource.serviceNamespace:apim}")
  private String serviceNamespace;

  // Falls back to the legacy flat key for one release.
  @Value(
    "${reporters.otellogs.resource.gcp.projectId:${reporters.otellogs.gcloud.projectId:#{null}}}"
  )
  private String gcpProjectId;

  @Value("${reporters.otellogs.resource.gcp.autoDetect:true}")
  private boolean gcpAutoDetect;

  public String getServiceName() {
    return serviceName;
  }

  public void setServiceName(String v) {
    this.serviceName = v;
  }

  public String getServiceNamespace() {
    return serviceNamespace;
  }

  public void setServiceNamespace(String v) {
    this.serviceNamespace = v;
  }

  public String getGcpProjectId() {
    return gcpProjectId;
  }

  public void setGcpProjectId(String v) {
    this.gcpProjectId = v;
  }

  public boolean isGcpAutoDetect() {
    return gcpAutoDetect;
  }

  public void setGcpAutoDetect(boolean v) {
    this.gcpAutoDetect = v;
  }
}
