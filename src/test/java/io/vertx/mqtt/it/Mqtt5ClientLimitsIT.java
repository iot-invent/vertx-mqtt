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

import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import io.vertx.mqtt.MqttException;
import io.vertx.mqtt.messages.MqttConnAckMessage;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The MQTT 5 limits a broker announces in the CONNACK (Receive Maximum, Topic Alias Maximum, Maximum Packet Size) and
 * the Maximum Packet Size the client announces in the CONNECT. The tests take the limits from the CONNACK, as they
 * differ between brokers.
 */
public class Mqtt5ClientLimitsIT extends Mqtt5ClientBaseIT {

  private static final String TOPIC = "mqtt5/it/limits/";

  private Vertx vertx;

  @After
  public void tearDown(TestContext ctx) {
    if (vertx != null) {
      vertx.close().onComplete(ctx.asyncAssertSuccess());
    }
  }

  /**
   * Up to the Receive Maximum of the broker QoS 1 messages can be in flight, the next one fails until they are
   * acknowledged.
   */
  @Test
  public void receiveMaximumOfTheBroker(TestContext ctx) {
    vertx = Vertx.vertx();
    MqttClient client = createClient(vertx, v5Options().setMaxInflightQueue(1000));
    MqttConnAckMessage ack = client.connect(port, host).await();
    int receiveMaximum = ack.receiveMaximum() == null ? 65535 : ack.receiveMaximum();
    Assume.assumeTrue("Receive Maximum " + receiveMaximum + " above the in-flight limit of the test", receiveMaximum < 1000);

    Async done = ctx.async();
    AtomicInteger acknowledged = new AtomicInteger();
    client.publishCompletionHandler(id -> {
      if (acknowledged.incrementAndGet() == receiveMaximum) {
        // all acknowledged, so the window is free again
        client.publish(TOPIC + "rm", Buffer.buffer("after"), MqttQoS.AT_LEAST_ONCE, false, false)
          .compose(v -> client.disconnect())
          .onComplete(ctx.asyncAssertSuccess(v -> done.complete()));
      }
    });
    // on the context of the client, so no PUBACK is handled before the loop is done
    vertx.runOnContext(v -> {
      for (int i = 0; i < receiveMaximum; i++) {
        client.publish(TOPIC + "rm", Buffer.buffer("m" + i), MqttQoS.AT_LEAST_ONCE, false, false)
          .onComplete(ctx.asyncAssertSuccess());
      }
      client.publish(TOPIC + "rm", Buffer.buffer("one too many"), MqttQoS.AT_LEAST_ONCE, false, false)
        .onComplete(ctx.asyncAssertFailure(err ->
          ctx.assertEquals(MqttException.MQTT_INFLIGHT_QUEUE_FULL, ((MqttException) err).code())));
    });
  }

  /**
   * The client assigns topic aliases up to the Topic Alias Maximum of the broker and sends the full topic name for
   * the topics beyond, the subscriber gets every message with its topic name.
   */
  @Test
  public void topicAliasesUpToTheMaximumOfTheBroker(TestContext ctx) {
    vertx = Vertx.vertx();
    MqttClient publisher = createClient(vertx, v5Options());
    MqttConnAckMessage ack = publisher.connect(port, host).await();
    int topicAliasMaximum = ack.topicAliasMaximum() == null ? 0 : ack.topicAliasMaximum();
    Assume.assumeTrue("Broker accepts no topic aliases", topicAliasMaximum > 0);

    List<String> topics = new ArrayList<>();
    for (int i = 0; i < topicAliasMaximum + 3; i++) {
      topics.add(TOPIC + "alias/" + i);
    }
    Map<String, AtomicInteger> received = new ConcurrentHashMap<>();
    Async all = ctx.async(2 * topics.size());

    MqttClient subscriber = createClient(vertx, v5Options());
    subscriber.publishHandler(msg -> {
      received.computeIfAbsent(msg.topicName(), t -> new AtomicInteger()).incrementAndGet();
      all.countDown();
    });
    subscriber.subscribeCompletionHandler(suback -> {
      // each topic twice: the first PUBLISH carries the topic name, the second only the alias if one was assigned
      for (int round = 0; round < 2; round++) {
        for (String topic : topics) {
          publisher.publish(topic, Buffer.buffer("x"), MqttQoS.AT_MOST_ONCE, false, false);
        }
      }
    });
    subscriber.connect(port, host).await();
    subscriber.subscribe(TOPIC + "alias/#", 0);

    all.awaitSuccess(10000);
    for (String topic : topics) {
      ctx.assertEquals(2, received.get(topic) == null ? 0 : received.get(topic).get(), topic);
    }
    ctx.assertEquals(topics.size(), received.size());
  }

  /**
   * A message just below the Maximum Packet Size of the broker is accepted, one above is rejected by the client
   * without sending it, and the connection stays usable.
   */
  @Test
  public void maximumPacketSizeOfTheBroker(TestContext ctx) {
    vertx = Vertx.vertx();
    MqttClient client = createClient(vertx, v5Options());
    MqttConnAckMessage ack = client.connect(port, host).await();
    Assume.assumeTrue("Broker announces no Maximum Packet Size", ack.maximumPacketSize() != null);
    int maximumPacketSize = (int) Math.min(ack.maximumPacketSize(), 16 * 1024 * 1024);

    Async acknowledged = ctx.async();
    client.publishCompletionHandler(id -> acknowledged.complete());

    // topic, packet id and headers take far less than 1000 bytes
    client.publish(TOPIC + "max", Buffer.buffer(new byte[maximumPacketSize + 1]), MqttQoS.AT_LEAST_ONCE, false, false)
      .onComplete(ctx.asyncAssertFailure(err ->
        ctx.assertEquals(MqttException.MQTT_PACKET_TOO_LARGE, ((MqttException) err).code())));
    client.publish(TOPIC + "max", Buffer.buffer(new byte[maximumPacketSize - 1000]), MqttQoS.AT_LEAST_ONCE, false, false)
      .onComplete(ctx.asyncAssertSuccess());

    acknowledged.awaitSuccess(20000);
    ctx.assertTrue(client.isConnected());
    client.disconnect().await();
  }

  /**
   * The broker does not forward a message exceeding the Maximum Packet Size the subscriber announced.
   */
  @Test
  public void maximumPacketSizeOfTheClient(TestContext ctx) {
    vertx = Vertx.vertx();
    MqttClientOptions subscriberOptions = v5Options();
    subscriberOptions.setMaximumPacketSize(1024L);
    // Mosquitto 2.1.2 assigns its outgoing topic alias to the PUBLISH it then drops as too large and sends the next
    // one with that alias only, which the client rightly rejects as a protocol error
    subscriberOptions.setTopicAliasMaximum(0);
    MqttClient subscriber = createClient(vertx, subscriberOptions);
    MqttClient publisher = createClient(vertx, v5Options());

    Async received = ctx.async();
    subscriber.publishHandler(msg -> {
      ctx.assertEquals("small", msg.payload().toString(), "the broker forwarded the message above 1024 bytes");
      received.complete();
    });
    subscriber.subscribeCompletionHandler(suback -> publisher.connect(port, host)
      .compose(v -> publisher.publish(TOPIC + "client-max", Buffer.buffer(new byte[4096]), MqttQoS.AT_LEAST_ONCE, false, false))
      .compose(v -> publisher.publish(TOPIC + "client-max", Buffer.buffer("small"), MqttQoS.AT_LEAST_ONCE, false, false))
      .onComplete(ctx.asyncAssertSuccess()));
    subscriber.connect(port, host).await();
    subscriber.subscribe(TOPIC + "client-max", 1);

    received.awaitSuccess(10000);
  }
}
