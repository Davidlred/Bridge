import { env } from './env.js';
import { buildApp } from './app.js';
import { purgeDeletedAccounts } from './auth/routes.js';

const app = await buildApp();
await app.listen({ port: env.PORT, host: '0.0.0.0' });

// Housekeeping (hard-delete of accounts removed 30+ days ago, expired tokens). Moves to the worker process with BullMQ.
const sweep = () => purgeDeletedAccounts().then(n => n && app.log.info({ purged: n }, 'accounts_purged'), e => app.log.error(e));
setInterval(sweep, 6 * 60 * 60 * 1000).unref();
sweep();

// Deploys and restarts send SIGTERM: stop taking requests, let the ones in flight finish (a plan can take a minute), then exit.
// fly.toml gives us kill_timeout to do it.
let stopping = false;
for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.on(signal, () => {
    if (stopping) return;
    stopping = true;
    app.log.info({ signal }, 'shutting_down');
    setTimeout(() => process.exit(1), 55_000).unref();
    app.close().then(() => process.exit(0), () => process.exit(1));
  });
}
