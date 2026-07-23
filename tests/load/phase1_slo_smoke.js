import { check, fail } from 'k6';
import exec from 'k6/execution';
import http from 'k6/http';
import ws from 'k6/ws';
import { Trend } from 'k6/metrics';

const fixturePath = __ENV.TIMA_K6_FIXTURE || 'build/phase1-k6-fixture.json';
const fixture = JSON.parse(open(fixturePath));
const baseUrl = (__ENV.TIMA_K6_BASE_URL || fixture.base_url).replace(/\/$/, '');

export const options = {
  scenarios: {
    phase1_slo: {
      executor: 'shared-iterations',
      vus: Number(__ENV.TIMA_K6_VUS || 1),
      iterations: fixture.samples.length,
      maxDuration: __ENV.TIMA_K6_MAX_DURATION || '2m',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    tima_message_send_ack_seconds: ['p(99)<0.8'],
    tima_ws_online_delivery_seconds: ['p(99)<2'],
  },
};

const sendAck = new Trend('tima_message_send_ack_seconds');
const wsDelivery = new Trend('tima_ws_online_delivery_seconds');

function varint(value) {
  const bytes = [];
  let remaining = value;
  while (remaining >= 128) {
    bytes.push((remaining & 127) | 128);
    remaining = Math.floor(remaining / 128);
  }
  bytes.push(remaining);
  return bytes;
}

function bytesField(field, value) {
  return [...varint((field << 3) | 2), ...varint(value.length), ...value];
}

function uuidBytes(value) {
  const compact = value.replace(/-/g, '');
  if (!/^[0-9a-fA-F]{32}$/.test(compact)) {
    fail(`invalid fixture chat UUID: ${value}`);
  }
  const result = [];
  for (let index = 0; index < compact.length; index += 2) {
    result.push(parseInt(compact.slice(index, index + 2), 16));
  }
  return result;
}

function subscribeFrame(chatId, requestId) {
  const uuid = bytesField(1, uuidBytes(chatId));
  const subscribe = bytesField(1, uuid);
  return new Uint8Array([...varint(8), ...varint(requestId), ...bytesField(10, subscribe)]).buffer;
}

function websocketUrl(baseUrl, accessToken) {
  const root = baseUrl.startsWith('https://')
    ? `wss://${baseUrl.slice(8)}`
    : `ws://${baseUrl.replace(/^http:\/\//, '')}`;
  return `${root}/v1/ws?token=${encodeURIComponent(accessToken)}`;
}

export default function () {
  const sample = fixture.samples[exec.scenario.iterationInTest];
  if (!sample) {
    fail('fixture does not contain enough unique send samples');
  }

  let frameCount = 0;
  let sentAt = 0;
  let delivered = false;
  const socketResponse = ws.connect(
    websocketUrl(baseUrl, fixture.recipient.access_token),
    {
      headers: {
        'Sec-WebSocket-Protocol': 'tima.pb.v1',
        'X-Device-Id': fixture.recipient.device_id,
      },
      tags: { journey: 'phase1-online-delivery' },
    },
    (socket) => {
      socket.on('open', () => {
        socket.sendBinary(subscribeFrame(fixture.chat_id, exec.scenario.iterationInTest + 1));
      });
      socket.on('binaryMessage', () => {
        frameCount += 1;
        if (frameCount === 1) {
          sentAt = Date.now();
          const response = http.post(
            `${baseUrl}/v1/chats/${fixture.chat_id}/messages`,
            JSON.stringify(sample.body),
            {
              headers: {
                Accept: 'application/json',
                Authorization: `Bearer ${fixture.sender.access_token}`,
                'Content-Type': 'application/json',
                'Idempotency-Key': sample.idempotency_key,
                'X-Device-Id': fixture.sender.device_id,
              },
              tags: { operation: 'message-send' },
            },
          );
          sendAck.add(response.timings.duration / 1000);
          if (!check(response, { 'message send acknowledged': (r) => r.status >= 200 && r.status < 300 })) {
            socket.close();
          }
          return;
        }
        if (frameCount === 2 && sentAt > 0) {
          wsDelivery.add((Date.now() - sentAt) / 1000);
          delivered = true;
          socket.close();
        }
      });
      socket.setTimeout(() => socket.close(), 10000);
    },
  );

  check(socketResponse, { 'websocket upgraded': (response) => response && response.status === 101 });
  check(delivered, { 'online websocket event delivered': (value) => value === true });
}
