package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.auth.UserContext;
import com.zewbby.smartticket.auth.PasswordPolicy;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.domain.dto.CreateUserRequest;
import com.zewbby.smartticket.domain.entity.UserAccount;
import com.zewbby.smartticket.domain.vo.UserVO;
import com.zewbby.smartticket.enums.UserStatusEnum;
import com.zewbby.smartticket.mapper.UserMapper;
import com.zewbby.smartticket.service.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UserVO createUser(CreateUserRequest request) {
        PasswordPolicy.validate(request.getUsername(), request.getPhone(), request.getPassword());
        //新建一个userAccount对象，通过请求传来的request得到的phone字段，调用mapper查询数据库获得对应的数据
        UserAccount existingUser = userMapper.selectByPhone(request.getPhone());
        //如果得到的existingUser不为空的话，证明这个手机号已经创建过了，抛异常就行
        if (existingUser != null) {
            throw new BusinessException(ErrorMessageConstant.PHONE_EXISTS);
        }
        if (userMapper.selectByUsername(request.getUsername()) != null) {
            throw new BusinessException(ErrorMessageConstant.USERNAME_EXISTS);
        }
        //获得当前时间，用于添加时间
        LocalDateTime now = LocalDateTime.now();
        //新建一个user对象
        UserAccount user = new UserAccount();
        //为user对象赋值，request中传来了phone和username
        user.setUsername(request.getUsername());
        user.setPhone(request.getPhone());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setStatus(UserStatusEnum.NORMAL.name());
        //刚刚的时间给赋上
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        //调用userMapper执行一个插入Sql语句
        userMapper.insert(user);
        //将整好的user对象转成UserVO
        return toUserVO(user);
    }

    @Override
    public UserVO getUserById(Long id) {
        // 新建一个UserAccount对象来接收调用mapper通过id查询语句返回的数据
        UserAccount user = userMapper.selectById(id);
        //如果user为空的话
        if (user == null) {
            //抛异常
            throw new BusinessException(ErrorMessageConstant.USER_NOT_FOUND);
        }
        //转UserVO
        return toUserVO(user);
    }

    @Override
    public UserVO getCurrentUser() {
        UserAccount user = getExistingUser(UserContext.requireUserId());
        if (!UserStatusEnum.isNormal(user.getStatus())) {
            throw new BusinessException(ErrorMessageConstant.ACCOUNT_UNAVAILABLE);
        }
        return toUserVO(user);
    }

    private UserAccount getExistingUser(Long id) {
        UserAccount user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorMessageConstant.USER_NOT_FOUND);
        }
        return user;
    }

    //user转UserVO的方法
    private UserVO toUserVO(UserAccount user) {
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }
}
