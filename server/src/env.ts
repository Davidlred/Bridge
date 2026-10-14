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
  // Image model that edits a reference photo (keeps the person's face) for Future Self / If nothing changes
  MODEL_IMAGE_EDIT: z.string().min(3).default('black-forest-labs/flux-kontext-pro'),
  // Object storage (S3 API, e.g. Tigris on Fly). Without S3_BUCKET files go to STORAGE_DIR on local disk (development only).
  S3_BUCKET: z.string().optional(),
  S3_ENDPOINT: z.string().url().optional(),
  S3_REGION: z.string().default('auto'),
  STORAGE_DIR: z.string().default('.data/media'),
  // Wraps each user's journal key (32 bytes, base64). Defaults to a value derived from the JWT key; set it explicitly in production
  // and never change it without re-wrapping the keys, or existing journals become unreadable.
  JOURNAL_KEK: z.string().optional(),
  // Push notifications. Without these, pushes are skipped (events still show in the app).
  FCM_SERVICE_ACCOUNT_JSON: z.string().optional(),
  APNS_KEY_P8: z.string().optional(),
  APNS_KEY_ID: z.string().optional(),
  APNS_TEAM_ID: z.string().optional(),
  APNS_BUNDLE_ID: z.string().default('app.thebridge.Bridge'),
  // Signs short-lived media links. Defaults to a value derived from the JWT key.
  MEDIA_SIGNING_SECRET: z.string().min(32).optional(),
});

const parsed = Env.parse(process.env);
export const env = { ...parsed, PUBLIC_URL: (parsed.PUBLIC_URL ?? parsed.JWT_ISSUER).replace(/\/$/, '') };
