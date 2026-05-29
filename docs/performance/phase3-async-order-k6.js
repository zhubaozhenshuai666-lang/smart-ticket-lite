import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    vus_10_30s: {
      executor: 'constant-vus',
      vus: 10,
      duration: '30s',
      exec: 'submitAsyncOrder',
    },
    vus_50_30s: {
      executor: 'constant-vus',
      vus: 50,
      duration: '30s',
      startTime: '35s',
      exec: 'submitAsyncOrder',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.20'],
    http_req_duration: ['p(95)<1000'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const USER_ID = Number(__ENV.USER_ID || 1);
const SHOW_ID = Number(__ENV.SHOW_ID || 1);
const SESSION_ID = Number(__ENV.SESSION_ID || 1);
const TICKET_CATEGORY_ID = Number(__ENV.TICKET_CATEGORY_ID || 2);

export function submitAsyncOrder() {
  const payload = JSON.stringify({
    userId: USER_ID,
    showId: SHOW_ID,
    sessionId: SESSION_ID,
    ticketCategoryId: TICKET_CATEGORY_ID,
    quantity: 1,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  const response = http.post(`${BASE_URL}/api/orders/async`, payload, params);

  check(response, {
    'status is 200': (res) => res.status === 200,
    'response has code': (res) => {
      try {
        return JSON.parse(res.body).code !== undefined;
      } catch (error) {
        return false;
      }
    },
  });

  sleep(0.1);
}
