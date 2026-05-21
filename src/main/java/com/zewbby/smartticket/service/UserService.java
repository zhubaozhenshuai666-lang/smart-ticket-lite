package com.zewbby.smartticket.service;

import com.zewbby.smartticket.domain.dto.CreateUserRequest;
import com.zewbby.smartticket.domain.vo.UserVO;

public interface UserService {

    UserVO createUser(CreateUserRequest request);

    UserVO getUserById(Long id);
}
