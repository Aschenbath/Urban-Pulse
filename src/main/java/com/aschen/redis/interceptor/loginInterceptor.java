package com.aschen.redis.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.aschen.redis.dto.UserDTO;
import com.aschen.redis.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.aschen.redis.utils.RedisConstants.LOGIN_USER_KEY;
import static com.aschen.redis.utils.RedisConstants.LOGIN_USER_TTL;


public class loginInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;
    public loginInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        /*//1.get hashtoken from header and check
        String hashtoken = request.getHeader("authorization");
        if(StrUtil.isBlank(hashtoken)){
            response.setStatus(401);
            return false;
        }


        //2.get user from redis
        Map<Object,Object> userdtoInfo = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY+hashtoken);
        if (userdtoInfo.isEmpty()) {
            response.setStatus(401);
            return false;
        }

        //3.turn hashmap to userDTO
        UserDTO userDTO = BeanUtil.mapToBean(userdtoInfo, UserDTO.class, false);

        //4.save userDTO to threadlocal
        UserHolder.saveUser(userDTO);

        //5.refresh token time
        stringRedisTemplate.expire(LOGIN_USER_KEY+hashtoken,LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;*/

        // 1.判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser() == null) {
            // 没有，需要拦截，设置状态码
            response.setStatus(401);
            // 拦截
            return false;
        }
        // 有用户，则放行
        return true;
    }

}




