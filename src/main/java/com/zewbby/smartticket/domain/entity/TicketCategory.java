package com.zewbby.smartticket.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("ticket_category")
public class TicketCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private String categoryName;

    private BigDecimal price;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public TicketCategory(Long id,
                          Long sessionId,
                          String categoryName,
                          BigDecimal price,
                          LocalDateTime createdAt,
                          LocalDateTime updatedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.categoryName = categoryName;
        this.price = price;
        this.status = "PUBLISHED";
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
