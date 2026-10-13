import postgres from 'postgres';
import { env } from './env.js';

export const sql = postgres(env.DATABASE_URL, { max: 10, idle_timeout: 20, onnotice: () => {} });
export type Tx = postgres.TransactionSql;
