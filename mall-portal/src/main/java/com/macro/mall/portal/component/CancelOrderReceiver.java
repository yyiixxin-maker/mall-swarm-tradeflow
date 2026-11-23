package com.macro.mall.portal.component;

import com.macro.mall.portal.service.OmsPortalOrderService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CancelOrderReceiver {
    @Autowired private OmsPortalOrderService portalOrderService;
    private final SimpleMessageConverter converter = new SimpleMessageConverter();

    @RabbitListener(queues = "mall.order.cancel")
    public void handle(Message message) {
        // Convert inside the retry advice so malformed legacy messages reach the failure queue too.
        Object payload = converter.fromMessage(message);
        if (!(payload instanceof Long) || (Long) payload <= 0) throw new IllegalArgumentException("Invalid order timeout message");
        portalOrderService.cancelOrder((Long) payload);
    }
}
