import { env } from '../env.js';
import { runText, AIUnavailable } from './replicate.js';
import { Plan, type PlanRequest } from './planSchema.js';

const SYSTEM = `You are the planning engine for The Bridge, a goal coach app.
Turn the user's goal into a realistic daily plan.
Rules:
- The "deadline" field is authoritative. Ignore any date written in the goal text. If the text contradicts the deadline, say so in feasibility.reason. Never output a different deadline except as feasibility.suggestedDeadline.
- If the goal is too vague to plan (fewer than 3 meaningful words, or no clear outcome), set needsClarification to ONE short question and return an empty tasks array.
- Otherwise return 2 to 6 daily tasks. Each takes 90 minutes or less, fits outside busy blocks and between wake and sleep, and prefers the user's preferred times.
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
