package com.macro.mall.portal;

import com.macro.mall.common.domain.OrderCloseRequest;
import com.macro.mall.portal.component.CancelOrderSender;
import com.macro.mall.portal.controller.InternalOrderController;
import com.macro.mall.portal.service.OrderStateService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalEndpointReliabilityTests {
    @Test void emptyOrWrongServiceIdentityCannotMutateOrdersOrReplayMessages() {
        OrderStateService states=mock(OrderStateService.class);CancelOrderSender sender=mock(CancelOrderSender.class);
        InternalOrderController disabled=new InternalOrderController(states,sender,"");
        assertThrows(ResponseStatusException.class,()->disabled.close("",new OrderCloseRequest()));
        String token="fixture-service-identity-at-least-32";
        InternalOrderController controller=new InternalOrderController(states,sender,token);
        assertThrows(ResponseStatusException.class,()->controller.close("wrong",new OrderCloseRequest()));
        assertThrows(ResponseStatusException.class,()->controller.replay("wrong",10));
        verifyNoInteractions(states,sender);
        OrderCloseRequest request=new OrderCloseRequest();controller.close(token,request);verify(states).close(request);
    }
}
