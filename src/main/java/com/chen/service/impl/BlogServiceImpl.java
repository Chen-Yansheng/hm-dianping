package com.chen.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chen.dto.Result;
import com.chen.dto.UserDTO;
import com.chen.entity.Blog;
import com.chen.entity.User;
import com.chen.mapper.BlogMapper;
import com.chen.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chen.service.IUserService;
import com.chen.utils.SystemConstants;
import com.chen.utils.ThreadLocalUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.chen.utils.RedisConstants.BLOG_LIKED_KEY;


@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询并设置用户信息
        // 查询并设置点赞状态
        records.forEach(blog -> {
            fillBlogUserInfo(blog);
            fillBlogLikeStatus(blog);
        });
        return Result.success(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 查询博客
        Blog blog = getById(id);
        // 查询并设置用户信息
        fillBlogUserInfo(blog);
        // 查询并设置点赞状态
        fillBlogLikeStatus(blog);
        return Result.success(blog);
    }

    private void fillBlogUserInfo(Blog blog) {
        // 1.查询博客作者
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        // 2.设置博客作者信息
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryBlogLike(Long id) {
        // 1.获取登录用户
        Long userId = ThreadLocalUtils.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if(BooleanUtil.isFalse(isMember)){
            //3.如果未点赞，可以点赞
            //3.1 数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2 保存用户到Redis的set集合
            if(isSuccess){
                stringRedisTemplate.opsForSet().add(key,userId.toString());
            }
        }else{
            //4.如果已点赞，取消点赞
            //4.1 数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //4.2 把用户从Redis的set集合移除
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key,userId.toString());
            }
        }
        return Result.success();
    }

    private void fillBlogLikeStatus(Blog blog) {
        // 1.查询当前登录用户id
        //Long userId = ThreadLocalUtils.getUser().getId();
        UserDTO user = ThreadLocalUtils.getUser();
        if (user == null) {
            // 没登录，不需要查点赞状态，直接返回
            return;
        }
        Long userId = user.getId();
        // 2.判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        Boolean isLiked = BooleanUtil.isTrue(isMember);
        // 3.设置点赞状态
        blog.setIsLike(isLiked);
    }
}
