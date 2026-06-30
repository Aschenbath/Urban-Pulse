package com.aschen.redis.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aschen.redis.dto.LoginFormDTO;
import com.aschen.redis.dto.Result;
import com.aschen.redis.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);
}
