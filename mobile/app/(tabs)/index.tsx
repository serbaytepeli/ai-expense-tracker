import { router } from 'expo-router';
import { useTranslation } from 'react-i18next';
import { RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';

import { Button, EmptyState, ErrorBanner, LoadingView } from '../../src/components/ui';
import { useSessionStore } from '../../src/features/auth/sessionStore';
import { useMonthSummary } from '../../src/features/expenses/api';
import { currentMonth, toMonthParam } from '../../src/features/expenses/dates';
import { ExpenseRow } from '../../src/features/expenses/ExpenseRow';
import { FlashBanner } from '../../src/features/expenses/FlashBanner';
import { formatMoney } from '../../src/features/expenses/money';
import { errorMessage, monthName } from '../../src/i18n/format';
import { useAppTheme } from '../../src/theme/useAppTheme';

export default function HomeScreen() {
  const { t } = useTranslation();
  const { colors } = useAppTheme();
  const name = useSessionStore((s) => s.session?.user.displayName ?? '');
  const month = currentMonth();
  const summary = useMonthSummary(toMonthParam(month));

  return (
    <ScrollView
      style={{ backgroundColor: colors.background }}
      contentContainerStyle={styles.content}
      refreshControl={
        <RefreshControl
          refreshing={summary.isRefetching}
          onRefresh={() => void summary.refetch()}
        />
      }
    >
      <Text accessibilityRole="header" style={[styles.greeting, { color: colors.text }]}>
        {t('home.greeting', { name })}
      </Text>
      <FlashBanner />

      <View style={[styles.card, { backgroundColor: colors.primary }]}>
        <Text style={[styles.cardLabel, { color: colors.onPrimary }]}>
          {t('home.monthTotal', { month: monthName(t, month.month) })}
        </Text>
        <Text style={[styles.total, { color: colors.onPrimary }]} testID="month-total">
          {summary.data ? formatMoney(summary.data.total) : '—'}
        </Text>
        {summary.data ? (
          <Text style={{ color: colors.onPrimary }}>
            {t('home.expenseCount', { count: summary.data.count })}
          </Text>
        ) : null}
      </View>

      <Button
        label={t('home.addExpense')}
        icon="add-circle-outline"
        onPress={() => router.push('/expense/new')}
        testID="home-add-expense"
      />

      {summary.isPending ? <LoadingView /> : null}
      {summary.isError ? (
        <ErrorBanner
          message={errorMessage(t, summary.error)}
          onRetry={() => void summary.refetch()}
        />
      ) : null}
      {summary.data && summary.data.count === 0 ? (
        <EmptyState
          icon="receipt-outline"
          title={t('home.emptyTitle')}
          message={t('home.emptyMessage')}
        />
      ) : null}
      {summary.data && summary.data.count > 0 ? (
        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <Text style={[styles.sectionTitle, { color: colors.text }]}>{t('home.recent')}</Text>
            <Text
              accessibilityRole="link"
              onPress={() => router.navigate('/expenses')}
              style={{ color: colors.primary, fontWeight: '600' }}
            >
              {t('home.seeAll')}
            </Text>
          </View>
          {summary.data.latest.map((expense) => (
            <ExpenseRow
              key={expense.id}
              expense={expense}
              onPress={() => router.push(`/expense/${expense.id}`)}
            />
          ))}
        </View>
      ) : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: 20, gap: 16 },
  greeting: { fontSize: 24, fontWeight: '700' },
  card: { borderRadius: 20, padding: 20, gap: 6 },
  cardLabel: { fontSize: 15, opacity: 0.9 },
  total: { fontSize: 34, fontWeight: '800', fontVariant: ['tabular-nums'] },
  section: { gap: 8 },
  sectionHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  sectionTitle: { fontSize: 17, fontWeight: '600' },
});
