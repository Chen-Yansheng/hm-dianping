package com.chen.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chen.dto.Result;
import com.chen.dto.UserDTO;
import com.chen.entity.Blog;
import com.chen.entity.User;
import com.chen.service.IBlogService;
import com.chen.service.IUserService;
import com.chen.utils.SystemConstants;
import com.chen.utils.ThreadLocalUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        // 获取登录用户
        UserDTO user = ThreadLocalUtils.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        blogService.save(blog);
        // 返回id
        return Result.success(blog.getId());
    }

    /**
     * 点赞博客
     * @param id 博客id
     * @return 点赞结果
     */
    @PutMapping("/like/{id}")
    public Result queryBlogLike(@PathVariable("id") Long id) {
        return blogService.queryBlogLike(id);
    }

    /**
     * 查询博客点赞列表
     * @param id 博客id
     * @return 点赞列表用户信息
     */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = ThreadLocalUtils.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.success(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

}
