import { describe, expect, it, vi } from 'vitest';
import { resendSender } from '../src/mailer.js';

const reply = (status: number) => new Response(status < 300 ? '{"id":"e1"}' : '{"message":"no"}', { status });

describe('Resend sender', () => {
  it('posts the message with the key and a verified sender', async () => {
    const f = vi.fn(async () => reply(200));
    await resendSender('re_test', 'The Bridge <hello@thebridge.app>', f as unknown as typeof fetch)({ to: 'a@example.com', subject: 'Reset', text: 'link' });
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('https://api.resend.com/emails');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer re_test');
    expect(JSON.parse(String(init.body))).toEqual({ from: 'The Bridge <hello@thebridge.app>', to: ['a@example.com'], subject: 'Reset', text: 'link' });
  });

  it('retries once when Resend is busy, and reports other failures by status only', async () => {
    const busy = vi.fn().mockResolvedValueOnce(reply(429)).mockResolvedValueOnce(reply(200));
    await resendSender('re_test', 'x@thebridge.app', busy as unknown as typeof fetch)({ to: 'a@example.com', subject: 's', text: 't' });
    expect(busy).toHaveBeenCalledTimes(2);
    const bad = vi.fn(async () => reply(422));
    await expect(resendSender('re_test', 'x@thebridge.app', bad as unknown as typeof fetch)({ to: 'a@example.com', subject: 's', text: 'secret-link' }))
      .rejects.toThrow('mail_send_failed_422');
    expect(bad).toHaveBeenCalledTimes(1);
  });
});
