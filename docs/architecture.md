# AI Expense Tracker — Mimari

> Durum: **TASLAK — ürün sahibi onayı bekleniyor**

## 1. Genel bakış

```
┌──────────────────────┐   HTTPS / JSON (REST, /api/v1)   ┌───────────────────────────┐
│  Mobil uygulama       │ ───────────────────────────────▶ │  Backend (Spring Boot)     │
│  React Native + Expo  │ ◀─────────────────────────────── │  Java 21                   │
│  TypeScript           │        JWT Bearer token           │                           │
└──────────────────────┘                                    │  ┌──────────┐ ┌─────────┐ │
                                                            │  │ Domain   │ │ AI      │─┼──▶ AI sağlayıcı API
                                                            │  │ servisler│ │ adapter │ │    (KARAR-2)
                                                            │  └────┬─────┘ └─────────┘ │
                                                            └───────┼───────────────────┘
                                                                    │ JDBC (Flyway migration)
                                                            ┌───────▼───────┐
                                                            │ PostgreSQL     │
                                                            └───────────────┘
```

- **İstemci tek:** Mobil uygulama. İş kuralları (rapor hesaplama, yetki, doğrulama) backend'de; mobil uygulama yalnızca görsel doğrulama yapar.
- **AI çağrısı yalnızca backend'den yapılır.** API anahtarı hiçbir zaman mobil uygulamaya girmez.
- **Rapor hesaplaması SQL ile backend'de yapılır;** AI'a sadece hesaplanmış rakamlar verilir, AI yalnızca yorum metni üretir.

## 2. Depo yapısı (monorepo)

```
ai-expense-tracker/
├── CLAUDE.md
├── README.md
├── docker-compose.yml          # postgres + backend (yerel geliştirme)
├── .env.example                # gizli olmayan örnek değişkenler
├── docs/                       # bu belgeler
├── backend/                    # Spring Boot uygulaması
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── src/
│       ├── main/java/com/aiexpense/
│       │   ├── auth/           # kayıt, giriş, JWT, refresh token
│       │   ├── user/           # profil, hesap silme
│       │   ├── category/
│       │   ├── expense/
│       │   ├── report/         # aylık rapor sorguları
│       │   ├── insight/        # AI yorumları (servis + sağlayıcı adaptörü)
│       │   └── common/         # hata yönetimi, güvenlik, config, para tipi
│       ├── main/resources/db/migration/   # Flyway SQL dosyaları
│       └── test/
├── mobile/                     # Expo uygulaması
│   ├── app/                    # Expo Router ekranları
│   ├── src/
│   │   ├── api/                # HTTP istemcisi, OpenAPI'den üretilen tipler
│   │   ├── features/           # expenses, categories, reports, insights, auth
│   │   ├── components/         # ortak UI bileşenleri
│   │   ├── i18n/
│   │   └── theme/
│   └── app.config.ts
└── .github/workflows/
    ├── backend.yml
    └── mobile.yml
```

## 3. Backend

| Konu | Seçim | Gerekçe |
|---|---|---|
| Dil / çatı | Java 21, Spring Boot (güncel kararlı sürüm, T0.2'de sabitlenir) | Belirlenen teknoloji |
| Paketleme | Özellik bazlı paketler (`expense/`, `report/` …); her pakette `Controller → Service → Repository` | Kod büyüdükçe bulunabilirlik |
| Veri erişimi | Spring Data JPA (CRUD) + rapor için yerel SQL / `JdbcClient` | Rapor sorguları toplama (aggregate) ağırlıklı |
| Migration | Flyway | SQL'i açıkça görmek, sürümlemek |
| Doğrulama | Jakarta Bean Validation | DTO'larda bildirimsel kurallar |
| Güvenlik | Spring Security, stateless JWT (access 15 dk, refresh 30 gün, refresh rotasyonu) | KARAR-1 |
| Hata formatı | RFC 9457 `application/problem+json` | Standart, istemcide tek tip işleme |
| API dokümantasyonu | springdoc-openapi → `/v3/api-docs`, Swagger UI (yalnızca dev profili) | Mobil tiplerin otomatik üretilmesi |
| AI entegrasyonu | `InsightProvider` arayüzü + sağlayıcı adaptörü; HTTP istemcisi zaman aşımlı (20 sn) | Sağlayıcı değiştirilebilir, testte sahte sağlayıcı |
| Hız sınırı | Kullanıcı başına AI üretim sayacı (DB tabanlı) + giriş uç noktasında basit IP sınırı | KARAR-9, kaba kuvvet saldırısı koruması |
| Test | JUnit 5, Mockito, Testcontainers (PostgreSQL), MockMvc | Gerçek veritabanıyla entegrasyon testi |
| Loglama | Logback JSON, `requestId` MDC | NFR-10 |

### Katmanlar ve kurallar
- Controller'lar entity döndürmez; yalnızca DTO (Java `record`).
- Her sorgu `user_id` ile filtrelenir. Kullanıcı kimliği yalnızca güvenlik bağlamından (JWT) alınır, istekten asla alınmaz.
- Para: `BigDecimal`, ölçek 2, `RoundingMode.HALF_EVEN`.
- Tarih: harcama tarihi `LocalDate`; zaman damgaları `Instant` (UTC).

### AI yorum akışı
1. Mobil `POST /api/v1/insights/monthly?month=2026-10` çağırır.
2. Backend o ayın rapor özetini hesaplar ve bir **veri parmak izi** (özetin SHA-256 özeti) üretir.
3. Aynı ay + aynı parmak izi için kayıtlı yorum varsa onu döner (AI çağrılmaz).
4. Yoksa günlük sınırı kontrol eder, özeti sabit bir şablonla sağlayıcıya gönderir, yanıtı yapılandırılmış JSON (özet, gözlemler, öneriler) olarak ayrıştırır, kaydeder ve döner.
5. Sağlayıcı hatasında `503` + problem detayı döner; hiçbir şey kaydedilmez ve kota düşülmez.

## 4. Mobil uygulama

| Konu | Seçim | Gerekçe |
|---|---|---|
| Çatı | Expo (managed workflow, güncel SDK), TypeScript strict | Belirlenen teknoloji; EAS ile derleme |
| Navigasyon | Expo Router (dosya tabanlı) | Expo'nun önerdiği yol |
| Sunucu durumu | TanStack Query | Önbellek, yeniden deneme, işlem sonrası yenileme (FR-2 AC) |
| İstemci durumu | Zustand (oturum, tercih) | Hafif |
| Formlar | React Hook Form + Zod | Tip güvenli doğrulama |
| API tipleri | OpenAPI şemasından `openapi-typescript` ile üretim | Backend ile sözleşme uyumu |
| Grafikler | `react-native-gifted-charts` (+ `react-native-svg`) | Expo Go ile uyumlu, donut/bar/line destekli |
| Güvenli depo | `expo-secure-store` (refresh token) | NFR-3 |
| i18n | `i18next` + `expo-localization` | NFR-6 |
| Test | Jest + React Native Testing Library; ESLint + Prettier | NFR-8/9 |

## 5. Ortamlar ve yapılandırma

| Ortam | Açıklama |
|---|---|
| `local` | `docker compose up` → PostgreSQL + backend; mobil Expo Go ile yerel IP'ye bağlanır |
| `test` | CI'da Testcontainers |
| `prod` | KARAR-11'e göre belirlenecek |

Gizli değerler (DB şifresi, JWT anahtarı, AI API anahtarı) yalnızca ortam değişkeniyle verilir; depoya yalnızca `.env.example` girer.

## 6. CI/CD (GitHub Actions)

- **backend.yml** (`backend/**` değişince): Gradle build → birim + Testcontainers testleri → Docker imajı derleme (push yok, MVP).
- **mobile.yml** (`mobile/**` değişince): `npm ci` → ESLint → `tsc --noEmit` → Jest.
- **Sözleşme kontrolü:** Backend'den üretilen OpenAPI şeması ile mobildeki üretilmiş tipler uyuşmazsa CI başarısız olur.
- Mobil mağaza derlemesi (EAS Build) MVP sonunda elle tetiklenir.

## 7. Güvenlik özeti

- BCrypt (cost 12) şifre saklama; giriş uç noktasında hız sınırı.
- Refresh token'lar DB'de **özetlenmiş (hash)** saklanır, her kullanımda yenilenir; çıkışta iptal edilir.
- CORS: yalnızca gerekli kaynaklar (mobil uygulama CORS'a tabi değil; Swagger için dev'de açık).
- AI'a kişisel kimlik bilgisi gönderilmez (KARAR-8); istem (prompt) şablonu sürümlenir ve kodda tutulur.
- Hesap silme: kullanıcıya bağlı tüm satırlar `ON DELETE CASCADE` ile silinir.

## 8. Mimari karar kayıtları (ADR) — özet

| ADR | Karar |
|---|---|
| ADR-1 | Monorepo (backend + mobile + docs tek depoda) |
| ADR-2 | REST + OpenAPI, sürüm öneki `/api/v1` |
| ADR-3 | Rapor hesaplaması backend'de SQL ile; AI yalnızca metin üretir |
| ADR-4 | Para `NUMERIC(14,2)` / `BigDecimal`; float yasak |
| ADR-5 | AI sağlayıcısı arayüz arkasında; testlerde sahte sağlayıcı |
| ADR-6 | Kategoriler silinmez, arşivlenir (geçmiş raporlar bozulmaz) |
