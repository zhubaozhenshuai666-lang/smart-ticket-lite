package com.zewbby.smartticket.service;

import com.zewbby.smartticket.common.BusinessException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

@Service
public class BucketRouteService {

    /**
     * 根据 requestId 稳定路由到库存 bucket。
     *
     * 热点票档如果所有请求都打到一个 Redis Key 和一行 MySQL 库存，会形成热 key 和单行锁竞争。
     * bucket 分片的前提是同一个业务请求必须稳定命中同一个初始 bucket：预扣、消费者落库、失败补偿
     * 都要围绕同一个 bucketNo 执行，不能每次随机，否则 release 时可能把库存释放到错误桶。
     */
    public int route(String requestId, int bucketCount) {
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException("requestId不能为空");
        }
        if (bucketCount <= 0) {
            throw new BusinessException("bucket数量必须大于0");
        }
        CRC32 crc32 = new CRC32();
        crc32.update(requestId.getBytes(StandardCharsets.UTF_8));
        long positiveHash = crc32.getValue() & 0x7fffffffL;
        return (int) (positiveHash % bucketCount);
    }
}
