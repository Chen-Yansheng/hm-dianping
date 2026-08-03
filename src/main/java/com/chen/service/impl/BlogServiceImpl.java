package com.chen.service.impl;

import com.chen.entity.Blog;
import com.chen.mapper.BlogMapper;
import com.chen.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;


@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

}
