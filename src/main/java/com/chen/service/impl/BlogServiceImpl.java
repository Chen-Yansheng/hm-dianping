package com.chen.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chen.dto.Result;
import com.chen.dto.ScrollResult;
import com.chen.dto.UserDTO;
import com.chen.entity.Blog;
import com.chen.entity.Follow;
import com.chen.entity.User;
import com.chen.mapper.BlogMapper;
import com.chen.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chen.service.IFollowService;
import com.chen.service.IUserService;
import com.chen.utils.SystemConstants;
import com.chen.utils.ThreadLocalUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.chen.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.chen.utils.RedisConstants.FEED_KEY;


@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

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
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            //3.如果未点赞，可以点赞
            //3.1 数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2 保存用户到Redis的zset集合 zadd key value score
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(), System.currentTimeMillis());
            }
        }else{
            //4.如果已点赞，取消点赞
            //4.1 数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //4.2 把用户从Redis的set集合移除
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
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
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        Boolean isLiked = score != null;
        // 3.设置点赞状态
        blog.setIsLike(isLiked);
    }


    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.success(Collections.emptyList());
        }
        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4.返回
        return Result.success(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        Long authorId = ThreadLocalUtils.getUser().getId();
        // 2.设置博客作者
        blog.setUserId(authorId);
        // 3.保存博客
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("保存博客失败");
        }
        // 4.推送博客到粉丝    select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", authorId).list();
        for(Follow follow : follows){
            Long fanId = follow.getUserId();
            String key = FEED_KEY + fanId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 5.返回id
        return Result.success(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = ThreadLocalUtils.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
            .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.success();
        }
        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;   // 本次结果中最早的时间戳
        int os = 1;     // 偏移量（处理同一时间戳）
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 例5 4 4 2 2
            // 4.1.获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳）
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;   // 同时间戳，偏移量 +1
            }else{
                minTime = time;
                os = 1; // 遇到更早的时间戳，重置偏移量
            }
        }
        // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            fillBlogUserInfo(blog);
            // 5.2.查询blog是否被点赞
            fillBlogLikeStatus(blog);
        }

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.success(r);
    }
}
