import type { components } from './schema';

// Short names for the generated OpenAPI types (schema.ts is generated; do not edit it).
type Schemas = components['schemas'];

export type AuthResponse = Schemas['AuthResponse'];
export type TokenResponse = Schemas['TokenResponse'];
export type User = Schemas['UserResponse'];
export type Category = Schemas['CategoryResponse'];
export type Expense = Schemas['ExpenseResponse'];
export type ExpenseRequest = Schemas['ExpenseRequest'];
export type ExpensePage = Schemas['ExpensePageResponse'];
export type PaymentMethod = NonNullable<Expense['paymentMethod']>;
