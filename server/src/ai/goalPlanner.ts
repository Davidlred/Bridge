import { env } from '../env.js';
import { runText, AIUnavailable } from './replicate.js';
import { Feasibility, Plan, type PlanRequest, type RealityRequest } from './planSchema.js';

const SYSTEM = `You are the planning engine for The Bridge, a goal coach app.
Turn the user's goal into a realistic daily plan.
Rules:
- The "deadline" field is authoritative. Ignore any date written in the goal text. If the text contradicts the deadline, say so in feasibility.reason. Never output a different deadline except as feasibility.suggestedDeadline.
- If the goal is too vague to plan (fewer than 3 meaningful words, or no clear outcome), set needsClarification to ONE short question and return an empty tasks array.
- Otherwise return 2 to 6 daily tasks. Each takes 90 minutes or less, fits outside busy blocks and between wake and sleep, and prefers the user's preferred times.
- The routine may be free text written by the user. Read wake and sleep times, work hours and habits from it, and build on the habits they want to change.
- Week one is deliberately light so the plan sticks.
- priority: High for the task that most moves the goal, Low for supporting habits.
- Each rationale is one plain sentence. No motivational filler.
- Health, money or legal goals: general guidance only; add "check with a professional" to a rationale where relevant.
- If the goal is harmful (self-harm, disordered eating, illegal activity), set needsClarification to a supportive sentence and return no tasks.
- label: 1 to 3 words naming the goal (e.g. "Half marathon").
Output ONLY a JSON object with keys: needsClarification, label, goal, feasibility {verdict, reason, suggestedDeadline}, tasks [{title, priority, timeOfDay, minutes, rationale}]. No prose, no code fences.`;

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
- Deadlines more than 3 years away: suggest working in milestones in the reason.
Output ONLY a JSON object: {"verdict", "reason", "suggestedDeadline"}. No prose, no code fences.`;

/** Feasibility only: used when the deadline changes, so the plan itself is left alone. */
export async function assessReality(req: RealityRequest, signal?: AbortSignal): Promise<Feasibility> {
  let lastError = '';
  for (let attempt = 0; attempt < 2; attempt++) {
    const repair = attempt ? `

Your previous output was invalid: ${lastError}. Return corrected JSON only.` : '';
    const raw = await runText(env.MODEL_PLANNER, { system_prompt: REALITY, prompt: JSON.stringify(req) + repair, max_tokens: 300, temperature: 0.2 }, signal);
    try {
      const f = Feasibility.parse(extractJSON(raw));
      return f.suggestedDeadline && f.suggestedDeadline <= req.today ? { ...f, suggestedDeadline: null } : f;
    } catch (e) {
      lastError = e instanceof Error ? e.message.slice(0, 400) : 'invalid';
    }
  }
  throw new AIUnavailable(`reality_invalid: ${lastError}`);
}

export async function generatePlan(req: PlanRequest, signal?: AbortSignal): Promise<Plan> {
  const prompt = JSON.stringify(req);
  let lastError = '';
  for (let attempt = 0; attempt < 2; attempt++) {
    const repair = attempt ? `\n\nYour previous output was invalid: ${lastError}. Return corrected JSON only.` : '';
    const raw = await runText(env.MODEL_PLANNER, { system_prompt: SYSTEM, prompt: prompt + repair, max_tokens: 1500, temperature: 0.4 }, signal);
    try {
      const plan = Plan.parse(extractJSON(raw));
      if (!plan.needsClarification && plan.tasks.length < 2) throw new Error('fewer than 2 tasks');
      return plan;
    } catch (e) {
      lastError = e instanceof Error ? e.message.slice(0, 400) : 'invalid';
    }
  }
  throw new AIUnavailable(`plan_invalid: ${lastError}`);
}
