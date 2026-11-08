import { router } from 'expo-router';
import { useTranslation } from 'react-i18next';
import { ScrollView } from 'react-native';

import { ErrorBanner, LoadingView } from '../../src/components/ui';
import { useCategories } from '../../src/features/categories/api';
import { useSaveExpense } from '../../src/features/expenses/api';
import { ExpenseForm } from '../../src/features/expenses/ExpenseForm';
import { useFlash } from '../../src/features/expenses/flash';
import { errorMessage } from '../../src/i18n/format';

export default function NewExpenseScreen() {
  const { t } = useTranslation();
  const categories = useCategories();
  const save = useSaveExpense();
  const flash = useFlash((s) => s.show);

  if (categories.isPending) {
    return <LoadingView />;
  }
  if (categories.isError) {
    return (
      // Same inset as the form: the iOS modal header is drawn over the screen.
      <ScrollView
        contentInsetAdjustmentBehavior="automatic"
        contentContainerStyle={{ padding: 20 }}
      >
        <ErrorBanner
          message={errorMessage(t, categories.error)}
          onRetry={() => void categories.refetch()}
        />
      </ScrollView>
    );
  }
  return (
    <ExpenseForm
      categories={categories.data}
      onSave={async (body) => {
        await save.mutateAsync(body);
        flash('saved');
        router.back();
      }}
    />
  );
}
