package com.zewbby.smartticket.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 16, message = "用户名长度不能超过16个字符")
    private String username;

    @NotBlank(message = "手机号不能为空")
    @Size(max = 11, message = "手机号长度不能超过11个字符")
    private String phone;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 32, message = "密码长度必须在8到32个字符之间")
    private String password;
}
