package com.chen.service.impl;

import com.chen.entity.VoucherOrder;
import com.chen.mapper.VoucherOrderMapper;
import com.chen.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

}
