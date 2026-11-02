import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiRequest } from '../../api/client';
import type { Expense, ExpensePage, ExpenseRequest } from '../../api/types';
import { sumAmounts } from './money';

export interface ExpenseFilter {
  month: string; // YYYY-MM
  categoryId?: string;
}

export const PAGE_SIZE = 20;
/** The largest page the API allows; used to add up a month. */
const MAX_PAGE_SIZE = 100;

export const expenseKeys = {
  all: ['expenses'] as const,
  list: (filter: ExpenseFilter) => ['expenses', 'list', filter] as const,
  detail: (id: string) => ['expenses', 'detail', id] as const,
  monthTotal: (month: string) => ['expenses', 'monthTotal', month] as const,
};

function fetchPage(filter: ExpenseFilter, page: number, size: number) {
  return apiRequest<ExpensePage>('/expenses', {
    query: { month: filter.month, categoryId: filter.categoryId, page, size },
  });
}

/** Paged list, newest first; the next page loads when the list reaches its end. */
export function useExpenseList(filter: ExpenseFilter) {
  return useInfiniteQuery({
    queryKey: expenseKeys.list(filter),
    queryFn: ({ pageParam }) => fetchPage(filter, pageParam, PAGE_SIZE),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
  });
}

export interface MonthSummary {
  total: string;
  count: number;
  latest: Expense[];
}

/**
 * Month total from the expense list (no report API yet: T3.1). Pages are added up exactly
 * with sumAmounts; a month rarely has more than one page of 100.
 */
export function useMonthSummary(month: string) {
  return useQuery({
    queryKey: expenseKeys.monthTotal(month),
    queryFn: async (): Promise<MonthSummary> => {
      const first = await fetchPage({ month }, 0, MAX_PAGE_SIZE);
      const items = [...first.items];
      for (let page = 1; page < first.totalPages; page++) {
        items.push(...(await fetchPage({ month }, page, MAX_PAGE_SIZE)).items);
      }
      return {
        total: sumAmounts(items.map((e) => e.amount)),
        count: first.totalItems,
        latest: items.slice(0, 5),
      };
    },
  });
}

export function useExpense(id: string | undefined) {
  return useQuery({
    queryKey: expenseKeys.detail(id ?? ''),
    queryFn: () => apiRequest<Expense>(`/expenses/${encodeURIComponent(id ?? '')}`),
    enabled: Boolean(id),
  });
}

/** Create, update and delete refresh every expense list and total. */
export function useSaveExpense(id?: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: ExpenseRequest) =>
      id
        ? apiRequest<Expense>(`/expenses/${encodeURIComponent(id)}`, { method: 'PUT', body })
        : apiRequest<Expense>('/expenses', { method: 'POST', body }),
    onSuccess: () => client.invalidateQueries({ queryKey: expenseKeys.all }),
  });
}

export function useDeleteExpense() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      apiRequest<void>(`/expenses/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    onSuccess: (_, id) => {
      client.removeQueries({ queryKey: expenseKeys.detail(id) });
      return client.invalidateQueries({ queryKey: expenseKeys.all });
    },
  });
}
