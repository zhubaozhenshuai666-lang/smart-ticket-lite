import http from 'k6/http';
import { Counter } from 'k6/metrics';

export const phase6ProbeMiss = new Counter('phase6_probe_miss');

export const options = {
  scenarios: {
    cross_version_lifecycle: {
      executor: 'ramping-arrival-rate',
      startRate: 20,
      timeUnit: '1s',
      stages: [
        { target: 80, duration: '30s' },
        { target: 80, duration: '1m' },
        { target: 0, duration: '15s' },
      ],
      preAllocatedVUs: 100,
      maxVUs: 300,
    },
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8082';

export default function () {
  const token = __ENV.TOKEN;
  if (!token) {
    throw new Error('TOKEN is required');
  }

  const response = http.post(
    `${BASE_URL}/api/orders/async`,
    JSON.stringify({
      showId: 1,
      sessionId: 1,
      ticketCategoryId: 2,
      quantity: 1,
      idempotencyToken: `phase6-${__VU}-${__ITER}-${Date.now()}`,
    }),
    {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
    }
  );

  if (response.status === 400 && response.body.includes('抢票人数过多')) {
    phase6ProbeMiss.add(1);
  }
}
