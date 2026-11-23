package com.macro.mall.portal;

import com.macro.mall.portal.component.CancelOrderSender;
import com.macro.mall.portal.domain.OutboxEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicationReliabilityTests {
    @Test void negativeConfirmAndLostConnectionCannotReportSuccess() {
        RabbitTemplate template=mock(RabbitTemplate.class);
        CancelOrderSender sender=new CancelOrderSender();ReflectionTestUtils.setField(sender,"rabbitTemplate",template);
        OutboxEvent event=new OutboxEvent();event.setEventId(UUID.randomUUID().toString());event.setOrderId(1L);event.setDeadline(System.currentTimeMillis()+60_000);
        doAnswer(invocation -> {
            CorrelationData correlation=invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(false,"fixture nack"));return null;
        }).when(template).convertAndSend(anyString(),anyString(),any(Object.class),any(MessagePostProcessor.class),any(CorrelationData.class));
        assertThrows(Exception.class,()->sender.sendMessage(event));
        doAnswer(invocation -> {
            CorrelationData correlation=invocation.getArgument(4);
            correlation.getFuture().completeExceptionally(new java.io.IOException("fixture disconnect"));return null;
        }).when(template).convertAndSend(anyString(),anyString(),any(Object.class),any(MessagePostProcessor.class),any(CorrelationData.class));
        assertThrows(Exception.class,()->sender.sendMessage(event));
    }
}
