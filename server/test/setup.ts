import { generateKeyPairSync } from 'node:crypto';

// env.ts parses process.env at import time, so this runs first (vitest setupFiles).
const { privateKey } = generateKeyPairSync('ed25519');
Object.assign(process.env, {
  NODE_ENV: 'test',
  DATABASE_URL: process.env.TEST_DATABASE_URL ?? 'postgres://bridge:bridge@localhost:5432/bridge_test',
  REDIS_URL: 'redis://localhost:6379',
  JWT_PRIVATE_KEY_PEM: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(),
  JWT_ISSUER: 'https://api.test.thebridge.app',
  REPLICATE_API_TOKEN: 'test',
  MODEL_PLANNER: 'test/model', MODEL_COACH: 'test/model', MODEL_FAST: 'test/model', MODEL_IMAGE: 'test/model',
});
