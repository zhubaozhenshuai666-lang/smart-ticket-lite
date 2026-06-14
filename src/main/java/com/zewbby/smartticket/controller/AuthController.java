package com.zewbby.smartticket.controller;

import com.zewbby.smartticket.common.ApiResponse;
import com.zewbby.smartticket.domain.dto.CreateUserRequest;
import com.zewbby.smartticket.domain.dto.LoginRequest;
import com.zewbby.smartticket.domain.vo.LoginResponseVO;
import com.zewbby.smartticket.domain.vo.UserVO;
import com.zewbby.smartticket.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 用户注册
     * @param request
     * @return
     */
    @PostMapping("/register")
    public ApiResponse<UserVO> register(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    /**
     * 用户登陆
     * @param request
     * @return
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponseVO> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        authService.logout(request.getHeader("Authorization"));
        return ApiResponse.success();
    }
}
