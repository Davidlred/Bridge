import { EventEmitter } from 'node:events';
import { Redis } from 'ioredis';
import { env } from './env.js';

/**
 * Fan-out for realtime events ("alliance:<id>", "user:<id>"). Messages are persisted before they are published,
 * so delivery is at-least-once and clients dedupe by id. With several machines, Redis pub/sub carries events between them.
 */
class Bus {
  private local = new EventEmitter().setMaxListeners(0);
  private pub: Redis | null = null;
  private sub: Redis | null = null;

  constructor() {
    if (env.NODE_ENV === 'test' || !env.REDIS_URL) return;
    const opts = { lazyConnect: true, maxRetriesPerRequest: 1, enableOfflineQueue: false };
    this.pub = new Redis(env.REDIS_URL, opts);
    this.sub = new Redis(env.REDIS_URL, opts);
    // Without Redis (e.g. one dev machine) everything still works in-process.
    Promise.all([this.pub.connect(), this.sub.connect()]).then(() => this.sub!.psubscribe('rt:*')).catch(() => { this.pub = this.sub = null; });
    this.sub.on('pmessage', (_p, channel, raw) => this.local.emit(channel.slice(3), JSON.parse(raw)));
  }

  publish(channel: string, event: Record<string, unknown>) {
    if (this.pub?.status === 'ready') this.pub.publish(`rt:${channel}`, JSON.stringify(event)).catch(() => this.local.emit(channel, event));
    else this.local.emit(channel, event);
  }

  subscribe(channel: string, fn: (e: Record<string, unknown>) => void): () => void {
    this.local.on(channel, fn);
    return () => this.local.off(channel, fn);
  }
}

export const bus = new Bus();
