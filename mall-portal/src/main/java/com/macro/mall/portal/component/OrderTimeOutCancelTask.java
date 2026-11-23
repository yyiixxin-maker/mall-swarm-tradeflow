package com.macro.mall.portal.component;

import com.macro.mall.portal.dao.TradeOrderDao;
import com.macro.mall.portal.domain.OutboxEvent;
import com.macro.mall.portal.service.OmsPortalOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(name = "tradeflow.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OrderTimeOutCancelTask {
    @Autowired private TradeOrderDao tradeDao;
    @Autowired private CancelOrderSender sender;
    @Autowired private OmsPortalOrderService orderService;

    @Scheduled(fixedDelayString = "${tradeflow.outbox.poll-ms:2000}", initialDelay = 5000)
    public void publish() {
        for (OutboxEvent event : tradeDao.pending(System.currentTimeMillis())) {
            String token = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            if (tradeDao.lease(event.getEventId(), token, now, now + 30_000) != 1) continue;
            try {
                sender.sendMessage(event);
                tradeDao.published(event.getEventId(), token, System.currentTimeMillis());
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                long delay = Math.min(60_000L, 1000L << Math.min(event.getAttempts(), 6));
                tradeDao.retry(event.getEventId(), token, System.currentTimeMillis() + delay, e.getClass().getSimpleName());
                log.warn("Order timeout publication will retry: eventId={}, error={}", event.getEventId(), e.getClass().getSimpleName());
            }
        }
    }

    @Scheduled(fixedDelayString = "${tradeflow.timeout.scan-ms:30000}", initialDelay = 10000)
    public void cancelTimeOutOrder() {
        orderService.cancelTimeOutOrder();
    }
}
