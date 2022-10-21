package com.example.mqprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Arrays;
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
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

//@SpringBootApplication
@Slf4j
public class RmqReceiverApplication {

  @Autowired
  private StreamBridge streamBridge;

  @Autowired
  BindingServiceProperties bindingServiceProperties;

  @Autowired
  ObjectMapper objectMapper;

  public static void main(String[] args) {
    SpringApplication.run(RmqReceiverApplication.class, args);
  }

  @PostConstruct
  public void init(){
    bindingServiceProperties.getBindings().forEach((binding,properties) -> log.info("Binding: {}", binding));
  }

  @Bean
  public Function<Flux<Message<byte[]>>, Mono<Void>> receiver() {
    return flux -> flux
        //.bufferTimeout(batchSize, Duration.ofMillis(batchTimeout))
        //.limitRate(3)
        .doOnNext(msg -> {
          JsonNode result = handle(msg);
          System.out.println(result);
        })
        .onErrorContinue(this::handleErrorInDlq)
        .then();
  }

  public void handleErrorInDlq(Throwable t, Object o) {
    Message<byte[]> originalMessage = (Message<byte[]>)o;
    var message = MessageBuilder
        .fromMessage(originalMessage)
        //.setHeader("spring.cloud.stream.sendto.destination", "rcv.dlx")
        //.setHeader("spring.cloud.stream.rabbit.sendto.producer.routingKeyExpression", "headers['x-original-routingKey']")
        .setHeader("x-original-routingKey", originalMessage.getHeaders().get(AmqpHeaders.RECEIVED_ROUTING_KEY))
        .setHeader("x-original-exchange", originalMessage.getHeaders().get(AmqpHeaders.RECEIVED_EXCHANGE))
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
