import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const run = describe.skipIf(!process.env.TEST_DATABASE_URL);

run('daily AI budget', () => {
  let sql: typeof import('../src/db.js').sql;
  let env: typeof import('../src/env.js').env;
  let spendBudget: typeof import('../src/ai/replicate.js').spendBudget;
  beforeAll(async () => {
    ({ sql } = await import('../src/db.js'));
    ({ env } = await import('../src/env.js'));
    ({ spendBudget } = await import('../src/ai/replicate.js'));
    await sql.unsafe('drop schema public cascade; create schema public;');
    await (await import('../src/migrate.js')).migrate();
  });
  afterAll(async () => { env.AI_DAILY_BUDGET = 0; await sql?.end(); });

  it('stops AI calls across everyone once the day is used up, but never moderation', async () => {
    env.AI_DAILY_BUDGET = 2;
    await spendBudget('test/coach');
    await spendBudget('test/image');
    await expect(spendBudget('test/coach')).rejects.toThrow('ai_budget');
    await expect(spendBudget(env.MODEL_FAST)).resolves.toBeUndefined();
    env.AI_DAILY_BUDGET = 0;                                                     // 0 = no ceiling
    await expect(spendBudget('test/coach')).resolves.toBeUndefined();
  });
});
