import type { AxiosInstance } from 'axios'
import { api } from './client'
import type { GroupDetail, GroupSummary, Invite, InvitePreview, IssuedInvite } from './types'

export function groupsApi(client: AxiosInstance = api) {
  return {
    list: () => client.get<GroupSummary[]>('/groups').then((r) => r.data),

    listArchived: () => client.get<GroupSummary[]>('/groups/archived').then((r) => r.data),

    /**
     * Put a group away. Deliberately not a DELETE: nothing is destroyed, because the
     * ledger is immutable and a group's rows are the evidence behind balances its members
     * have already acted on.
     */
    archive: (groupId: string) =>
      client.post<GroupDetail>(`/groups/${groupId}/archive`).then((r) => r.data),

    unarchive: (groupId: string) =>
      client.post<GroupDetail>(`/groups/${groupId}/unarchive`).then((r) => r.data),

    create: (name: string) => client.post<GroupDetail>('/groups', { name }).then((r) => r.data),

    detail: (groupId: string) => client.get<GroupDetail>(`/groups/${groupId}`).then((r) => r.data),

    /** Removing yourself is leaving; the backend runs the same checks either way. */
    removeMember: (groupId: string, userId: string) =>
      client.delete<void>(`/groups/${groupId}/members/${userId}`).then(() => undefined),

    listInvites: (groupId: string) =>
      client.get<Invite[]>(`/groups/${groupId}/invites`).then((r) => r.data),

    invite: (groupId: string, email: string) =>
      client.post<IssuedInvite>(`/groups/${groupId}/invites`, { email }).then((r) => r.data),

    revokeInvite: (groupId: string, inviteId: string) =>
      client.delete<void>(`/groups/${groupId}/invites/${inviteId}`).then(() => undefined),
  }
}

/**
 * Redeeming is addressed by token rather than by group: the recipient is not a member yet
 * and cannot read anything under /groups. The preview needs no session, which is the
 * point -- the landing page says what the invite is for before asking anyone to sign in.
 */
export function invitesApi(client: AxiosInstance = api) {
  return {
    preview: (token: string) => client.get<InvitePreview>(`/invites/${token}`).then((r) => r.data),

    accept: (token: string) =>
      client.post<{ groupId: string }>(`/invites/${token}/accept`).then((r) => r.data),

    decline: (token: string) =>
      client.post<void>(`/invites/${token}/decline`).then(() => undefined),
  }
}
