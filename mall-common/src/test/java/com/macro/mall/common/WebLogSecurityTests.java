package com.macro.mall.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.macro.mall.common.log.WebLogAspect;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebLogSecurityTests {
    public Object login(@RequestParam String password) { return null; }

    @Test void requestLogsDoNotContainCredentialsOrResponseTokens() throws Throwable {
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/auth/login");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        ProceedingJoinPoint point=mock(ProceedingJoinPoint.class);MethodSignature signature=mock(MethodSignature.class);
        when(point.getSignature()).thenReturn(signature);when(signature.getMethod()).thenReturn(getClass().getMethod("login",String.class));
        when(point.getArgs()).thenReturn(new Object[]{"fixture-private-password"});
        Map<String,String> result=Map.of("token","fixture-private-token");when(point.proceed()).thenReturn(result);
        Logger logger=(Logger)LoggerFactory.getLogger(WebLogAspect.class);
        ListAppender<ILoggingEvent> appender=new ListAppender<>();appender.start();logger.addAppender(appender);
        try {
            assertSame(result,new WebLogAspect().doAround(point));
            assertEquals(1,appender.list.size());
            String message=appender.list.get(0).getFormattedMessage();
            assertTrue(message.contains("/auth/login"));
            assertFalse(message.contains("fixture-private-password"));
            assertFalse(message.contains("fixture-private-token"));
        } finally { logger.detachAppender(appender);RequestContextHolder.resetRequestAttributes(); }
    }
}
