/**
 * Copyright 2015-2018 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.collector.kafka10;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin.Span;
import zipkin.SpanDecoder;
import zipkin.collector.Collector;
import zipkin.collector.CollectorMetrics;

import static zipkin.SpanDecoder.DETECTING_DECODER;
import static zipkin.storage.Callback.NOOP;

/** Consumes spans from Kafka messages, ignoring malformed input */
final class KafkaCollectorWorker implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(KafkaCollectorWorker.class);

  final Consumer<byte[], byte[]> kafkaConsumer;
  final Collector collector;
  final CollectorMetrics metrics;
  /** Kafka topic partitions currently assigned to this worker. List is not modifiable. */
  final AtomicReference<List<TopicPartition>> assignedPartitions =
      new AtomicReference<>(Collections.emptyList());

  final String field;

  KafkaCollectorWorker(KafkaCollector.Builder builder) {
    kafkaConsumer = new KafkaConsumer<>(builder.properties);
    List<String> topics = Arrays.asList(builder.topic.split(","));
    kafkaConsumer.subscribe(topics, new ConsumerRebalanceListener() {
      @Override public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        assignedPartitions.set(Collections.emptyList());
      }

      @Override public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        assignedPartitions.set(Collections.unmodifiableList(new ArrayList<>(partitions)));
      }
    });
    this.collector = builder.delegate.build();
    this.metrics = builder.metrics;
    this.field = builder.field;
  }

  @Override
  public void run() {
    try {
      LOG.info("Kafka consumer starting polling loop.");
      while (true) {
        final ConsumerRecords<byte[], byte[]> consumerRecords = kafkaConsumer.poll(1000);
        LOG.debug("Kafka polling returned batch of {} messages.", consumerRecords.count());
        for (ConsumerRecord<byte[], byte[]> record : consumerRecords) {
          metrics.incrementMessages();
          final byte[] bytes = record.value();

          if (bytes.length < 2) { // need two bytes to check if protobuf
            metrics.incrementMessagesDropped();
          } else {
            // If we received legacy single-span encoding, decode it into a singleton list
            if (!protobuf3(bytes) && bytes[0] <= 16 && bytes[0] != 12 /* thrift, but not list */) {
              metrics.incrementBytes(bytes.length);
              try {
                Span span = SpanDecoder.THRIFT_DECODER.readSpan(bytes);
                collector.accept(Collections.singletonList(span), NOOP);
              } catch (RuntimeException e) {
                metrics.incrementMessagesDropped();
              }
            } else if (bytes[0] == 123) {
              // If the string start with "{", we parse the Json Object, then get specified field
              String newStr;
              byte[] newBytes;
              Gson gson = new Gson();
              String str = new String(bytes);
              HashMap<String, String> map = new HashMap<>();
              map = gson.fromJson(str, map.getClass());
              newStr = map.get(field);
              if (newStr != null) {
                newBytes = newStr.getBytes();
                collector.acceptSpans(newBytes, DETECTING_DECODER, NOOP);
              } else {
                LOG.warn("Unexpected json formate, not included field: {}, data: {}", field, str);
              }
            } else {
              collector.acceptSpans(bytes, DETECTING_DECODER, NOOP);
            }
          }
        }
      }
    } catch (InterruptException e) {
      // Interrupts are normal on shutdown, intentionally swallow
    } catch (RuntimeException | Error e) {
      LOG.warn("Unexpected error in polling loop spans", e);
      throw e;
    } finally {
      LOG.info("Kafka consumer polling loop stopped.");
      LOG.info("Closing Kafka consumer...");
      kafkaConsumer.close();
      LOG.info("Kafka consumer closed.");
    }
  }

  /* span key or trace ID key */
  static boolean protobuf3(byte[] bytes) {
    return bytes[0] == 10 && bytes[1] != 0; // varint follows and won't be zero
  }
}
