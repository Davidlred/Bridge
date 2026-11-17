import postgres from 'postgres';
import { env } from './env.js';

/** Hosted databases (Supabase, Neon) need TLS; a database on this machine doesn't. */
const local = /@(localhost|127\.0\.0\.1|\[::1\])[:/]/.test(env.DATABASE_URL) || !/@/.test(env.DATABASE_URL);
export const sql = postgres(env.DATABASE_URL, { max: 10, idle_timeout: 20, onnotice: () => {}, ...(local || /sslmode=disable/.test(env.DATABASE_URL) ? {} : { ssl: 'require' as const }) });
export type Tx = postgres.TransactionSql;
