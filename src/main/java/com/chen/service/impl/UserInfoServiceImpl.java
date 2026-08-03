package com.chen.service.impl;

import com.chen.entity.UserInfo;
import com.chen.mapper.UserInfoMapper;
import com.chen.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
