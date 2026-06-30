package com.aschen.redis.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.lang.generator.UUIDGenerator;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aschen.redis.dto.LoginFormDTO;
import com.aschen.redis.dto.Result;
import com.aschen.redis.dto.UserDTO;
import com.aschen.redis.entity.User;
import com.aschen.redis.mapper.UserMapper;
import com.aschen.redis.service.IUserService;
import com.aschen.redis.utils.RegexUtils;
import com.aschen.redis.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.el.parser.Token;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.aschen.redis.utils.RedisConstants.*;
import static com.aschen.redis.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 *
 */


@Slf4j
@Service //此处user类有mybatis-plus的注解,因此不用写mapper层,mapper层继承了mybatis-plus的basemapper
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    public StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)) return Result.fail("手机号格式错误！");

        //2.生成验证码
        String code =RandomUtil.randomNumbers(6);

        //3.保存验证码到redis //加业务逻辑前缀
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.HOURS);

        //4.发送验证码
        log.info("发送短信验证码成功，验证码：{}",code);
        System.out.println("LOGIN_CODE phone=" + phone + " code=" + code);

        return Result.ok();
    }

    public User createUserWithPhone(String phone) {
        //创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }


    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }

        //2.从session中获得验证码---->正确的验证码 -->redis
        String cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();//用户输入的验证码
        if(cachecode == null || !cachecode.equals(code)) {
            return Result.fail("验证码错误！");
        }

        //3.验证码一致，查询用户
        User user = query().eq("phone",phone).one();

        //初次登陆注册
        if(user==null) user = createUserWithPhone(phone);

        //3.保存用户信息到session--->redis

        //3.1 将key转化为uuid,防止黑客通过sessionid获得用户信息
        String hashtoken = UUID.randomUUID().toString(true);

        //3.2 将用户对象转为hash存储
        //注意这里的map的key和value都必须是string类型,否则报错--->但是我们的userdto有long类型
        //因为redis的hash类型的key和value都是string类型
        //所以不能直接存储对象,必须转为map
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userinfo = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));//将所有的值都转为string


        //3.3 存储
        String prefix = LOGIN_USER_KEY;
        stringRedisTemplate.opsForHash().putAll(prefix+hashtoken,userinfo);

        //设置有效期 , 防止爆满
        stringRedisTemplate.expire(prefix+hashtoken,LOGIN_USER_TTL,TimeUnit.MINUTES);

        //但是不能简单粗暴的归结为
        //3.4 将hashtoken返回给前端,前端会保存数据并且
        return Result.ok(hashtoken);
    }




}
