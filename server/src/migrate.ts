import { readdir, readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { sql } from './db.js';

/** Applies db/*.sql in filename order, once each. */
export async function migrate(dir = fileURLToPath(new URL('../db', import.meta.url))): Promise<string[]> {
  await sql`create table if not exists schema_migrations (name text primary key, applied_at timestamptz not null default now())`;
  const done = new Set((await sql<{ name: string }[]>`select name from schema_migrations`).map(r => r.name));
  const applied: string[] = [];
  for (const name of (await readdir(dir)).filter(f => f.endsWith('.sql')).sort()) {
    if (done.has(name)) continue;
    const body = await readFile(`${dir}/${name}`, 'utf8');
    await sql.begin(async tx => {
      await tx.unsafe(body);
      await tx`insert into schema_migrations (name) values (${name})`;
    });
    applied.push(name);
  }
  // Hosted Postgres like Supabase publishes every table in public through its own web API. Row-level security with no
  // policies closes that; this server connects as the tables' owner, which RLS doesn't apply to.
  await sql.unsafe(`do $rls$ declare t record; begin
    for t in select tablename from pg_tables where schemaname = 'public' and not rowsecurity loop
      execute format('alter table public.%I enable row level security', t.tablename);
    end loop; end $rls$`);
  return applied;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const applied = await migrate();
  console.log(applied.length ? `applied: ${applied.join(', ')}` : 'up to date');
  await sql.end();
}
