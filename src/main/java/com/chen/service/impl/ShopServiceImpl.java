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

        // 2.存在,直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            // 将查询到的数据 从Json字符串类型转为Shop类型
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.success(shop);
        }

        // 3.不存在,去数据库查询
        Shop shop = getById(id);
        // 4.数据库中不存在,返回失败
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        // 5.存在,写入Redis,设置过期时间
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 6.返回
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
