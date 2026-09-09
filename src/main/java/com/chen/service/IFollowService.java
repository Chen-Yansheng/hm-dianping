package com.chen.service;

import com.chen.dto.Result;
import com.chen.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IFollowService extends IService<Follow> {

    Result follow(Long followUserId, Boolean isFollow);

    Result isFollow(Long followUserId);

    Result queryCommonFollow(Long followUserId);
}
