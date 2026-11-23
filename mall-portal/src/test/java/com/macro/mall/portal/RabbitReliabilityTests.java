package com.macro.mall.portal;

import com.macro.mall.portal.component.CancelOrderSender;
import com.macro.mall.portal.component.CancelOrderReceiver;
import com.macro.mall.portal.config.RabbitMqConfig;
import com.macro.mall.portal.domain.OutboxEvent;
import com.macro.mall.portal.service.OmsPortalOrderService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="TRADEFLOW_TEST_AMQP_VHOST",matches="/tradeflow_test_[a-f0-9]{12}")
class RabbitReliabilityTests {
    private AnnotationConfigApplicationContext context;
    private RabbitTemplate template;
    private RabbitAdmin admin;
    private CancelOrderSender sender;
    private SimpleMessageListenerContainer container;

    @BeforeEach void connect() {
        context=new AnnotationConfigApplicationContext();
        Map<String,Object> properties=new HashMap<>();
        properties.put("spring.rabbitmq.host","127.0.0.1");properties.put("spring.rabbitmq.port",5673);
        properties.put("spring.rabbitmq.username","mall");properties.put("spring.rabbitmq.password",System.getenv("TRADEFLOW_RABBIT_PASSWORD"));
        properties.put("spring.rabbitmq.virtual-host",System.getenv("TRADEFLOW_TEST_AMQP_VHOST"));
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-test-broker",properties));
        context.register(RabbitAutoConfiguration.class,RabbitMqConfig.class);context.refresh();
        template=context.getBean(RabbitTemplate.class);admin=new RabbitAdmin(template);admin.initialize();
        for(String queue:List.of("mall.order.cancel","mall.order.cancel.ttl",RabbitMqConfig.FAILURE_QUEUE))admin.purgeQueue(queue,false);
        sender=new CancelOrderSender();ReflectionTestUtils.setField(sender,"rabbitTemplate",template);
    }
    @AfterEach void close() { if(container!=null)container.stop();if(context!=null)context.close(); }
    private OutboxEvent event(long deadline) {
        OutboxEvent event=new OutboxEvent();event.setEventId(UUID.randomUUID().toString());event.setOrderId(7L);event.setDeadline(deadline);return event;
    }
    private void waitUntil(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(!condition.getAsBoolean() && System.nanoTime()<end)Thread.sleep(50);
        assertTrue(condition.getAsBoolean(),"Broker condition did not occur before the deadline");
    }

    @Test void confirmedTimeoutKeepsLongPayloadAndOriginalDeadline() throws Exception {
        OutboxEvent event=event(System.currentTimeMillis()+10_000);
        sender.sendMessage(event);
        Message message=template.receive("mall.order.cancel.ttl",2000);
        assertNotNull(message);
        assertEquals(event.getEventId(),message.getMessageProperties().getMessageId());
        assertEquals(event.getDeadline(),((Number)message.getMessageProperties().getHeaders().get("tradeflow.deadline")).longValue());
        assertTrue(Long.parseLong(message.getMessageProperties().getExpiration())<=10_000);
        assertEquals(7L,new org.springframework.amqp.support.converter.SimpleMessageConverter().fromMessage(message));
    }

    @Test void unroutablePublicationIsNotConsideredSuccessful() {
        admin.removeBinding(new Binding("mall.order.cancel.ttl",Binding.DestinationType.QUEUE,"mall.order.direct.ttl","mall.order.cancel.ttl",null));
        try { assertThrows(Exception.class,()->sender.sendMessage(event(System.currentTimeMillis()+10_000))); }
        finally { admin.declareBinding(new Binding("mall.order.cancel.ttl",Binding.DestinationType.QUEUE,"mall.order.direct.ttl","mall.order.cancel.ttl",null)); }
    }

    @Test void dueTimeoutTravelsThroughTheExistingTtlAndDeadLetterTopology() throws Exception {
        sender.sendMessage(event(System.currentTimeMillis()-1000));
        Message message=template.receive("mall.order.cancel",5000);
        assertNotNull(message);assertEquals(7L,new org.springframework.amqp.support.converter.SimpleMessageConverter().fromMessage(message));
    }

    @Test void poisonMessageIsRetriedThreeTimesThenIsolatedAndCanBeReplayed() throws Exception {
        AtomicInteger attempts=new AtomicInteger();
        SimpleRabbitListenerEndpoint endpoint=new SimpleRabbitListenerEndpoint();
        endpoint.setQueueNames("mall.order.cancel");
        endpoint.setMessageListener(message -> { attempts.incrementAndGet();throw new IllegalStateException("fixture failure"); });
        container=context.getBean(SimpleRabbitListenerContainerFactory.class).createListenerContainer(endpoint);
        container.afterPropertiesSet();container.start();
        template.convertAndSend("mall.order.direct","mall.order.cancel",7L);
        waitUntil(()->attempts.get()>=3 && admin.getQueueInfo(RabbitMqConfig.FAILURE_QUEUE).getMessageCount()==1);
        container.stop();container=null;
        assertEquals(3,attempts.get());
        assertEquals(1,sender.replayFailed(1));
        Message replay=template.receive("mall.order.cancel",2000);assertNotNull(replay);
        assertEquals(Boolean.TRUE,replay.getMessageProperties().getHeaders().get("tradeflow.replayed"));
        assertEquals(0,admin.getQueueInfo(RabbitMqConfig.FAILURE_QUEUE).getMessageCount());
    }

    @Test void malformedPayloadIsConvertedInsideRetryAndRetainedInFailureQueue() throws Exception {
        CancelOrderReceiver receiver=new CancelOrderReceiver();OmsPortalOrderService orders=mock(OmsPortalOrderService.class);
        ReflectionTestUtils.setField(receiver,"portalOrderService",orders);
        SimpleRabbitListenerEndpoint endpoint=new SimpleRabbitListenerEndpoint();endpoint.setQueueNames("mall.order.cancel");endpoint.setMessageListener(receiver::handle);
        container=context.getBean(SimpleRabbitListenerContainerFactory.class).createListenerContainer(endpoint);
        container.afterPropertiesSet();container.start();
        template.convertAndSend("mall.order.direct","mall.order.cancel","invalid-fixture");
        waitUntil(()->admin.getQueueInfo(RabbitMqConfig.FAILURE_QUEUE).getMessageCount()==1);
        verifyNoInteractions(orders);
    }
}
