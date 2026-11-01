# AI Expense Tracker — API Sözleşmesi (v1)

> Durum: **TASLAK — ürün sahibi onayı bekleniyor**
> Kesin kaynak, uygulama yazıldıktan sonra backend'in ürettiği OpenAPI şemasıdır (`/v3/api-docs`). Bu belge o şemanın tasarım taslağıdır; ikisi ayrışırsa önce bu belge güncellenir.

## 1. Genel kurallar

- Taban yol: `/api/v1`
- Biçim: JSON, UTF-8. Alan adları `camelCase`.
- Kimlik doğrulama: `Authorization: Bearer <accessToken>` (`/auth/*` dışındaki tüm uç noktalar).
- Para: JSON'da **string** olarak, nokta ondalık ayraçlı, 2 basamak: `"149.90"` (kayan nokta hatasını önlemek için).
- Tarih: `YYYY-MM-DD`; ay: `YYYY-MM`; zaman damgası: ISO 8601 UTC (`2026-11-01T10:15:30Z`).
- Kimlikler: UUID string.
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
  "errors": [ { "field": "amount", "message": "must be greater than 0" } ],
  "requestId": "b3f1..."
}
```

| HTTP | `code` | Ne zaman |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Alan doğrulaması başarısız |
| 401 | `UNAUTHORIZED` / `INVALID_CREDENTIALS` / `TOKEN_EXPIRED` | Belirteç yok/geçersiz, yanlış giriş |
| 404 | `NOT_FOUND` | Kaynak yok **veya başka kullanıcıya ait** |
| 409 | `EMAIL_TAKEN` / `CATEGORY_NAME_TAKEN` / `CATEGORY_PROTECTED` | Çakışma |
| 422 | `INSUFFICIENT_DATA` | AI yorumu için yeterli harcama yok |
| 429 | `RATE_LIMITED` / `AI_DAILY_LIMIT` | Sınır aşıldı (`Retry-After` başlığıyla) |
| 503 | `AI_UNAVAILABLE` | AI sağlayıcısı hata verdi / zaman aşımı |

## 2. Auth

### `POST /auth/register`
```json
// istek
{ "email": "ali@example.com", "password": "S3cure-pass", "displayName": "Ali" }
// 201 yanıt
{ "accessToken": "...", "refreshToken": "...", "expiresIn": 900,
  "user": { "id": "...", "email": "ali@example.com", "displayName": "Ali",
            "currency": "TRY", "timeZone": "Europe/Istanbul", "locale": "tr-TR" } }
```
Hatalar: 400, 409 `EMAIL_TAKEN`. Kayıtla birlikte hazır kategoriler oluşturulur.

### `POST /auth/login`
İstek `{ "email", "password" }` → 200, register ile aynı yanıt. Hatalar: 401 `INVALID_CREDENTIALS`, 429.

### `POST /auth/refresh`
İstek `{ "refreshToken" }` → 200 `{ accessToken, refreshToken, expiresIn }`. Eski refresh token iptal edilir (rotasyon). Hata: 401.

### `POST /auth/logout`
İstek `{ "refreshToken" }` → 204.

## 3. Kullanıcı

| Metot | Yol | Açıklama |
|---|---|---|
| GET | `/me` | Profil |
| PATCH | `/me` | `displayName`, `timeZone`, `currency` güncelle (KARAR-3 kabul edilirse `currency` değiştirmek mevcut tutarları dönüştürmez; uyarı gösterilir) |
| DELETE | `/me` | Hesabı ve tüm veriyi sil → 204 (KARAR-10) |

## 4. Kategoriler

### `GET /categories?includeArchived=false`
```json
[ { "id": "...", "name": "Market", "icon": "cart", "color": "#4CAF50",
    "systemKey": "GROCERIES", "sortOrder": 1, "archived": false } ]
```

### `POST /categories`
`{ "name": "Evcil Hayvan", "icon": "paw", "color": "#8D6E63" }` → 201 kategori. Hatalar: 400, 409 `CATEGORY_NAME_TAKEN`.

### `PATCH /categories/{id}`
Gönderilen alanlar güncellenir: `name`, `icon`, `color`, `sortOrder`. → 200.

### `POST /categories/{id}/archive` · `POST /categories/{id}/unarchive`
→ 200 kategori. `OTHER` kategorisini arşivleme → 409 `CATEGORY_PROTECTED`.

## 5. Harcamalar

### Harcama nesnesi
```json
{ "id": "...", "amount": "149.90", "currency": "TRY", "date": "2026-10-28",
  "category": { "id": "...", "name": "Market", "icon": "cart", "color": "#4CAF50" },
  "description": "Haftalık market", "note": null, "paymentMethod": "CARD",
  "createdAt": "2026-10-28T17:02:11Z", "updatedAt": "2026-10-28T17:02:11Z" }
```

### `GET /expenses`
Sorgu parametreleri: `month=YYYY-MM` (ya da `from` + `to` tarihleri), `categoryId`, `q` (açıklamada arama, en az 2 karakter), `page`, `size`. Sıralama: `date desc, createdAt desc`. → 200 sayfalı liste.

### `GET /expenses/{id}` → 200 · 404

### `POST /expenses`
```json
{ "amount": "149.90", "date": "2026-10-28", "categoryId": "...",
  "description": "Haftalık market", "note": null, "paymentMethod": "CARD" }
```
→ 201 harcama + `Location` başlığı. Doğrulama: `amount > 0`, en fazla 2 ondalık; `date` en fazla bugün + 1 yıl (KARAR-7); `categoryId` kullanıcıya ait ve aktif olmalı.

### `PUT /expenses/{id}`
POST ile aynı gövde (tam güncelleme) → 200. Arşivli bir kategoriye sahip harcama, kategorisi değiştirilmeden düzenlenebilir.

### `DELETE /expenses/{id}` → 204 · 404

## 6. Raporlar

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
Notlar: `changePercent` önceki ay 0 ise `null`. `daily` ayın tüm günlerini içerir (harcama yoksa `"0.00"`). `dailyAverage` = toplam / ayın gün sayısı (içinde bulunulan ay için bugüne kadarki gün sayısı).

### `GET /reports/trend?months=6&endMonth=2026-10`
```json
{ "currency": "TRY", "points": [ { "month": "2026-05", "total": "9800.00" } ] }
```
`months` 1–12, harcamasız aylar `"0.00"` ile döner.

## 7. AI yorumları

### Yorum nesnesi
```json
{ "id": "...", "month": "2026-10",
  "summary": "Ekim'de toplam 12.450,75 TL harcadınız; bu Eylül'e göre %13 fazla.",
  "observations": [ "Yeme-İçme harcamanız %35 arttı.", "..." ],
  "suggestions": [ "Haftalık dışarıda yemek bütçesi belirlemeyi deneyebilirsiniz." ],
  "stale": false,
  "generatedAt": "2026-11-01T09:30:00Z",
  "disclaimer": "Bu yorum yapay zekâ tarafından üretilmiştir, finansal tavsiye değildir." }
```

### `GET /insights/monthly?month=2026-10`
Kayıtlı en son yorum → 200. Yorum yoksa 404. `stale: true` → yorum oluşturulduktan sonra veri değişmiş (mobil "Güncelle" önerir).

### `POST /insights/monthly?month=2026-10`
Yorum üretir veya veri değişmediyse mevcut yorumu döner. → 200 (mevcut) / 201 (yeni).
Hatalar: 422 `INSUFFICIENT_DATA` (< 3 harcama), 429 `AI_DAILY_LIMIT`, 503 `AI_UNAVAILABLE`.
Yanıt başlığı: `X-AI-Remaining-Today: 4`.

### AI sağlayıcısına giden veri (iç sözleşme, KARAR-8)
```json
{ "month": "2026-10", "currency": "TRY", "locale": "tr-TR",
  "total": "12450.75", "previousTotal": "10980.00", "expenseCount": 63,
  "byCategory": [ { "name": "Market", "total": "3820.40", "percent": "30.68", "changePercent": "9.15" } ],
  "topExpenses": [ { "category": "Kira/Konut", "amount": "2500.00", "description": "Aidat" } ] }
```
Ad, e-posta, kullanıcı kimliği ve `note` alanı gönderilmez.

## 8. Sağlık

`GET /actuator/health` → `{ "status": "UP" }` (kimlik doğrulama gerektirmez).
