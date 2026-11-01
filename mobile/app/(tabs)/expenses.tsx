import { useTranslation } from 'react-i18next';

import { PlaceholderScreen } from '../../src/components/PlaceholderScreen';

export default function ExpensesScreen() {
  const { t } = useTranslation();
  return (
    <PlaceholderScreen
      title={t('screens.expenses.title')}
      message={t('screens.expenses.placeholder')}
    />
  );
}
