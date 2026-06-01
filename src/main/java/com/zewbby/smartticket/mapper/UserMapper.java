package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.UserAccount;

public interface UserMapper {

    int insert(UserAccount user);

    UserAccount selectById(Long id);

    UserAccount selectByPhone(String phone);

    UserAccount selectByUsername(String username);
}
