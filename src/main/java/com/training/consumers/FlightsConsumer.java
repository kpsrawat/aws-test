package com.training.consumers;

import com.training.IKafkaConstants;
import com.training.pojos.FlightsData;
import com.training.pojos.generatedSources.FlightDataAvroSchema;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Printed;
import org.bson.Document;

import java.util.*;

/**
 * Idea
 * |-> Validated unmodified flights data stream to DB (Mongo) -> Charts (Consumption rate, real time charts)
 * Stream Producers -> Broker -> Consumers -|-> Validated denormalized Stream join records store to HDFS -> Analytical charts, blend with historical data for timesearies or ML
 * *
 */
public class FlightsConsumer {

    public static void main(String[] args) {
       // new FlightsConsumer().consumeFlightsRawData();
        new FlightsConsumer().consumeAvroFlightData();
    }

    private Properties getProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, IKafkaConstants.BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "TG4");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, IKafkaConstants.stringDeserializer);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return props;
    }

    public void consumeFlightsRawData() {
        Properties props = getProperties();
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, IKafkaConstants.stringDeserializer);
        Consumer<String, String> consumer = new KafkaConsumer(props);
        consumer.subscribe(Collections.singleton(IKafkaConstants.RAW_FLIGHT_TOPIC));

        final int minBatchSize = 200;
        List<Document> buffer = new ArrayList<>();
        MongoConnect mongoConnect = MongoConnect.getInstance();
        // TODO: Add insertion timestamp so that we can query to assertain new records inserted
        while (true) {
            consumer.poll(2000).forEach(record -> {
                System.out.println(record.key() + "," + record.value());
                buffer.add(new FlightsData().getFlightDataDocumentFromStr(record.value().split(",")));
                // Store data to MongoDB
                if (buffer.size() >= minBatchSize) {
                    mongoConnect.insertIntoDB(buffer, IKafkaConstants.USERS_MONGO_DB, IKafkaConstants.Flights_Data_MONGO_DB);
                    consumer.commitSync();
                    buffer.clear();
                }

                consumer.commitAsync((offset, e) -> {
                    if (e == null)
                        System.out.println(offset.toString());
                });
            });
        }
    }

    public void consumeAvroFlightData() {
        Properties props = getProperties();
        props.put("application.id", "FlightDataStreamApplication");
        //props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);
        props.put("schema.registry.url", IKafkaConstants.SCHEMA_REGISTRY_URL);
        //props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);

        StreamsBuilder builder = new StreamsBuilder();
        KStream<String, FlightDataAvroSchema> flightDataStream = builder.stream(IKafkaConstants.AVRO_FLIGHT_TOPIC);

        // Print on console
        // flightDataStream.foreach((k,v) -> System.out.println(k+"--"+v.toString()));
        flightDataStream.print(Printed.toSysOut());

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        //streams.cleanUp();
        streams.start();

        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }
}