package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(long id) {
        //缓存穿透
        /*Shop shop = queryWithThrough(id);*/
        //缓存击穿
        Shop shop =queryWithLock(id);

        //返回
        return Result.ok(shop);
    }

    /*
    * 缓存击穿
    * */
    public Shop queryWithLock(long id){
        String key = CACHE_SHOP_KEY+id;
        //从redis中判断是否有缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //有的话直接返回
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的值是否是空值
        if (shopJson != null) {
            //返回一个错误信息
            return null;
        }


        //实现缓存重构
        String lock = LOCK_SHOP_KEY+id;
        Shop shop = null;
        try {

            if (!tryLock(lock)){
                Thread.sleep(50);
                return queryWithLock(id);
            }


            //如果获得锁
            //二次检查 查看是否有缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
            //有的话直接返回
            if (StrUtil.isNotBlank(shopJson)){
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            //没有就从mysql读取
            shop = getById(id);
            //不存在就报错
            if (shop==null){
                //缓存空值,解决缓存穿透
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);

                return null;
            }
            //存入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }
        catch (Exception e){
            throw new RuntimeException(e);
        }
        finally {
            freeLock(lock);
        }


        //返回
        return shop;
    }


    /*
    * 缓存穿透
    * */
    public Shop queryWithThrough(long id){
        String key = CACHE_SHOP_KEY+id;
        //从redis中判断是否有缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //有的话直接返回
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson!=null){
            return null;
        }

        //没有就从mysql读取
        Shop shop = getById(id);
        //不存在就报错
        if (shop==null){
            //缓存空值,解决缓存穿透
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }
        //存入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回
        return shop;
    }

    /*
    * 解决缓存击穿
    * */
    public boolean tryLock(String key){
        /*
        * 使用redis setEX 来实现互斥锁
        * */
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }

    public void freeLock(String key){
        stringRedisTemplate.delete(key);
    }

    /*
    * 保证操作的原子性
    * */
    @Override
    @Transactional
    public Result updateShop(Shop shop) {

        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }
}
