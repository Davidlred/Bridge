import { z } from 'zod';

const ISODate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);

const Routine = z.object({
  wake: z.string().regex(/^\d{2}:\d{2}$/),
  sleep: z.string().regex(/^\d{2}:\d{2}$/),
  busy: z.array(z.string().regex(/^\d{2}:\d{2}-\d{2}:\d{2}$/)).max(8),
  preferred: z.array(z.enum(['Morning', 'Midday', 'Afternoon', 'Evening', 'Night'])).max(5),
});

export const PlanRequest = z.object({
  goalText: z.string().trim().min(3).max(500),
  deadline: ISODate,
  today: ISODate,
  timezone: z.string().min(2).max(64),
  // The apps send the routine as the user wrote it (onboarding step 2); the structured form is kept for API clients.
  routine: z.union([Routine, z.string().trim().min(10).max(1000)]),
  locale: z.string().max(16).default('en'),
  existingGoals: z.number().int().min(0).max(10).default(0),
}).refine(r => r.deadline > r.today, { message: 'deadline must be after today', path: ['deadline'] });

/**
 * Something to read, watch or use. The model gives a url only when it is sure the page exists, otherwise a search phrase;
 * the server checks every link before the plan goes out (ai/links.ts), so the apps only ever see { title, url }.
 */
export const Resource = z.object({
  title: z.string().trim().min(2).max(80),
  url: z.string().trim().max(500).regex(/^https?:\/\//).nullish().transform(v => v ?? null),
  search: z.string().trim().max(100).nullish().transform(v => v ?? null),
});
export const Resources = z.array(Resource).max(3).catch([]).default([]);

/** A habit that repeats every day. */
export const PlanTask = z.object({
  title: z.string().min(3).max(80),
  priority: z.enum(['High', 'Medium', 'Low']),
  timeOfDay: z.string().min(2).max(24),
  minutes: z.number().int().min(5).max(90),
  rationale: z.string().min(5).max(200),
  resources: Resources,
});

/** A one-time action on a given day of the plan (1 = today): install this, read these chapters, book that. */
export const PlanStep = z.object({
  day: z.number().int().min(1).max(14),
  title: z.string().min(3).max(80),
  minutes: z.number().int().min(5).max(120),
  rationale: z.string().min(5).max(200),
  resources: Resources,
});

export const Feasibility = z.object({
  verdict: z.enum(['realistic', 'stretch', 'unrealistic']),
  reason: z.string().max(280),
  // The apps leave nulls out of their JSON (Android explicitNulls = false, iOS Codable), so missing means none.
  suggestedDeadline: ISODate.nullish().transform(v => v ?? null),
});

/** Reality Check after the deadline (or routine) changes: feasibility only, the plan stays as it is. */
export const RealityRequest = z.object({
  goalText: z.string().trim().min(3).max(500),
  deadline: ISODate,
  today: ISODate,
  routine: z.string().trim().max(1000).default(''),
  tasks: z.array(z.string().max(120)).max(20).default([]),
}).refine(r => r.deadline > r.today, { message: 'deadline must be after today', path: ['deadline'] });

export const Plan = z.object({
  needsClarification: z.string().max(200).nullish().transform(v => v ?? null),
  label: z.string().min(1).max(24),
  goal: z.string().min(8).max(200),
  category: z.enum(['fitness', 'study', 'language', 'career', 'money', 'creative', 'health', 'habits', 'other']).catch('other').default('other'),
  feasibility: Feasibility,
  tasks: z.array(PlanTask).max(6),
  steps: z.array(PlanStep).max(20).catch([]).default([]),
});

/** The next week of steps for a goal, after the first ones are done or used up. */
export const StepsRequest = z.object({
  goalText: z.string().trim().min(3).max(500),
  deadline: ISODate,
  today: ISODate,
  routine: z.string().trim().max(1000).default(''),
  habits: z.array(z.string().max(120)).max(20).default([]),
  done: z.array(z.string().max(120)).max(60).default([]),
  open: z.array(z.string().max(120)).max(30).default([]),
}).refine(r => r.deadline > r.today, { message: 'deadline must be after today', path: ['deadline'] });
export const StepsReply = z.object({ steps: z.array(PlanStep).min(1).max(14) });

export type PlanRequest = z.infer<typeof PlanRequest>;
export type Plan = z.infer<typeof Plan>;
export type PlanStep = z.infer<typeof PlanStep>;
export type Resource = z.infer<typeof Resource>;
export type StepsRequest = z.infer<typeof StepsRequest>;
export type Feasibility = z.infer<typeof Feasibility>;
export type RealityRequest = z.infer<typeof RealityRequest>;
