package com.macro.mall.portal.config;

import com.macro.mall.portal.domain.QueueEnum;
import org.springframework.amqp.core.*;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecovererWithConfirms;

/**
 * 消息队列相关配置
 * Created by macro on 2018/9/14.
 */
@Configuration
public class RabbitMqConfig {
    public static final String FAILURE_EXCHANGE = "mall.order.failure";
    public static final String FAILURE_QUEUE = "mall.order.cancel.failed";

    @Bean
    public Declarables orderFailureTopology() {
        DirectExchange exchange = new DirectExchange(FAILURE_EXCHANGE);
        Queue queue = new Queue(FAILURE_QUEUE);
        return new Declarables(exchange, queue, BindingBuilder.bind(queue).to(exchange).with(FAILURE_QUEUE));
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, CachingConnectionFactory connectionFactory,
            RabbitTemplate template) {
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        template.setMandatory(true);
        RepublishMessageRecovererWithConfirms recoverer = new RepublishMessageRecovererWithConfirms(
                template, FAILURE_EXCHANGE, FAILURE_QUEUE, CachingConnectionFactory.ConfirmType.CORRELATED);
        recoverer.setConfirmTimeout(5000);
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(true);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(3)
                .backOffOptions(200, 2, 1000).recoverer((message, cause) -> {
                    message.getMessageProperties().setExpiration(null);
                    try { recoverer.recover(message, cause); }
                    catch (RuntimeException e) {
                        throw new ImmediateRequeueAmqpException("Order failure queue publication was not confirmed");
                    }
                }).build());
        return factory;
    }

    /**
     * 订单消息实际消费队列所绑定的交换机
     */
    @Bean
    DirectExchange orderDirect() {
        return (DirectExchange) ExchangeBuilder
                .directExchange(QueueEnum.QUEUE_ORDER_CANCEL.getExchange())
                .durable(true)
                .build();
    }

    /**
     * 订单延迟队列队列所绑定的交换机
     */
    @Bean
    DirectExchange orderTtlDirect() {
        return (DirectExchange) ExchangeBuilder
                .directExchange(QueueEnum.QUEUE_TTL_ORDER_CANCEL.getExchange())
                .durable(true)
                .build();
    }

    /**
     * 订单实际消费队列
     */
    @Bean
    public Queue orderQueue() {
        return new Queue(QueueEnum.QUEUE_ORDER_CANCEL.getName());
    }

    /**
     * 订单延迟队列（死信队列）
     */
    @Bean
    public Queue orderTtlQueue() {
        return QueueBuilder
                .durable(QueueEnum.QUEUE_TTL_ORDER_CANCEL.getName())
                .withArgument("x-dead-letter-exchange", QueueEnum.QUEUE_ORDER_CANCEL.getExchange())//到期后转发的交换机
                .withArgument("x-dead-letter-routing-key", QueueEnum.QUEUE_ORDER_CANCEL.getRouteKey())//到期后转发的路由键
                .build();
    }

    /**
     * 将订单队列绑定到交换机
     */
    @Bean
    Binding orderBinding(DirectExchange orderDirect,Queue orderQueue){
        return BindingBuilder
                .bind(orderQueue)
                .to(orderDirect)
                .with(QueueEnum.QUEUE_ORDER_CANCEL.getRouteKey());
    }

    /**
     * 将订单延迟队列绑定到交换机
     */
    @Bean
    Binding orderTtlBinding(DirectExchange orderTtlDirect,Queue orderTtlQueue){
        return BindingBuilder
                .bind(orderTtlQueue)
                .to(orderTtlDirect)
                .with(QueueEnum.QUEUE_TTL_ORDER_CANCEL.getRouteKey());
    }

}
