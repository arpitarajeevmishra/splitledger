import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders, SOMEONE } from '../../test/renderWithProviders'
import { InvitePage } from './InvitePage'

// vi.hoisted, because vi.mock is lifted above plain const declarations and InvitePage
// calls invitesApi() at module scope -- so the factory runs while the spies would still
// be in their temporal dead zone.
const { preview, accept, decline } = vi.hoisted(() => ({
  preview: vi.fn(),
  accept: vi.fn(),
  decline: vi.fn(),
}))
vi.mock('../../api/groups', () => ({
  invitesApi: () => ({ preview, accept, decline }),
}))

function notFound() {
  const error = new AxiosError('failed')
  error.response = {
    status: 404,
    statusText: '',
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
    data: { code: 'INVITE_INVALID' },
  }
  return error
}

const INVITED = {
  groupName: 'Flat 402',
  email: 'bob@example.com',
  expiresAt: '2026-11-01T10:00:00Z',
}

function renderInvite(auth: Parameters<typeof renderWithProviders>[1] = {}) {
  renderWithProviders(<InvitePage />, {
    ...auth,
    route: '/invite/tok-123',
    path: '/invite/:token',
  })
}

describe('InvitePage', () => {
  beforeEach(() => {
    preview.mockReset()
    accept.mockReset()
    decline.mockReset()
  })

  it('names the group before anyone has signed in', async () => {
    preview.mockResolvedValue(INVITED)
    renderInvite({ auth: { status: 'anonymous', user: null } })

    expect(await screen.findByText(/You are invited to Flat 402/)).toBeInTheDocument()
    // The whole point of the preview being public: it says which address to use.
    expect(screen.getByText('bob@example.com')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Accept/ })).not.toBeInTheDocument()
  })

  it('does not say whether a bad link was unknown, revoked, used or expired', async () => {
    preview.mockRejectedValue(notFound())
    renderInvite({ auth: { status: 'anonymous', user: null } })

    expect(await screen.findByText('This link is not valid')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(
      'This invite link is no longer valid. Ask for a new one.',
    )
  })

  it('refuses to offer acceptance to the wrong account, and says which one to use', async () => {
    preview.mockResolvedValue(INVITED)
    // Signed in as alice@example.com; the invite is addressed to bob@example.com.
    renderInvite({ auth: { status: 'authenticated', user: SOMEONE } })

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /signed in as .*alice@example.com.*sent to someone else/s,
    )
    expect(screen.queryByRole('button', { name: /Accept/ })).not.toBeInTheDocument()
    expect(accept).not.toHaveBeenCalled()
  })

  it('matches the invited address regardless of how it was typed', async () => {
    preview.mockResolvedValue({ ...INVITED, email: 'Alice@Example.COM' })
    renderInvite({ auth: { status: 'authenticated', user: SOMEONE } })

    expect(await screen.findByRole('button', { name: 'Accept and join' })).toBeInTheDocument()
  })

  it('accepts and lands in the group', async () => {
    preview.mockResolvedValue({ ...INVITED, email: SOMEONE.email })
    accept.mockResolvedValue({ groupId: 'g1' })
    renderInvite({ auth: { status: 'authenticated', user: SOMEONE } })

    await userEvent.click(await screen.findByRole('button', { name: 'Accept and join' }))

    expect(accept).toHaveBeenCalledWith('tok-123')
  })

  it('confirms a decline rather than silently doing nothing', async () => {
    preview.mockResolvedValue({ ...INVITED, email: SOMEONE.email })
    decline.mockResolvedValue(undefined)
    renderInvite({ auth: { status: 'authenticated', user: SOMEONE } })

    await userEvent.click(await screen.findByRole('button', { name: 'Decline' }))

    expect(await screen.findByText('Invite declined')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Accept and join' })).not.toBeInTheDocument()
  })

  it('explains a refused acceptance', async () => {
    preview.mockResolvedValue({ ...INVITED, email: SOMEONE.email })
    accept.mockRejectedValue(notFound())
    renderInvite({ auth: { status: 'authenticated', user: SOMEONE } })

    await userEvent.click(await screen.findByRole('button', { name: 'Accept and join' }))

    expect(await screen.findByText(/no longer valid/)).toBeInTheDocument()
  })
})
