import { env } from './env.js';

export type Mail = { to: string; subject: string; text: string };

/**
 * Transactional email. No provider is chosen yet: in development the message is printed so links can be clicked;
 * in production this must be replaced with a real sender (see server/README.md → "Before launch").
 */
export let sendMail = async (mail: Mail): Promise<void> => {
  if (env.NODE_ENV === 'production') throw new Error('mailer_not_configured');
  if (env.NODE_ENV === 'development') console.log(`\n--- mail to ${mail.to}: ${mail.subject}\n${mail.text}\n---`);
};

/** Tests swap in a capturing sender. */
export const setMailer = (fn: typeof sendMail) => { sendMail = fn; };
