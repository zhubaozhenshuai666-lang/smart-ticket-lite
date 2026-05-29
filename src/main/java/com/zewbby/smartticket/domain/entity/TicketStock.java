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
@TableName("ticket_stock")
public class TicketStock {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketCategoryId;

    private Integer totalStock;

    private Integer availableStock;

    private Integer lockedStock;

    private Integer soldStock;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
