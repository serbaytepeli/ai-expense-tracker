import { act, fireEvent, renderRouter, screen, waitFor, within } from 'expo-router/testing-library';
import { Alert } from 'react-native';

import type { Category, Expense } from '../src/api/types';
import { useSessionStore } from '../src/features/auth/sessionStore';
import { todayIso } from '../src/features/expenses/dates';
import tr from '../src/i18n/locales/tr.json';
import {
  installFetch,
  problem,
  resetSession,
  savedSession,
  signInAs,
  USER,
  type Call,
} from '../test-support/fakeBackend';

// Renders the real app/ directory against an in-memory backend (no persistent mock data in the app).
const APP_DIR = './app';

const CATEGORIES: Category[] = [
  {
    id: 'c-market',
    name: 'Market',
    icon: 'cart-outline',
    color: '#2E7D32',
    systemKey: 'GROCERIES',
    sortOrder: 1,
    archived: false,
  },
  {
    id: 'c-old',
    name: 'Eski',
    icon: 'archive-outline',
    color: '#616161',
    systemKey: null,
    sortOrder: 2,
    archived: true,
  },
  {
    id: 'c-other',
    name: 'Diğer',
    icon: 'ellipsis-horizontal-outline',
    color: '#616161',
    systemKey: 'OTHER',
    sortOrder: 11,
    archived: false,
  },
];

function expense(id: string, amount: string, overrides: Partial<Expense> = {}): Expense {
  return {
    id,
    amount,
    currency: 'TRY',
    date: todayIso(),
    category: {
      id: 'c-market',
      name: 'Market',
      icon: 'cart-outline',
      color: '#2E7D32',
      archived: false,
    },
    description: null,
    note: null,
    paymentMethod: null,
    createdAt: '2026-11-01T10:00:00Z',
    updatedAt: '2026-11-01T10:00:00Z',
    ...overrides,
  };
}

/** Minimal stateful backend: auth, categories, expenses. */
function backend(initial: Expense[] = []) {
  const expenses = [...initial];
  const calls = installFetch((call: Call) => {
    const auth = { accessToken: 'access-1', refreshToken: 'refresh-1', expiresIn: 900, user: USER };
    if (call.path === '/auth/login') {
      const body = call.body as { password: string };
      return body.password === 'correct-horse'
        ? { status: 200, body: auth }
        : problem(401, 'INVALID_CREDENTIALS');
    }
    if (call.path === '/auth/register') {
      const body = call.body as { displayName: string };
      return { status: 201, body: { ...auth, user: { ...USER, displayName: body.displayName } } };
    }
    if (call.path === '/auth/logout') {
      return { status: 204 };
    }
    if (call.path === '/categories') {
      return { status: 200, body: CATEGORIES };
    }
    if (call.path === '/expenses' && call.method === 'GET') {
      const size = Number(call.query.size);
      const page = Number(call.query.page);
      const matching = expenses.filter(
        (e) => !call.query.categoryId || e.category.id === call.query.categoryId,
      );
      return {
        status: 200,
        body: {
          items: matching.slice(page * size, page * size + size),
          page,
          size,
          totalItems: matching.length,
          totalPages: Math.ceil(matching.length / size),
        },
      };
    }
    if (call.path === '/expenses' && call.method === 'POST') {
      const created = expense(`e-${expenses.length + 1}`, (call.body as { amount: string }).amount);
      expenses.unshift(created);
      return { status: 201, body: created };
    }
    const match = /^\/expenses\/(.+)$/.exec(call.path);
    if (match) {
      const index = expenses.findIndex((e) => e.id === match[1]);
      if (index < 0) {
        return problem(404, 'NOT_FOUND');
      }
      if (call.method === 'DELETE') {
        expenses.splice(index, 1);
        return { status: 204 };
      }
      if (call.method === 'PUT') {
        expenses[index] = { ...expenses[index], amount: (call.body as { amount: string }).amount };
      }
      return { status: 200, body: expenses[index] };
    }
    return problem(404, 'NOT_FOUND');
  });
  return { calls, expenses };
}

beforeEach(async () => {
  await resetSession();
  useSessionStore.setState({ status: 'loading' });
});

describe('authentication', () => {
  it('starts on the login screen and signs in', async () => {
    backend([expense('e-1', '149.90', { description: 'Haftalık market' })]);
    await renderRouter(APP_DIR);

    expect(await screen.findByRole('header', { name: tr.auth.loginTitle })).toBeOnTheScreen();
    await fireEvent.changeText(screen.getByTestId('login-email'), ' ali@example.com ');
    await fireEvent.changeText(screen.getByTestId('login-password'), 'correct-horse');
    await fireEvent.press(screen.getByTestId('login-submit'));

    expect(await screen.findByText('Merhaba, Ali')).toBeOnTheScreen();
    await waitFor(() => expect(screen.getByTestId('month-total')).toHaveTextContent('₺149,90'));
    expect(screen.getByText('1 harcama')).toBeOnTheScreen();
    expect(screen.getByText('Haftalık market')).toBeOnTheScreen();
    expect(await savedSession()).toMatchObject({ accessToken: 'access-1', user: { id: USER.id } });
  });

  it('shows the Turkish message for wrong credentials', async () => {
    backend();
    await renderRouter(APP_DIR);

    await fireEvent.changeText(await screen.findByTestId('login-email'), 'ali@example.com');
    await fireEvent.changeText(screen.getByTestId('login-password'), 'wrong-password');
    await fireEvent.press(screen.getByTestId('login-submit'));

    expect(await screen.findByText(tr.errors.INVALID_CREDENTIALS)).toBeOnTheScreen();
    expect(useSessionStore.getState().status).toBe('signedOut');
  });

  it('validates the login form before calling the server', async () => {
    const { calls } = backend();
    await renderRouter(APP_DIR);

    await fireEvent.changeText(await screen.findByTestId('login-email'), 'not-an-email');
    await fireEvent.press(screen.getByTestId('login-submit'));

    expect(await screen.findByText(tr.auth.validation.email)).toBeOnTheScreen();
    expect(screen.getByText(tr.auth.validation.passwordRequired)).toBeOnTheScreen();
    expect(calls).toHaveLength(0);
  });

  it('registers with the device time zone and opens the home screen', async () => {
    const { calls } = backend();
    await renderRouter(APP_DIR, { initialUrl: '/register' });

    await fireEvent.changeText(await screen.findByTestId('register-name'), '  Ayşe ');
    await fireEvent.changeText(screen.getByTestId('register-email'), 'ayse@example.com');
    await fireEvent.changeText(screen.getByTestId('register-password'), 'ğüşiöçİı');
    await fireEvent.press(screen.getByTestId('register-submit'));

    expect(await screen.findByText('Merhaba, Ayşe')).toBeOnTheScreen();
    const register = calls.find((c) => c.path === '/auth/register');
    expect(register?.body).toMatchObject({ displayName: 'Ayşe', email: 'ayse@example.com' });
    expect(typeof (register?.body as { timeZone?: unknown }).timeZone).toBe('string');
  });

  it('rejects passwords over 72 UTF-8 bytes in the form', async () => {
    const { calls } = backend();
    await renderRouter(APP_DIR, { initialUrl: '/register' });

    await fireEvent.changeText(await screen.findByTestId('register-name'), 'Ayşe');
    await fireEvent.changeText(screen.getByTestId('register-email'), 'ayse@example.com');
    // 40 characters, 80 bytes.
    await fireEvent.changeText(screen.getByTestId('register-password'), 'ğ'.repeat(40));
    await fireEvent.press(screen.getByTestId('register-submit'));

    expect(await screen.findByText(tr.auth.validation.passwordBytes)).toBeOnTheScreen();
    expect(calls).toHaveLength(0);
  });

  it('keeps the session after the app restarts', async () => {
    backend();
    await signInAs();
    useSessionStore.setState({ status: 'loading', session: null });

    await renderRouter(APP_DIR);

    expect(await screen.findByText('Merhaba, Ali')).toBeOnTheScreen();
    expect(await screen.findByText(tr.home.emptyTitle)).toBeOnTheScreen();
  });

  it('logs out and forgets the session', async () => {
    const { calls } = backend();
    await signInAs();
    useSessionStore.setState({ status: 'loading', session: null });
    await renderRouter(APP_DIR, { initialUrl: '/settings' });

    await fireEvent.press(await screen.findByTestId('logout'));

    expect(await screen.findByRole('header', { name: tr.auth.loginTitle })).toBeOnTheScreen();
    expect(
      calls.some((c) => c.path === '/auth/logout' && c.headers.Authorization === 'Bearer access-1'),
    ).toBe(true);
    expect(await savedSession()).toBeNull();
  });
});

describe('expenses', () => {
  beforeEach(async () => {
    await signInAs();
    useSessionStore.setState({ status: 'loading', session: null });
  });

  it('adds an expense in Turkish number format', async () => {
    const { calls } = backend();
    await renderRouter(APP_DIR);

    await fireEvent.press(await screen.findByTestId('home-add-expense'));
    await fireEvent.changeText(await screen.findByTestId('expense-amount'), '1.250,50');
    // Archived categories are not offered for a new expense.
    expect(screen.queryByTestId('category-c-old')).toBeNull();
    await fireEvent.press(screen.getByTestId('category-c-market'));
    await fireEvent.changeText(screen.getByTestId('expense-description'), ' Haftalık market ');
    await fireEvent.press(screen.getByTestId('payment-CARD'));
    await fireEvent.press(screen.getByTestId('expense-save'));

    await waitFor(() =>
      expect(calls.some((c) => c.method === 'POST' && c.path === '/expenses')).toBe(true),
    );
    const post = calls.find((c) => c.method === 'POST' && c.path === '/expenses');
    expect(post?.body).toEqual({
      amount: '1250.50',
      categoryId: 'c-market',
      date: todayIso(),
      description: 'Haftalık market',
      note: null,
      paymentMethod: 'CARD',
    });
    expect(await screen.findByText(tr.expenses.saved)).toBeOnTheScreen();
    await waitFor(() => expect(screen.getByTestId('month-total')).toHaveTextContent('₺1.250,50'));
  });

  it('lays the form out below the iOS modal header and above the keyboard', async () => {
    backend();
    await renderRouter(APP_DIR, { initialUrl: '/expense/new' });

    // iOS draws the modal header over the screen; without these the amount field sits under it
    // (bug seen on iPhone) and the keyboard inset is computed in the wrong coordinates.
    const form = await screen.findByTestId('expense-form');
    expect(form.props.contentInsetAdjustmentBehavior).toBe('automatic');
    expect(form.props.automaticallyAdjustKeyboardInsets).toBe(true);
    // The amount field is the first field and stays inside the scrollable form.
    expect(within(form).getByTestId('expense-amount')).toBeOnTheScreen();
  });

  it('does not send an invalid amount or a missing category', async () => {
    const { calls } = backend();
    await renderRouter(APP_DIR, { initialUrl: '/expense/new' });

    await fireEvent.changeText(await screen.findByTestId('expense-amount'), '1,234');
    await fireEvent.press(screen.getByTestId('expense-save'));

    expect(await screen.findByText(tr.expenses.validation.amount)).toBeOnTheScreen();
    expect(screen.getByText(tr.expenses.validation.category)).toBeOnTheScreen();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('shows the server error when the category was archived meanwhile', async () => {
    const { calls } = backend();
    const handler = globalThis.fetch;
    globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) =>
      init?.method === 'POST'
        ? new Response(JSON.stringify({ status: 409, code: 'CATEGORY_ARCHIVED' }), { status: 409 })
        : handler(input, init),
    ) as typeof fetch;
    await renderRouter(APP_DIR, { initialUrl: '/expense/new' });

    await fireEvent.changeText(await screen.findByTestId('expense-amount'), '10');
    await fireEvent.press(screen.getByTestId('category-c-market'));
    await fireEvent.press(screen.getByTestId('expense-save'));

    expect(await screen.findByText(tr.errors.CATEGORY_ARCHIVED)).toBeOnTheScreen();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('edits an expense and keeps its archived category selectable', async () => {
    const archived = expense('e-1', '10.00', {
      category: {
        id: 'c-old',
        name: 'Eski',
        icon: 'archive-outline',
        color: '#616161',
        archived: true,
      },
    });
    const { calls } = backend([archived]);
    await renderRouter(APP_DIR, { initialUrl: '/expense/e-1' });

    expect(await screen.findByDisplayValue('10,00')).toBeOnTheScreen();
    expect(screen.getByTestId('category-c-old')).toBeOnTheScreen();
    expect(screen.getByText(tr.expenses.form.archivedCategoryHint)).toBeOnTheScreen();
    await fireEvent.changeText(screen.getByTestId('expense-amount'), '12,5');
    await fireEvent.press(screen.getByTestId('expense-save'));

    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true));
    expect(calls.find((c) => c.method === 'PUT')?.body).toMatchObject({
      amount: '12.50',
      categoryId: 'c-old',
    });
  });

  it('deletes an expense after confirmation', async () => {
    const alert = jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => {
      buttons?.find((b) => b.style === 'destructive')?.onPress?.();
    });
    const { calls, expenses } = backend([expense('e-1', '10.00')]);
    await renderRouter(APP_DIR, { initialUrl: '/expense/e-1' });

    await fireEvent.press(await screen.findByTestId('expense-delete'));

    await waitFor(() =>
      expect(calls.some((c) => c.method === 'DELETE' && c.path === '/expenses/e-1')).toBe(true),
    );
    expect(alert).toHaveBeenCalledWith(
      tr.expenses.deleteConfirmTitle,
      tr.expenses.deleteConfirmMessage,
      expect.any(Array),
    );
    expect(expenses).toHaveLength(0);
    alert.mockRestore();
  });

  it('does not delete when the confirmation is cancelled', async () => {
    const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => undefined);
    const { calls } = backend([expense('e-1', '10.00')]);
    await renderRouter(APP_DIR, { initialUrl: '/expense/e-1' });

    await fireEvent.press(await screen.findByTestId('expense-delete'));

    expect(alert).toHaveBeenCalled();
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);
    alert.mockRestore();
  });

  it('lists expenses with a category filter and loads the next page', async () => {
    const many = Array.from({ length: 25 }, (_, i) => expense(`e-${i}`, `${i + 1}.00`));
    many.push(
      expense('e-other', '7.77', {
        category: {
          id: 'c-other',
          name: 'Diğer',
          icon: 'ellipsis-horizontal-outline',
          color: '#616161',
          archived: false,
        },
      }),
    );
    const { calls } = backend(many);
    await renderRouter(APP_DIR, { initialUrl: '/expenses' });

    expect(await screen.findByTestId('expense-e-0')).toBeOnTheScreen();
    const list = screen.getByTestId('expense-list');
    await act(async () => {
      list.props.onEndReached();
    });
    await waitFor(() =>
      expect(calls.some((c) => c.path === '/expenses' && c.query.page === '1')).toBe(true),
    );

    await fireEvent.press(screen.getByTestId('filter-c-other'));
    expect(await screen.findByTestId('expense-e-other')).toBeOnTheScreen();
    expect(calls.some((c) => c.query.categoryId === 'c-other')).toBe(true);
    expect(screen.queryByTestId('expense-e-0')).toBeNull();
  });

  it('switches months and cannot go past the current month', async () => {
    const { calls } = backend();
    await renderRouter(APP_DIR, { initialUrl: '/expenses' });

    expect(await screen.findByText(tr.expenses.emptyTitle)).toBeOnTheScreen();
    expect(screen.getByTestId('month-next')).toBeDisabled();
    await fireEvent.press(screen.getByTestId('month-previous'));

    const [year, month] = todayIso().split('-').map(Number);
    const previous =
      month === 1 ? `${year - 1}-12` : `${year}-${String(month - 1).padStart(2, '0')}`;
    await waitFor(() => expect(calls.some((c) => c.query.month === previous)).toBe(true));
  });
});
