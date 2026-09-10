package com.chen.service;

import com.chen.dto.Result;
import com.chen.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    void setWithLogicExpire(Long id, Long expireTime);

    Result update(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
