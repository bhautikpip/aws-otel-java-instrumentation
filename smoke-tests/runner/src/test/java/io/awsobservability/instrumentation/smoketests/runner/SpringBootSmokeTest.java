/*
 * Copyright Amazon.com, Inc. or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package io.awsobservability.instrumentation.smoketests.runner;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleDeserializers;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.Uninterruptibles;
import com.linecorp.armeria.client.WebClient;
import io.netty.buffer.ByteBufAllocator;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.Span;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers(disabledWithoutDocker = true)
class SpringBootSmokeTest {

  private static final Logger logger = LoggerFactory.getLogger(SpringBootSmokeTest.class);

  private static final String AGENT_PATH =
      System.getProperty("io.awsobservability.instrumentation.smoketests.runner.agentPath");

  private static final JsonMapper OBJECT_MAPPER;

  static {
    var marshaller =
        MessageMarshaller.builder()
            .register(ExportTraceServiceRequest.getDefaultInstance())
            .build();

    var mapper = JsonMapper.builder();
    var module = new SimpleModule();
    var deserializers = new SimpleDeserializers();
    deserializers.addDeserializer(
        ExportTraceServiceRequest.class,
        new StdDeserializer<ExportTraceServiceRequest>(ExportTraceServiceRequest.class) {
          @Override
          public ExportTraceServiceRequest deserialize(
              JsonParser parser, DeserializationContext ctxt)
              throws IOException, JsonProcessingException {
            var builder = ExportTraceServiceRequest.newBuilder();
            marshaller.mergeValue(parser, builder);
            return builder.build();
          }
        });
    module.setDeserializers(deserializers);
    mapper.addModule(module);
    OBJECT_MAPPER = mapper.build();
  }

  private static final int START_TIME_SECS =
      (int) TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());

  private static final Network network = Network.newNetwork();

  @Container
  private static final GenericContainer<?> backend =
      new GenericContainer<>(
              "docker.pkg.github.com/anuraaga/aws-opentelemetry-java-instrumentation/smoke-tests-fake-backend:master")
          .withExposedPorts(8080)
          .waitingFor(Wait.forHttp("/health").forPort(8080))
          .withNetwork(network)
          .withNetworkAliases("backend")
          .withLogConsumer(new Slf4jLogConsumer(logger));

  @Container
  private static final GenericContainer<?> collector =
      new GenericContainer<>("otel/opentelemetry-collector-dev")
          .withExposedPorts(13133)
          .waitingFor(Wait.forHttp("/").forPort(13133))
          .withNetwork(network)
          .withNetworkAliases("collector")
          .withLogConsumer(new Slf4jLogConsumer(logger))
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("/otel.yaml"), "/etc/otel.yaml")
          .withCommand("--config /etc/otel.yaml");

  @Container
  private static final GenericContainer<?> application =
      new GenericContainer<>(
              "docker.pkg.github.com/anuraaga/aws-opentelemetry-java-instrumentation/smoke-tests-spring-boot:master")
          .dependsOn(backend, collector)
          .withExposedPorts(8080)
          .withNetwork(network)
          .withLogConsumer(new Slf4jLogConsumer(logger))
          .withCopyFileToContainer(
              MountableFile.forHostPath(AGENT_PATH), "/opentelemetry-javaagent-all.jar")
          .withEnv("JAVA_TOOL_OPTIONS", "-javaagent:/opentelemetry-javaagent-all.jar")
          .withEnv("OTEL_BSP_MAX_EXPORT_BATCH", "1")
          .withEnv("OTEL_BSP_SCHEDULE_DELAY", "10")
          .withEnv("OTEL_OTLP_ENDPOINT", "collector:55680");

  private static final TypeReference<List<ExportTraceServiceRequest>>
      EXPORT_TRACE_SERVICE_REQUEST_LIST = new TypeReference<>() {};

  private WebClient appClient;
  private WebClient backendClient;

  @BeforeEach
  void setUp() {
    appClient = WebClient.of("http://localhost:" + application.getMappedPort(8080));
    backendClient = WebClient.of("http://localhost:" + backend.getMappedPort(8080));
  }

  @AfterEach
  void tearDown() {
    backendClient.get("/clear-requests").aggregate().join();
  }

  @Test
  void sendRequest() {
    var response = appClient.get("/hello").aggregate().join();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.contentUtf8()).isEqualTo("Hi there!");

    var exported = getExported();
    assertThat(exported)
        .anySatisfy(
            span -> {
              assertThat(span.getKind()).isEqualTo(Span.SpanKind.SERVER);
              assertThat(span.getName()).isEqualTo("/hello");
            });
    assertThat(exported)
        .anySatisfy(
            span -> {
              assertThat(span.getKind()).isEqualTo(Span.SpanKind.INTERNAL);
              assertThat(span.getName()).isEqualTo("AppController.hello");
            });
    assertThat(exported)
        .allSatisfy(
            span -> {
              var traceId = span.getTraceId();
              int epoch =
                  Ints.fromBytes(
                      traceId.byteAt(0), traceId.byteAt(1), traceId.byteAt(2), traceId.byteAt(3));
              assertThat(epoch).isGreaterThan(START_TIME_SECS);
            });
  }

  private List<Span> getExported() {
    List<ExportTraceServiceRequest> exported = null;
    for (int i = 0; i < 20; i++) {
      try (var content =
          backendClient
              .get("/get-requests")
              .aggregateWithPooledObjects(ByteBufAllocator.DEFAULT)
              .join()
              .content()) {
        exported =
            OBJECT_MAPPER.readValue(content.toInputStream(), EXPORT_TRACE_SERVICE_REQUEST_LIST);
        if (!exported.isEmpty()) {
          break;
        }
      } catch (IOException e) {
        logger.warn("Error reading JSON response.", e);
      }
      Uninterruptibles.sleepUninterruptibly(100, TimeUnit.MILLISECONDS);
    }
    if (exported == null) {
      throw new AssertionError("No traces after 20 attempts.");
    }
    return exported.stream()
        .flatMap(req -> req.getResourceSpansList().stream())
        .flatMap(rs -> rs.getInstrumentationLibrarySpansList().stream())
        .flatMap(ils -> ils.getSpansList().stream())
        .collect(toImmutableList());
  }
}