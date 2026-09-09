package com.chen.controller;


import com.chen.dto.Result;
import com.chen.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    // 关注或取消关注
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    // 查询是否关注
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
          return followService.isFollow(followUserId);
    }

    // 查询共同关注用户列表
    @GetMapping("/common/{id}")
    public Result queryCommonFollow(@PathVariable("id") Long followUserId) {
        return followService.queryCommonFollow(followUserId);
    }

}
