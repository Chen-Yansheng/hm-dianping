package com.chen.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // ThreadLocal中是否有用户,没有说明未登录,拦截; 有就放行
        if (ThreadLocalUtils.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
