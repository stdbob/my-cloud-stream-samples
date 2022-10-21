package com.example.mqprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

//@SpringBootApplication
@Slf4j
public class KfkReceiverApplication {

  @Autowired
  private StreamBridge streamBridge;

  @Autowired
  BindingServiceProperties bindingServiceProperties;

  @Autowired
  ObjectMapper objectMapper;

  public static void main(String[] args) {
    SpringApplication.run(KfkReceiverApplication.class, args);
  }

  @PostConstruct
  public void init(){
    bindingServiceProperties.getBindings().forEach((binding,properties) -> log.info("Binding: {}", binding));
  }

//  @Bean
  public Consumer<Message<byte[]>> receiverConsumer() {
    return msg -> {
      JsonNode result = handle(msg);
      System.out.println(result);
    };
  }

  @Bean
  public Function<Flux<Message<byte[]>>, Mono<Void>> receiver() {
    return flux -> flux
        //.bufferTimeout(batchSize, Duration.ofMillis(batchTimeout))
        //.limitRate(3)
        .map(msg -> {
          System.out.println("RECEIVED_TIMESTAMP: "+ Instant.ofEpochMilli((Long) msg.getHeaders().get(KafkaHeaders.RECEIVED_TIMESTAMP)));
          JsonNode result = handle(msg);
          System.out.println(result);
          return result;
        })
        .onErrorContinue(this::handleErrorInDlq)
        .then();
  }

  public void handleErrorInDlq(Throwable t, Object o) {
    log.error(t.getMessage());
    Message<byte[]> originalMessage = (Message<byte[]>)o;
    var message = MessageBuilder
        .fromMessage(originalMessage)
//        .setHeader("spring.cloud.stream.dlq-out-0.destination", "error.rcv.x.MqProcessorExample")
        //.setHeader("spring.cloud.stream.sendto.group", "MqProcessorExample")
        //.setHeader("spring.cloud.stream.rabbit.sendto.producer.routingKeyExpression", "headers['x-original-routingKey']")
        .setHeader("x-original-messageKey", originalMessage.getHeaders().get(KafkaHeaders.RECEIVED_MESSAGE_KEY))
        .setHeader("x-original-partition", originalMessage.getHeaders().get(KafkaHeaders.RECEIVED_PARTITION_ID))
        .setHeader("x-original-topic", originalMessage.getHeaders().get(KafkaHeaders.RECEIVED_TOPIC))
        .setHeader("x-original-offset", originalMessage.getHeaders().get(KafkaHeaders.OFFSET))
        .setHeader("x-original-timestamp", originalMessage.getHeaders().get(KafkaHeaders.RECEIVED_TIMESTAMP))
        .setHeader("x-exception-message", t.getMessage())
        .setHeader("x-exception-stacktrace", Arrays.stream(t.getStackTrace()).map(e -> e.toString()).collect(Collectors.joining(System.lineSeparator())))
        .build();
    streamBridge.send("dlq-out-0", message);

  }


  public JsonNode handle(Message<byte[]> msg) {
    try {
     return objectMapper.readValue(msg.getPayload(), JsonNode.class);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
