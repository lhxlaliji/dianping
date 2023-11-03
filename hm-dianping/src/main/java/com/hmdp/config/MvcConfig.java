package com.hmdp.config;

import com.hmdp.utils.LoginInterseptor;
import com.hmdp.utils.ReflashInterseptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
@EnableAspectJAutoProxy(exposeProxy = true)
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        /*
        * 默认先注册先执行
        * 谁的order小谁的优先级高
        * */
        registry.addInterceptor(new LoginInterseptor())
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                ).order(1);

        registry.addInterceptor(new ReflashInterseptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
