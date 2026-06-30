package com.aschen.redis.controller;


import cn.hutool.core.util.RandomUtil;
import com.aschen.redis.dto.LoginFormDTO;
import com.aschen.redis.dto.Result;
import com.aschen.redis.dto.UserDTO;
import com.aschen.redis.entity.User;
import com.aschen.redis.entity.UserInfo;
import com.aschen.redis.service.IUserInfoService;
import com.aschen.redis.service.IUserService;
import com.aschen.redis.utils.RegexUtils;
import com.aschen.redis.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource private IUserService userService;

    @Resource private IUserInfoService userInfoService;



    //发送手机验证码
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        return userService.sendCode(phone, session);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        return userService.login(loginForm,session);
    }

    @GetMapping("/me")
    public Result me(){
        // 获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }


    /**
     * 登出功能
     * @return 无
     */

    @PostMapping("/logout")
    public Result logout(){
        // TODO 实现登出功能
        return Result.fail("功能未完成");
    }


    /**
     * 根据手机号创建用户
     * phone 手机号
     * @return 新创建的用户
     */


    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }


}
