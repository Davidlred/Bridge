/**
 * Image model bake-off: the same goal, scenes and prompts through several Replicate image models, saved as a local page
 * to compare side by side. Uses the app's real prompts (goals/routes.ts) and, for the "with your photo" row, the photo of
 * the most recent account that made face images. Nothing is uploaded anywhere except to Replicate to generate.
 *
 *   npm run bakeoff -- [output folder] [--photo face.jpg] [--models a,b] [--goal "text"] [--routine "text"]
 *
 * Costs real Replicate credit (about $1 for the default set). Models run one after another (small accounts are throttled).
 */
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { sql } from '../db.js';
import { photoKey, storage } from '../storage.js';
import { runImage } from '../ai/replicate.js';
import { imagePrompt, sceneFor, type Kind } from '../goals/routes.js';

type Model = { id: string; label: string; face: boolean; usdPerImage: number; input: (prompt: string, photo: string | null) => Record<string, unknown> };
const ratio = { aspect_ratio: '3:4' };
const MODELS: Model[] = [
  // With the user's photo: keep the face, change everything else.
  { id: 'black-forest-labs/flux-kontext-pro', label: 'Flux Kontext Pro (current)', face: true, usdPerImage: 0.04, input: (p, img) => ({ prompt: p, input_image: img, ...ratio, output_format: 'jpg' }) },
  { id: 'black-forest-labs/flux-kontext-max', label: 'Flux Kontext Max', face: true, usdPerImage: 0.08, input: (p, img) => ({ prompt: p, input_image: img, ...ratio, output_format: 'jpg' }) },
  { id: 'google/nano-banana', label: 'Nano Banana (Gemini 2.5 Flash Image)', face: true, usdPerImage: 0.039, input: (p, img) => ({ prompt: p, image_input: [img], ...ratio, output_format: 'jpg' }) },
  { id: 'google/nano-banana-pro', label: 'Nano Banana Pro', face: true, usdPerImage: 0.15, input: (p, img) => ({ prompt: p, image_input: [img], ...ratio, output_format: 'jpg' }) },
  { id: 'bytedance/seedream-4', label: 'Seedream 4', face: true, usdPerImage: 0.03, input: (p, img) => ({ prompt: p, image_input: [img], ...ratio, max_images: 1 }) },
  // Without a photo: the person seen from behind or far away.
  { id: 'black-forest-labs/flux-1.1-pro', label: 'Flux 1.1 Pro (current)', face: false, usdPerImage: 0.04, input: p => ({ prompt: p, ...ratio, output_format: 'jpg' }) },
  { id: 'google/imagen-4', label: 'Imagen 4', face: false, usdPerImage: 0.04, input: p => ({ prompt: p, ...ratio }) },
  { id: 'bytedance/seedream-4', label: 'Seedream 4', face: false, usdPerImage: 0.03, input: p => ({ prompt: p, ...ratio, max_images: 1 }) },
];

const arg = (name: string) => { const i = process.argv.indexOf(name); return i > 0 ? process.argv[i + 1] : undefined; };
const outDir = process.argv[2] && !process.argv[2].startsWith('--') ? process.argv[2] : join(process.env.USERPROFILE ?? '.', 'Downloads', 'image-model-comparison');

async function download(url: string): Promise<Buffer> {
  if (url.startsWith('data:')) return Buffer.from(url.slice(url.indexOf(',') + 1), 'base64');
  const r = await fetch(url);
  if (!r.ok) throw new Error(`download ${r.status}`);
  return Buffer.from(await r.arrayBuffer());
}

const esc = (s: string) => s.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]!));

async function main() {
  await mkdir(outDir, { recursive: true });
  const [who] = await sql`select user_id from goal_images where with_face order by updated_at desc limit 1`;
  const userId = who?.user_id as string | undefined;
  const [g] = userId ? await sql`select statement, routine from goals where user_id = ${userId} and status = 'active' order by start_date desc limit 1` : [];
  const goal = arg('--goal') ?? (g?.statement as string | undefined) ?? 'Run a half marathon in under 2 hours';
  const routine = arg('--routine') ?? (g?.routine as string | undefined) ?? 'Up at 7, desk job 9 to 6, runs twice a week, asleep around midnight.';
  // --photo <file> uses a picture from disk; otherwise the photo of that account, if it still has one.
  const photoBytes = arg('--photo') ? await readFile(arg('--photo')!) : userId ? await storage.get(photoKey(userId)) : null;
  const photo = photoBytes ? `data:image/jpeg;base64,${photoBytes.toString('base64')}` : null;
  if (photoBytes) await writeFile(join(outDir, 'your-photo.jpg'), photoBytes);
  console.log(`goal: ${goal}\nphoto: ${photo ? 'yes' : 'none (photo rows skipped)'}\nout: ${outDir}`);

  // One scene per kind, shared by every model, so only the model differs.
  const scenes: Record<Kind, string | null> = { future: await sceneFor('future', goal, routine), reality: await sceneFor('reality', goal, routine) };
  type Cell = { file?: string; error?: string; seconds?: number };
  const results: { m: Model; cells: Record<Kind, Cell> }[] = [];
  let spent = 0;
  const only = arg('--models')?.split(',');
  for (const m of MODELS) {
    if (m.face && !photo) continue;
    if (only && !only.includes(m.id)) continue;
    const cells = {} as Record<Kind, Cell>;
    for (const kind of ['future', 'reality'] as Kind[]) {
      const p = imagePrompt(kind, goal, routine, m.face, scenes[kind]);
      const t0 = Date.now();
      try {
        const bytes = await download(await runImage(m.id, m.input(p, photo)));
        const file = `${m.id.replace(/[^a-z0-9]+/gi, '-')}-${m.face ? 'face' : 'noface'}-${kind}.jpg`;
        await writeFile(join(outDir, file), bytes);
        cells[kind] = { file, seconds: Math.round((Date.now() - t0) / 100) / 10 };
        spent += m.usdPerImage;
      } catch (e) {
        cells[kind] = { error: e instanceof Error ? e.message.slice(0, 160) : String(e) };
      }
      console.log(`${m.label} ${m.face ? 'photo' : 'no photo'} ${kind}: ${cells[kind].file ? `ok ${cells[kind].seconds}s` : cells[kind].error}`);
    }
    results.push({ m, cells });
  }

  const cell = (c: Cell, alt: string) => c.file
    ? `<figure><img src="${c.file}" alt="${esc(alt)}" loading="lazy"><figcaption>${c.seconds}s</figcaption></figure>`
    : `<figure class="err"><div>Failed</div><figcaption>${esc(c.error ?? '')}</figcaption></figure>`;
  const section = (face: boolean) => results.filter(r => r.m.face === face).map(r => `
      <section class="row">
        <h3>${esc(r.m.label)}<span>${esc(r.m.id)} · about $${r.m.usdPerImage.toFixed(3)} an image</span></h3>
        <div class="pair">${cell(r.cells.future, `${r.m.label}: future`)}${cell(r.cells.reality, `${r.m.label}: if nothing changes`)}</div>
      </section>`).join('');
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Image model comparison</title>
<style>
:root{--bg:#FBFAF7;--s1:#fff;--ink:#23262B;--mut:#7A7D83;--line:#E3E0D8}
@media (prefers-color-scheme:dark){:root{--bg:#0C0C0C;--s1:#151515;--ink:#F2F2F2;--mut:#8C8C8C;--line:#2a2a2a}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 system-ui,-apple-system,Segoe UI,sans-serif;padding:24px 16px 64px}
main{max-width:1100px;margin:0 auto}h1{font-size:26px;margin:0 0 6px}h2{font-size:19px;margin:40px 0 4px}p{color:var(--mut);margin:4px 0}
.top{display:flex;gap:20px;align-items:flex-start;flex-wrap:wrap;margin-top:16px}.top img{width:140px;border-radius:12px;border:1px solid var(--line)}
.scene{background:var(--s1);border:1px solid var(--line);border-radius:12px;padding:12px 14px;flex:1;min-width:260px}.scene b{display:block;font-size:12px;color:var(--mut);font-weight:600;margin-top:6px}
.cols{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:18px 0 6px;color:var(--mut);font-size:13px;font-weight:600}
.row{background:var(--s1);border:1px solid var(--line);border-radius:16px;padding:14px;margin-top:12px}
.row h3{margin:0 0 10px;font-size:16px;display:flex;flex-direction:column}.row h3 span{font-size:12px;color:var(--mut);font-weight:400}
.pair{display:grid;grid-template-columns:1fr 1fr;gap:12px}figure{margin:0}img{width:100%;border-radius:10px;display:block;cursor:zoom-in}
figcaption{font-size:12px;color:var(--mut);margin-top:4px}.err div{aspect-ratio:3/4;border:1px dashed var(--line);border-radius:10px;display:grid;place-items:center;color:var(--mut)}
img.zoom{position:fixed;inset:0;margin:auto;max-height:94vh;width:auto;max-width:94vw;z-index:9;cursor:zoom-out;box-shadow:0 0 0 100vmax rgba(0,0,0,.85)}
</style></head><body><main>
<h1>Image model comparison</h1>
<p>Same goal, same scenes and the app's real prompts; only the model changes. Tap an image to see it full size. Spent about $${spent.toFixed(2)}.</p>
<div class="top">${photoBytes ? '<img src="your-photo.jpg" alt="Your photo">' : ''}
<div class="scene"><b>GOAL</b>${esc(goal)}<b>FUTURE SCENE</b>${esc(scenes.future ?? '(default prompt)')}<b>IF NOTHING CHANGES</b>${esc(scenes.reality ?? '(default prompt)')}</div></div>
${photoBytes ? `<h2>With your photo</h2><p>What to look for: is it still clearly you (face, skin tone, hair, glasses), and did the clothes, place and pose really change?</p><div class="cols"><span>Future self</span><span>If nothing changes</span></div>${section(true)}` : ''}
<h2>Without a photo</h2><p>For people who skip the photo: the person seen from behind or far away. Look for a believable scene and no visible face.</p>
<div class="cols"><span>Future self</span><span>If nothing changes</span></div>${section(false)}
</main><script>document.querySelectorAll('img').forEach(i=>i.addEventListener('click',()=>i.classList.toggle('zoom')))</script></body></html>`;
  await writeFile(join(outDir, 'index.html'), html);
  console.log(`\nDone. Open ${join(outDir, 'index.html')} (about $${spent.toFixed(2)} spent)`);
  await sql.end();
}

main().catch(async e => { console.error(e); await sql.end(); process.exit(1); });
