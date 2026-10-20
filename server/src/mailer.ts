import { env } from './env.js';

export type Mail = { to: string; subject: string; text: string };

/**
 * Sends through Resend's HTTP API (https://resend.com/docs/api-reference/emails/send-email). `from` must be on a domain
 * verified in Resend. Retries once on a 429 or 5xx; anything else is the caller's error to log (never the body: it holds
 * one-time links).
 */
export function resendSender(apiKey: string, from: string, fetchImpl: typeof fetch = fetch) {
  return async (mail: Mail): Promise<void> => {
    for (let attempt = 0; ; attempt++) {
      const r = await fetchImpl('https://api.resend.com/emails', {
        method: 'POST',
        headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ from, to: [mail.to], subject: mail.subject, text: mail.text }),
      });
      if (r.ok) return;
      if (attempt === 0 && (r.status === 429 || r.status >= 500)) { await new Promise(res => setTimeout(res, 1000)); continue; }
      throw new Error(`mail_send_failed_${r.status}`);
    }
  };
}

/**
 * Transactional email. With RESEND_API_KEY and MAIL_FROM set it goes through Resend. Without them, development prints the
 * message so links can be clicked, and production refuses (a sign-up that can't confirm its email is worse than an error).
 */
export let sendMail = async (mail: Mail): Promise<void> => {
  if (env.RESEND_API_KEY && env.MAIL_FROM) return resendSender(env.RESEND_API_KEY, env.MAIL_FROM)(mail);
  if (env.NODE_ENV === 'production') throw new Error('mailer_not_configured');
  if (env.NODE_ENV === 'development') console.log(`\n--- mail to ${mail.to}: ${mail.subject}\n${mail.text}\n---`);
};

/** Tests swap in a capturing sender. */
export const setMailer = (fn: typeof sendMail) => { sendMail = fn; };
