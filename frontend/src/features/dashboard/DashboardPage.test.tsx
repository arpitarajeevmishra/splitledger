import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/renderWithProviders'
import { DashboardPage } from './DashboardPage'

const { useMyNetPosition } = vi.hoisted(() => ({ useMyNetPosition: vi.fn() }))
vi.mock('../expenses/useExpenses', () => ({ useMyNetPosition }))
vi.mock('../groups/GroupsPanel', () => ({ GroupsPanel: () => <div /> }))

/**
 * The landing screen's one number.
 *
 * It was hardcoded to formatPaise(0) from Sprint 2 until this was written, so everybody
 * was told they were square whatever they owed. These tests are mostly about the sign:
 * a balance that is confidently wrong about direction is worse than no balance at all.
 */
describe('DashboardPage', () => {
  beforeEach(() => {
    useMyNetPosition.mockReset()
  })

  it('says you owe when the figure is negative', () => {
    useMyNetPosition.mockReturnValue({
      data: { netPaise: -45_000, unsettledGroups: 1, settled: false },
    })
    renderWithProviders(<DashboardPage />)

    // Shown unsigned, with the direction in words: a minus sign in front of money is easy
    // to miss on the one screen meant to answer "am I square".
    expect(screen.getByText('₹450.00')).toBeInTheDocument()
    expect(screen.getByText(/you owe this, across 1 group\./i)).toBeInTheDocument()
  })

  it('says you are owed when it is positive', () => {
    useMyNetPosition.mockReturnValue({
      data: { netPaise: 120_000, unsettledGroups: 3, settled: false },
    })
    renderWithProviders(<DashboardPage />)

    expect(screen.getByText('₹1,200.00')).toBeInTheDocument()
    expect(screen.getByText(/you are owed this, across 3 groups\./i)).toBeInTheDocument()
  })

  it('says nothing is owed when it is settled', () => {
    useMyNetPosition.mockReturnValue({
      data: { netPaise: 0, unsettledGroups: 0, settled: true },
    })
    renderWithProviders(<DashboardPage />)

    expect(screen.getByText('₹0.00')).toBeInTheDocument()
    expect(screen.getByText(/nothing owed either way\./i)).toBeInTheDocument()
    // And emphatically not the old copy, which promised a feature that shipped weeks ago.
    expect(screen.queryByText(/next sprint/i)).not.toBeInTheDocument()
  })

  it('shows no figure at all until it knows one', () => {
    // A zero while loading is a lie that looks like an answer, which is exactly how the
    // hardcoded version went unnoticed for so long.
    useMyNetPosition.mockReturnValue({ data: undefined })
    renderWithProviders(<DashboardPage />)

    expect(screen.queryByText('₹0.00')).not.toBeInTheDocument()
    expect(screen.getByText(/working out where you stand/i)).toBeInTheDocument()
  })
})
