package com.zewbby.smartticket.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("venue")
public class Venue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String city;

    private String address;

    private Integer capacity;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
