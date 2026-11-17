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

/** "The Bridge <x@gmail.com>" or "x@gmail.com" → name + address. */
export function parseFrom(from: string): { name?: string; email: string } {
  const m = /^\s*(.*?)\s*<([^>]+)>\s*$/.exec(from);
  return m ? { ...(m[1] ? { name: m[1].replace(/^"|"$/g, '') } : {}), email: m[2]!.trim() } : { email: from.trim() };
}

/**
 * Sends through Brevo's HTTP API (https://developers.brevo.com/reference/sendtransacemail). Brevo accepts a single sender
 * address verified in its dashboard, so no domain is needed. Same retry and logging rules as Resend.
 */
export function brevoSender(apiKey: string, from: string, fetchImpl: typeof fetch = fetch) {
  const sender = parseFrom(from);
  return async (mail: Mail): Promise<void> => {
    for (let attempt = 0; ; attempt++) {
      const r = await fetchImpl('https://api.brevo.com/v3/smtp/email', {
        method: 'POST',
        headers: { 'api-key': apiKey, 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ sender, to: [{ email: mail.to }], subject: mail.subject, textContent: mail.text }),
      });
      if (r.ok) return;
      if (attempt === 0 && (r.status === 429 || r.status >= 500)) { await new Promise(res => setTimeout(res, 1000)); continue; }
      throw new Error(`mail_send_failed_${r.status}`);
    }
  };
}

/**
 * Transactional email. With MAIL_FROM and RESEND_API_KEY it goes through Resend, or with BREVO_API_KEY through Brevo.
 * Without either, development prints the message so links can be clicked, and production refuses (a sign-up that can't
 * confirm its email is worse than an error).
 */
export let sendMail = async (mail: Mail): Promise<void> => {
  if (env.RESEND_API_KEY && env.MAIL_FROM) return resendSender(env.RESEND_API_KEY, env.MAIL_FROM)(mail);
  if (env.BREVO_API_KEY && env.MAIL_FROM) return brevoSender(env.BREVO_API_KEY, env.MAIL_FROM)(mail);
  if (env.NODE_ENV === 'production') throw new Error('mailer_not_configured');
  if (env.NODE_ENV === 'development') console.log(`\n--- mail to ${mail.to}: ${mail.subject}\n${mail.text}\n---`);
};

/** Tests swap in a capturing sender. */
export const setMailer = (fn: typeof sendMail) => { sendMail = fn; };
