package com.zewbby.smartticket.service;

import com.zewbby.smartticket.domain.dto.CreateOrderRequest;
import com.zewbby.smartticket.domain.vo.OrderRequestVO;
import com.zewbby.smartticket.domain.vo.OrderVO;

import java.util.List;

public interface OrderService {

    OrderVO createOrder(CreateOrderRequest request);

    OrderRequestVO submitAsyncOrder(CreateOrderRequest request);

    OrderRequestVO getOrderRequestResult(String requestId);

    OrderVO getOrderById(Long orderId);

    List<OrderVO> listCurrentUserOrders();

    OrderVO cancelOrder(Long orderId);

    OrderVO payOrder(Long orderId);

    void closeTimeoutOrder(Long orderId);
}
