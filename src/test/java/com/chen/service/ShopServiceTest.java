package com.chen.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class ShopServiceTest {

    @Autowired
    private IShopService shopService;

    /**
     * 逻辑过期方案 - 缓存预热
     */
    @Test
    public void testSetWithLogicExpire() {
        shopService.setWithLogicExpire(1L, 60L);
    }
}
