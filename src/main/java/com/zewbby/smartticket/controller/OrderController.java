package com.zewbby.smartticket.controller;

import com.zewbby.smartticket.auth.UserContext;
import com.zewbby.smartticket.common.ApiResponse;
import com.zewbby.smartticket.domain.dto.CreateOrderRequest;
import com.zewbby.smartticket.domain.vo.IdempotencyTokenVO;
import com.zewbby.smartticket.domain.vo.OrderRequestVO;
import com.zewbby.smartticket.domain.vo.OrderVO;
import com.zewbby.smartticket.domain.vo.WaitingRoomStatusVO;
import com.zewbby.smartticket.idempotency.IdempotencyTokenService;
import com.zewbby.smartticket.ratelimit.ClientIpResolver;
import com.zewbby.smartticket.service.OrderService;
import com.zewbby.smartticket.service.WaitingRoomService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orderService;

    private final IdempotencyTokenService idempotencyTokenService;

    private final ClientIpResolver clientIpResolver;

    private final WaitingRoomService waitingRoomService;

    public OrderController(OrderService orderService,
                           IdempotencyTokenService idempotencyTokenService,
                           ClientIpResolver clientIpResolver,
                           WaitingRoomService waitingRoomService) {
        this.orderService = orderService;
        this.idempotencyTokenService = idempotencyTokenService;
        this.clientIpResolver = clientIpResolver;
        this.waitingRoomService = waitingRoomService;
    }

    @GetMapping("/orders/idempotency-token")
    public ApiResponse<IdempotencyTokenVO> generateOrderIdempotencyToken() {
        return ApiResponse.successZero(idempotencyTokenService.generateOrderToken(UserContext.requireUserId()));
    }

    @GetMapping("/orders/idempotency-tokens")
    public ApiResponse<List<IdempotencyTokenVO>> generateOrderIdempotencyTokens(
            @RequestParam(value = "count", required = false) Integer count) {
        return ApiResponse.successZero(idempotencyTokenService.generateOrderTokens(UserContext.requireUserId(), count));
    }

    @PostMapping("/waiting-room/queue")
    public ApiResponse<WaitingRoomStatusVO> enterWaitingRoomQueue(@RequestParam Long ticketCategoryId) {
        return ApiResponse.successZero(waitingRoomService.enterQueue(UserContext.requireUserId(), ticketCategoryId));
    }

    @GetMapping("/waiting-room/status")
    public ApiResponse<WaitingRoomStatusVO> getWaitingRoomStatus(@RequestParam Long ticketCategoryId) {
        return ApiResponse.successZero(waitingRoomService.getQueueStatus(UserContext.requireUserId(), ticketCategoryId));
    }

    /**
     * @deprecated 同步下单只保留为本地调试和历史兼容入口。
     *
     * 高并发抢票不能同时对外宣传同步/异步两套主链路，否则入口限流、Redis 预扣、Outbox 可靠消息和消费者幂等
     * 会被拆成两套治理边界，面试和生产排障都会说不清。用户侧抢票主路径请使用 /api/orders/async。
     */
    @Deprecated
    @PostMapping("/orders")
    public ApiResponse<OrderVO> createOrder(@Valid @RequestBody CreateOrderRequest request,
                                            HttpServletRequest httpServletRequest) {
        return ApiResponse.success(orderService.createOrder(request, clientIpResolver.resolve(httpServletRequest)));
    }

    /**
     * 异步创建订单请求，只返回请求ID，不在接口线程中扣库存或创建正式订单
     * @param request
     * @return
     */
    @PostMapping("/orders/async")
    public ApiResponse<OrderRequestVO> submitAsyncOrder(@Valid @RequestBody CreateOrderRequest request,
                                                        HttpServletRequest httpServletRequest) {
        return ApiResponse.successZero(orderService.submitAsyncOrder(request, clientIpResolver.resolve(httpServletRequest)));
    }

    /**
     * 查询异步下单请求处理结果
     * @param requestId
     * @return
     */
    @GetMapping("/order-requests/{requestId}")
    public ApiResponse<OrderRequestVO> getOrderRequestResult(@PathVariable String requestId) {
        return ApiResponse.successZero(orderService.getOrderRequestResult(requestId));
    }

    /**
     * 根据id来查询订单
     * @param id
     * @return
     */
    @GetMapping("/orders/{id}")
    public ApiResponse<OrderVO> getOrderById(@PathVariable Long id) {
        return ApiResponse.success(orderService.getOrderById(id));
    }

    /**
     * 列出当前登录用户订单
     */
    @GetMapping("/users/me/orders")
    public ApiResponse<List<OrderVO>> listCurrentUserOrders() {
        return ApiResponse.success(orderService.listCurrentUserOrders());
    }

    /**
     * @deprecated 兼容旧路径，忽略 path 中的 userId，只返回当前登录用户订单。
     */
    @Deprecated
    @GetMapping("/users/{userId}/orders")
    public ApiResponse<List<OrderVO>> listUserOrders(@PathVariable Long userId) {
        return ApiResponse.success(orderService.listCurrentUserOrders());
    }

    /**
     * 根据order.id取消订单
     * @param id
     * @return
     */
    @PostMapping("/orders/{id}/cancel")
    public ApiResponse<OrderVO> cancelOrder(@PathVariable Long id) {
        return ApiResponse.success(orderService.cancelOrder(id));
    }

    /**
     * 根据order.id将对应订单设为已支付
     * @param id
     * @return
     */
    @PostMapping("/orders/{id}/pay")
    public ApiResponse<OrderVO> payOrder(@PathVariable Long id) {
        return ApiResponse.success(orderService.payOrder(id));
    }
}
