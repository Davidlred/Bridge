import { env } from './env.js';
import { buildApp } from './app.js';
import { purgeDeletedAccounts } from './auth/routes.js';

const app = await buildApp();
await app.listen({ port: env.PORT, host: '0.0.0.0' });

// Housekeeping (hard-delete of accounts removed 30+ days ago, expired tokens). Moves to the worker process with BullMQ.
const sweep = () => purgeDeletedAccounts().then(n => n && app.log.info({ purged: n }, 'accounts_purged'), e => app.log.error(e));
setInterval(sweep, 6 * 60 * 60 * 1000).unref();
sweep();
