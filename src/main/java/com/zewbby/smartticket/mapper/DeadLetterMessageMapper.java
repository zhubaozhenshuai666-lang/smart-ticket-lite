package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.DeadLetterMessage;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DeadLetterMessageMapper {

    int insert(DeadLetterMessage deadLetterMessage);

    DeadLetterMessage selectById(@Param("id") Long id);

    List<DeadLetterMessage> selectRecent(@Param("status") String status,
                                         @Param("limit") Integer limit);

    Long countByStatus(@Param("status") String status);

    int markRetried(@Param("id") Long id,
                    @Param("lastRetryAt") LocalDateTime lastRetryAt);

    int markIgnored(@Param("id") Long id,
                    @Param("resolvedAt") LocalDateTime resolvedAt);

    int markResolved(@Param("id") Long id,
                     @Param("resolvedAt") LocalDateTime resolvedAt);

    int markManualRetryFailed(@Param("id") Long id,
                              @Param("exceptionMessage") String exceptionMessage,
                              @Param("lastRetryAt") LocalDateTime lastRetryAt);
}
