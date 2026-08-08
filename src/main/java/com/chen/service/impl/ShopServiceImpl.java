package com.chen.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.chen.dto.Result;
import com.chen.entity.Shop;
import com.chen.mapper.ShopMapper;
import com.chen.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chen.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private final StringRedisTemplate stringRedisTemplate;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result queryById(Long id) {
        // 1.从Redis查询商户信息
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.命中(信息为有效数据),直接返回
            // 将查询到的数据从Json字符串类型转为Shop类型
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.success(shop);
        }

        // 4.判断是否为""(缓存空对象)
        // 此时 shopJson 只有两种可能: null → key不存在,没缓存过; "" → 缓存了的空对象
        if (shopJson != null) {
            // 5."" → 缓存了的空对象,返回失败  (不用.equals判断,是因为shopJson可能是null,报空指针异常;如果先判断是否为空,那equals就多此一举了)
            return Result.fail("店铺不存在");
        }

        // 6.去数据库查询
        Shop shop = getById(id);
        if (shop == null) {
            // 7.数据库中不存在,返回失败
            return Result.fail("店铺不存在");
        }

        // 8.存在,写入Redis,设置过期时间
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return Result.success(shop);
    }

    @Override
    public Result update(Shop shop) {
        // 1.判断店铺是否存在
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺不存在");
        }
        // 2.更新数据库
        updateById(shop);
        // 3.删除Redis中的缓存,原因:避免频繁更新Redis,不查询更新Redis没啥用,查询时再更新Redis
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());

        return Result.success();
    }
}
