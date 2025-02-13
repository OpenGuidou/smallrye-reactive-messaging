package io.smallrye.reactive.messaging.kafka.perf;

import static io.smallrye.reactive.messaging.kafka.base.PerfTestUtils.generateRandomPayload;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import javax.enterprise.context.ApplicationScoped;

import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.KafkaConnector;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import io.smallrye.reactive.messaging.kafka.Record;
import io.smallrye.reactive.messaging.kafka.base.KafkaMapBasedConfig;
import io.smallrye.reactive.messaging.kafka.base.KafkaTestBase;
import io.smallrye.reactive.messaging.kafka.base.KafkaUsage;
import io.smallrye.reactive.messaging.kafka.base.PerfTestUtils;
import io.smallrye.reactive.messaging.kafka.converters.RecordConverter;
import io.smallrye.reactive.messaging.test.common.config.MapBasedConfig;

/**
 * This test is intended to be used to generate flame-graphs to see where the time is spent in an end-to-end scenario.
 * It generates records to a topic.
 * Then, the application read from this topic and write to another one.
 * The test stops when an external consumers has received all the records written by the application.
 */
@Disabled
public class EndToEndPayloadPerfTest extends KafkaTestBase {

    public static final int COUNT = 50_000;
    public static String input_topic = UUID.randomUUID().toString();
    public static String output_topic = UUID.randomUUID().toString();

    @BeforeAll
    static void insertRecords() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        KafkaUsage usage = new KafkaUsage();
        usage.produce("payload-producer", COUNT, new StringSerializer(), new ByteArraySerializer(), latch::countDown,
                () -> new ProducerRecord<>(input_topic, "key", generateRandomPayload(10000))); // 10kb
        latch.await();
    }

    private MapBasedConfig commonConfig() {
        return new KafkaMapBasedConfig()
                .with("mp.messaging.incoming.in.connector", KafkaConnector.CONNECTOR_NAME)
                .with("mp.messaging.incoming.in.topic", input_topic)
                .with("mp.messaging.incoming.in.graceful-shutdown", false)
                .with("mp.messaging.incoming.in.pause-if-no-requests", true)
                .with("mp.messaging.incoming.in.tracing-enabled", false)
                .with("mp.messaging.incoming.in.cloud-events", false)
                .with("mp.messaging.incoming.in.auto.offset.reset", "earliest")
                .with("mp.messaging.incoming.in.bootstrap.servers", getBootstrapServers())
                .with("mp.messaging.incoming.in.value.deserializer", ByteArrayDeserializer.class.getName())
                .with("mp.messaging.incoming.in.key.deserializer", StringDeserializer.class.getName())

                .with("mp.messaging.outgoing.out.connector", KafkaConnector.CONNECTOR_NAME)
                .with("mp.messaging.outgoing.out.topic", output_topic)
                .with("mp.messaging.outgoing.out.value.serializer", ByteArraySerializer.class.getName())
                .with("mp.messaging.outgoing.out.key.serializer", StringSerializer.class.getName())
                .with("mp.messaging.outgoing.out.bootstrap.servers", getBootstrapServers());
    }

    private void waitForOutMessages() {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", getBootstrapServers());
        properties.put("group.id", UUID.randomUUID().toString());
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties, new StringDeserializer(),
                new ByteArrayDeserializer());
        consumer.subscribe(Collections.singletonList(output_topic));
        boolean done = false;
        long received = 0;
        while (!done) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
            received = received + records.count();
            if (received == COUNT) {
                done = true;
            }
        }
    }

    @ApplicationScoped
    public static class MyNoopProcessor {
        @Incoming("in")
        @Outgoing("out")
        public Record<String, byte[]> transform(Record<String, byte[]> record) {
            return Record.of(record.key(), record.value());
        }

    }

    @ApplicationScoped
    public static class MyHardWorkerBlockingProcessor {
        @Incoming("in")
        @Outgoing("out")
        @Blocking
        public Record<String, byte[]> transform(Record<String, byte[]> record) {
            PerfTestUtils.consumeCPU(1_000_000);
            return Record.of(record.key(), record.value());
        }

    }

    @ApplicationScoped
    public static class MyHardWorkerProcessor {
        @Incoming("in")
        @Outgoing("out")
        public Uni<Message<byte[]>> transform(Message<byte[]> message) {
            return Uni.createFrom().item(message)
                    .onItem().invoke(() -> PerfTestUtils.consumeCPU(1_000_000))
                    .map(KafkaRecord::from);
        }

    }

    @Test
    public void test_noop_processor() {
        addBeans(RecordConverter.class);
        runApplication(commonConfig(), MyNoopProcessor.class);
        waitForOutMessages();
    }

    @Test
    public void test_hard_worker_blocking_processor() {
        addBeans(RecordConverter.class);
        runApplication(commonConfig(), MyHardWorkerBlockingProcessor.class);
        waitForOutMessages();
    }

    @Test
    public void test_hard_worker_processor() {
        addBeans(RecordConverter.class);
        runApplication(commonConfig(), MyHardWorkerProcessor.class);
        waitForOutMessages();
    }

}
