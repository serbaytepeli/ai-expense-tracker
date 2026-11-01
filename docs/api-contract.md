# AI Expense Tracker — API Sözleşmesi (v1)

> Durum: **Onaylı kararlar işlendi — belge onayı bekleniyor (PR)**
> Uygulama yazıldıktan sonra kesin kaynak, backend'in ürettiği OpenAPI şemasıdır (`/v3/api-docs`). Bu belge o şemanın tasarımıdır; API değişen her PR bu belgeyi de günceller.

## 1. Genel kurallar

- Taban yol: `/api/v1`
- Biçim: JSON, UTF-8, alan adları `camelCase`.
- Kimlik doğrulama: `Authorization: Bearer <accessToken>` — `/auth/register`, `/auth/login`, `/auth/refresh` ve `/actuator/health` dışında tüm uç noktalar.
- Kullanıcı kimliği yalnızca belirteçten alınır; hiçbir uç nokta `userId` parametresi kabul etmez.
- Para: JSON'da **string**, nokta ondalık ayraçlı, tam 2 basamak: `"149.90"`.
- Tarih `YYYY-MM-DD`; ay `YYYY-MM`; zaman damgası ISO 8601 UTC (`2026-11-01T10:15:30Z`).
- Kimlikler UUID string.
- Sayfalama: `?page=0&size=20` (size en fazla 100). Yanıt:
  ```json
  { "items": [ ... ], "page": 0, "size": 20, "totalItems": 134, "totalPages": 7 }
  ```
- Her yanıtta `X-Request-Id` başlığı bulunur.

### Hata biçimi (RFC 9457)
```json
{
  "type": "https://aiexpense.app/problems/validation-error",
  "title": "Validation failed",
  "status": 400,
  "detail": "Request contains invalid fields",
  "code": "VALIDATION_ERROR",
  "errors": [ { "field": "date", "message": "must not be in the future" } ],
  "requestId": "b3f1..."
}
```

| HTTP | `code` | Ne zaman |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Alan doğrulaması başarısız (ileri tarih dahil) |
| 401 | `UNAUTHORIZED` / `INVALID_CREDENTIALS` / `TOKEN_EXPIRED` | Belirteç yok/geçersiz, yanlış giriş |
| 404 | `NOT_FOUND` | Kaynak yok **veya başka kullanıcıya ait** |
| 409 | `EMAIL_TAKEN` / `CATEGORY_NAME_TAKEN` / `CATEGORY_PROTECTED` / `CATEGORY_ARCHIVED` | Çakışma |
| 422 | `INSUFFICIENT_DATA` | AI yorumu için yeterli harcama yok |
| 429 | `RATE_LIMITED` / `AI_DAILY_LIMIT` | Sınır aşıldı (`Retry-After` başlığıyla) |
| 503 | `AI_UNAVAILABLE` | AI sağlayıcısı hata verdi, zaman aşımı veya yapılandırılmamış |

## 2. Auth (`auth` modülü)

### `POST /auth/register`
```json
// istek
{ "email": "ali@example.com", "password": "S3cure-pass", "displayName": "Ali",
  "timeZone": "Europe/Istanbul" }
// 201 yanıt
{ "accessToken": "...", "refreshToken": "...", "expiresIn": 900,
  "user": { "id": "...", "email": "ali@example.com", "displayName": "Ali",
            "currency": "TRY", "timeZone": "Europe/Istanbul", "locale": "tr-TR" } }
```
- `timeZone` isteğe bağlı (mobil cihazın saat dilimini gönderir); geçersiz veya boşsa `Europe/Istanbul`.
- `currency` her zaman `TRY` olarak oluşturulur (D-3).
- Hazır kategoriler aynı transaction içinde oluşturulur.
- Hatalar: 400, 409 `EMAIL_TAKEN`, 429.

### `POST /auth/login`
`{ "email", "password" }` → 200, register ile aynı yanıt. Hatalar: 401 `INVALID_CREDENTIALS`, 429.

### `POST /auth/refresh`
`{ "refreshToken" }` → 200 `{ accessToken, refreshToken, expiresIn }`. Eski refresh token iptal edilir (rotasyon). İptal edilmiş belirteç tekrar kullanılırsa 401 ve o kullanıcının tüm refresh token'ları iptal edilir (çalıntı belirteç koruması).

### `POST /auth/logout`
`{ "refreshToken" }` → 204.

## 3. Kullanıcı (`user` modülü)

| Metot | Yol | Açıklama |
|---|---|---|
| GET | `/me` | Profil |
| PATCH | `/me` | Yalnızca `displayName` ve `timeZone` güncellenebilir. `currency` MVP'de değiştirilemez (D-3) |
| DELETE | `/me` | Gövde: `{ "password": "..." }`. Şifre doğruysa hesap ve tüm veri silinir → 204. Yanlış şifre → 401 `INVALID_CREDENTIALS` (FR-7) |

## 4. Kategoriler (`category` modülü)

### Kategori nesnesi
```json
{ "id": "...", "name": "Market", "icon": "cart", "color": "#4CAF50",
  "systemKey": "GROCERIES", "sortOrder": 1, "archived": false }
```

| Metot | Yol | Açıklama |
|---|---|---|
| GET | `/categories?includeArchived=false` | Kategori listesi (`sortOrder`, sonra ad sırasıyla) |
| POST | `/categories` | `{ "name", "icon", "color" }` → 201. Hatalar: 400, 409 `CATEGORY_NAME_TAKEN` |
| PATCH | `/categories/{id}` | `name`, `icon`, `color`, `sortOrder` → 200 |
| POST | `/categories/{id}/archive` | → 200. `OTHER` için 409 `CATEGORY_PROTECTED` |
| POST | `/categories/{id}/unarchive` | → 200. Aynı adda aktif kategori varsa 409 `CATEGORY_NAME_TAKEN` |

Kategori silme uç noktası yoktur.

## 5. Harcamalar (`expense` modülü)

### Harcama nesnesi
```json
{ "id": "...", "amount": "149.90", "currency": "TRY", "date": "2026-10-28",
  "category": { "id": "...", "name": "Market", "icon": "cart", "color": "#4CAF50", "archived": false },
  "description": "Haftalık market", "note": null, "paymentMethod": "CARD",
  "createdAt": "2026-10-28T17:02:11Z", "updatedAt": "2026-10-28T17:02:11Z" }
```

### `GET /expenses`
Parametreler: `month=YYYY-MM` **veya** `from` + `to` (ikisi dahil), `categoryId`, `q` (açıklamada arama, en az 2 karakter), `page`, `size`. Sıralama: `date desc, createdAt desc`. → 200 sayfalı liste.

### `GET /expenses/{id}` → 200 · 404

### `POST /expenses`
```json
{ "amount": "149.90", "date": "2026-10-28", "categoryId": "...",
  "description": "Haftalık market", "note": null, "paymentMethod": "CARD" }
```
→ 201 + `Location` başlığı.

Doğrulama:
- `amount`: > 0, en fazla 2 ondalık, en fazla `999999999999.99`.
- `date`: zorunlu; **kullanıcının saat dilimindeki bugünden ileri olamaz** (D-7) → aksi halde 400 `VALIDATION_ERROR`, `field: "date"`.
- `categoryId`: kullanıcıya ait olmalı (aksi 404) ve aktif olmalı (arşivliyse 409 `CATEGORY_ARCHIVED`).
- `description` ≤ 140, `note` ≤ 500, `paymentMethod` ∈ `CASH | CARD | OTHER | null`.

### `PUT /expenses/{id}`
POST ile aynı gövde ve kurallar → 200. İstisna: harcamanın mevcut kategorisi arşivliyse, kategori değiştirilmeden düzenlenebilir.

### `DELETE /expenses/{id}` → 204 · 404

## 6. Raporlar (`report` modülü)

### `GET /reports/monthly?month=2026-10`
```json
{
  "month": "2026-10",
  "currency": "TRY",
  "total": "12450.75",
  "expenseCount": 63,
  "dailyAverage": "401.64",
  "previousMonth": { "month": "2026-09", "total": "10980.00",
                     "changeAmount": "1470.75", "changePercent": "13.39" },
  "byCategory": [
    { "categoryId": "...", "name": "Market", "color": "#4CAF50", "icon": "cart",
      "total": "3820.40", "count": 14, "percent": "30.68",
      "previousTotal": "3500.00", "changePercent": "9.15" }
  ],
  "daily": [ { "date": "2026-10-01", "total": "320.00" } ],
  "topExpenses": [ { "id": "...", "amount": "2500.00", "date": "2026-10-05",
                     "categoryName": "Kira/Konut", "description": "Aidat" } ]
}
```
- `month` kullanıcının saat dilimine göre bugünün ayından ileri olamaz → 400.
- `changePercent` önceki değer 0 ise `null`.
- `daily` ayın tüm günlerini içerir (içinde bulunulan ayda bugüne kadar); harcamasız günler `"0.00"`.
- `dailyAverage` = toplam / gün sayısı (içinde bulunulan ay için bugüne kadarki gün sayısı).
- `topExpenses` yalnızca kullanıcıya gösterim içindir; **AI'a gönderilmez**.

### `GET /reports/trend?months=6&endMonth=2026-10`
```json
{ "currency": "TRY", "points": [ { "month": "2026-05", "total": "9800.00" } ] }
```
`months` 1–12; harcamasız aylar `"0.00"`.

## 7. AI yorumları (`insight` modülü)

### Yorum nesnesi
```json
{ "id": "...", "month": "2026-10",
  "summary": "Ekim'de toplam 12.450,75 TL harcadınız; bu Eylül'e göre %13 fazla.",
  "observations": [ "Yeme-İçme harcamanız %35 arttı." ],
  "suggestions": [ "Dışarıda yemek için haftalık bir sınır belirlemeyi deneyebilirsiniz." ],
  "stale": false,
  "generatedAt": "2026-11-01T09:30:00Z",
  "disclaimer": "Bu yorum yapay zekâ tarafından üretilmiştir, finansal tavsiye değildir." }
```

### `GET /insights/monthly?month=2026-10`
En son kayıtlı yorum → 200; yoksa 404. `stale: true` → yorum oluşturulduktan sonra veri değişmiş.
Yanıt başlığı: `X-AI-Remaining-Today: <0–5>`.

### `POST /insights/monthly?month=2026-10`
Veri değişmediyse mevcut yorumu döner (200, kota düşmez); değiştiyse yeni yorum üretir (201).
Hatalar: 422 `INSUFFICIENT_DATA` (< 3 harcama), 429 `AI_DAILY_LIMIT` (`Retry-After`: kullanıcının saat dilimine göre gece yarısına kalan saniye), 503 `AI_UNAVAILABLE`.
Yanıt başlığı: `X-AI-Remaining-Today`.

### AI sağlayıcısına giden yük — `InsightPayload` (iç sözleşme, D-8)

Yük bir izin listesiyle kurulur. Aşağıdaki alanlar **dışında hiçbir alan** eklenemez; bu, birim testiyle doğrulanır.

```json
{
  "schemaVersion": "1",
  "language": "tr",
  "month": "2026-10",
  "currency": "TRY",
  "monthStatus": "COMPLETE",
  "total": "12450.75",
  "expenseCount": 63,
  "previousMonthTotal": "10980.00",
  "changePercentVsPreviousMonth": "13.39",
  "sixMonthAverage": "11200.00",
  "changePercentVsSixMonthAverage": "11.17",
  "categories": [
    { "label": "Market", "total": "3820.40", "percentOfTotal": "30.68",
      "previousMonthTotal": "3500.00", "changePercentVsPreviousMonth": "9.15" }
  ],
  "budgetUsage": []
}
```

| Alan | Açıklama |
|---|---|
| `monthStatus` | `COMPLETE` (geçmiş ay) veya `IN_PROGRESS` (içinde bulunulan ay; AI kısmi veri olduğunu bilsin diye) |
| `categories[].label` | Hazır kategoriler için kategori adı. Kullanıcı kategorileri için biçim açık soru **S-2**'ye bağlı (önerilen: "Özel kategori 1" gibi genel etiket) |
| `budgetUsage` | `[{ "label", "budget", "spent", "usagePercent" }]`. Bütçe özelliği MVP'de olmadığı için **MVP'de her zaman boş dizi** (açık soru **S-1**) |

**Kesinlikle gönderilmeyenler:** harcama açıklamaları, notlar, tekil harcama kayıtları/tutarları/tarihleri, ödeme yöntemi, ad, e-posta, kullanıcı kimliği, saat dilimi, cihaz bilgisi.

AI'dan beklenen yanıt (yapılandırılmış JSON, sunucuda doğrulanır):
```json
{ "summary": "string", "observations": ["string"], "suggestions": ["string"] }
```
`observations` 2–4, `suggestions` 1–3 öğe; şemaya uymayan yanıt 503 olarak ele alınır.

## 8. Sağlık

`GET /actuator/health` → `{ "status": "UP" }` (kimlik doğrulama gerektirmez; ayrıntılar yalnızca `dev` profilinde).
