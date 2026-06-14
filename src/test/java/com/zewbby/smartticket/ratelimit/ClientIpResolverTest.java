package com.zewbby.smartticket.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    void resolvePrefersFirstForwardedForIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1, 10.0.0.2");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void resolveFallsBackToRealIpAndRemoteAddr() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Real-IP")).thenReturn("10.0.0.3");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.3");

        HttpServletRequest noHeaderRequest = mock(HttpServletRequest.class);
        when(noHeaderRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        assertThat(resolver.resolve(noHeaderRequest)).isEqualTo("127.0.0.1");
    }
}
