package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.auth.UserContext;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.domain.dto.AdjustStockRequest;
import com.zewbby.smartticket.domain.dto.ConfirmStockAdjustmentRequest;
import com.zewbby.smartticket.domain.dto.CreateStockAdjustmentRequest;
import com.zewbby.smartticket.domain.entity.StockAdjustmentRecord;
import com.zewbby.smartticket.domain.vo.AdminStockVO;
import com.zewbby.smartticket.domain.vo.StockAdjustmentRecordVO;
import com.zewbby.smartticket.enums.StockAdjustmentStatusEnum;
import com.zewbby.smartticket.mapper.StockAdjustmentRecordMapper;
import com.zewbby.smartticket.service.AdminBusinessService;
import com.zewbby.smartticket.service.StockAdjustmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class StockAdjustmentServiceImpl implements StockAdjustmentService {

    private static final int DEFAULT_LIMIT = 50;

    private static final int MAX_LIMIT = 200;

    private final StockAdjustmentRecordMapper stockAdjustmentRecordMapper;

    private final AdminBusinessService adminBusinessService;

    public StockAdjustmentServiceImpl(StockAdjustmentRecordMapper stockAdjustmentRecordMapper,
                                      AdminBusinessService adminBusinessService) {
        this.stockAdjustmentRecordMapper = stockAdjustmentRecordMapper;
        this.adminBusinessService = adminBusinessService;
    }

    /**
     * 创建库存调整申请，但不立刻修改库存。
     *
     * ADMIN 直接调库存调整接口在学习项目里看起来方便，但真实后台里非常危险：误填数量、误选票档、
     * 或者复制错请求都可能立刻影响售卖入口。这里先生成一条 PENDING_CONFIRM 记录和 confirmToken，
     * 把“申请”和“真正执行”拆开，让操作者至少有一次检查票档、数量和 reason 的机会。
     *
     * request.ticketCategoryId 表示要调整的票档；adjustQuantity 是增量，不是覆盖值；reason 是事后追责和复盘依据。
     * 方法成功只代表申请已记录，失败代表参数或当前库存视图不允许创建申请；成功不会改变 MySQL/Redis 库存。
     */
    @Override
    @Transactional
    public StockAdjustmentRecordVO createAdjustment(CreateStockAdjustmentRequest request) {
        validateCreateRequest(request);
        Long operatorUserId = UserContext.requireUserId();
        adminBusinessService.getStock(request.getTicketCategoryId());

        LocalDateTime now = LocalDateTime.now();
        StockAdjustmentRecord record = new StockAdjustmentRecord();
        record.setTicketCategoryId(request.getTicketCategoryId());
        record.setOperatorUserId(operatorUserId);
        record.setAdjustQuantity(request.getAdjustQuantity());
        record.setReason(request.getReason().trim());
        record.setStatus(StockAdjustmentStatusEnum.PENDING_CONFIRM.getCode());
        record.setConfirmToken(generateConfirmToken());
        record.setRollbackAvailable(false);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        stockAdjustmentRecordMapper.insert(record);
        return StockAdjustmentRecordVO.from(record);
    }

    /**
     * 二次确认并真正执行库存调整。
     *
     * 不能只在 Java 里先查 status 再 if 判断，因为两个后台请求可能同时确认同一条记录。
     * markConfirmed 使用 SQL 条件更新抢占确认权：只有 PENDING_CONFIRM 且 confirmToken 正确时才会变为 CONFIRMED。
     * 抢占成功的线程才允许执行 AdminBusinessService.adjustStock，从而避免重复 confirm 重复增减库存。
     *
     * before/after 记录用于解释“这次调整到底把库存从多少改到了多少”。回滚不能盲目自动执行，
     * 因为确认后可能已经有新的下单、预扣或支付流转；所以这里只生成反向调整建议记录，真正回滚仍需再次确认。
     */
    @Override
    @Transactional
    public StockAdjustmentRecordVO confirmAdjustment(Long id, ConfirmStockAdjustmentRequest request) {
        if (id == null) {
            throw new BusinessException("库存调整记录不存在");
        }
        if (request == null || !StringUtils.hasText(request.getConfirmToken())) {
            throw new BusinessException("确认token不能为空");
        }

        StockAdjustmentRecord current = requireRecord(id);
        if (StockAdjustmentStatusEnum.isApplied(current.getStatus())) {
            return StockAdjustmentRecordVO.from(current);
        }
        if (StockAdjustmentStatusEnum.FAILED.getCode().equals(current.getStatus())) {
            throw new BusinessException("库存调整记录已失败，不能确认");
        }

        int updated = stockAdjustmentRecordMapper.markConfirmed(
                id,
                request.getConfirmToken(),
                LocalDateTime.now()
        );
        if (updated == 0) {
            StockAdjustmentRecord latest = requireRecord(id);
            if (StockAdjustmentStatusEnum.isApplied(latest.getStatus())) {
                return StockAdjustmentRecordVO.from(latest);
            }
            throw new BusinessException("库存调整确认失败，请检查确认token或记录状态");
        }

        StockAdjustmentRecord confirmed = requireRecord(id);
        AdminStockVO before = adminBusinessService.getStock(confirmed.getTicketCategoryId());
        AdjustStockRequest adjustRequest = new AdjustStockRequest();
        adjustRequest.setAdjustQuantity(confirmed.getAdjustQuantity());
        adjustRequest.setReason(confirmed.getReason());
        AdminStockVO after = adminBusinessService.adjustStock(confirmed.getTicketCategoryId(), adjustRequest);

        StockAdjustmentRecord rollback = createRollbackSuggestion(confirmed, LocalDateTime.now());
        stockAdjustmentRecordMapper.insert(rollback);
        int applied = stockAdjustmentRecordMapper.markApplied(
                confirmed.getId(),
                before.getMysqlAvailableStock(),
                after.getMysqlAvailableStock(),
                before.getRedisAvailableStock(),
                after.getRedisAvailableStock(),
                rollback.getId()
        );
        if (applied == 0) {
            throw new BusinessException("库存调整记录落库失败");
        }
        return StockAdjustmentRecordVO.from(requireRecord(id));
    }

    @Override
    public List<StockAdjustmentRecordVO> listRecent(Integer limit) {
        return stockAdjustmentRecordMapper.selectRecent(normalizeLimit(limit))
                .stream()
                .map(StockAdjustmentRecordVO::from)
                .toList();
    }

    private void validateCreateRequest(CreateStockAdjustmentRequest request) {
        if (request == null || request.getTicketCategoryId() == null) {
            throw new BusinessException("票档ID不能为空");
        }
        if (request.getAdjustQuantity() == null || request.getAdjustQuantity() == 0) {
            throw new BusinessException("库存调整数量不能为0");
        }
        if (!StringUtils.hasText(request.getReason())) {
            throw new BusinessException("库存调整原因不能为空");
        }
    }

    private StockAdjustmentRecord requireRecord(Long id) {
        StockAdjustmentRecord record = stockAdjustmentRecordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException("库存调整记录不存在");
        }
        return record;
    }

    private StockAdjustmentRecord createRollbackSuggestion(StockAdjustmentRecord source, LocalDateTime now) {
        StockAdjustmentRecord rollback = new StockAdjustmentRecord();
        rollback.setTicketCategoryId(source.getTicketCategoryId());
        rollback.setOperatorUserId(source.getOperatorUserId());
        rollback.setAdjustQuantity(-source.getAdjustQuantity());
        rollback.setReason("回滚库存调整记录#" + source.getId() + ": " + source.getReason());
        rollback.setStatus(StockAdjustmentStatusEnum.PENDING_CONFIRM.getCode());
        rollback.setConfirmToken(generateConfirmToken());
        rollback.setRollbackAvailable(false);
        rollback.setCreatedAt(now);
        rollback.setUpdatedAt(now);
        return rollback;
    }

    private String generateConfirmToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
