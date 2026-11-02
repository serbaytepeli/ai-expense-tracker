# AI Expense Tracker — API Sözleşmesi (v1)

> Durum: **Revizyon 3 — ürün sahibi onayı bekleniyor (PR #1)**
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
- Her yanıtta `X-Request-Id` başlığı bulunur. İstemci `[A-Za-z0-9._-]{1,64}` biçiminde bir `X-Request-Id` gönderirse aynısı kullanılır; göndermezse veya biçim geçersizse sunucu bir UUID üretir. Aynı değer hata gövdesindeki `requestId` alanında ve sunucu loglarında bulunur.

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
| 401 | `UNAUTHORIZED` | Belirteç yok, imzası geçersiz veya bilinmeyen refresh token |
| 401 | `INVALID_CREDENTIALS` | Yanlış e-posta/şifre |
| 401 | `TOKEN_EXPIRED` | Access veya refresh token'ın süresi dolmuş |
| 401 | `SESSION_REVOKED` | Oturum iptal edilmiş (çıkış, token yeniden kullanımı) |
| 401 | `TOKEN_ALREADY_ROTATED` | Refresh token 30 sn içinde ikinci kez kullanıldı; oturum açık kalır |
| 401 | `TOKEN_REUSED` | Refresh token 30 sn'den sonra yeniden kullanıldı; oturum iptal edildi |
| 404 | `NOT_FOUND` | Kaynak yok **veya başka kullanıcıya ait** |
| 409 | `EMAIL_TAKEN` / `CATEGORY_NAME_TAKEN` / `CATEGORY_PROTECTED` / `CATEGORY_ARCHIVED` / `CATEGORY_SORT_ORDER_LIMIT` | Çakışma |
| 409 | `AI_GENERATION_IN_PROGRESS` | Aynı ay ve veri için üretim sürüyor (`Retry-After: 3`) |
| 422 | `INSUFFICIENT_DATA` | AI yorumu için yeterli harcama yok |
| 429 | `RATE_LIMITED` | Giriş/kayıt hız sınırı (`Retry-After`) |
| 429 | `AI_DAILY_LIMIT` | Günlük başarılı AI üretim sınırı doldu (`Retry-After`: kullanıcının yerel gece yarısına kalan saniye) |
| 503 | `AI_UNAVAILABLE` | AI sağlayıcısı hata verdi, zaman aşımı, geçersiz yanıt veya yapılandırılmamış; kota düşülmez |
| 500 | `INTERNAL_ERROR` | Beklenmeyen sunucu hatası. `detail` her zaman genel bir metindir; istisna mesajı, sınıf adı veya stack trace yanıta konmaz (sunucu logunda `requestId` ile bulunur) |

Yukarıda adı geçmeyen diğer HTTP hatalarında `code`, HTTP durumunun adıdır (ör. 405 → `METHOD_NOT_ALLOWED`, 415 → `UNSUPPORTED_MEDIA_TYPE`) ve `type` = `https://aiexpense.app/problems/<code küçük harf, _ yerine ->`. Bilinmeyen yol 404 `NOT_FOUND` döner.

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
- Hazır kategoriler (§4) kullanıcıyla aynı transaction içinde oluşturulur; oluşturulamazsa kayıt tümüyle geri alınır.
- Doğrulama (400 `VALIDATION_ERROR`, `errors[].field`): `email` en fazla 254 karakter ve `ad@alan` biçimi; `password` 8–72 karakter **ve** UTF-8'de en fazla 72 bayt (BCrypt ilk 72 baytı kullanır; daha uzun şifre kırpılmaz, reddedilir); `displayName` kırpıldıktan sonra 1–60 karakter.
- Hatalar: 400, 409 `EMAIL_TAKEN` (eşzamanlı iki kayıtta da yalnızca biri başarılı olur), 429 `RATE_LIMITED`.

- E-posta kayıt ve girişte normalize edilir (database-design.md §3).

Her başarılı kayıt/girişte yeni bir **oturum** oluşur. Access token (JWT, HS256, 15 dk) `iss` (`ai-expense-tracker`), `sub` (kullanıcı kimliği) ve `sid` (oturum kimliği) içerir; korunan her istekte oturumun iptal edilmemiş olduğu kontrol edilir. Ayrıntılar: architecture.md §3.6.

**Korumalı istekler:** Token yoksa, bozuksa veya imzası geçersizse 401 `UNAUTHORIZED`; süresi dolmuşsa 401 `TOKEN_EXPIRED`; oturumu iptal edilmişse (çıkış, yeniden kullanım, hesap silme) 401 `SESSION_REVOKED`. 401 yanıtlarında `WWW-Authenticate: Bearer` başlığı bulunur.

**Hız sınırı** (istemci IP'si başına, bellek içi): giriş 5 dakikada 10 deneme, kayıt 1 saatte 10 deneme (başarılı ve başarısız denemeler sayılır). Aşılırsa 429 `RATE_LIMITED` ve pencerenin bitimine kalan saniye `Retry-After` başlığında.

### `POST /auth/login`
`{ "email", "password" }` → 200, register ile aynı yanıt. Hatalar: 401 `INVALID_CREDENTIALS` (yanlış şifre ve kayıtlı olmayan e-posta **aynı** yanıtı alır; ikisinde de BCrypt çalışır, süre farkı adresin kayıtlı olup olmadığını belli etmez), 429 `RATE_LIMITED`.

### `POST /auth/refresh`
`{ "refreshToken" }` → 200 `{ accessToken, refreshToken, expiresIn }`. Gönderilen refresh token kullanılmış olarak işaretlenir ve aynı oturumda yenisi üretilir (rotasyon).

| Durum | Yanıt |
|---|---|
| Geçerli token | 200, yeni çift |
| Süresi dolmuş | 401 `TOKEN_EXPIRED` |
| Bilinmeyen | 401 `UNAUTHORIZED` |
| Oturum iptal edilmiş | 401 `SESSION_REVOKED` |
| Kullanılmış, rotasyondan ≤ 30 sn sonra (eşzamanlı istek / kaybolmuş yanıt) | 401 `TOKEN_ALREADY_ROTATED`; oturum açık kalır |
| Kullanılmış, rotasyondan > 30 sn sonra | 401 `TOKEN_REUSED`; oturumun tamamı iptal edilir |

### `POST /auth/logout`
Kimlik doğrulama gerekir. Gövde yok. Access token'daki `sid` oturumu iptal edilir → 204. Oturumun access ve refresh token'ları hemen geçersiz olur.

## 3. Kullanıcı (`user` modülü)

| Metot | Yol | Açıklama |
|---|---|---|
| GET | `/me` | Profil |
| PATCH | `/me` | Yalnızca `displayName` ve `timeZone` güncellenebilir. `currency` MVP'de değiştirilemez (D-3) |
| DELETE | `/me` | Gövde: `{ "password": "..." }`. Şifre doğruysa hesap ve tüm veri silinir → 204. Yanlış şifre → 401 `INVALID_CREDENTIALS` (FR-7) |

## 4. Kategoriler (`category` modülü)

### Kategori nesnesi
```json
{ "id": "...", "name": "Market", "icon": "cart-outline", "color": "#2E7D32",
  "systemKey": "GROCERIES", "sortOrder": 1, "archived": false }
```
- Tüm alanlar yanıtta her zaman bulunur. `systemKey` yalnızca hazır kategorilerde dolu; kullanıcının oluşturduğu kategorilerde `null`.

| Metot | Yol | Açıklama |
|---|---|---|
| GET | `/categories?includeArchived=false` | Kategori listesi (`sortOrder`, sonra ad sırasıyla). Varsayılan yalnızca aktif kategoriler; `includeArchived=true` arşivlileri de verir |
| POST | `/categories` | `{ "name", "icon", "color" }` → 201. Hatalar: 400, 409 `CATEGORY_NAME_TAKEN`, 409 `CATEGORY_SORT_ORDER_LIMIT` |
| PATCH | `/categories/{id}` | `name`, `icon`, `color`, `sortOrder` (hepsi isteğe bağlı; gönderilmeyen alan değişmez) → 200. Arşivli kategori de düzenlenebilir. Hatalar: 400, 404, 409 `CATEGORY_NAME_TAKEN` |
| POST | `/categories/{id}/archive` | → 200 (zaten arşivliyse değişiklik yapmaz). `OTHER` için 409 `CATEGORY_PROTECTED` |
| POST | `/categories/{id}/unarchive` | → 200 (zaten aktifse değişiklik yapmaz). Aynı adda aktif kategori varsa 409 `CATEGORY_NAME_TAKEN` |

Kategori silme uç noktası yoktur. Tüm uç noktalar kimlik doğrulaması ister; sahip her zaman JWT'deki kullanıcıdır. Başka kullanıcıya ait, bilinmeyen veya biçimi geçersiz `id` → 404 `NOT_FOUND`.

**Doğrulama** (400 `VALIDATION_ERROR`, `errors[].field`):
- `name`: baştaki/sondaki boşluk karakterleri kırpılarak saklanır; kırpıldıktan sonra 1–30 karakter. Kırpılanlar: tüm Unicode `White_Space` karakterleri (normal boşluk, sekme, satır sonu, bölünmez boşluklar U+00A0/U+2007/U+202F, U+3000 vb.) ile görünmez U+200B ve U+FEFF. Aradaki boşluklara dokunulmaz.
- `icon`: 1–40 karakter; yalnızca küçük harf `a-z`, rakam ve `-` (Ionicons adı biçimi; backend ikon listesi tutmaz).
- `color`: `#RRGGBB`; büyük harfe çevrilerek saklanır.
- `sortOrder`: 0–32767.

**Ad benzersizliği:** Aynı kullanıcının **aktif** kategorileri arasında, kırpılmış ad üzerinde büyük/küçük harf duyarsız. Karşılaştırmayı veritabanı yapar (`uk_categories_user_active_name`, `lower(name)`); uygulama ayrıca karşılaştırma yapmaz. Arşivli kategorinin adı yeni kategoride kullanılabilir; bu durumda arşivli kategori arşivden çıkarılamaz (409 `CATEGORY_NAME_TAKEN`).

**Sıralama:** Yeni kategori, kullanıcının (arşivliler dahil) en büyük `sortOrder` değerinin bir fazlasını alır; aynı kullanıcının eşzamanlı oluşturma istekleri sırayla işlenir, böylece her yeni kategori ayrı bir değer alır. Bu değer 32767'yi aşacaksa kategori oluşturulmaz: 409 `CATEGORY_SORT_ORDER_LIMIT` (çözüm: mevcut bir kategorinin `sortOrder` değerini PATCH ile düşürmek).

**Hazır kategoriler** (kayıtta oluşturulur; yeniden adlandırılabilir ve arşivlenebilir, `OTHER` arşivlenemez):

| sortOrder | name | systemKey | icon | color |
|---|---|---|---|---|
| 1 | Market | `GROCERIES` | `cart-outline` | `#2E7D32` |
| 2 | Yeme-İçme | `FOOD_DRINK` | `restaurant-outline` | `#EF6C00` |
| 3 | Ulaşım | `TRANSPORT` | `bus-outline` | `#1565C0` |
| 4 | Faturalar | `BILLS` | `receipt-outline` | `#6A1B9A` |
| 5 | Kira/Konut | `HOUSING` | `home-outline` | `#4E342E` |
| 6 | Sağlık | `HEALTH` | `medkit-outline` | `#C62828` |
| 7 | Eğlence | `ENTERTAINMENT` | `film-outline` | `#AD1457` |
| 8 | Giyim | `CLOTHING` | `shirt-outline` | `#00838F` |
| 9 | Eğitim | `EDUCATION` | `school-outline` | `#283593` |
| 10 | Abonelikler | `SUBSCRIPTIONS` | `repeat-outline` | `#F9A825` |
| 11 | Diğer | `OTHER` | `ellipsis-horizontal-outline` | `#616161` |

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
- `amount`: string, desen `^[0-9]{1,12}(\.[0-9]{1,2})?$`, `> 0`, en fazla `999999999999.99`; yuvarlanmaz (database-design.md §2).
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
{ "id": "...", "generationId": "...", "month": "2026-10",
  "summary": "Ekim'de toplam 12.450,75 TL harcadınız; bu Eylül'e göre %13 fazla.",
  "observations": [ "Yeme-İçme harcamanız %35 arttı." ],
  "suggestions": [ "Dışarıda yemek için haftalık bir sınır belirlemeyi deneyebilirsiniz." ],
  "stale": false,
  "generatedAt": "2026-11-01T09:30:00Z",
  "disclaimer": "Bu yorum yapay zekâ tarafından üretilmiştir, finansal tavsiye değildir." }
```

### `GET /insights/monthly?month=2026-10`
En son kayıtlı yorum → 200; yoksa 404. `stale: true` → yorum oluşturulduktan sonra veri değişmiş. Mobil, üretim sürerken bu uç noktayı yoklar.
Yanıt başlığı: `X-AI-Remaining-Today: <0–AI_DAILY_LIMIT>` (bugünkü başarılı + devam eden üretimler düşülmüş).

### `POST /insights/monthly?month=2026-10`
Gövde yok. İşlem akışı: architecture.md §3.5.

| Durum | Yanıt |
|---|---|
| Aynı veri için yorum zaten var | **200** mevcut yorum; AI çağrılmaz, kota değişmez |
| Yeni yorum üretildi | **201** yorum; 1 başarılı üretim kotadan düşer |
| < 3 harcama | 422 `INSUFFICIENT_DATA` |
| Aynı ay ve veri için üretim sürüyor | 409 `AI_GENERATION_IN_PROGRESS`, `Retry-After: 3` |
| Bugünkü başarılı (+ devam eden) üretim sayısı ≥ `AI_DAILY_LIMIT` | 429 `AI_DAILY_LIMIT`, `Retry-After` = yerel gece yarısına kalan saniye |
| Sağlayıcı hatası / zaman aşımı / geçersiz yanıt / anahtar yok | 503 `AI_UNAVAILABLE`; kota düşmez |

Yanıt başlıkları: `X-AI-Generation-Id` (201 ve 503'te; problem gövdesinde de `generationId` alanı), `X-AI-Remaining-Today`.

**İdempotency:** İstek doğal anahtarla (kullanıcı + ay + veri parmak izi) idempotenttir; ağ hatası sonrası tekrar gönderilen `POST` aynı veri için ikinci kez kota tüketmez. Ayrı `Idempotency-Key` başlığı yoktur.

**Mobil yoklama:** `POST` ağ hatası/zaman aşımıyla biterse veya 409 dönerse, `GET` 3 sn aralıkla en fazla 60 sn çağrılır.

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

`GET /actuator/health` → `{ "status": "UP" }` (kimlik doğrulama gerektirmez). Varsayılan ve `prod` profillerinde yanıt tam olarak budur: bileşen, ayrıntı veya probe grupları gösterilmez. Ayrıntılar (veritabanı, disk vb.) yalnızca `dev` profilinde görünür. Veritabanına ulaşılamazsa `503` ve `{ "status": "DOWN" }`.

Actuator'da `health` dışında hiçbir uç nokta HTTP üzerinden açık değildir (`env`, `beans`, `configprops`, `loggers`, `heapdump` vb. → 404).
