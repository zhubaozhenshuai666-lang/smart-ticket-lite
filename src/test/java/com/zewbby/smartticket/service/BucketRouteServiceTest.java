package com.zewbby.smartticket.service;

import com.zewbby.smartticket.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BucketRouteServiceTest {

    private final BucketRouteService bucketRouteService = new BucketRouteService();

    @Test
    void sameRequestIdAlwaysRoutesToSameBucket() {
        int first = bucketRouteService.route("REQ-BUCKET-20260603", 10);
        int second = bucketRouteService.route("REQ-BUCKET-20260603", 10);

        assertThat(first).isEqualTo(second);
        assertThat(first).isBetween(0, 9);
    }

    @Test
    void routeRejectsInvalidBucketCount() {
        assertThatThrownBy(() -> bucketRouteService.route("REQ1", 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("bucket数量必须大于0");
    }
}
