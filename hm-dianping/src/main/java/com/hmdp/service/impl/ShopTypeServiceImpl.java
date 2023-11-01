package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {

        String key= CACHE_SHOPTYPE_KEY;
        //查找缓存
        List<String> cacheList = stringRedisTemplate.opsForList().range(key, 0, -1);
        List<ShopType> typeList = new ArrayList<>();
        //命中返回
        if (cacheList!=null&&!cacheList.isEmpty()){

            //反序列化
            for (String item:cacheList){
                ShopType shopType = JSONUtil.toBean(item, ShopType.class);
                typeList.add(shopType);
            }
            return Result.ok(typeList);
        }
        //未命中查找数据库
        typeList = query().orderByAsc("sort").list();
        //未找到报错
        if (typeList==null)
        {
            return Result.fail("查找不到类型列表");
        }
        //命中更新缓存，返回数据
        for (ShopType item:typeList){
            stringRedisTemplate.opsForList().rightPush(key,JSONUtil.toJsonStr(item));
        }

        return Result.ok(typeList);
    }
}
