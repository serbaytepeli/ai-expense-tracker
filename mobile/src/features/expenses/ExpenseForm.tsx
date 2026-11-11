import DateTimePicker, { type DateTimePickerEvent } from '@react-native-community/datetimepicker';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo, useState } from 'react';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Alert, Platform, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';

import { ApiError } from '../../api/errors';
import type { Category, Expense } from '../../api/types';
import {
  Button,
  CategoryBadge,
  ErrorBanner,
  FieldError,
  FieldLabel,
  TextField,
} from '../../components/ui';
import { errorMessage, formatDate } from '../../i18n/format';
import { useAppTheme } from '../../theme/useAppTheme';
import { activeCategories } from '../categories/api';
import { fromIsoDate, toIsoDate, todayIso } from './dates';
import {
  emptyForm,
  expenseFormSchema,
  PAYMENT_METHODS,
  toFormValues,
  toRequest,
  type ExpenseFormValues,
} from './expenseForm';

interface ExpenseFormProps {
  categories: Category[];
  /** The expense being edited; undefined for a new one. */
  expense?: Expense;
  onSave: (values: ReturnType<typeof toRequest>) => Promise<unknown>;
  onDelete?: () => Promise<unknown>;
}

const FORM_FIELDS: readonly FieldPath<ExpenseFormValues>[] = [
  'amount',
  'categoryId',
  'date',
  'description',
  'note',
  'paymentMethod',
];

function yesterdayIso(today: string): string {
  const date = fromIsoDate(today);
  date.setDate(date.getDate() - 1);
  return toIsoDate(date);
}

export function ExpenseForm({ categories, expense, onSave, onDelete }: ExpenseFormProps) {
  const { t } = useTranslation();
  const { colors } = useAppTheme();
  const today = todayIso();
  const [serverError, setServerError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [showAndroidPicker, setShowAndroidPicker] = useState(false);

  const {
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ExpenseFormValues>({
    resolver: zodResolver(expenseFormSchema(today)),
    defaultValues: expense ? toFormValues(expense) : emptyForm(today),
  });

  // Active categories; an edited expense may keep its archived category (api-contract.md section 5).
  const options = useMemo(() => {
    const active = activeCategories(categories);
    const current = expense?.category;
    if (current?.archived) {
      const archived = categories.find((c) => c.id === current.id);
      return archived ? [archived, ...active] : active;
    }
    return active;
  }, [categories, expense]);

  const onSubmit = handleSubmit(async (values) => {
    setServerError(null);
    try {
      await onSave(toRequest(values));
    } catch (error) {
      if (error instanceof ApiError) {
        for (const fieldError of error.fieldErrors) {
          const field = FORM_FIELDS.find((f) => f === fieldError.field);
          if (field) {
            setError(field, { type: 'server' });
          }
        }
      }
      setServerError(errorMessage(t, error));
    }
  });

  const confirmDelete = () => {
    if (!onDelete) {
      return;
    }
    Alert.alert(t('expenses.deleteConfirmTitle'), t('expenses.deleteConfirmMessage'), [
      { text: t('common.cancel'), style: 'cancel' },
      {
        text: t('common.delete'),
        style: 'destructive',
        onPress: () => {
          setDeleting(true);
          setServerError(null);
          onDelete().catch((error: unknown) => {
            setDeleting(false);
            setServerError(errorMessage(t, error));
          });
        },
      },
    ]);
  };

  return (
    // The form opens in a native-stack modal. On iOS the header can be drawn over the screen
    // (react-native-screens lays iOS 26+ screens out under the navigation bar), so the form is
    // placed below it by a SafeAreaView whose insets come from this nested provider: the
    // provider reports UIKit's safe area for its own frame, i.e. exactly the part covered by the
    // header (0 when the screen already starts below it) and the home indicator.
    // The ScrollView itself must not use contentInsetAdjustmentBehavior="automatic": when the
    // keyboard opens, React Native's automaticallyAdjustKeyboardInsets clamps the scroll offset
    // to contentInset.top, which ignores the automatic (adjusted) inset and scrolled the top of
    // the form back under the header.
    <SafeAreaProvider style={styles.flex}>
      <SafeAreaView
        edges={['top', 'bottom']}
        style={[styles.flex, { backgroundColor: colors.background }]}
        testID="expense-form-safe-area"
      >
        <ScrollView
          automaticallyAdjustKeyboardInsets
          contentContainerStyle={styles.content}
          keyboardShouldPersistTaps="handled"
          style={styles.flex}
          testID="expense-form"
        >
          {serverError ? <ErrorBanner message={serverError} /> : null}

          <Controller
            control={control}
            name="amount"
            render={({ field }) => (
              <TextField
                label={t('expenses.form.amount')}
                value={field.value}
                onChangeText={field.onChange}
                onBlur={field.onBlur}
                keyboardType="decimal-pad"
                placeholder={t('expenses.form.amountPlaceholder')}
                autoFocus={!expense}
                style={styles.amountInput}
                error={errors.amount ? t('expenses.validation.amount') : undefined}
                testID="expense-amount"
              />
            )}
          />

          <Controller
            control={control}
            name="categoryId"
            render={({ field }) => (
              <View style={styles.group}>
                <FieldLabel>{t('expenses.form.category')}</FieldLabel>
                <View style={styles.categoryGrid}>
                  {options.map((category) => {
                    const selected = field.value === category.id;
                    return (
                      <Pressable
                        key={category.id}
                        accessibilityRole="radio"
                        accessibilityState={{ selected }}
                        accessibilityLabel={category.name}
                        onPress={() => field.onChange(category.id)}
                        testID={`category-${category.id}`}
                        style={[
                          styles.categoryChip,
                          {
                            backgroundColor: colors.surface,
                            borderColor: selected ? category.color : colors.border,
                            borderWidth: selected ? 2 : 1,
                          },
                        ]}
                      >
                        <CategoryBadge icon={category.icon} color={category.color} size={32} />
                        <Text numberOfLines={1} style={[styles.chipText, { color: colors.text }]}>
                          {category.name}
                          {category.archived ? ` (${t('expenses.archived')})` : ''}
                        </Text>
                      </Pressable>
                    );
                  })}
                </View>
                {expense?.category.archived ? (
                  <Text style={{ color: colors.textMuted }}>
                    {t('expenses.form.archivedCategoryHint')}
                  </Text>
                ) : null}
                <FieldError
                  message={errors.categoryId ? t('expenses.validation.category') : undefined}
                />
              </View>
            )}
          />

          <Controller
            control={control}
            name="date"
            render={({ field }) => (
              <View style={styles.group}>
                <FieldLabel>{t('expenses.form.date')}</FieldLabel>
                <View style={styles.row}>
                  {[
                    { iso: today, label: t('expenses.form.today') },
                    { iso: yesterdayIso(today), label: t('expenses.form.yesterday') },
                  ].map((option) => (
                    <Chip
                      key={option.iso}
                      label={option.label}
                      selected={field.value === option.iso}
                      onPress={() => field.onChange(option.iso)}
                    />
                  ))}
                  {Platform.OS === 'ios' ? (
                    <DateTimePicker
                      value={fromIsoDate(field.value)}
                      mode="date"
                      display="compact"
                      locale="tr-TR"
                      maximumDate={fromIsoDate(today)}
                      onChange={(_: DateTimePickerEvent, date?: Date) => {
                        if (date) {
                          field.onChange(toIsoDate(date));
                        }
                      }}
                      testID="expense-date-picker"
                    />
                  ) : (
                    <Chip
                      label={formatDate(t, field.value)}
                      selected
                      onPress={() => setShowAndroidPicker(true)}
                      testID="expense-date"
                    />
                  )}
                </View>
                {Platform.OS !== 'ios' && showAndroidPicker ? (
                  <DateTimePicker
                    value={fromIsoDate(field.value)}
                    mode="date"
                    maximumDate={fromIsoDate(today)}
                    onChange={(event: DateTimePickerEvent, date?: Date) => {
                      setShowAndroidPicker(false);
                      if (event.type === 'set' && date) {
                        field.onChange(toIsoDate(date));
                      }
                    }}
                  />
                ) : null}
                <FieldError message={errors.date ? t('expenses.validation.date') : undefined} />
              </View>
            )}
          />

          <Controller
            control={control}
            name="description"
            render={({ field }) => (
              <TextField
                label={t('expenses.form.description')}
                hint={t('common.optional')}
                value={field.value}
                onChangeText={field.onChange}
                onBlur={field.onBlur}
                placeholder={t('expenses.form.descriptionPlaceholder')}
                maxLength={140}
                error={errors.description ? t('expenses.validation.description') : undefined}
                testID="expense-description"
              />
            )}
          />

          <Controller
            control={control}
            name="paymentMethod"
            render={({ field }) => (
              <View style={styles.group}>
                <FieldLabel>{t('expenses.form.paymentMethod')}</FieldLabel>
                <View style={styles.row}>
                  {(['none', ...PAYMENT_METHODS] as const).map((method) => (
                    <Chip
                      key={method}
                      label={t(`expenses.paymentMethods.${method}`)}
                      selected={field.value === method}
                      onPress={() => field.onChange(method)}
                      testID={`payment-${method}`}
                    />
                  ))}
                </View>
              </View>
            )}
          />

          <Controller
            control={control}
            name="note"
            render={({ field }) => (
              <TextField
                label={t('expenses.form.note')}
                hint={t('common.optional')}
                value={field.value}
                onChangeText={field.onChange}
                onBlur={field.onBlur}
                multiline
                maxLength={500}
                style={styles.note}
                error={errors.note ? t('expenses.validation.note') : undefined}
                testID="expense-note"
              />
            )}
          />

          <Button
            label={t('common.save')}
            onPress={onSubmit}
            loading={isSubmitting}
            disabled={deleting}
            icon="checkmark"
            testID="expense-save"
          />
          {onDelete ? (
            <Button
              label={t('common.delete')}
              variant="danger"
              onPress={confirmDelete}
              loading={deleting}
              disabled={isSubmitting}
              icon="trash-outline"
              testID="expense-delete"
            />
          ) : null}
        </ScrollView>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

function Chip({
  label,
  selected,
  onPress,
  testID,
}: {
  label: string;
  selected: boolean;
  onPress: () => void;
  testID?: string;
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
      <Text style={{ color: selected ? colors.onPrimary : colors.text, fontWeight: '500' }}>
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  content: { padding: 20, gap: 20, paddingBottom: 48 },
  group: { gap: 8 },
  row: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 8 },
  amountInput: { fontSize: 28, fontWeight: '700', minHeight: 60 },
  categoryGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  categoryChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingVertical: 6,
    paddingLeft: 6,
    paddingRight: 12,
    borderRadius: 24,
    maxWidth: '100%',
  },
  chipText: { fontSize: 14, flexShrink: 1 },
  chip: { paddingVertical: 8, paddingHorizontal: 14, borderRadius: 20, borderWidth: 1 },
  note: { minHeight: 80, paddingTop: 12, textAlignVertical: 'top' },
});
