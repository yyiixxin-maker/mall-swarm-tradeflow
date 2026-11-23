package com.macro.mall;

import com.macro.mall.common.api.CommonResult;
import com.macro.mall.common.exception.ApiException;
import com.macro.mall.model.*;
import com.macro.mall.service.*;
import com.macro.mall.service.impl.OmsOrderServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminCloseReliabilityTests {
    @Test void adminIdentityAndPermissionAreCheckedBeforeCallingPortal() {
        OmsOrderServiceImpl service=new OmsOrderServiceImpl();
        UmsAdminService admins=mock(UmsAdminService.class);PortalOrderClient client=mock(PortalOrderClient.class);
        ReflectionTestUtils.setField(service,"adminService",admins);ReflectionTestUtils.setField(service,"portalOrderClient",client);
        ReflectionTestUtils.setField(service,"internalToken","fixture-token-for-unit-test-only-32");
        UmsAdmin admin=new UmsAdmin();admin.setId(1L);admin.setUsername("fixture-admin");
        when(admins.getCurrentAdmin()).thenReturn(admin);when(admins.getResourceList(1L)).thenReturn(List.of());
        assertThrows(ApiException.class,()->service.close(List.of(1L),"fixture"));verifyNoInteractions(client);
        UmsResource resource=new UmsResource();resource.setUrl("/order/update/close");when(admins.getResourceList(1L)).thenReturn(List.of(resource));
        when(client.close(anyString(),any())).thenReturn(CommonResult.success(1));
        assertEquals(1,service.close(List.of(1L),"fixture"));
        verify(client).close(anyString(),argThat(r -> "fixture-admin".equals(r.getOperator()) && r.getIds().equals(List.of(1L))));
    }
}
