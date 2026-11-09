/*
 * Copyright (c) 2026 IoT Invent GmbH
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
 *
 * AI Disclosure: This file was largely AI-generated. The AI-generated
 * portions are made available under CC0-1.0 and not subject to the
 * project's licence. The human contributor has reviewed and verified
 * that the code is correct.
 *
 * SPDX-License-Identifier: Apache-2.0 AND CC0-1.0
 * Assisted-by: Anthropic Claude Opus 5.5 (claude-opus-5-5)
 */

package io.vertx.mqtt.test.client;

import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.MqttException;
import io.vertx.mqtt.MqttServer;
import io.vertx.mqtt.MqttServerOptions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The in-flight state of a network connection does not survive it: a client connected again starts with free slots.
 */
@RunWith(VertxUnitRunner.class)
public class MqttClientStateAfterCloseTest extends MqttClientTestBase {

  private Vertx vertx;
  private MqttServer server;

  @Before
  public void before() {
    vertx = Vertx.vertx();
    server = createServer(vertx, new MqttServerOptions().setPort(0));
  }

  @After
  public void after(TestContext ctx) {
    server.close().onComplete(ctx.asyncAssertSuccess(v -> vertx.close().onComplete(ctx.asyncAssertSuccess())));
  }

  /**
   * QoS 1 messages lost with the connection free their in-flight slots and are reported as expired.
   */
  @Test
  public void inFlightMessagesDoNotSurviveTheConnection(TestContext ctx) {
    List<MqttEndpoint> endpoints = new CopyOnWriteArrayList<>();
    server.endpointHandler(endpoint -> {
      // never acknowledges a PUBLISH
      endpoint.publishHandler(msg -> { });
      endpoint.accept(false);
      endpoints.add(endpoint);
    });
    int port = server.listen().await().actualPort();

    AtomicInteger expired = new AtomicInteger();
    MqttClient client = createClient(vertx, new MqttClientOptions().setMaxInflightQueue(3));
    client.publishCompletionExpirationHandler(id -> expired.incrementAndGet());
    client.connect(port, "localhost").await();
    for (int i = 0; i < 3; i++) {
      client.publish("state/inflight", Buffer.buffer("m" + i), MqttQoS.AT_LEAST_ONCE, false, false).await();
    }
    client.publish("state/inflight", Buffer.buffer("full"), MqttQoS.AT_LEAST_ONCE, false, false)
      .onComplete(ctx.asyncAssertFailure(err ->
        ctx.assertEquals(MqttException.MQTT_INFLIGHT_QUEUE_FULL, ((MqttException) err).code())));

    Promise<Void> closed = Promise.promise();
    client.closeHandler(v -> closed.tryComplete());
    endpoints.get(0).close();
    closed.future().await();
    ctx.assertEquals(3, expired.get());

    client.connect(port, "localhost").await();
    client.publish("state/inflight", Buffer.buffer("again"), MqttQoS.AT_LEAST_ONCE, false, false)
      .onComplete(ctx.asyncAssertSuccess());
  }
}
