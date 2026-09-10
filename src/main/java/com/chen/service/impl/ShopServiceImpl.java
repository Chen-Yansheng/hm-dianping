package com.chen.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chen.dto.Result;
import com.chen.entity.Shop;
import com.chen.mapper.ShopMapper;
import com.chen.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chen.utils.RedisConstants;
import com.chen.utils.RedisData;
import com.chen.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.chen.utils.RedisConstants.SHOP_GEO_KEY;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private final StringRedisTemplate stringRedisTemplate;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result queryById(Long id) {
        // 方案A: 普通查询 → 简单直接,适合数据不常变的情况,但高并发下缓存过期瞬间可能导致DB被打爆
        return queryWithPassThrough(id);

        // 方案B: 互斥锁 → 数据一致性更强,但用户可能等待
        //return queryWithMutex(id);

        // 方案C: 逻辑过期 → 性能最好,用户永远不等待,但可能读到旧数据
        //return queryWithLogicExpire(id);
    }

    /**
     * 预防缓存穿透
     */
    private Result queryWithPassThrough(Long id) {
        // 1.从Redis查询商户信息
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            //命中(信息为有效数据),直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.success(shop);
        }

        // 3.判断是否为""(缓存空对象)
        // 此时 shopJson 只有两种可能: null → key不存在,没缓存过; "" → 缓存了的空对象
        if (shopJson != null) {
            // "" → 缓存了的空对象,返回失败
            // (不用.equals判断,是因为shopJson可能是null,报空指针异常;如果先判断是否为空,那equals就多此一举了)
            return Result.fail("店铺不存在");
        }

        // 4.查数据库
        Shop shop = getById(id);
        if (shop == null) {
            // 不存在,缓存空对象,返回失败
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
            return Result.fail("店铺不存在");
        }

        // 4.2.缓存成功,返回成功
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);

        return Result.success(shop);
    }

    /**
     * 预防缓存击穿,方法一:互斥锁
     */
    private boolean tryLock(String key) {
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, "lock", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    private Result queryWithMutex(Long id) {
        // 1.从Redis查询商户信息
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            //命中(信息为有效数据),直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.success(shop);
        }

        // 3.判断是否为""(缓存空对象)
        // 此时 shopJson 只有两种可能: null → key不存在,没缓存过; "" → 缓存了的空对象
        if (shopJson != null) {
            // "" → 缓存了的空对象,返回失败
            // (不用.equals判断,是因为shopJson可能是null,报空指针异常;如果先判断是否为空,那equals就多此一举了)
            return Result.fail("店铺不存在");
        }

        // 4.查数据库,查询前加个锁,避免多个线程同时请求数据库  (预防缓存击穿)
        // 4.1.获取锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (!isLock) {
            // 未获取到锁,返回失败
            return Result.fail("获取锁失败");
        }
        Shop shop;
        try {
            // 4.3.查询数据库,判断是否存在
            shop = getById(id);
            if (shop == null) {
                // 不存在,缓存空对象,返回失败
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("店铺不存在");
            }
            // 4.4.存在,写入Redis,设置过期时间
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            // 4.5.释放锁
            unLock(lockKey);
        }

        return Result.success(shop);
    }

    /**
     * 预防缓存击穿,方法二:逻辑删除(不直接给key设置过期时间,而是用属性expireTime,所以永远能查询到缓存)
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Result queryWithLogicExpire(Long id) {
        // 1.从Redis查询商户信息
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断缓存是否命中(理论上不可能未命中,而且逻辑过期方案只会处理"缓存命中"的情况)
        if (StrUtil.isBlank(shopJson)) {
            // 未命中,说明未预热,不属于本方案处理范围,返回提示
            return Result.fail("店铺信息未预热,请稍后重试");
        }

        // 3.缓存命中,判断逻辑过期时间
        // 3.1 将数据从Json字符串类型转为Shop类型
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        // 3.2 根据expireTime判断key是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 没过期,直接返回商户信息
            return Result.success(shop);
        }

        // 4.过期,异步缓存重建
        // 4.1 获取锁,判断是否获取到锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean success = tryLock(lockKey);
        if (!success) {
            // 未获取到锁,返回旧数据
            return Result.success(shop);
        }
        // 4.2 异步线程,实现缓存重建
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                // 查询数据库
                Shop newShop = getById(id);
                // 将数据转为RedisData类型
                RedisData newRedisData = new RedisData();
                newRedisData.setData(JSONUtil.toJsonStr(newShop));
                newRedisData.setExpireTime(LocalDateTime.now().plusSeconds(RedisConstants.CACHE_SHOP_TTL));
                // 写入Redis
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(newRedisData));
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unLock(lockKey);
            }
        });
        // 4.3 返回
        return Result.success(shop);
    }

    /**
     * 逻辑过期方案 - 缓存预热
     * @param id 商铺id
     * @param expireTime 过期时间,单位秒
     */
    @Override
    public void setWithLogicExpire(Long id, Long expireTime) {
        // 查询数据库
        Shop newShop = getById(id);
        // 将数据转为RedisData类型
        RedisData newRedisData = new RedisData();
        newRedisData.setData(JSONUtil.toJsonStr(newShop));
        newRedisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(newRedisData));
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                .eq("type_id", typeId)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.success(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
            .search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
            );
        // 4.解析出id
        if (results == null) {
            return Result.success(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.success(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.success(shops);
    }
}
