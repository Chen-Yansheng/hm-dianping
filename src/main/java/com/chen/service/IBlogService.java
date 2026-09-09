package com.chen.service;

import com.chen.dto.Result;
import com.chen.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IBlogService extends IService<Blog> {

    Result queryHotBlog(Integer current);

    Result queryBlogById(Long id);

    Result queryBlogLike(Long id);
}
