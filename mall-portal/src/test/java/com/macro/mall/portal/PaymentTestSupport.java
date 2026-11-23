package com.macro.mall.portal;

import com.alipay.api.internal.util.AlipaySignature;
import com.macro.mall.portal.config.AlipayConfig;
import java.security.*;
import java.util.*;

public final class PaymentTestSupport {
    private static final KeyPair KEYS;
    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEYS = generator.generateKeyPair();
        } catch (GeneralSecurityException e) { throw new ExceptionInInitializerError(e); }
    }
    public static AlipayConfig config() {
        AlipayConfig config = new AlipayConfig();
        config.setAppId("test-app");
        config.setSellerId("test-seller");
        config.setAlipayPublicKey(Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
        config.setAppPrivateKey(Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()));
        return config;
    }
    public static Map<String, String> signed(String orderSn, String tradeNo, String amount) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", orderSn);
        params.put("trade_no", tradeNo);
        params.put("total_amount", amount);
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("app_id", "test-app");
        params.put("seller_id", "test-seller");
        sign(params);
        return params;
    }
    public static void sign(Map<String, String> params) throws Exception {
        params.remove("sign");
        params.remove("sign_type");
        params.put("sign", AlipaySignature.rsaSign(AlipaySignature.getSignContent(params), config().getAppPrivateKey(), "UTF-8", "RSA2"));
        params.put("sign_type", "RSA2");
    }
    private PaymentTestSupport() { }
}
