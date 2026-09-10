package com.chen.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chen.dto.LoginFormDTO;
import com.chen.dto.Result;
import com.chen.dto.UserDTO;
import com.chen.entity.User;
import com.chen.mapper.UserMapper;
import com.chen.service.IUserService;
import com.chen.utils.RedisConstants;
import com.chen.utils.SystemConstants;
import com.chen.utils.ThreadLocalUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.chen.utils.RegexUtils;

import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.chen.utils.RedisConstants.USER_SIGN_KEY;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号格式
        if (phone == null || RegexUtils.isPhoneInvalid(phone)) {
            // 2.不合法,返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 3.合法,生成验证码
        String code = RandomUtil.randomString(6);

        // 4.发送验证码并保存到redis, 过期时间为2分钟
        //...发送业务
        log.debug("已发送验证码到手机号: {}, 验证码: {}", phone, code);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5.返回结果
        return Result.success();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        // 是否为空, 格式是否正确
        if (phone == null || RegexUtils.isPhoneInvalid(phone)) {
            // 2.不合法,返回错误信息
            return Result.fail("手机号格式错误");
        }

        // 3.校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code= loginForm.getCode();
        // 传来的验证码是否为空, 生成的验证码是否过期/出错, 是否一致
        if (cacheCode == null || !cacheCode.equals(code)) {
            // 不合法,返回错误信息
            return Result.fail("验证码错误");
        }

        // 4.校验完后立即删除验证码,防止重复使用
        stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + phone);

        // 5.判断用户是否存在
        User user = query().eq("phone", phone).one();
        if(user == null){
            // 6.不存在,添加用户到数据库
            user =  createUserWithPhone(phone);
        }

        // 7.生成登录凭证token,保存用户信息到redis,返回token并放行(将user脱敏处理为UserDTO,避免泄露 phone,password等敏感信息)
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 转换为JSON字符串存储
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_USER_KEY + token, JSONUtil.toJsonStr(userDTO), RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.success(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // user.setCreateTime();
        save(user);
        return user;
    }

    @Override
    public Result sign() {
        // 1.获取当前登录用户id
        Long userId = ThreadLocalUtils.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.写入Redis SETBIT key offset 1
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.success();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户id
        Long userId = ThreadLocalUtils.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取本月截止今天的签到数据，返回的是一个十进制数
        int dayOfMonth = now.getDayOfMonth();
        List<Long> signData = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(signData == null || signData.isEmpty()){
            // 没有任何签到结果
            return Result.success(0);
        }
        Long num = signData.get(0);
        if (num == null || num == 0) {
            return Result.success(0);
        }
        // 5.循环遍历签到数据，统计1的个数，即签到次数
        // 签到数据与1进行与操作，判断是否为1，再右移。
        int signCount = 0;
        while ((num & 1) == 1) {
            signCount++;
            num >>>= 1;
        }
        return Result.success(signCount);
    }
}
