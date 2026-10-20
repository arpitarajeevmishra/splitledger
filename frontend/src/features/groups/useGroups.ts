import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { groupsApi } from '../../api/groups'
import type { GroupDetail, GroupSummary, Invite, IssuedInvite } from '../../api/types'

const api = groupsApi()

/**
 * One place for the cache keys. Writing them inline is how a mutation ends up
 * invalidating a key that nothing reads, which fails silently -- the screen simply does
 * not update.
 */
export const groupKeys = {
  all: ['groups'] as const,
  list: () => [...groupKeys.all, 'list'] as const,
  detail: (groupId: string) => [...groupKeys.all, 'detail', groupId] as const,
  invites: (groupId: string) => [...groupKeys.all, 'invites', groupId] as const,
}

export function useGroups() {
  return useQuery<GroupSummary[]>({ queryKey: groupKeys.list(), queryFn: api.list })
}

export function useGroup(groupId: string) {
  return useQuery<GroupDetail>({
    queryKey: groupKeys.detail(groupId),
    queryFn: () => api.detail(groupId),
  })
}

export function useCreateGroup() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (name: string) => api.create(name),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: groupKeys.list() }),
  })
}

export function useGroupInvites(groupId: string, enabled: boolean) {
  return useQuery<Invite[]>({
    queryKey: groupKeys.invites(groupId),
    queryFn: () => api.listInvites(groupId),
    // Admin-only on the server: asking as a plain member is a 403, so do not ask.
    enabled,
  })
}

export function useInviteToGroup(groupId: string) {
  const queryClient = useQueryClient()
  return useMutation<IssuedInvite, unknown, string>({
    mutationFn: (email: string) => api.invite(groupId, email),
    // The issued link is returned to the caller, never written into the cache: the
    // listing deliberately does not carry tokens, and caching one would put a live
    // credential somewhere it can be re-read.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: groupKeys.invites(groupId) }),
  })
}

export function useRevokeInvite(groupId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (inviteId: string) => api.revokeInvite(groupId, inviteId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: groupKeys.invites(groupId) }),
  })
}

export function useRemoveMember(groupId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (userId: string) => api.removeMember(groupId, userId),
    onSuccess: async () => {
      // Both: the detail's member list shrinks, and leaving removes the group from your
      // own list entirely.
      await queryClient.invalidateQueries({ queryKey: groupKeys.detail(groupId) })
      await queryClient.invalidateQueries({ queryKey: groupKeys.list() })
    },
  })
}
