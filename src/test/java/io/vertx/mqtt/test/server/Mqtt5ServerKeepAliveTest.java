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

package io.vertx.mqtt.test.server;

import io.netty.handler.codec.mqtt.MqttProperties;
import io.netty.handler.codec.mqtt.MqttVersion;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.MqttServer;
import io.vertx.mqtt.MqttServerOptions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * After accepting with a Server Keep Alive (MQTT 5.0 §3.2.2.3.14) the server watches that keep alive instead of the
 * one the client requested. The clients ping manually, so the tests do not depend on the client's keep alive handling.
 */
@RunWith(VertxUnitRunner.class)
public class Mqtt5ServerKeepAliveTest {

  private Vertx vertx;
  private MqttServer server;

  @Before
  public void before() {
    vertx = Vertx.vertx();
    server = MqttServer.create(vertx, new MqttServerOptions().setPort(0));
  }

  @After
  public void after(TestContext ctx) {
    server.close().onComplete(ctx.asyncAssertSuccess(v -> vertx.close().onComplete(ctx.asyncAssertSuccess())));
  }

  private int listenAcceptingWithServerKeepAlive(int serverKeepAlive) {
    server.endpointHandler(endpoint -> {
      MqttProperties props = new MqttProperties();
      props.add(new MqttProperties.IntegerProperty(MqttProperties.MqttPropertyType.SERVER_KEEP_ALIVE.value(), serverKeepAlive));
      endpoint.accept(false, props);
    });
    return server.listen().await().actualPort();
  }

  private MqttClient silentClient(int keepAliveInterval) {
    MqttClientOptions options = new MqttClientOptions().setKeepAliveInterval(keepAliveInterval).setAutoKeepAlive(false);
    options.setVersion(MqttVersion.MQTT_5.protocolLevel());
    return MqttClient.create(vertx, options);
  }

  /**
   * Server Keep Alive 0 switches keep alive off: a client that sends nothing is not closed.
   */
  @Test
  public void serverKeepAliveZeroKeepsSilentClientConnected(TestContext ctx) {
    int port = listenAcceptingWithServerKeepAlive(0);

    Async done = ctx.async();
    MqttClient client = silentClient(1);
    client.connect(port, "localhost").onComplete(ctx.asyncAssertSuccess(ack ->
      // past the 2 s after which the requested keep alive of 1 s would close the connection
      vertx.setTimer(3000, id -> {
        ctx.assertTrue(client.isConnected());
        client.disconnect().onComplete(ctx.asyncAssertSuccess(v -> done.complete()));
      })));
  }

  /**
   * A client that sends nothing is closed after 1.5 times the assigned keep alive, not the requested one.
   */
  @Test
  public void silentClientIsClosedAfterServerKeepAlive(TestContext ctx) {
    int port = listenAcceptingWithServerKeepAlive(1);

    Async closed = ctx.async();
    MqttClient client = silentClient(30);
    client.connect(port, "localhost").onComplete(ctx.asyncAssertSuccess(ack -> {
      // closed after 2 s, with the requested 30 s it would take 45 s
      long timeout = vertx.setTimer(5000, id -> ctx.fail("Client not closed after the assigned keep alive"));
      client.closeHandler(v -> {
        vertx.cancelTimer(timeout);
        closed.complete();
      });
    }));
  }

  /**
   * A client pinging within the assigned keep alive stays connected, although it pings less often than it requested.
   */
  @Test
  public void clientPingingWithinServerKeepAliveStaysConnected(TestContext ctx) {
    int port = listenAcceptingWithServerKeepAlive(3);

    Async done = ctx.async();
    MqttClient client = silentClient(1);
    client.connect(port, "localhost").onComplete(ctx.asyncAssertSuccess(ack -> {
      // every 2.5 s: within the 4.5 s of the assigned keep alive, past the 2 s of the requested one
      long pinger = vertx.setPeriodic(2500, id -> client.ping());
      vertx.setTimer(6000, id -> {
        vertx.cancelTimer(pinger);
        ctx.assertTrue(client.isConnected());
        client.disconnect().onComplete(ctx.asyncAssertSuccess(v -> done.complete()));
      });
    }));
  }
}
