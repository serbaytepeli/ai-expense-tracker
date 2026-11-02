import { useQuery } from '@tanstack/react-query';

import { apiRequest } from '../../api/client';
import type { Category } from '../../api/types';

export const categoryKeys = { all: ['categories'] as const };

/** All of the user's categories, archived ones included (old expenses can use them). */
export function useCategories() {
  return useQuery({
    queryKey: categoryKeys.all,
    queryFn: () => apiRequest<Category[]>('/categories', { query: { includeArchived: 'true' } }),
    staleTime: 5 * 60_000,
  });
}

/** Categories a new expense may use: active only (archived -> 409 CATEGORY_ARCHIVED). */
export function activeCategories(categories: readonly Category[] | undefined): Category[] {
  return (categories ?? []).filter((c) => !c.archived);
}
