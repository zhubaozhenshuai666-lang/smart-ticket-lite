import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    async_order_rate_limit: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 100),
      timeUnit: '1s',
      duration: __ENV.DURATION || '1m',
      preAllocatedVUs: Number(__ENV.VUS || 100),
      maxVUs: Number(__ENV.MAX_VUS || 300),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<500'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8082';

export default function () {
  const token = __ENV.TOKEN;
  if (!token) {
    throw new Error('TOKEN is required');
  }

  const payload = JSON.stringify({
    showId: 1,
    sessionId: 1,
    ticketCategoryId: 2,
    quantity: 1,
    idempotencyToken: `${__VU}-${__ITER}-${Date.now()}`,
  });

  const response = http.post(`${BASE_URL}/api/orders/async`, payload, {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });

  check(response, {
    'async order accepted or guarded': (r) => [200, 400, 429].includes(r.status),
  });

  sleep(1);
}
