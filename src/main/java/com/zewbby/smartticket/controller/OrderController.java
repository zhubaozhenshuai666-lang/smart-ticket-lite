package com.zewbby.smartticket.controller;

import com.zewbby.smartticket.common.ApiResponse;
import com.zewbby.smartticket.domain.dto.CreateOrderRequest;
import com.zewbby.smartticket.domain.vo.IdempotencyTokenVO;
import com.zewbby.smartticket.domain.vo.OrderRequestVO;
import com.zewbby.smartticket.domain.vo.OrderVO;
import com.zewbby.smartticket.idempotency.IdempotencyTokenService;
import com.zewbby.smartticket.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orderService;

    private final IdempotencyTokenService idempotencyTokenService;

    public OrderController(OrderService orderService,
                           IdempotencyTokenService idempotencyTokenService) {
        this.orderService = orderService;
        this.idempotencyTokenService = idempotencyTokenService;
    }

    @GetMapping("/orders/idempotency-token")
    public ApiResponse<IdempotencyTokenVO> generateOrderIdempotencyToken(@RequestParam Long userId) {
        return ApiResponse.successZero(idempotencyTokenService.generateOrderToken(userId));
    }

    /**
     * 创建订单
     * @param request
     * @return
     */
    @PostMapping("/orders")
    public ApiResponse<OrderVO> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.success(orderService.createOrder(request));
    }

    /**
     * 异步创建订单请求，只返回请求ID，不在接口线程中扣库存或创建正式订单
     * @param request
     * @return
     */
    @PostMapping("/orders/async")
    public ApiResponse<OrderRequestVO> submitAsyncOrder(@Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.successZero(orderService.submitAsyncOrder(request));
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
     * 列出用户订单
     * @param userId
     * @return
     */
    @GetMapping("/users/{userId}/orders")
    public ApiResponse<List<OrderVO>> listUserOrders(@PathVariable Long userId) {
        return ApiResponse.success(orderService.listUserOrders(userId));
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
