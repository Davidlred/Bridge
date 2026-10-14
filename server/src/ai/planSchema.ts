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

export const PlanTask = z.object({
  title: z.string().min(3).max(80),
  priority: z.enum(['High', 'Medium', 'Low']),
  timeOfDay: z.string().min(2).max(24),
  minutes: z.number().int().min(5).max(90),
  rationale: z.string().min(5).max(200),
});

export const Feasibility = z.object({
  verdict: z.enum(['realistic', 'stretch', 'unrealistic']),
  reason: z.string().max(280),
  suggestedDeadline: ISODate.nullable(),
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
  needsClarification: z.string().max(200).nullable(),
  label: z.string().min(1).max(24),
  goal: z.string().min(8).max(200),
  feasibility: Feasibility,
  tasks: z.array(PlanTask).max(6),
});

export type PlanRequest = z.infer<typeof PlanRequest>;
export type Plan = z.infer<typeof Plan>;
export type Feasibility = z.infer<typeof Feasibility>;
export type RealityRequest = z.infer<typeof RealityRequest>;
