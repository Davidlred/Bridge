import { z } from 'zod';

const ISODate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);

export const PlanRequest = z.object({
  goalText: z.string().trim().min(3).max(500),
  deadline: ISODate,
  today: ISODate,
  timezone: z.string().min(2).max(64),
  routine: z.object({
    wake: z.string().regex(/^\d{2}:\d{2}$/),
    sleep: z.string().regex(/^\d{2}:\d{2}$/),
    busy: z.array(z.string().regex(/^\d{2}:\d{2}-\d{2}:\d{2}$/)).max(8),
    preferred: z.array(z.enum(['Morning', 'Midday', 'Afternoon', 'Evening', 'Night'])).max(5),
  }),
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

export const Plan = z.object({
  needsClarification: z.string().max(200).nullable(),
  label: z.string().min(1).max(24),
  goal: z.string().min(8).max(200),
  feasibility: z.object({
    verdict: z.enum(['realistic', 'stretch', 'unrealistic']),
    reason: z.string().max(280),
    suggestedDeadline: ISODate.nullable(),
  }),
  tasks: z.array(PlanTask).max(6),
});

export type PlanRequest = z.infer<typeof PlanRequest>;
export type Plan = z.infer<typeof Plan>;
