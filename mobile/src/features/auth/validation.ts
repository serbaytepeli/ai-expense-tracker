import { z } from 'zod';

/** UTF-8 size: BCrypt uses only the first 72 bytes, so the server rejects longer passwords (FR-1). */
export function utf8Length(value: string): number {
  let bytes = 0;
  for (const char of value) {
    const code = char.codePointAt(0) ?? 0;
    bytes += code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4;
  }
  return bytes;
}

// Same shape check as the backend: something@domain, at most 254 characters.
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const email = z
  .string()
  .trim()
  .max(254)
  .refine((value) => EMAIL.test(value));

export const loginSchema = z.object({
  email,
  password: z.string().min(1),
});

/** Error messages are internal codes; the form shows the translated text. */
export const registerSchema = z.object({
  displayName: z.string().trim().min(1).max(60),
  email,
  password: z
    .string()
    .refine((value) => [...value].length >= 8 && [...value].length <= 72, { message: 'length' })
    .refine((value) => utf8Length(value) <= 72, { message: 'bytes' }),
});

export type LoginValues = z.infer<typeof loginSchema>;
export type RegisterValues = z.infer<typeof registerSchema>;
