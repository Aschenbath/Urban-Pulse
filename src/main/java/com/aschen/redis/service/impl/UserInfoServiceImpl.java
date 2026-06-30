package com.aschen.redis.service.impl;

import com.aschen.redis.entity.UserInfo;
import com.aschen.redis.mapper.UserInfoMapper;
import com.aschen.redis.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @since 2021-12-24
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
