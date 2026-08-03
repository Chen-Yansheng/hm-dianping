package com.chen.service.impl;

import com.chen.entity.BlogComments;
import com.chen.mapper.BlogCommentsMapper;
import com.chen.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;


@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
