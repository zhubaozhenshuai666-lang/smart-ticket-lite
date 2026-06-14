package com.zewbby.smartticket.service;

import com.zewbby.smartticket.domain.dto.CreateUserRequest;
import com.zewbby.smartticket.domain.dto.LoginRequest;
import com.zewbby.smartticket.domain.vo.LoginResponseVO;
import com.zewbby.smartticket.domain.vo.UserVO;

public interface AuthService {

    UserVO register(CreateUserRequest request);

    LoginResponseVO login(LoginRequest request);

    void logout(String authorizationHeader);
}
