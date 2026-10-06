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

package io.vertx.mqtt.it;

import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.utility.DockerImageName;

/**
 * The broker the integration tests run against, chosen with the system property {@code it.broker}:
 * {@code mosquitto} (default) or {@code hivemq} (HiveMQ Community Edition).
 */
final class MqttBrokerContainers {

  static final String BROKER = System.getProperty("it.broker", "mosquitto");

  private MqttBrokerContainers() {
  }

  /**
   * @param mosquittoImage the Mosquitto image to use when running against Mosquitto
   * @param webSocket whether the broker must accept MQTT over WebSocket
   */
  static GenericContainer<?> create(String mosquittoImage, boolean webSocket) {
    switch (BROKER) {
      case "hivemq":
        // the default configuration of the image listens for TCP on 1883 and for WebSocket on 8000 at /mqtt
        return new GenericContainer<>(DockerImageName.parse("hivemq/hivemq-ce:2026.5"))
          .withExposedPorts(1883, 8000)
          .waitingFor(new WaitAllStrategy()
            .withStrategy(Wait.forLogMessage(".*Started HiveMQ in.*", 1))
            .withStrategy(Wait.forListeningPort()));
      case "mosquitto":
        if (!webSocket && mosquittoImage.equals("ansi/mosquitto")) {
          return new GenericContainer<>(DockerImageName.parse(mosquittoImage)).withExposedPorts(1883);
        }
        return new GenericContainer<>(DockerImageName.parse(mosquittoImage))
          .withExposedPorts(1883, 9001)
          .withClasspathResourceMapping("it/mosquitto.conf", "/mosquitto/config/mosquitto.conf", BindMode.READ_ONLY)
          // the log line alone does not mean the mapped ports already accept connections
          .waitingFor(new WaitAllStrategy()
            .withStrategy(Wait.forLogMessage(".*mosquitto .* running.*", 1))
            .withStrategy(Wait.forListeningPort()));
      default:
        throw new IllegalArgumentException("Unknown it.broker " + BROKER + ", expected mosquitto or hivemq");
    }
  }

  /**
   * @return the container port of the WebSocket listener
   */
  static int webSocketPort() {
    return BROKER.equals("hivemq") ? 8000 : 9001;
  }
}
