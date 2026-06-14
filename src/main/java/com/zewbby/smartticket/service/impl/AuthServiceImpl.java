package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.auth.JwtTokenProvider;
import com.zewbby.smartticket.auth.JwtUserClaims;
import com.zewbby.smartticket.auth.LoginFailureService;
import com.zewbby.smartticket.auth.PasswordPolicy;
import com.zewbby.smartticket.auth.TokenBlacklistService;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.domain.dto.CreateUserRequest;
import com.zewbby.smartticket.domain.dto.LoginRequest;
import com.zewbby.smartticket.domain.entity.UserAccount;
import com.zewbby.smartticket.domain.vo.LoginResponseVO;
import com.zewbby.smartticket.domain.vo.UserVO;
import com.zewbby.smartticket.enums.UserRoleEnum;
import com.zewbby.smartticket.enums.UserStatusEnum;
import com.zewbby.smartticket.mapper.UserMapper;
import com.zewbby.smartticket.service.AuthService;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String BEARER_PREFIX = "Bearer ";

    private final UserMapper userMapper;

    private final PasswordEncoder passwordEncoder;

    private final JwtTokenProvider jwtTokenProvider;

    private final TokenBlacklistService tokenBlacklistService;

    private final LoginFailureService loginFailureService;

    public AuthServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           JwtTokenProvider jwtTokenProvider,
                           TokenBlacklistService tokenBlacklistService,
                           LoginFailureService loginFailureService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistService = tokenBlacklistService;
        this.loginFailureService = loginFailureService;
    }

    @Override
    public UserVO register(CreateUserRequest request) {
        //校验密码是否符合规则
        PasswordPolicy.validate(request.getUsername(), request.getPhone(), request.getPassword());
        //校验用户是否唯一
        ensureUserUnique(request.getUsername(), request.getPhone());

        //创建用户对象并赋值
        LocalDateTime now = LocalDateTime.now();
        UserAccount user = new UserAccount();
        user.setUsername(request.getUsername());
        user.setPhone(request.getPhone());
        //加密
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        user.setStatus(UserStatusEnum.NORMAL.name());
        /*
         * 注册入口不信任前端传角色。普通注册用户只能是 USER，ADMIN/OPERATOR 必须由后台或初始化数据授予。
         */
        user.setRoleCode(UserRoleEnum.USER.getCode());
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        //把用户插入到用户表中
        userMapper.insert(user);
        return toUserVO(user);
    }

    /**
     * 登陆模块
     * @param request
     * @return
     */
    @Override
    public LoginResponseVO login(LoginRequest request) {
        //取一个唯一标识检查一下是否多次登陆被锁
        String loginName = request.getPhone();
        loginFailureService.checkLoginAllowed(loginName);

        UserAccount user = userMapper.selectByPhone(loginName);
        if (user == null) {
            //记录一次失败
            loginFailureService.recordFailure(loginName);
            throw new BusinessException(ErrorMessageConstant.ACCOUNT_OR_PASSWORD_ERROR);
        }
        // 用户状态不是normal，抛用户不可用异常
        if (!UserStatusEnum.isNormal(user.getStatus())) {
            throw new BusinessException(ErrorMessageConstant.ACCOUNT_UNAVAILABLE);
        }
        //校验密码是否正确
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginFailureService.recordFailure(loginName);
            throw new BusinessException(ErrorMessageConstant.ACCOUNT_OR_PASSWORD_ERROR);
        }
        //登陆成功后消除掉redis记录的失败次数
        loginFailureService.clearFailure(loginName);

        //生成token
        String token = jwtTokenProvider.generateToken(user);
        //token的过期时间：参数一获取过期的时间，参数二是设置使用操作系统当前的默认时间
        LocalDateTime expireAt = LocalDateTime.ofInstant(jwtTokenProvider.getExpireAt(), ZoneId.systemDefault());
        return new LoginResponseVO(user.getId(), user.getUsername(), user.getPhone(),
                UserRoleEnum.normalize(user.getRoleCode()).getCode(), token, expireAt);
    }

    /**
     * 退出登陆
     * @param authorizationHeader
     */
    @Override
    public void logout(String authorizationHeader) {
        //提取与解析 Token
        JwtUserClaims claims = jwtTokenProvider.parseToken(resolveBearerToken(authorizationHeader));
        //使token失效
        tokenBlacklistService.blacklist(claims);
    }

    /**
     * 保证用户唯一
     * @param username
     * @param phone
     */
    private void ensureUserUnique(String username, String phone) {
        if (userMapper.selectByPhone(phone) != null) {
            throw new BusinessException(ErrorMessageConstant.PHONE_EXISTS);
        }
        if (userMapper.selectByUsername(username) != null) {
            throw new BusinessException(ErrorMessageConstant.USERNAME_EXISTS);
        }
    }

    private UserVO toUserVO(UserAccount user) {
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    private String resolveBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new BusinessException(401, ErrorMessageConstant.UNAUTHORIZED);
        }
        if (!authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(401, ErrorMessageConstant.TOKEN_INVALID);
        }
        return authorizationHeader.substring(BEARER_PREFIX.length()).trim();
    }
}
