# AI Expense Tracker — Veritabanı Tasarımı

> Durum: **Revizyon 3 — ürün sahibi onayı bekleniyor (PR #1)**
> Veritabanı: **PostgreSQL 16** (tüm ortamlarda, ADR-9). Şema değişiklikleri yalnızca yeni Flyway migration dosyalarıyla yapılır; uygulanmış migration düzenlenmez.

## 1. Genel kurallar

- Birincil anahtarlar: `UUID` (`gen_random_uuid()`, PostgreSQL 13+ çekirdekte; eklenti gerekmez).
- Zaman damgaları: `TIMESTAMPTZ`, UTC; her tabloda `created_at`, değişebilen tablolarda `updated_at`.
- Kullanıcıya ait her tabloda `user_id NOT NULL` + `REFERENCES users ON DELETE CASCADE`.
- Tablo ve sütun adları `snake_case`, tablo adları çoğul.
- Harcamalar kalıcı silinir; kategoriler arşivlenir, silinmez.
- Hiçbir tabloda düz metin şifre, ham refresh token veya AI API anahtarı bulunmaz.
- **Zamana bağlı kurallar (ör. "ileri tarih olamaz") veritabanı kısıtı olarak yazılmaz.** "Bugün" kullanıcının saat dilimine bağlıdır ve Java servis katmanında enjekte edilen `Clock` ile doğrulanır. Veritabanında yalnızca zamandan bağımsız kısıtlar bulunur (`amount > 0`, biçim kontrolleri, benzersizlik, FK).

## 2. Para kuralları

| Konu | Kural |
|---|---|
| DB tipi | `NUMERIC(14,2)`: toplam 14 basamak, 2'si ondalık → aralık **-999.999.999.999,99 … 999.999.999.999,99** (12 tam basamak). Tek harcama için ek kısıt `amount > 0` |
| Java tipi | `BigDecimal`. `double`/`float` para için yasak (ArchUnit kuralı) |
| Girdi (API) | JSON string, deseni `^[0-9]{1,12}(\.[0-9]{1,2})?$`. İşaret (`+`/`-`), üstel gösterim (`1e3`), virgül ve boşluk reddedilir. Sıfır reddedilir (`> 0`) |
| Girdi ölçeği | Girdi **yuvarlanmaz**: 2'den fazla ondalık basamak `400 VALIDATION_ERROR`. Kabul edilen değer `setScale(2)` ile normalize edilir (`"5"` → `5.00`) |
| Üst sınır | Tek harcama en fazla `999999999999.99` (DB sınırıyla aynı); aşan değer `400` |
| Hesaplama | Toplamlar kesin (`SUM` → `NUMERIC`, Java'da `BigDecimal.add`). Bölme gerektiren değerler (ortalama, yüzde) `RoundingMode.HALF_EVEN`, ölçek 2 |
| Toplu değerler | Rapor toplamları tek harcama sınırına tabi değildir; SQL `SUM(numeric)` sınırsız hassasiyetle döner ve JSON'a string olarak yazılır |
| Çıktı | JSON string, tam 2 ondalık (`"1250.50"`) |

## 3. E-posta kuralları

- Normalizasyon (kayıt ve girişte aynı fonksiyon): baştaki/sondaki boşluk kırpılır, `toLowerCase(Locale.ROOT)` uygulanır. `Locale.ROOT` zorunludur: Türkçe yerel ayarda `"I".toLowerCase()` → `"ı"` olur ve aynı e-posta iki farklı biçimde saklanabilir.
- Gmail nokta/artı gibi sağlayıcıya özel dönüşümler **yapılmaz** (`ali.veli@` ve `aliveli@` farklı hesaplardır).
- En fazla 254 karakter; sunucuda basit biçim doğrulaması (`@` içerir, boşluk içermez); doğrulama e-postası MVP'de yok.
- Benzersizlik normalize edilmiş değer üzerinde: `UNIQUE (email)`. Eşzamanlı iki kayıtta biri `409 EMAIL_TAKEN` alır (benzersizlik ihlali yakalanır).
- Normalizasyon birim testleri: büyük harf, boşluk, Türkçe `I/İ` içeren adresler.

## 4. Modül → tablo sahipliği

| Modül | Sahip olduğu tablolar | Okuma erişimi |
|---|---|---|
| `user` | `users` | — |
| `auth` | `auth_sessions`, `refresh_tokens` | `users` (`user` modülü API'si üzerinden) |
| `category` | `categories` | — |
| `expense` | `expenses` | — |
| `report` | — | `expenses`, `categories` (salt okuma SQL, ADR-3) |
| `insight` | `ai_generation_requests`, `ai_insights` | — |

## 5. ER diyagramı

```
users 1 ──── * auth_sessions 1 ──── * refresh_tokens
users 1 ──── * categories
users 1 ──── * expenses * ──── 1 categories        (bileşik FK: category_id + user_id)
users 1 ──── * ai_generation_requests 1 ──── 0..1 ai_insights
```

## 6. Tablolar

### users
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | |
| email | VARCHAR(254) | NOT NULL, UNIQUE | Normalize edilmiş (§3) |
| password_hash | VARCHAR(100) | NOT NULL | BCrypt (cost 12) |
| display_name | VARCHAR(60) | NOT NULL | AI'a gönderilmez |
| currency | CHAR(3) | NOT NULL DEFAULT 'TRY' | MVP'de kayıttan sonra değişmez (D-3) |
| time_zone | VARCHAR(64) | NOT NULL DEFAULT 'Europe/Istanbul' | IANA; servis katmanında `ZoneId.of` ile doğrulanır (D-4) |
| locale | VARCHAR(10) | NOT NULL DEFAULT 'tr-TR' | |
| created_at / updated_at | TIMESTAMPTZ | NOT NULL | |

### auth_sessions
Bir giriş = bir oturum (token ailesi). Rotasyonla üretilen tüm refresh token'lar aynı oturuma bağlıdır.

| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | Access token'daki `sid` claim'i |
| user_id | UUID | FK → users, CASCADE, NOT NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |
| last_refreshed_at | TIMESTAMPTZ | NULL | |
| revoked_at | TIMESTAMPTZ | NULL | NULL = aktif |
| revoke_reason | VARCHAR(30) | NULL, CHECK IN ('LOGOUT','TOKEN_REUSE','PASSWORD_CHANGE','ACCOUNT_DELETED','EXPIRED') | |

İndeks: `(user_id) WHERE revoked_at IS NULL`.

### refresh_tokens
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | |
| session_id | UUID | FK → auth_sessions, CASCADE, NOT NULL | |
| token_hash | CHAR(64) | NOT NULL, UNIQUE | 256 bit rastgele belirtecin SHA-256 özeti (hex). Ham belirteç saklanmaz |
| expires_at | TIMESTAMPTZ | NOT NULL | Üretimden 30 gün sonra |
| used_at | TIMESTAMPTZ | NULL | Rotasyonda doldurulur; dolu token bir daha geçerli değildir |
| created_at | TIMESTAMPTZ | NOT NULL | |

İndeks: `(session_id)`. Akış ve senaryolar: architecture.md §3.6.

### categories
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | |
| user_id | UUID | FK → users, CASCADE, NOT NULL | |
| name | VARCHAR(30) | NOT NULL, CHECK (char_length(btrim(name)) BETWEEN 1 AND 30) | |
| icon | VARCHAR(40) | NOT NULL | İkon anahtarı |
| color | CHAR(7) | NOT NULL, CHECK (color ~ '^#[0-9A-Fa-f]{6}$') | |
| system_key | VARCHAR(30) | NULL | Hazır kategori anahtarı (`GROCERIES`, `OTHER` …) |
| sort_order | SMALLINT | NOT NULL DEFAULT 0 | |
| archived_at | TIMESTAMPTZ | NULL | NULL = aktif |
| created_at / updated_at | TIMESTAMPTZ | NOT NULL | |

Kısıtlar / indeksler:
- `UNIQUE (user_id, lower(name)) WHERE archived_at IS NULL`
- `UNIQUE (user_id, system_key) WHERE system_key IS NOT NULL`
- `UNIQUE (id, user_id)` — bileşik FK hedefi
- `CHECK (NOT (system_key = 'OTHER' AND archived_at IS NOT NULL))` — "Diğer" arşivlenemez

Hazır kategoriler kayıtla aynı transaction içinde kullanıcıya kopyalanır.

### expenses
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | |
| user_id | UUID | FK → users, CASCADE, NOT NULL | |
| category_id | UUID | NOT NULL | Bileşik FK |
| amount | NUMERIC(14,2) | NOT NULL, CHECK (amount > 0) | §2 |
| expense_date | DATE | NOT NULL | Kullanıcının yerel tarihi; ileri tarih kontrolü **yalnızca servis katmanında** (§1) |
| description | VARCHAR(140) | NULL | AI'a gönderilmez |
| note | VARCHAR(500) | NULL | AI'a gönderilmez |
| payment_method | VARCHAR(10) | NULL, CHECK IN ('CASH','CARD','OTHER') | |
| created_at / updated_at | TIMESTAMPTZ | NOT NULL | |

Kısıtlar / indeksler:
- `FOREIGN KEY (category_id, user_id) REFERENCES categories (id, user_id)` — harcamanın kategorisi veritabanı düzeyinde aynı kullanıcıya aittir.
- `(user_id, expense_date DESC, created_at DESC)`
- `(user_id, category_id, expense_date)`

### ai_generation_requests
Her AI üretim denemesi için bir satır. Kota, eşzamanlılık ve izlenebilirliğin tek kaynağıdır. Akış: architecture.md §3.5.

| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | **Üretim kimliği (generation ID)**; yanıtta `generationId` ve `X-AI-Generation-Id` olarak döner, loglarda geçer |
| user_id | UUID | FK → users, CASCADE, NOT NULL | |
| period_month | DATE | NOT NULL, CHECK (EXTRACT(DAY FROM period_month) = 1) | Yorumlanan ay |
| data_fingerprint | CHAR(64) | NOT NULL | `InsightPayload`'ın SHA-256'sı |
| quota_date | DATE | NOT NULL | Rezervasyon anında kullanıcının saat dilimine göre bugün |
| status | VARCHAR(10) | NOT NULL, CHECK IN ('RESERVED','SUCCEEDED','FAILED','EXPIRED') | |
| expires_at | TIMESTAMPTZ | NOT NULL | Rezervasyon geçerlilik sonu (`created_at` + `AI_RESERVATION_TTL`, varsayılan 2 dk) |
| http_request_id | VARCHAR(64) | NULL | İsteği başlatan HTTP `X-Request-Id` |
| error_code | VARCHAR(40) | NULL | FAILED için: `PROVIDER_TIMEOUT`, `PROVIDER_ERROR`, `INVALID_RESPONSE`, `NOT_CONFIGURED`, `RESULT_AFTER_EXPIRY` |
| attempt_count | SMALLINT | NOT NULL DEFAULT 0 | Sağlayıcıya yapılan HTTP deneme sayısı (en fazla 2) |
| input_tokens / output_tokens | INTEGER | NULL | Maliyet takibi |
| model | VARCHAR(100) | NULL | O anki `AI_MODEL` değeri |
| created_at | TIMESTAMPTZ | NOT NULL | |
| completed_at | TIMESTAMPTZ | NULL | SUCCEEDED/FAILED/EXPIRED anı |

Kısıtlar / indeksler:
- `UNIQUE (user_id, period_month, data_fingerprint) WHERE status = 'RESERVED'` — aynı kullanıcı + ay + veri için **aynı anda en fazla bir** üretim.
- `(user_id, quota_date) WHERE status IN ('RESERVED','SUCCEEDED')` — kota sayımı.
- `(status, expires_at) WHERE status = 'RESERVED'` — süresi dolanları temizleme.
- `CHECK ((status = 'RESERVED') = (completed_at IS NULL))`

Durum geçişleri (yalnızca bunlar):

| Geçiş | Ne zaman | Kotaya etkisi |
|---|---|---|
| — → `RESERVED` | Rezervasyon transaction'ı | Geçici olarak sayılır (eşzamanlı aşımı önler) |
| `RESERVED` → `SUCCEEDED` | Geçerli AI yanıtı kaydedildi | **Kalıcı olarak sayılır** |
| `RESERVED` → `FAILED` | Sağlayıcı hatası, zaman aşımı, geçersiz yanıt, yapılandırma eksik | Sayılmaz |
| `RESERVED` → `EXPIRED` | `expires_at` geçti ve sonuç yazılmadı (ör. uygulama yeniden başladı) | Sayılmaz |

Her geçiş `WHERE id = :id AND status = 'RESERVED'` koşullu güncellemeyle yapılır; etkilenen satır 0 ise geçiş kaybedilmiş sayılır (ör. temizleyici önce davrandı).

**Kota sayımı** (bir kullanıcının bugünkü kullanımı):
```sql
SELECT COUNT(*) FROM ai_generation_requests
WHERE user_id = :userId AND quota_date = :todayInUserZone
  AND (status = 'SUCCEEDED' OR (status = 'RESERVED' AND expires_at > now()));
```

**Rezervasyon transaction'ı** (milisaniyeler sürer; harici çağrı içermez):
```sql
BEGIN;
-- 1) Kullanıcı bazında serileştirme: aynı kullanıcının rezervasyonları sırayla işlenir
SELECT id FROM users WHERE id = :userId FOR UPDATE;
-- 2) Bu kullanıcının süresi dolmuş rezervasyonlarını kapat
UPDATE ai_generation_requests SET status = 'EXPIRED', completed_at = now()
 WHERE user_id = :userId AND status = 'RESERVED' AND expires_at <= now();
-- 3) Aynı ay + veri için devam eden üretim var mı? → varsa 409 AI_GENERATION_IN_PROGRESS
-- 4) Kota sayımı (yukarıdaki sorgu) >= AI_DAILY_LIMIT ise → 429 AI_DAILY_LIMIT
-- 5) Rezervasyon
INSERT INTO ai_generation_requests (id, user_id, period_month, data_fingerprint,
       quota_date, status, expires_at, http_request_id, created_at)
VALUES (:id, :userId, :month, :fp, :todayInUserZone, 'RESERVED', now() + :ttl, :reqId, now());
COMMIT;
```
`users` satırı kilidi yalnızca bu kısa transaction boyunca tutulur. Benzersiz kısmi indeks, kilit atlansa bile aynı veri için ikinci rezervasyonu engelleyen ikinci savunma hattıdır (ihlal → 409).

### ai_insights
| Sütun | Tip | Kısıt | Açıklama |
|---|---|---|---|
| id | UUID | PK | |
| user_id | UUID | FK → users, CASCADE, NOT NULL | |
| generation_request_id | UUID | FK → ai_generation_requests, CASCADE, NOT NULL, UNIQUE | Hangi üretimden geldiği |
| period_month | DATE | NOT NULL | |
| data_fingerprint | CHAR(64) | NOT NULL | |
| summary | TEXT | NOT NULL | |
| observations | JSONB | NOT NULL | `string[]` (2–4) |
| suggestions | JSONB | NOT NULL | `string[]` (1–3) |
| prompt_version | VARCHAR(20) | NOT NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |

İndeks: `(user_id, period_month, created_at DESC)`. AI'a gönderilen yükün kendisi saklanmaz.

## 7. Zamanlanmış temizlik

Spring `@Scheduled` görevleri (ek altyapı yok; tek uygulama örneği varsayımı, birden çok örnekte de güvenlidir çünkü işlemler koşullu güncellemedir):

| Görev | Sıklık | İşlem |
|---|---|---|
| Süresi dolmuş AI rezervasyonları | 1 dk | `RESERVED` ve `expires_at <= now()` → `EXPIRED` (kota kontrolü bunu zaten yok sayar; bu görev durum kaydını doğru tutar) |
| Eski AI üretim kayıtları | Günlük | `ai_insights`'a bağlı olmayan ve 90 günden eski satırlar silinir |
| Refresh token'lar | Günlük | `expires_at` 7 günden eski token'lar; tüm token'ları dolmuş veya 30 günden eski iptal edilmiş oturumlar silinir |

## 8. Rapor sorguları (taslak)

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

## 9. Migration planı

| Dosya | İçerik | Görev |
|---|---|---|
| `V1__create_users.sql` | `users` | T1.1 |
| `V2__create_auth_sessions_and_refresh_tokens.sql` | `auth_sessions`, `refresh_tokens` | T1.1 |
| `V3__create_categories.sql` | `categories` + kısıtlar | T1.1 |
| `V4__create_expenses.sql` | `expenses` + bileşik FK + indeksler (tarih kısıtı yok) | T2.1 |
| `V5__create_ai_generation_tables.sql` | `ai_generation_requests`, `ai_insights` | T4.1 |

Hazır kategori listesi uygulama kodunda sabittir; migration'larda seed verisi yoktur.

## 10. Veritabanı testleri

Gerçek PostgreSQL 16 üzerinde (architecture.md §6):
- Boş veritabanına tüm migration'lar sırayla uygulanır; Flyway doğrulaması geçer.
- Her `CHECK`, `UNIQUE` ve FK için en az bir "ihlal reddedilir" testi; `expenses` tablosunda ileri tarihli satırın **veritabanınca kabul edildiği** (kuralın serviste olduğunu kanıtlayan) bir test.
- Bileşik FK: A'nın harcaması B'nin kategorisine bağlanamaz.
- CASCADE: kullanıcı silinince tüm tablolarda o kullanıcıya ait satır kalmaz.
- Para: `NUMERIC` ↔ `BigDecimal` gidiş-dönüşünde değer ve ölçek korunur; `999999999999.99` kabul, `1000000000000.00` red.
- `ai_generation_requests`: aynı anahtar için ikinci `RESERVED` satırı reddedilir; `SUCCEEDED` sonrası aynı anahtarla yeni `RESERVED` kabul edilir.

## 11. Hesap silme, yedekler ve saklama

- Hesap silindiğinde kullanıcıya ait tüm satırlar aynı transaction'da CASCADE ile silinir; oturumlar da silindiği için mevcut access token'lar hemen geçersizleşir (architecture.md §3.6).
- MVP'de üretim veritabanı ve yedek yoktur (D-11).
- **Üretim öncesi zorunlu görev (P-2):** Yedek saklama süresi, silinen hesap verisinin yedeklerden ne zaman kalktığı, log saklama süresi ve KVKK aydınlatma metni yazılı politika olarak belirlenmeden üretime çıkılmaz (development-plan.md §4).
