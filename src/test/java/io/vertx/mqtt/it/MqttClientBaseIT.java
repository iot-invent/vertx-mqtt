/*
 * Copyright 2016 Red Hat Inc.
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

package io.vertx.mqtt.it;

import io.vertx.core.Vertx;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import io.vertx.mqtt.MqttServer;
import io.vertx.mqtt.MqttServerOptions;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.testcontainers.containers.GenericContainer;

/**
 * MQTT client testing about connection
 */
@RunWith(VertxUnitRunner.class)
public abstract class MqttClientBaseIT {

  public GenericContainer mosquitto = newBroker();

  protected int port;
  protected String host;

  @Before
  public void setUp() {
    mosquitto.start();
    port = mosquitto.getMappedPort(useWebSocket() ? MqttBrokerContainers.webSocketPort() : 1883);
    host = mosquitto.getHost();
  }

  @After
  public void stopBroker() {
    // runs after the tearDown of the subclass, a container left running would live until the end of the test JVM
    mosquitto.stop();
  }

  private GenericContainer newBroker() {
    // the ansi/mosquitto image is built without WebSocket support
    return MqttBrokerContainers.create(useWebSocket() ? "eclipse-mosquitto:2.1.2-alpine" : "ansi/mosquitto", useWebSocket());
  }

  /**
   * @return {@code true} when the client connects to the broker using MQTT over WebSocket instead of plain TCP
   */
  protected boolean useWebSocket() {
    return false;
  }

  protected MqttServer createServer(Vertx vertx) {
    return MqttServer.create(vertx, new MqttServerOptions().setUseWebSocket(useWebSocket()));
  }

  protected MqttClient createClient(Vertx vertx) {
    return createClient(vertx, new MqttClientOptions());
  }

  protected MqttClient createClient(Vertx vertx, MqttClientOptions options) {
    return MqttClient.create(vertx, options.setUseWebSocket(useWebSocket()));
  }
}
