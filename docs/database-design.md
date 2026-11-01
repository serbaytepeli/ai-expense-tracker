# AI Expense Tracker — Veritabanı Tasarımı

> Durum: **Onaylı kararlar işlendi — belge onayı bekleniyor (PR)**
> Veritabanı: **PostgreSQL 16** (tüm ortamlarda, ADR-9). Şema değişiklikleri yalnızca yeni Flyway migration dosyalarıyla yapılır; uygulanmış migration düzenlenmez.

## 1. Genel kurallar

- Birincil anahtarlar: `UUID` (`gen_random_uuid()`).
- Zaman damgaları: `TIMESTAMPTZ`, UTC; her tabloda `created_at`, değişebilen tablolarda `updated_at`.
- Para: `NUMERIC(14,2)` (en fazla 999.999.999.999,99). Uygulamada `BigDecimal`.
- Kullanıcıya ait her tabloda `user_id NOT NULL` + `REFERENCES users ON DELETE CASCADE` (hesap silme, FR-7).
- Tablo ve sütun adları `snake_case`, tablo adları çoğul.
- Silme: harcamalar kalıcı silinir; kategoriler arşivlenir, silinmez.
- Hiçbir tabloda düz metin şifre, ham refresh token veya AI API anahtarı bulunmaz.

## 2. Modül → tablo sahipliği

| Modül | Sahip olduğu tablolar | Okuma erişimi |
|---|---|---|
| `user` | `users` | — |
| `auth` | `refresh_tokens` | `users` (`user` modülü API'si üzerinden) |
| `category` | `categories` | — |
| `expense` | `expenses` | — |
| `report` | — | `expenses`, `categories` (salt okuma SQL, ADR-3) |
| `insight` | `ai_insights`, `ai_usage` | — |

## 3. ER diyagramı

```
users 1 ──── * refresh_tokens
users 1 ──── * categories
users 1 ──── * expenses * ──── 1 categories
users 1 ──── * ai_insights
users 1 ──── * ai_usage
```

## 4. Tablolar

### users
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | |
| email | VARCHAR(254) | NOT NULL | Küçük harfe çevrilerek saklanır |
| password_hash | VARCHAR(100) | NOT NULL | BCrypt özeti (cost 12) |
| display_name | VARCHAR(60) | NOT NULL | AI'a gönderilmez |
| currency | CHAR(3) | NOT NULL DEFAULT 'TRY' | ISO 4217; MVP'de kayıttan sonra değişmez (D-3) |
| time_zone | VARCHAR(64) | NOT NULL DEFAULT 'Europe/Istanbul' | IANA (D-4) |
| locale | VARCHAR(10) | NOT NULL DEFAULT 'tr-TR' | |
| created_at / updated_at | TIMESTAMPTZ | NOT NULL | |

İndeks: `UNIQUE (email)`.

### refresh_tokens
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | |
| user_id | UUID | FK → users, CASCADE | |
| token_hash | CHAR(64) | NOT NULL, UNIQUE | Belirtecin SHA-256 özeti |
| expires_at | TIMESTAMPTZ | NOT NULL | |
| revoked_at | TIMESTAMPTZ | NULL | Rotasyon / çıkışta dolar |
| created_at | TIMESTAMPTZ | NOT NULL | |

İndeks: `(user_id)`.

### categories
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | |
| user_id | UUID | FK → users, CASCADE, NOT NULL | |
| name | VARCHAR(30) | NOT NULL | |
| icon | VARCHAR(40) | NOT NULL | İkon anahtarı (ör. `cart`) |
| color | CHAR(7) | NOT NULL | `#RRGGBB` |
| system_key | VARCHAR(30) | NULL | Hazır kategorinin sabit anahtarı (`GROCERIES`, `OTHER` …); kullanıcı kategorilerinde NULL |
| sort_order | SMALLINT | NOT NULL DEFAULT 0 | |
| archived_at | TIMESTAMPTZ | NULL | NULL = aktif |
| created_at / updated_at | TIMESTAMPTZ | NOT NULL | |

Kısıtlar / indeksler:
- `UNIQUE (user_id, lower(name)) WHERE archived_at IS NULL`
- `UNIQUE (user_id, system_key) WHERE system_key IS NOT NULL`
- `UNIQUE (id, user_id)` — `expenses` bileşik yabancı anahtarı için
- `CHECK (color ~ '^#[0-9A-Fa-f]{6}$')`
- `CHECK (NOT (system_key = 'OTHER' AND archived_at IS NOT NULL))` — "Diğer" arşivlenemez

> Hazır kategoriler kayıt anında, kayıtla aynı transaction içinde kullanıcıya kopyalanır. Böylece kullanıcı yeniden adlandırabilir/arşivleyebilir ve her sorgu tek `user_id` filtresiyle çalışır.

### expenses
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | |
| user_id | UUID | FK → users, CASCADE, NOT NULL | |
| category_id | UUID | NOT NULL | Bileşik FK, aşağıda |
| amount | NUMERIC(14,2) | NOT NULL, CHECK (amount > 0) | |
| expense_date | DATE | NOT NULL | Kullanıcının yerel tarihi (D-4) |
| description | VARCHAR(140) | NULL | AI'a gönderilmez (D-8) |
| note | VARCHAR(500) | NULL | AI'a gönderilmez (D-8) |
| payment_method | VARCHAR(10) | NULL, CHECK IN ('CASH','CARD','OTHER') | |
| created_at / updated_at | TIMESTAMPTZ | NOT NULL | |

Kısıtlar / indeksler:
- `FOREIGN KEY (category_id, user_id) REFERENCES categories (id, user_id)` — harcamanın kategorisi **veritabanı düzeyinde** aynı kullanıcıya ait olmak zorundadır (veri izolasyonu).
- `(user_id, expense_date DESC, created_at DESC)` — liste ve aylık rapor.
- `(user_id, category_id, expense_date)` — kategori filtresi.

**İleri tarih kuralı (D-7):** "Bugün" kullanıcının saat dilimine bağlı olduğundan bu kural bir DB `CHECK` kısıtı ile değil, `expense` servisinde doğrulanır. Ek güvenlik ağı olarak `CHECK (expense_date <= CURRENT_DATE + 1)` eklenir (dünyadaki en ileri saat dilimi farkını tolere eder).

### ai_insights
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | |
| user_id | UUID | FK → users, CASCADE, NOT NULL | |
| period_month | DATE | NOT NULL, CHECK (EXTRACT(DAY FROM period_month) = 1) | Ayın ilk günü |
| data_fingerprint | CHAR(64) | NOT NULL | AI yükünün SHA-256'sı |
| summary | TEXT | NOT NULL | |
| observations | JSONB | NOT NULL | `string[]` |
| suggestions | JSONB | NOT NULL | `string[]` |
| provider | VARCHAR(30) | NOT NULL | `anthropic` |
| model | VARCHAR(100) | NOT NULL | O anda `AI_MODEL` ortam değişkeninde olan değer |
| prompt_version | VARCHAR(20) | NOT NULL | ör. `v1` |
| input_tokens / output_tokens | INTEGER | NULL | Maliyet takibi |
| created_at | TIMESTAMPTZ | NOT NULL | |

İndeks: `(user_id, period_month, created_at DESC)`. AI'a gönderilen yükün kendisi saklanmaz (yalnızca parmak izi).

### ai_usage
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| user_id | UUID | FK → users, CASCADE | |
| usage_date | DATE | NOT NULL | Kullanıcının saat dilimine göre gün |
| generation_count | INTEGER | NOT NULL DEFAULT 0, CHECK (generation_count >= 0) | |

PK: `(user_id, usage_date)`. Kota kontrolü ve artırma tek atomik sorguyla yapılır:

```sql
INSERT INTO ai_usage (user_id, usage_date, generation_count)
VALUES (:userId, :today, 1)
ON CONFLICT (user_id, usage_date)
DO UPDATE SET generation_count = ai_usage.generation_count + 1
WHERE ai_usage.generation_count < :dailyLimit
RETURNING generation_count;
-- Satır dönmezse sınır aşılmıştır → 429
```

Akış: AI çağrısından **önce** bu sorguyla bir hak rezerve edilir (eşzamanlı isteklerde sınır aşılamaz). AI çağrısı başarısız olursa sayaç `generation_count - 1` ile geri alınır; böylece başarısız çağrı kotayı düşürmez. Her iki durum da T4.3'te testle doğrulanır.

## 5. Rapor sorguları (taslak)

```sql
-- Ay toplamı ve kategori dağılımı
SELECT c.id, c.name, c.system_key, c.color, c.icon,
       SUM(e.amount) AS total, COUNT(*) AS cnt
FROM expenses e
JOIN categories c ON c.id = e.category_id AND c.user_id = e.user_id
WHERE e.user_id = :userId
  AND e.expense_date >= :monthStart AND e.expense_date < :nextMonthStart
GROUP BY c.id, c.name, c.system_key, c.color, c.icon
ORDER BY total DESC;

-- Günlük toplamlar
SELECT e.expense_date, SUM(e.amount) AS total
FROM expenses e
WHERE e.user_id = :userId
  AND e.expense_date >= :monthStart AND e.expense_date < :nextMonthStart
GROUP BY e.expense_date ORDER BY e.expense_date;

-- Son N ay trendi
SELECT date_trunc('month', e.expense_date)::date AS month, SUM(e.amount) AS total
FROM expenses e
WHERE e.user_id = :userId
  AND e.expense_date >= :rangeStart AND e.expense_date < :nextMonthStart
GROUP BY 1 ORDER BY 1;
```

Yüzdeler ve değişim oranları Java'da `BigDecimal` ile hesaplanır.

## 6. Migration planı

| Dosya | İçerik | Görev |
|---|---|---|
| `V1__create_users_and_refresh_tokens.sql` | `users`, `refresh_tokens` | T1.1 |
| `V2__create_categories.sql` | `categories` + kısıtlar | T1.1 |
| `V3__create_expenses.sql` | `expenses` + bileşik FK + indeksler | T2.1 |
| `V4__create_ai_tables.sql` | `ai_insights`, `ai_usage` | T4.1 |

`pgcrypto` gerekmez (`gen_random_uuid()` PostgreSQL 13+ çekirdekte). Hazır kategori listesi uygulama kodunda sabittir; migration içinde seed verisi yoktur.

## 7. Veritabanı testleri

Her migration için T-görevinde şu testler yazılır (gerçek PostgreSQL 16 üzerinde, bkz. architecture.md §6):
- Boş veritabanına tüm migration'lar sırayla uygulanır; Flyway doğrulaması geçer.
- Her `CHECK`, `UNIQUE` ve FK kısıtı için en az bir "ihlal reddedilir" testi.
- Bileşik FK: A kullanıcısının harcaması B kullanıcısının kategorisine bağlanamaz.
- CASCADE: kullanıcı silinince tüm tablolarda o kullanıcıya ait satır kalmaz.
- Para: `NUMERIC` ↔ `BigDecimal` dönüşümünde hassasiyet kaybı olmaz.

## 8. Yedekleme ve saklama

MVP'de üretim veritabanı yoktur (D-11); yedekleme politikası bulut dağıtım aşamasında belirlenir. Hesap silindiğinde tüm veriler hemen ve kalıcı olarak silinir.
