import { z } from 'zod';

const list = z.string().default('').transform(s => s.split(',').map(x => x.trim()).filter(Boolean));

const Env = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().default(8080),
  DATABASE_URL: z.string().url(),
  REDIS_URL: z.string().url(),
  JWT_PRIVATE_KEY_PEM: z.string().min(1),
  JWT_ISSUER: z.string().url(),
  // Public origin of this API (Apple web callback, links in emails). Defaults to JWT_ISSUER.
  PUBLIC_URL: z.string().url().optional(),
  APPLE_CLIENT_IDS: list,
  GOOGLE_CLIENT_IDS: list,
  REPLICATE_API_TOKEN: z.string().min(1),
  MODEL_PLANNER: z.string().min(3),
  MODEL_COACH: z.string().min(3),
  MODEL_FAST: z.string().min(3),
  MODEL_IMAGE: z.string().min(3),
});

const parsed = Env.parse(process.env);
export const env = { ...parsed, PUBLIC_URL: (parsed.PUBLIC_URL ?? parsed.JWT_ISSUER).replace(/\/$/, '') };
