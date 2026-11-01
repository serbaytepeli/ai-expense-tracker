# AI Expense Tracker — Veritabanı Tasarımı

> Durum: **TASLAK — ürün sahibi onayı bekleniyor**
> Veritabanı: PostgreSQL (güncel kararlı sürüm, T0'da sabitlenir). Şema değişiklikleri yalnızca Flyway migration dosyalarıyla yapılır.

## 1. Genel kurallar

- Birincil anahtarlar: `UUID` (`gen_random_uuid()`), mobilde tahmin edilemez kimlikler için.
- Zaman damgaları: `TIMESTAMPTZ`, UTC; her tabloda `created_at`, değişebilen tablolarda `updated_at`.
- Para: `NUMERIC(14,2)` — en fazla 999.999.999.999,99.
- Kullanıcıya ait her tabloda `user_id` + `ON DELETE CASCADE` (hesap silme için).
- Tablo ve sütun adları `snake_case`, tablo adları çoğul.
- Silme: harcamalar kalıcı silinir (hard delete); kategoriler arşivlenir.

## 2. ER diyagramı

```
users 1 ──── * refresh_tokens
users 1 ──── * categories
users 1 ──── * expenses * ──── 1 categories
users 1 ──── * ai_insights
users 1 ──── * ai_usage
```

## 3. Tablolar

### users
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | |
| email | VARCHAR(254) | NOT NULL | Küçük harfe çevrilerek saklanır |
| password_hash | VARCHAR(100) | NOT NULL | BCrypt |
| display_name | VARCHAR(60) | NOT NULL | |
| currency | CHAR(3) | NOT NULL DEFAULT 'TRY' | ISO 4217 (KARAR-3) |
| time_zone | VARCHAR(64) | NOT NULL DEFAULT 'Europe/Istanbul' | IANA (KARAR-4) |
| locale | VARCHAR(10) | NOT NULL DEFAULT 'tr-TR' | |
| created_at / updated_at | TIMESTAMPTZ | NOT NULL | |

İndeks: `UNIQUE (email)`.

### refresh_tokens
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | |
| user_id | UUID | FK → users, CASCADE | |
| token_hash | CHAR(64) | NOT NULL, UNIQUE | Belirtecin SHA-256 özeti (ham belirteç saklanmaz) |
| expires_at | TIMESTAMPTZ | NOT NULL | |
| revoked_at | TIMESTAMPTZ | NULL | Rotasyon / çıkışta dolar |
| created_at | TIMESTAMPTZ | NOT NULL | |

İndeks: `(user_id)`.

### categories
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | |
| user_id | UUID | FK → users, CASCADE, NOT NULL | Hazır kategoriler de kullanıcıya kopyalanır |
| name | VARCHAR(30) | NOT NULL | |
| icon | VARCHAR(40) | NOT NULL | İkon anahtarı (ör. `cart`) |
| color | CHAR(7) | NOT NULL | `#RRGGBB` |
| system_key | VARCHAR(30) | NULL | Hazır kategorinin sabit anahtarı (ör. `GROCERIES`, `OTHER`); kullanıcının eklediklerinde NULL |
| sort_order | SMALLINT | NOT NULL DEFAULT 0 | |
| archived_at | TIMESTAMPTZ | NULL | NULL = aktif |
| created_at / updated_at | TIMESTAMPTZ | NOT NULL | |

İndeksler / kısıtlar:
- `UNIQUE (user_id, lower(name)) WHERE archived_at IS NULL` — aktif kategorilerde ad tekrarı yok (FR-3 AC).
- `UNIQUE (user_id, system_key) WHERE system_key IS NOT NULL`.
- `CHECK (color ~ '^#[0-9A-Fa-f]{6}$')`.

> Tasarım notu: Hazır kategoriler ortak bir tabloda tutulmak yerine kayıt anında kullanıcıya kopyalanır. Böylece kullanıcı yeniden adlandırabilir/arşivleyebilir ve her sorgu tek bir `user_id` filtresiyle çalışır. Kopyalama işlemi kayıt ile aynı transaction içinde yapılır.

### expenses
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | |
| user_id | UUID | FK → users, CASCADE, NOT NULL | |
| category_id | UUID | FK → categories, RESTRICT, NOT NULL | Kategoriler silinmediği için RESTRICT güvenli |
| amount | NUMERIC(14,2) | NOT NULL, CHECK (amount > 0) | |
| expense_date | DATE | NOT NULL | Kullanıcının yerel tarihi (KARAR-4) |
| description | VARCHAR(140) | NULL | |
| note | VARCHAR(500) | NULL | AI'a gönderilmez (KARAR-8) |
| payment_method | VARCHAR(10) | NULL, CHECK IN ('CASH','CARD','OTHER') | |
| created_at / updated_at | TIMESTAMPTZ | NOT NULL | |

İndeksler:
- `(user_id, expense_date DESC, created_at DESC)` — liste ve aylık rapor.
- `(user_id, category_id, expense_date)` — kategori filtresi.

Uygulama katmanında ek kural: `category_id` aynı kullanıcıya ait olmalı (servis doğrular; ileride bileşik FK ile de garanti edilebilir).

### ai_insights
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | |
| user_id | UUID | FK → users, CASCADE, NOT NULL | |
| period_month | DATE | NOT NULL | Ayın ilk günü (ör. 2026-10-01) |
| data_fingerprint | CHAR(64) | NOT NULL | Gönderilen özetin SHA-256'sı |
| summary | TEXT | NOT NULL | |
| observations | JSONB | NOT NULL | `string[]` |
| suggestions | JSONB | NOT NULL | `string[]` |
| provider | VARCHAR(30) | NOT NULL | ör. `anthropic` |
| model | VARCHAR(60) | NOT NULL | |
| prompt_version | VARCHAR(20) | NOT NULL | ör. `v1` |
| input_tokens / output_tokens | INTEGER | NULL | Maliyet takibi |
| created_at | TIMESTAMPTZ | NOT NULL | |

İndeks: `(user_id, period_month, created_at DESC)`. Bir ay için birden çok kayıt olabilir (yeniden üretim geçmişi); en yenisi gösterilir.

### ai_usage
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| user_id | UUID | FK → users, CASCADE | |
| usage_date | DATE | NOT NULL | UTC gün |
| generation_count | INTEGER | NOT NULL DEFAULT 0 | |

PK: `(user_id, usage_date)`. Sayaç artırma `INSERT … ON CONFLICT DO UPDATE` ile atomik yapılır (KARAR-9).

## 4. Rapor sorguları (taslak)

```sql
-- Ay toplamı ve kategori dağılımı
SELECT c.id, c.name, c.color, c.icon,
       SUM(e.amount) AS total, COUNT(*) AS cnt
FROM expenses e
JOIN categories c ON c.id = e.category_id
WHERE e.user_id = :userId
  AND e.expense_date >= :monthStart AND e.expense_date < :nextMonthStart
GROUP BY c.id, c.name, c.color, c.icon
ORDER BY total DESC;

-- Günlük toplamlar
SELECT e.expense_date, SUM(e.amount) AS total
FROM expenses e
WHERE e.user_id = :userId
  AND e.expense_date >= :monthStart AND e.expense_date < :nextMonthStart
GROUP BY e.expense_date ORDER BY e.expense_date;

-- Son 6 ay trendi
SELECT date_trunc('month', e.expense_date)::date AS month, SUM(e.amount) AS total
FROM expenses e
WHERE e.user_id = :userId
  AND e.expense_date >= :sixMonthsAgoStart AND e.expense_date < :nextMonthStart
GROUP BY 1 ORDER BY 1;
```

Yüzdeler ve değişim oranları Java tarafında `BigDecimal` ile hesaplanır.

## 5. Migration planı

| Dosya | İçerik |
|---|---|
| `V1__create_users_and_tokens.sql` | `users`, `refresh_tokens` |
| `V2__create_categories.sql` | `categories` + kısıtlar |
| `V3__create_expenses.sql` | `expenses` + indeksler |
| `V4__create_ai_tables.sql` | `ai_insights`, `ai_usage` |

Hazır kategori listesi (ad, ikon, renk, `system_key`) uygulama kodunda sabit olarak tutulur ve kayıt sırasında kopyalanır; migration içinde seed verisi yoktur.

## 6. Yedekleme ve saklama

- MVP (yerel): Docker volume. Üretim yedekleme politikası KARAR-11 ile birlikte belirlenir.
- Hesap silindiğinde tüm veriler CASCADE ile hemen silinir.
