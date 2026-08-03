package com.chen.service.impl;

import com.chen.entity.Follow;
import com.chen.mapper.FollowMapper;
import com.chen.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;


@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

}
