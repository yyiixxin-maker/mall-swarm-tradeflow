package com.macro.mall.portal;

import com.macro.mall.portal.component.*;
import com.macro.mall.portal.dao.TradeOrderDao;
import com.macro.mall.portal.domain.OutboxEvent;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class OutboxReliabilityTests {
    @Test void failedPublicationIsRetriedAndOnlyConfirmedPublicationIsMarked() throws Exception {
        TradeOrderDao dao=mock(TradeOrderDao.class);
        CancelOrderSender sender=mock(CancelOrderSender.class);
        OutboxEvent event=new OutboxEvent();event.setEventId(UUID.randomUUID().toString());event.setOrderId(1L);
        event.setDeadline(System.currentTimeMillis()+60_000);event.setAttempts(2);
        when(dao.pending(anyLong())).thenReturn(List.of(event));
        when(dao.lease(anyString(),anyString(),anyLong(),anyLong())).thenReturn(1);
        OrderTimeOutCancelTask task=new OrderTimeOutCancelTask();
        ReflectionTestUtils.setField(task,"tradeDao",dao);ReflectionTestUtils.setField(task,"sender",sender);
        doThrow(new IllegalStateException("broker unavailable")).when(sender).sendMessage(event);
        task.publish();
        verify(dao).retry(eq(event.getEventId()),anyString(),longThat(t -> t>System.currentTimeMillis()),eq("IllegalStateException"));
        verify(dao,never()).published(anyString(),anyString(),anyLong());
        reset(sender);task.publish();
        verify(dao).published(eq(event.getEventId()),anyString(),anyLong());
        assertNotNull(event.getDeadline());
    }

    @Test void publisherThatLosesLeaseDoesNotSend() throws Exception {
        TradeOrderDao dao=mock(TradeOrderDao.class);CancelOrderSender sender=mock(CancelOrderSender.class);
        OutboxEvent event=new OutboxEvent();event.setEventId("leased");
        when(dao.pending(anyLong())).thenReturn(List.of(event));
        OrderTimeOutCancelTask task=new OrderTimeOutCancelTask();
        ReflectionTestUtils.setField(task,"tradeDao",dao);ReflectionTestUtils.setField(task,"sender",sender);
        task.publish();verifyNoInteractions(sender);
    }
}
