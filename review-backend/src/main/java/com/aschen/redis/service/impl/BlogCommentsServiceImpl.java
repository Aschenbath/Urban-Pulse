package com.aschen.redis.service.impl;

import com.aschen.redis.entity.BlogComments;
import com.aschen.redis.mapper.BlogCommentsMapper;
import com.aschen.redis.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
