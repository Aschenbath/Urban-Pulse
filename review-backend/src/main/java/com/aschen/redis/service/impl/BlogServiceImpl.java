package com.aschen.redis.service.impl;

import com.aschen.redis.entity.Blog;
import com.aschen.redis.mapper.BlogMapper;
import com.aschen.redis.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

}
