import { z } from 'zod'

/** Mirrors the backend's Bean Validation; the server still validates. */
export const createGroupSchema = z.object({
  name: z.string().min(1, 'Required').max(100, 'At most 100 characters'),
})

export const inviteSchema = z.object({
  email: z.string().min(1, 'Required').email('Enter a valid email').max(254),
})

export type CreateGroupValues = z.infer<typeof createGroupSchema>
export type InviteValues = z.infer<typeof inviteSchema>
