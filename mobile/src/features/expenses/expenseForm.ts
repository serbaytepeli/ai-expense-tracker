import { z } from 'zod';

import type { Expense, ExpenseRequest, PaymentMethod } from '../../api/types';
import { todayIso } from './dates';
import { parseAmountInput, toInputText } from './money';

export const PAYMENT_METHODS = [
  'CASH',
  'CARD',
  'OTHER',
] as const satisfies readonly PaymentMethod[];

function codePoints(value: string): number {
  return [...value].length;
}

/** Form rules mirror the API (api-contract.md section 5); the server checks them again. */
export function expenseFormSchema(today: string = todayIso()) {
  return z.object({
    amount: z.string().refine((value) => parseAmountInput(value) !== null),
    categoryId: z.string().min(1),
    // ISO strings compare like dates.
    date: z.string().refine((value) => value <= today),
    description: z.string().refine((value) => codePoints(value.trim()) <= 140),
    note: z.string().refine((value) => codePoints(value.trim()) <= 500),
    paymentMethod: z.enum(['none', ...PAYMENT_METHODS]),
  });
}

export type ExpenseFormValues = z.infer<ReturnType<typeof expenseFormSchema>>;

export function emptyForm(today: string = todayIso()): ExpenseFormValues {
  return {
    amount: '',
    categoryId: '',
    date: today,
    description: '',
    note: '',
    paymentMethod: 'none',
  };
}

export function toFormValues(expense: Expense): ExpenseFormValues {
  return {
    amount: toInputText(expense.amount),
    categoryId: expense.category.id,
    date: expense.date,
    description: expense.description ?? '',
    note: expense.note ?? '',
    paymentMethod: expense.paymentMethod ?? 'none',
  };
}

/** Valid form values to the request body; the amount becomes the API string ("1250.50"). */
export function toRequest(values: ExpenseFormValues): ExpenseRequest {
  const amount = parseAmountInput(values.amount);
  if (amount === null) {
    throw new Error('amount was not validated');
  }
  const description = values.description.trim();
  const note = values.note.trim();
  return {
    amount,
    categoryId: values.categoryId,
    date: values.date,
    description: description === '' ? null : description,
    note: note === '' ? null : note,
    paymentMethod: values.paymentMethod === 'none' ? null : values.paymentMethod,
  };
}
