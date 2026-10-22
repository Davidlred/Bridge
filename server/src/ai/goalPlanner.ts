import { env } from '../env.js';
import { runText, AIUnavailable } from './replicate.js';
import { Feasibility, Plan, StepsReply, type PlanRequest, type PlanStep, type RealityRequest, type Resource, type StepsRequest } from './planSchema.js';
import { verifyResources, type Link } from './links.js';
import { PLAIN_VOICE, plain } from './voice.js';

/** Shared by the plan and the next steps: what makes a step concrete, and how to point at resources without inventing links. */
const STEP_RULES = `- A step is one concrete action for one day, done once. Say exactly what to do and with what: what to install or download and from where, which book and which chapters or sections, which exercises, which video, which form to fill in. Not "Learn Rust basics" but "Read chapters 1 and 2 of The Rust Programming Language" or "Install Rust with rustup and run cargo new hello".
- Steps build on each other in order and fit the routine. 1 or 2 steps per day, each 120 minutes or less.
- Prefer well-known, free, reputable sources: official sites, docs and free books, university courses (MIT OpenCourseWare, Khan Academy), freeCodeCamp, Exercism, NHS, government sites.
- resources: up to 3 per task or step, each {"title", "url"} or {"title", "search"}. Give "url" only for a page you are certain exists, such as an official home page or documentation (e.g. https://doc.rust-lang.org/book/). For a video, give "search": a precise YouTube search phrase (e.g. "Let's Get Rusty ownership explained"), unless you are certain of the exact video URL. Never invent or guess a URL. Leave resources empty when nothing specific helps.`;

const SYSTEM = `You are the planning engine for The Bridge, a goal coach app.
Turn the user's goal into a specific, realistic plan: daily habits ("tasks") and the concrete first steps ("steps").
Rules:
- The "deadline" field is authoritative. Ignore any date written in the goal text. If the text contradicts the deadline, say so in feasibility.reason. Never output a different deadline except as feasibility.suggestedDeadline.
- Use daysUntilDeadline and weeksUntilDeadline for any statement about time left. Never work out durations yourself.
- If the goal is too vague to plan (fewer than 3 meaningful words, or no clear outcome), set needsClarification to ONE short question and return empty tasks and steps.
- tasks: 1 to 4 habits that repeat EVERY day: never write weekly frequencies ("three times a week") in a title or rationale. If an activity shouldn't be daily, make a daily version of it (e.g. "Run or strength session (alternate days)"). Name the actual thing (e.g. "Read The Rust Programming Language for 30 min", not "Study Rust").
- steps: the one-time actions for the first 7 days, "day" 1 = today.
${STEP_RULES}
- Each task takes 90 minutes or less, fits outside busy blocks and between wake and sleep, and prefers the user's preferred times.
- title: specific and doable today, with an amount where it helps (e.g. "Easy run, 5 km at conversational pace"). At most 60 characters.
- timeOfDay (tasks only): a clock time like "7:00 AM" or "6:30 PM" whenever the routine allows, so a reminder can fire; otherwise a short anchor in sentence case like "After work" or "Before bed".
- The routine may be free text written by the user. Read wake and sleep times, work hours and habits from it, and build on the habits they want to change.
- Week one is deliberately light so the plan sticks.
- priority: High for the task that most moves the goal, Low for supporting habits.
- Each rationale is one short plain sentence. No motivational filler.
- ${PLAIN_VOICE}
- Health, money or legal goals: general guidance only; add "check with a professional" to a rationale where relevant.
- If the goal is harmful (self-harm, disordered eating, illegal activity), set needsClarification to a supportive sentence and return no tasks or steps.
- label: 1 to 3 words naming the goal (e.g. "Half marathon").
- category: one of fitness, study, language, career, money, creative, health, habits, other.
Output ONLY a JSON object with keys: needsClarification, label, goal, category, feasibility {verdict, reason, suggestedDeadline}, tasks [{title, priority, timeOfDay, minutes, rationale, resources}], steps [{day, title, minutes, rationale, resources}]. No prose, no code fences.`;

const STEPS = `You are the planning engine for The Bridge, a goal coach app. Plan the next 7 days of one-time steps for a goal that is under way.
You get the goal, the deadline, the routine, the daily habits, the steps already done and the steps still open.
Rules:
- Continue from where the user is: never repeat a done step, never duplicate an open one, move the goal forward at a pace that fits the deadline (use daysUntilDeadline).
- Return 4 to 10 steps, "day" 1 = today.
${STEP_RULES}
- Each rationale is one short plain sentence. No motivational filler.
- ${PLAIN_VOICE}
- Health, money or legal goals: general guidance only.
Output ONLY a JSON object: {"steps": [{day, title, minutes, rationale, resources}]}. No prose, no code fences.`;

/** Models are unreliable at date arithmetic (one said "16 months" for 4), so the time left is computed here and given to them. */
export function timeLeft(today: string, deadline: string) {
  const days = Math.round((Date.parse(`${deadline}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 864e5);
  return { daysUntilDeadline: days, weeksUntilDeadline: Math.round(days / 7 * 10) / 10 };
}

function extractJSON(raw: string): unknown {
  const s = raw.replace(/^```(?:json)?\s*/i, '').replace(/```\s*$/, '').trim();
  const a = s.indexOf('{'), b = s.lastIndexOf('}');
  if (a < 0 || b <= a) throw new SyntaxError('no JSON object');
  return JSON.parse(s.slice(a, b + 1));
}

const REALITY = `You are the Reality Check for The Bridge, a goal coach app.
Judge whether the goal is achievable by the deadline, given today's date, the user's routine and their current daily tasks.
Rules:
- The deadline is the user's choice. Never change it. If it looks too tight or too loose, propose suggestedDeadline (ISO date after today), otherwise null.
- verdict: "realistic", "stretch" (possible with consistent effort) or "unrealistic".
- reason: at most two plain sentences, specific to this goal. No shaming, no motivational filler.
- ${PLAIN_VOICE}
- Deadlines more than 3 years away: suggest working in milestones in the reason.
- Use daysUntilDeadline and weeksUntilDeadline for any statement about time left. Never work out durations yourself.
Output ONLY a JSON object: {"verdict", "reason", "suggestedDeadline"}. No prose, no code fences.`;

/** Feasibility only: used when the deadline changes, so the plan itself is left alone. */
export async function assessReality(req: RealityRequest, signal?: AbortSignal): Promise<Feasibility> {
  let lastError = '';
  for (let attempt = 0; attempt < 2; attempt++) {
    const repair = attempt ? `

Your previous output was invalid: ${lastError}. Return corrected JSON only.` : '';
    const raw = await runText(env.MODEL_PLANNER, { system_prompt: REALITY, prompt: JSON.stringify({ ...req, ...timeLeft(req.today, req.deadline) }) + repair, max_tokens: 1024, temperature: 0.2 }, signal);
    try {
      const f = Feasibility.parse(extractJSON(raw));
      return f.suggestedDeadline && f.suggestedDeadline <= req.today ? { ...f, suggestedDeadline: null } : f;
    } catch (e) {
      lastError = e instanceof Error ? e.message.slice(0, 400) : 'invalid';
    }
  }
  throw new AIUnavailable(`reality_invalid: ${lastError}`);
}

/** A step as the apps get it: plain text, checked links, days in order. */
export type StepOut = Omit<PlanStep, 'resources'> & { resources: Link[] };
const plainStep = (s: PlanStep): PlanStep => ({ ...s, title: plain(s.title), rationale: plain(s.rationale) });

/** Checks the links of tasks and steps together (in parallel), so the plan waits for the slowest link only once. */
async function withLinks<T extends { resources: Resource[] }>(items: T[]): Promise<(Omit<T, 'resources'> & { resources: Link[] })[]> {
  const links = await verifyResources(items.map(i => i.resources));
  return items.map((i, n) => ({ ...i, resources: links[n]! }));
}

export async function generatePlan(req: PlanRequest, signal?: AbortSignal) {
  const prompt = JSON.stringify({ ...req, ...timeLeft(req.today, req.deadline) });
  let lastError = '';
  for (let attempt = 0; attempt < 2; attempt++) {
    const repair = attempt ? `\n\nYour previous output was invalid: ${lastError}. Return corrected JSON only.` : '';
    const raw = await runText(env.MODEL_PLANNER, { system_prompt: SYSTEM, prompt: prompt + repair, max_tokens: 2500, temperature: 0.4 }, signal);
    let parsed: Plan;
    try {
      parsed = Plan.parse(extractJSON(raw));
      if (!parsed.needsClarification && parsed.tasks.length < 1) throw new Error('no tasks');
    } catch (e) {
      lastError = e instanceof Error ? e.message.slice(0, 400) : 'invalid';
      continue;
    }
    const tasks = parsed.tasks.map(t => ({ ...t, title: plain(t.title), rationale: plain(t.rationale) }));
    const steps = parsed.steps.map(plainStep).sort((a, b) => a.day - b.day);
    const checked = await withLinks<{ resources: Resource[] }>([...tasks, ...steps]);
    return {
      ...parsed,
      needsClarification: parsed.needsClarification ? plain(parsed.needsClarification) : null,
      feasibility: { ...parsed.feasibility, reason: plain(parsed.feasibility.reason) },
      tasks: tasks.map((t, i) => ({ ...t, resources: checked[i]!.resources })),
      steps: steps.map((s, i) => ({ ...s, resources: checked[tasks.length + i]!.resources })) as StepOut[],
    };
  }
  throw new AIUnavailable(`plan_invalid: ${lastError}`);
}

/** The next week of steps for a goal that is under way. */
export async function generateSteps(req: StepsRequest, signal?: AbortSignal): Promise<StepOut[]> {
  const prompt = JSON.stringify({ ...req, ...timeLeft(req.today, req.deadline) });
  let lastError = '';
  for (let attempt = 0; attempt < 2; attempt++) {
    const repair = attempt ? `\n\nYour previous output was invalid: ${lastError}. Return corrected JSON only.` : '';
    const raw = await runText(env.MODEL_PLANNER, { system_prompt: STEPS, prompt: prompt + repair, max_tokens: 2000, temperature: 0.4 }, signal);
    try {
      const steps = StepsReply.parse(extractJSON(raw)).steps.map(plainStep).sort((a, b) => a.day - b.day);
      return await withLinks(steps);
    } catch (e) {
      lastError = e instanceof Error ? e.message.slice(0, 400) : 'invalid';
    }
  }
  throw new AIUnavailable(`steps_invalid: ${lastError}`);
}
