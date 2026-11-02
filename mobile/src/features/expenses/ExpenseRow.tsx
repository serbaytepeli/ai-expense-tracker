import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import type { Expense } from '../../api/types';
import { CategoryBadge } from '../../components/ui';
import { formatDate } from '../../i18n/format';
import { useAppTheme } from '../../theme/useAppTheme';
import { formatMoney } from './money';

export function ExpenseRow({ expense, onPress }: { expense: Expense; onPress: () => void }) {
  const { colors } = useAppTheme();
  const { t } = useTranslation();
  const title = expense.description ?? expense.category.name;
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      testID={`expense-${expense.id}`}
      style={({ pressed }) => [
        styles.row,
        { backgroundColor: colors.surface, opacity: pressed ? 0.85 : 1 },
      ]}
    >
      <CategoryBadge icon={expense.category.icon} color={expense.category.color} />
      <View style={styles.texts}>
        <Text numberOfLines={1} style={[styles.title, { color: colors.text }]}>
          {title}
        </Text>
        <Text numberOfLines={1} style={[styles.subtitle, { color: colors.textMuted }]}>
          {expense.description ? `${expense.category.name} · ` : ''}
          {formatDate(t, expense.date)}
        </Text>
      </View>
      <Text style={[styles.amount, { color: colors.text }]}>{formatMoney(expense.amount)}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    padding: 12,
    borderRadius: 14,
  },
  texts: { flex: 1, gap: 2 },
  title: { fontSize: 16, fontWeight: '500' },
  subtitle: { fontSize: 13 },
  amount: { fontSize: 16, fontWeight: '600', fontVariant: ['tabular-nums'] },
});
