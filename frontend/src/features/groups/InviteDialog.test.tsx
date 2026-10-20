import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/renderWithProviders'
import { InviteDialog } from './InviteDialog'

const invite = vi.fn()
vi.mock('./useGroups', () => ({
  useInviteToGroup: () => ({ mutateAsync: invite, isPending: false }),
}))

function problem(status: number, code: string) {
  const error = new AxiosError('failed')
  error.response = {
    status,
    statusText: '',
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
    data: { code },
  }
  return error
}

function renderDialog(onClose = vi.fn()) {
  renderWithProviders(<InviteDialog groupId="g1" groupName="Flat 402" open onClose={onClose} />)
  return onClose
}

describe('InviteDialog', () => {
  beforeEach(() => {
    invite.mockReset()
  })

  it('will not send a malformed address to the API', async () => {
    renderDialog()

    await userEvent.type(screen.getByLabelText('Email'), 'not-an-email')
    await userEvent.click(screen.getByRole('button', { name: 'Create invite' }))

    expect(await screen.findByText('Enter a valid email')).toBeInTheDocument()
    expect(invite).not.toHaveBeenCalled()
  })

  it('shows the link once the invite exists, and says it will not be shown again', async () => {
    invite.mockResolvedValue({
      id: 'i1',
      email: 'bob@example.com',
      url: 'https://app.test/invite/tok-123',
      expiresAt: '2026-11-01T10:00:00Z',
    })
    renderDialog()

    await userEvent.type(screen.getByLabelText('Email'), 'bob@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Create invite' }))

    expect(await screen.findByText('https://app.test/invite/tok-123')).toBeInTheDocument()
    // The server stores only a hash, so there is no second chance to read it. The screen
    // has to say so, or someone closes the dialog and quietly loses the link.
    expect(screen.getByText(/only time the link is shown/i)).toBeInTheDocument()
  })

  it('copies the link to the clipboard', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    invite.mockResolvedValue({
      id: 'i1',
      email: 'bob@example.com',
      url: 'https://app.test/invite/tok-123',
      expiresAt: '2026-11-01T10:00:00Z',
    })
    renderDialog()

    await userEvent.type(screen.getByLabelText('Email'), 'bob@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Create invite' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Copy link' }))

    expect(writeText).toHaveBeenCalledWith('https://app.test/invite/tok-123')
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument()
  })

  it('survives a clipboard the browser refuses, because the link is on screen anyway', async () => {
    Object.assign(navigator, {
      clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) },
    })
    invite.mockResolvedValue({
      id: 'i1',
      email: 'bob@example.com',
      url: 'https://app.test/invite/tok-123',
      expiresAt: '2026-11-01T10:00:00Z',
    })
    renderDialog()

    await userEvent.type(screen.getByLabelText('Email'), 'bob@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Create invite' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Copy link' }))

    expect(screen.getByText('https://app.test/invite/tok-123')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy link' })).toBeInTheDocument()
  })

  it('explains a rejected invite in the dialog rather than closing it', async () => {
    invite.mockRejectedValue(problem(409, 'ALREADY_GROUP_MEMBER'))
    const onClose = renderDialog()

    await userEvent.type(screen.getByLabelText('Email'), 'bob@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Create invite' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('They are already in this group.')
    expect(onClose).not.toHaveBeenCalled()
  })

  it('closes on Escape', async () => {
    const onClose = renderDialog()

    await userEvent.keyboard('{Escape}')

    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })
})
