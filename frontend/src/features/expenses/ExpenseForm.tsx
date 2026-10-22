import { useMemo, useState } from 'react'
import { messageFor } from '../../api/problem'
import type { GroupDetail } from '../../api/types'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { Field } from '../../components/ui/Field'
import { safeParsePaise } from '../../lib/money'
import { previewSplit, type SplitType } from '../../lib/split'
import { SplitEditor } from './SplitEditor'
import { useCreateExpense } from './useExpenses'

function today(): string {
  return new Date().toISOString().slice(0, 10)
}

/** Paise for EXACT, basis points for PERCENT, a plain count for SHARES. */
function parseValue(splitType: SplitType, raw: string): number {
  const trimmed = raw.trim()
  if (trimmed === '') {
    return 0
  }
  if (splitType === 'EXACT') {
    const parsed = safeParsePaise(trimmed)
    return 'paise' in parsed ? parsed.paise : 0
  }
  if (splitType === 'PERCENT') {
    // Basis points, so 33.33% is 3333 and the percentages can sum exactly.
    const asPaise = safeParsePaise(trimmed)
    return 'paise' in asPaise ? asPaise.paise : 0
  }
  const shares = Number(trimmed)
  return Number.isSafeInteger(shares) ? shares : 0
}

export function ExpenseForm({
  group,
  open,
  onClose,
}: {
  group: GroupDetail
  open: boolean
  onClose: () => void
}) {
  const create = useCreateExpense(group.id)
  const members = group.members

  const [description, setDescription] = useState('')
  const [amount, setAmount] = useState('')
  const [paidBy, setPaidBy] = useState(members[0]?.userId ?? '')
  const [date, setDate] = useState(today())
  const [splitType, setSplitType] = useState<SplitType>('EQUAL')
  const [selected, setSelected] = useState<Set<string>>(
    () => new Set(members.map((member) => member.userId)),
  )
  const [values, setValues] = useState<Map<string, string>>(new Map())
  const [formError, setFormError] = useState<string | null>(null)

  const parsedAmount = safeParsePaise(amount)
  const amountPaise = 'paise' in parsedAmount ? parsedAmount.paise : 0
  const participants = useMemo(
    () => members.map((member) => member.userId).filter((id) => selected.has(id)),
    [members, selected],
  )

  const preview = useMemo(() => {
    const numeric = new Map<string, number>()
    participants.forEach((id) => numeric.set(id, parseValue(splitType, values.get(id) ?? '')))
    return previewSplit(splitType, amountPaise, participants, numeric)
  }, [splitType, amountPaise, participants, values])

  const amountError = amount === '' ? null : 'error' in parsedAmount ? parsedAmount.error : null
  // The server would refuse an unbalanced split anyway; refusing here means nobody has to
  // submit to find out.
  const submittable =
    description.trim() !== '' &&
    amountPaise > 0 &&
    paidBy !== '' &&
    preview.error === null &&
    preview.remainingPaise === 0

  function close() {
    setFormError(null)
    onClose()
  }

  async function submit() {
    setFormError(null)
    try {
      await create.mutateAsync({
        expense: {
          description: description.trim(),
          amountPaise,
          paidBy,
          expenseDate: date,
          splitType,
          participants: participants.map((userId) => ({
            userId,
            ...(splitType === 'EQUAL'
              ? {}
              : { value: parseValue(splitType, values.get(userId) ?? '') }),
          })),
        },
        // One key per submission: a retry after a dropped connection must reuse it, and
        // a genuinely new expense must not.
        idempotencyKey: crypto.randomUUID(),
      })
      setDescription('')
      setAmount('')
      setValues(new Map())
      close()
    } catch (error) {
      setFormError(messageFor(error, 'Could not add that expense.'))
    }
  }

  return (
    <Dialog open={open} onClose={close} title="Add expense">
      <div className="flex max-h-[70vh] flex-col gap-4 overflow-y-auto">
        <Field
          label="Description"
          name="description"
          placeholder="Dinner"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />
        <Field
          label="Amount"
          name="amount"
          placeholder="1,000.50"
          inputMode="decimal"
          value={amount}
          onChange={(event) => setAmount(event.target.value)}
          error={amountError ?? undefined}
        />

        <div className="flex flex-col gap-2">
          <label
            htmlFor="paid-by"
            className="text-[13px] font-medium tracking-[0.01em] text-ink-muted"
          >
            Paid by
          </label>
          <select
            id="paid-by"
            value={paidBy}
            onChange={(event) => setPaidBy(event.target.value)}
            className="h-10 rounded border border-line bg-surface-1 px-3 font-mono text-sm text-ink focus:border-info focus:outline-none"
          >
            {members.map((member) => (
              <option key={member.userId} value={member.userId}>
                {member.name}
              </option>
            ))}
          </select>
        </div>

        <Field
          label="Date"
          name="expenseDate"
          type="date"
          value={date}
          onChange={(event) => setDate(event.target.value)}
        />

        <SplitEditor
          members={members}
          splitType={splitType}
          onSplitTypeChange={setSplitType}
          selected={selected}
          onToggle={(userId) =>
            setSelected((current) => {
              const next = new Set(current)
              if (next.has(userId)) {
                next.delete(userId)
              } else {
                next.add(userId)
              }
              return next
            })
          }
          values={values}
          onValueChange={(userId, raw) => setValues((current) => new Map(current).set(userId, raw))}
          preview={preview}
        />

        {formError !== null && (
          <p
            role="alert"
            className="rounded border border-negative/40 bg-negative/10 px-3 py-2 text-xs text-negative"
          >
            {formError}
          </p>
        )}

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={close}>
            Cancel
          </Button>
          <Button
            type="button"
            onClick={() => void submit()}
            disabled={!submittable || create.isPending}
          >
            {create.isPending ? 'Adding\u2026' : 'Add expense'}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
