package com.macro.mall.portal;

import com.macro.mall.portal.controller.AlipayController;
import com.macro.mall.portal.service.AlipayService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlipayControllerProtocolTests {
    @ParameterizedTest
    @ValueSource(strings = {"success", "failure"})
    void notificationReturnsTheProviderProtocolBody(String result) throws Exception {
        AlipayService service = mock(AlipayService.class);
        when(service.notify(anyMap())).thenReturn(result);
        AlipayController controller = new AlipayController();
        ReflectionTestUtils.setField(controller, "alipayService", service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/alipay/notify").param("out_trade_no", "protocol-test"))
                .andExpect(status().isOk())
                .andExpect(content().string(result))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));
    }
}
