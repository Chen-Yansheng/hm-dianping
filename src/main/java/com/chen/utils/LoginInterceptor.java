package com.chen.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.chen.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    // 实现登录拦截功能.1 拦截器
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.从请求头中获取token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            response.setStatus(401);
            return false;
        }

        // 2.根据token获取Redis中的用户信息
        String key = RedisConstants.LOGIN_USER_KEY + token;
        String userDTOStr = stringRedisTemplate.opsForValue().get(key);
        // 3.判断用户信息是否为空
        if (userDTOStr == null) {
            // 4.空,拦截,返回401状态码  (common.js中写了 如果返回401状态码就跳转登录界面)
            response.setStatus(401);
            return false;
        }

        // 5.将userDTOStr转换为UserDTO对象
        UserDTO userDTO = JSONUtil.toBean(userDTOStr, UserDTO.class);

        // 6.保存用户信息到threadLocal;
        ThreadLocalUtils.saveUser(userDTO);

        // 7.刷新token的过期时间
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);

        // 8.放行
        return true;
    }

    @Override
    // 实现登录拦截功能.3 从threadLocal中移除用户信息
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        ThreadLocalUtils.removeUser();
    }
}
