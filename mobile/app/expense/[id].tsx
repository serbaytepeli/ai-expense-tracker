import { router, useLocalSearchParams } from 'expo-router';
import { useTranslation } from 'react-i18next';
import { View } from 'react-native';

import { ErrorBanner, LoadingView } from '../../src/components/ui';
import { useCategories } from '../../src/features/categories/api';
import { useDeleteExpense, useExpense, useSaveExpense } from '../../src/features/expenses/api';
import { ExpenseForm } from '../../src/features/expenses/ExpenseForm';
import { useFlash } from '../../src/features/expenses/flash';
import { errorMessage } from '../../src/i18n/format';

export default function EditExpenseScreen() {
  const { t } = useTranslation();
  const { id } = useLocalSearchParams<{ id: string }>();
  const expense = useExpense(id);
  const categories = useCategories();
  const save = useSaveExpense(id);
  const remove = useDeleteExpense();
  const flash = useFlash((s) => s.show);

  if (expense.isPending || categories.isPending) {
    return <LoadingView />;
  }
  if (expense.isError || categories.isError) {
    const error = expense.error ?? categories.error;
    return (
      <View style={{ padding: 20 }}>
        <ErrorBanner
          message={errorMessage(t, error)}
          onRetry={() => {
            void expense.refetch();
            void categories.refetch();
          }}
        />
      </View>
    );
  }
  return (
    <ExpenseForm
      key={expense.data.id}
      expense={expense.data}
      categories={categories.data}
      onSave={async (body) => {
        await save.mutateAsync(body);
        flash('saved');
        router.back();
      }}
      onDelete={async () => {
        await remove.mutateAsync(expense.data.id);
        flash('deleted');
        router.back();
      }}
    />
  );
}
