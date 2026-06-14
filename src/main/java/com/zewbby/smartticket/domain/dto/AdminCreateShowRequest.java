package com.zewbby.smartticket.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminCreateShowRequest {

    @NotBlank(message = "演出标题不能为空")
    @Size(max = 128, message = "演出标题长度不能超过128个字符")
    private String title;

    @NotBlank(message = "艺人不能为空")
    @Size(max = 128, message = "艺人长度不能超过128个字符")
    private String artist;

    @NotNull(message = "场馆不能为空")
    private Long venueId;

    private String description;
}
