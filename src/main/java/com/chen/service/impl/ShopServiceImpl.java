package com.chen.service.impl;

import com.chen.entity.Shop;
import com.chen.mapper.ShopMapper;
import com.chen.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

}
