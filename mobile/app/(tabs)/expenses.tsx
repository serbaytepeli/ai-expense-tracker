import Ionicons from '@expo/vector-icons/Ionicons';
import { router } from 'expo-router';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import { EmptyState, ErrorBanner, LoadingView } from '../../src/components/ui';
import { useCategories } from '../../src/features/categories/api';
import { useExpenseList } from '../../src/features/expenses/api';
import {
  currentMonth,
  isSameMonth,
  shiftMonth,
  toMonthParam,
  type YearMonth,
} from '../../src/features/expenses/dates';
import { ExpenseRow } from '../../src/features/expenses/ExpenseRow';
import { FlashBanner } from '../../src/features/expenses/FlashBanner';
import { errorMessage, formatYearMonth } from '../../src/i18n/format';
import { useAppTheme } from '../../src/theme/useAppTheme';

export default function ExpensesScreen() {
  const { t } = useTranslation();
  const { colors } = useAppTheme();
  const [month, setMonth] = useState<YearMonth>(() => currentMonth());
  const [categoryId, setCategoryId] = useState<string | undefined>(undefined);
  const categories = useCategories();
  const filter = useMemo(() => ({ month: toMonthParam(month), categoryId }), [month, categoryId]);
  const list = useExpenseList(filter);
  const items = useMemo(() => list.data?.pages.flatMap((page) => page.items) ?? [], [list.data]);
  const atCurrentMonth = isSameMonth(month, currentMonth());

  return (
    <View style={[styles.flex, { backgroundColor: colors.background }]}>
      <View style={styles.filters}>
        <View style={styles.monthRow}>
          <IconButton
            icon="chevron-back"
            label={t('expenses.previousMonth')}
            onPress={() => setMonth((m) => shiftMonth(m, -1))}
            testID="month-previous"
          />
          <Text style={[styles.month, { color: colors.text }]} testID="month-label">
            {formatYearMonth(t, month)}
          </Text>
          <IconButton
            icon="chevron-forward"
            label={t('expenses.nextMonth')}
            onPress={() => setMonth((m) => shiftMonth(m, 1))}
            disabled={atCurrentMonth}
            testID="month-next"
          />
        </View>
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.chips}
        >
          <FilterChip
            label={t('expenses.allCategories')}
            selected={categoryId === undefined}
            onPress={() => setCategoryId(undefined)}
            testID="filter-all"
          />
          {(categories.data ?? []).map((category) => (
            <FilterChip
              key={category.id}
              label={category.name}
              color={category.color}
              selected={categoryId === category.id}
              onPress={() => setCategoryId(category.id)}
              testID={`filter-${category.id}`}
            />
          ))}
        </ScrollView>
        <FlashBanner />
      </View>

      {list.isPending ? (
        <LoadingView />
      ) : list.isError ? (
        <View style={styles.padded}>
          <ErrorBanner
            message={`${t('expenses.loadError')} ${errorMessage(t, list.error)}`}
            onRetry={() => void list.refetch()}
          />
        </View>
      ) : (
        <FlatList
          data={items}
          keyExtractor={(expense) => expense.id}
          renderItem={({ item }) => (
            <ExpenseRow expense={item} onPress={() => router.push(`/expense/${item.id}`)} />
          )}
          contentContainerStyle={styles.list}
          onEndReachedThreshold={0.4}
          onEndReached={() => {
            if (list.hasNextPage && !list.isFetchingNextPage) {
              void list.fetchNextPage();
            }
          }}
          refreshControl={
            <RefreshControl
              refreshing={list.isRefetching && !list.isFetchingNextPage}
              onRefresh={() => void list.refetch()}
            />
          }
          ListEmptyComponent={
            <EmptyState
              icon="search-outline"
              title={t('expenses.emptyTitle')}
              message={t('expenses.emptyMessage')}
            />
          }
          ListFooterComponent={
            list.isFetchingNextPage ? <ActivityIndicator color={colors.primary} /> : undefined
          }
          testID="expense-list"
        />
      )}

      <Pressable
        accessibilityRole="button"
        accessibilityLabel={t('expenses.add')}
        onPress={() => router.push('/expense/new')}
        style={[styles.fab, { backgroundColor: colors.primary }]}
        testID="add-expense"
      >
        <Ionicons name="add" size={30} style={{ color: colors.onPrimary }} />
      </Pressable>
    </View>
  );
}

function IconButton({
  icon,
  label,
  onPress,
  disabled,
  testID,
}: {
  icon: 'chevron-back' | 'chevron-forward';
  label: string;
  onPress: () => void;
  disabled?: boolean;
  testID: string;
}) {
  const { colors } = useAppTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: Boolean(disabled) }}
      disabled={disabled}
      onPress={onPress}
      hitSlop={12}
      style={{ opacity: disabled ? 0.3 : 1, padding: 6 }}
      testID={testID}
    >
      <Ionicons name={icon} size={24} style={{ color: colors.text }} />
    </Pressable>
  );
}

function FilterChip({
  label,
  selected,
  onPress,
  color,
  testID,
}: {
  label: string;
  selected: boolean;
  onPress: () => void;
  color?: string;
  testID: string;
}) {
  const { colors } = useAppTheme();
  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityState={{ selected }}
      onPress={onPress}
      testID={testID}
      style={[
        styles.chip,
        {
          backgroundColor: selected ? colors.primary : colors.surface,
          borderColor: selected ? colors.primary : colors.border,
        },
      ]}
    >
      {color ? <View style={[styles.dot, { backgroundColor: color }]} /> : null}
      <Text style={{ color: selected ? colors.onPrimary : colors.text }}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  filters: { paddingTop: 12, paddingHorizontal: 16, gap: 10 },
  monthRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  month: { fontSize: 18, fontWeight: '600' },
  chips: { gap: 8, paddingVertical: 2 },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingVertical: 7,
    paddingHorizontal: 12,
    borderRadius: 18,
    borderWidth: 1,
  },
  dot: { width: 10, height: 10, borderRadius: 5 },
  list: { padding: 16, gap: 8, paddingBottom: 96 },
  padded: { padding: 16 },
  fab: {
    position: 'absolute',
    right: 20,
    bottom: 24,
    width: 60,
    height: 60,
    borderRadius: 30,
    alignItems: 'center',
    justifyContent: 'center',
    elevation: 4,
    shadowColor: '#000',
    shadowOpacity: 0.2,
    shadowRadius: 6,
    shadowOffset: { width: 0, height: 3 },
  },
});
