package com.chen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.chen.dto.LoginFormDTO;
import com.chen.dto.Result;
import com.chen.entity.User;

import javax.servlet.http.HttpSession;

public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);
}
