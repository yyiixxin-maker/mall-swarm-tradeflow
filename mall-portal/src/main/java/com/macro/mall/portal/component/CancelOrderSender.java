package com.macro.mall.portal.component;

import com.macro.mall.portal.domain.OutboxEvent;
import com.macro.mall.portal.domain.QueueEnum;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import com.macro.mall.portal.config.RabbitMqConfig;
import com.rabbitmq.client.GetResponse;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class CancelOrderSender {
    @Autowired private RabbitTemplate rabbitTemplate;

    public void sendMessage(OutboxEvent event) throws Exception {
        CorrelationData correlation = new CorrelationData(event.getEventId());
        rabbitTemplate.convertAndSend(QueueEnum.QUEUE_TTL_ORDER_CANCEL.getExchange(),
                QueueEnum.QUEUE_TTL_ORDER_CANCEL.getRouteKey(), event.getOrderId(), message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setMessageId(event.getEventId());
                    message.getMessageProperties().setHeader("tradeflow.deadline", event.getDeadline());
                    message.getMessageProperties().setExpiration(Long.toString(Math.max(1, event.getDeadline() - System.currentTimeMillis())));
                    return message;
                }, correlation);
        await(correlation);
    }

    public int replayFailed(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Replay limit must be between 1 and 100");
        Integer replayed = rabbitTemplate.execute(channel -> {
            int count = 0;
            while (count < limit) {
                GetResponse delivery = channel.basicGet(RabbitMqConfig.FAILURE_QUEUE, false);
                if (delivery == null) break;
                try {
                    var properties = new DefaultMessagePropertiesConverter().toMessageProperties(delivery.getProps(), delivery.getEnvelope(), "UTF-8");
                    properties.setExpiration(null);
                    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    properties.setHeader("tradeflow.replayed", true);
                    CorrelationData correlation = new CorrelationData();
                    rabbitTemplate.send(QueueEnum.QUEUE_ORDER_CANCEL.getExchange(), QueueEnum.QUEUE_ORDER_CANCEL.getRouteKey(),
                            new Message(delivery.getBody(), properties), correlation);
                    await(correlation);
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    count++;
                } catch (Exception e) {
                    channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    throw new AmqpException("Failure replay was not confirmed; original message retained", e);
                }
            }
            return count;
        });
        return replayed == null ? 0 : replayed;
    }

    private void await(CorrelationData correlation) throws Exception {
        CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
        if (!confirm.isAck() || correlation.getReturned() != null) {
            throw new IllegalStateException("Order timeout publication was not routed and confirmed");
        }
    }
}
