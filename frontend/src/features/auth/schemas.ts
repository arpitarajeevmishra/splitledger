import { z } from 'zod'

/**
 * Mirrors the backend's Bean Validation so the first failure is caught here, without a
 * round trip. The server still validates -- this is the convenience, not the control.
 * The 72-character ceiling is not arbitrary: BCrypt ignores anything beyond it.
 */
export const loginSchema = z.object({
  email: z.string().min(1, 'Required').email('Enter a valid email'),
  password: z.string().min(1, 'Required'),
})

export const registerSchema = z.object({
  email: z.string().min(1, 'Required').email('Enter a valid email').max(254),
  name: z.string().min(1, 'Required').max(100),
  password: z
    .string()
    .min(8, 'At least 8 characters')
    .max(72, 'At most 72 characters'),
})

export type LoginValues = z.infer<typeof loginSchema>
export type RegisterValues = z.infer<typeof registerSchema>
