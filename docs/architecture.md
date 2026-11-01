# AI Expense Tracker — Mimari

> Durum: **Onaylı kararlar işlendi — belge onayı bekleniyor (PR)**
> Karar kodları (D-x) ve açık sorular (S-x) `requirements.md` §7–8'dedir.

## 1. Genel bakış

```
┌──────────────────────┐   HTTPS / JSON (REST, /api/v1)   ┌──────────────────────────────────────┐
│  Mobil uygulama       │ ───────────────────────────────▶ │  Backend — modüler monolit (D-12)     │
│  React Native + Expo  │ ◀─────────────────────────────── │  Java 21 + Spring Boot                │
│  TypeScript           │        JWT Bearer token           │                                      │
└──────────────────────┘                                    │  auth · user · category · expense    │
                                                            │  report · insight · common           │
                                                            │                    │ insight          │
                                                            │                    └──────────────────┼──▶ Anthropic Claude API
                                                            └───────────┬──────────────────────────┘    (yalnızca toplu veri, D-8)
                                                                        │ JDBC + Flyway
                                                                ┌───────▼───────┐
                                                                │ PostgreSQL 16  │
                                                                └───────────────┘
```

Temel ilkeler:
- **Tek dağıtılabilir birim:** Backend tek bir Spring Boot uygulamasıdır; içeride sınırları belirli modüllere ayrılır.
- **İş kuralları backend'de:** Rapor hesaplama, yetki, doğrulama (ör. ileri tarih kontrolü) sunucudadır; mobil yalnızca kullanıcı deneyimi için ön doğrulama yapar.
- **AI çağrısı yalnızca backend'den:** API anahtarı yalnızca backend ortam değişkenindedir.
- **AI hesaplama yapmaz:** Rakamları backend SQL ile hesaplar; AI'a yalnızca toplu özet verilir, AI yalnızca yorum metni üretir.

## 2. Depo yapısı (monorepo)

```
ai-expense-tracker/
├── CLAUDE.md
├── README.md
├── docker-compose.yml          # isteğe bağlı: Docker olan makinelerde postgres + backend
├── .env.example                # gizli olmayan örnek değişkenler
├── docs/
├── scripts/
│   └── dev-db.sh               # Docker'sız ortamda (Claude Code Cloud) yerel PostgreSQL'i hazırlar
├── backend/
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradle/libs.versions.toml   # bağımlılık sürümleri tek yerde
│   ├── Dockerfile
│   └── src/
│       ├── main/java/com/aiexpense/
│       │   ├── auth/  user/  category/  expense/  report/  insight/  common/
│       ├── main/resources/db/migration/   # Flyway
│       └── test/
├── mobile/
│   ├── app/                    # Expo Router ekranları
│   ├── src/
│   │   ├── api/                # HTTP istemcisi + OpenAPI'den üretilen tipler
│   │   ├── features/           # auth, expenses, categories, reports, insights, settings
│   │   ├── components/  i18n/  theme/
│   └── app.config.ts
└── .github/
    ├── workflows/              # bkz. §6
    └── dependabot.yml
```

## 3. Backend — modüler monolit

### 3.1 Modüller

| Modül | Sorumluluk | Bağımlı olabileceği modüller |
|---|---|---|
| `common` | Hata işleme (problem+json), güvenlik altyapısı, `Money` yardımcıları, saat/tarih sağlayıcı (`Clock`), `requestId` | — |
| `auth` | Kayıt, giriş, JWT üretme/doğrulama, refresh token | `user`, `category` (kayıtta hazır kategoriler), `common` |
| `user` | Profil, saat dilimi, hesap silme | `common` |
| `category` | Kategori CRUD, arşivleme, hazır kategori listesi | `common` |
| `expense` | Harcama CRUD, listeleme, filtreleme | `category`, `user`, `common` |
| `report` | Aylık rapor, trend (yalnızca okuma sorguları) | `category`, `user`, `common` |
| `insight` | AI yorumu, kota, AI sağlayıcı adaptörü | `report`, `user`, `common` |

### 3.2 Modül kuralları
- Her modülün dışa açık yüzü yalnızca kök paketindeki **public servis arayüzleri ve DTO'lardır** (ör. `category.CategoryApi`). Alt paketler (`internal`, `web`, `persistence`) başka modüller tarafından kullanılamaz.
- Modüller birbirinin tablosuna doğrudan SQL/JPA ile erişmez. İstisna: `report` modülü raporlama için `expenses` ve `categories` tablolarını **salt okuma** sorgularıyla okur (performans için bilinçli karar, ADR-3).
- Döngüsel bağımlılık yasaktır.
- Bu kurallar **ArchUnit** testleriyle denetlenir; kural ihlali derlemeyi kırar.
- Hesap silme: `user` modülü kullanıcıyı siler; diğer tablolar veritabanındaki `ON DELETE CASCADE` ile temizlenir.

### 3.3 Teknik seçimler

| Konu | Seçim |
|---|---|
| Dil / çatı | Java 21, Spring Boot (T0.2'de güncel kararlı sürüm seçilir ve `libs.versions.toml`'da sabitlenir) |
| Build | Gradle Kotlin DSL + Gradle Wrapper + version catalog (D-13) |
| Veri erişimi | Spring Data JPA (CRUD) + rapor için `JdbcClient` ile SQL |
| Migration | Flyway |
| Doğrulama | Jakarta Bean Validation; ileri tarih kontrolü kullanıcı saat dilimiyle servis katmanında |
| Güvenlik | Spring Security, stateless JWT (access 15 dk, refresh 30 gün, rotasyonlu), BCrypt cost 12 |
| Hata formatı | RFC 9457 `application/problem+json` |
| API dokümantasyonu | springdoc-openapi → `/v3/api-docs`; Swagger UI yalnızca `dev` profilinde |
| AI | `InsightProvider` arayüzü; `AnthropicInsightProvider` (gerçek) ve `FakeInsightProvider` (test); model adı `AI_MODEL`, anahtar `ANTHROPIC_API_KEY` ortam değişkeninden |
| Test | JUnit 5, Mockito, Spring Boot Test + MockMvc, ArchUnit, JaCoCo (kapsam) |
| Loglama | Logback JSON, MDC'de `requestId`; şifre, belirteç, API anahtarı loglanmaz |

### 3.4 Para ve zaman
- Para: `BigDecimal`, ölçek 2, `RoundingMode.HALF_EVEN`; DB `NUMERIC(14,2)`; JSON string.
- Harcama tarihi `LocalDate`; zaman damgaları `Instant` (UTC).
- "Bugün" ve ay sınırları, kullanıcının saat dilimi ve enjekte edilen `Clock` ile hesaplanır (testlerde sabit saat kullanılabilir).

### 3.5 AI yorum akışı
1. Mobil `POST /api/v1/insights/monthly?month=2026-10` çağırır.
2. `insight`, `report` modülünden o ayın ve önceki dönemlerin toplu özetini alır.
3. Özetten **AI yükü** (`InsightPayload`, api-contract.md §7) üretilir. Yük bir izin listesi (allow-list) ile kurulur: yalnızca tanımlı alanlar eklenebilir. Açıklama, not, tekil harcama, ad, e-posta, kullanıcı kimliği eklenemez (D-8).
4. Yükün SHA-256 **parmak izi** hesaplanır. Aynı ay + aynı parmak izi için kayıtlı yorum varsa o döner; AI çağrılmaz, kota düşmez.
5. Yoksa günlük kota (kullanıcının saat dilimine göre gün, en fazla 5) atomik olarak kontrol edilir.
6. Sağlayıcı çağrılır (20 sn zaman aşımı, ağ hatasında 1 yeniden deneme). Yanıt yapılandırılmış JSON (özet, gözlemler, öneriler) olarak ayrıştırılır ve doğrulanır.
7. Başarılıysa kaydedilir ve kota sayacı artırılır. Hata veya geçersiz yanıtta `503 AI_UNAVAILABLE`; hiçbir şey kaydedilmez, kota düşmez.

## 4. Mobil uygulama

| Konu | Seçim |
|---|---|
| Çatı | Expo (managed workflow; T0.4'te güncel kararlı SDK seçilir), TypeScript `strict` |
| Navigasyon | Expo Router |
| Sunucu durumu | TanStack Query |
| İstemci durumu | Zustand (oturum, tercihler) |
| Formlar | React Hook Form + Zod |
| API tipleri | Backend OpenAPI şemasından `openapi-typescript` ile üretim |
| Grafikler | `react-native-gifted-charts` + `react-native-svg` |
| Güvenli depo | `expo-secure-store` |
| i18n | `i18next` + `expo-localization` (yalnızca `tr` kaynak dosyası) |
| Test | Jest (`jest-expo`) + React Native Testing Library; ESLint + Prettier |

Mobil uygulamanın GitHub Actions'ta doğrulanması: lint, tip kontrolü, birim/bileşen testleri ve `npx expo export` ile paketleme kontrolü (derlemenin kırılmadığını gösterir). iOS/Android mağaza derlemesi (EAS Build) sonraki aşamadadır (D-11).

## 5. Ortamlar ve yapılandırma

| Ortam | Nerede | Veritabanı |
|---|---|---|
| `ci` | GitHub Actions (ubuntu runner) | `services: postgres:16` servis konteyneri |
| `cloud-dev` | Claude Code Cloud oturumu | Oturumda kurulu PostgreSQL 16, `scripts/dev-db.sh` ile başlatılır (Docker yok) |
| `local` (isteğe bağlı) | Docker olan bir geliştirici makinesi | `docker compose up` |
| `prod` | Sonraki aşama (D-11) | — |

Ortam değişkenleri (hepsi `.env.example`'da açıklamalı; gerçek değerler commit edilmez):

| Değişken | Açıklama |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL bağlantısı |
| `JWT_SECRET` | En az 256 bit rastgele anahtar |
| `ANTHROPIC_API_KEY` | Yalnızca backend; boşsa uygulama açılır ama AI uç noktası `503` döner |
| `AI_MODEL` | Kullanılacak Claude modelinin adı |
| `AI_DAILY_LIMIT` | Varsayılan `5` |

GitHub Actions'ta `ANTHROPIC_API_KEY` **kullanılmaz**: CI testleri sahte sağlayıcıyla çalışır, böylece anahtar CI'a hiç verilmez.

## 6. Test stratejisi ve CI/CD (GitHub Actions)

### 6.1 Test katmanları

| Katman | Kapsam | Veritabanı | Nerede çalışır |
|---|---|---|---|
| Birim | Servisler, para/tarih hesapları, AI yükü izin listesi | Yok | CI + Claude Code Cloud |
| Mimari | ArchUnit modül sınırları, float/double para yasağı | Yok | CI + Claude Code Cloud |
| Veritabanı | Flyway migration'ları, kısıtlar (unique, check, cascade), rapor SQL'leri | Gerçek PostgreSQL 16 | CI + Claude Code Cloud |
| API (entegrasyon) | MockMvc ile uç noktalar: doğrulama, hata formatı, yetki, **iki kullanıcılı izolasyon** | Gerçek PostgreSQL 16 | CI + Claude Code Cloud |
| Sözleşme | OpenAPI şeması ↔ mobil üretilmiş tipler uyumu | — | CI |
| Mobil | Bileşen ve hook testleri (API sahte) | — | CI + Claude Code Cloud |

**Docker'a bağımlılık yok:** Veritabanı testleri bağlantıyı `TEST_DB_URL` (+ kullanıcı/şifre) ortam değişkeninden alır. CI'da bu adres servis konteynerini, Claude Code Cloud'da yerel PostgreSQL'i gösterir. Her test sınıfı kendi şemasında veya transaction geri alma ile izole çalışır. Testcontainers kullanılmaz (Docker gerektirdiği için); Docker olan makinelerde `docker compose` ile aynı değişkenler sağlanabilir.

**Raporlama kuralı (NFR-11):** Bir test ortamda çalıştırılamadıysa (ör. veritabanı yok) "atlandı" olarak raporlanır, başarılı sayılmaz. Veritabanı testleri `TEST_DB_URL` yoksa **başarısız olur** (sessizce atlanmaz), böylece eksik ortam fark edilir.

### 6.2 İş akışları

| Dosya | Tetikleyici | Adımlar |
|---|---|---|
| `backend-ci.yml` | `backend/**` değişen PR ve `main` push | JDK 21 + Gradle önbelleği → `./gradlew build` (derleme, birim, mimari, veritabanı ve API testleri; PostgreSQL 16 servis konteyneriyle) → JaCoCo kapsam raporu ve eşik kontrolü → test raporlarını artifact olarak yükleme → Docker imajı derleme (push yok) |
| `mobile-ci.yml` | `mobile/**` değişen PR ve `main` push | Node LTS + npm önbelleği → `npm ci` → ESLint → `tsc --noEmit` → Jest → `npx expo export` |
| `contract-ci.yml` | `backend/**` veya `mobile/src/api/**` değişince | Backend'i başlatıp OpenAPI şemasını üretir, mobil tipleri yeniden üretir; fark varsa başarısız |
| `security.yml` | Her PR, `main` push, haftalık zamanlanmış | Aşağıdaki güvenlik kontrolleri |

### 6.3 Güvenlik ve bağımlılık kontrolleri

| Kontrol | Araç | Başarısızlık eşiği |
|---|---|---|
| Bağımlılık açıkları (Gradle + npm kilit dosyaları) | Trivy `fs` taraması | HIGH / CRITICAL |
| npm bağımlılık açıkları | `npm audit --audit-level=high` | high ve üstü |
| Docker imajı açıkları | Trivy `image` taraması | HIGH / CRITICAL (düzeltmesi olanlar) |
| Gizli anahtar sızıntısı | Gitleaks (tüm geçmiş) | Herhangi bir bulgu |
| Lisans kontrolü | Trivy lisans taraması | Yasaklı listede lisans (GPL/AGPL) — uyarı |
| Otomatik güncelleme | Dependabot (Gradle, npm, GitHub Actions, Docker) | Haftalık PR |
| Statik kod analizi | CodeQL (Java, TypeScript) | **Not:** Özel (private) depolarda CodeQL için GitHub Advanced Security gerekir. Depo özel kaldıkça devre dışı; depo açık olursa veya GHAS alınırsa etkinleştirilir |

Gradle bağımlılıkları için `gradle.lockfile` (dependency locking) açık tutulur; böylece tarayıcılar kesin sürümleri görür. Tüm GitHub Actions adımları sabit sürüm/commit ile referanslanır.

### 6.4 Dal koruma (önerilen)
`main` için: PR zorunlu, `backend-ci`, `mobile-ci`, `security` kontrollerinin geçmesi zorunlu. Bu ayar GitHub depo ayarlarından ürün sahibi tarafından açılır.

## 7. Güvenlik özeti

- Şifreler BCrypt (cost 12); düz metin saklanmaz, loglanmaz.
- Refresh token'lar DB'de SHA-256 özetiyle saklanır, her kullanımda yenilenir, çıkışta iptal edilir.
- Giriş ve kayıt uç noktalarında IP başına hız sınırı.
- Veri izolasyonu: `user_id` yalnızca JWT'den; her sorguda filtre; iki kullanıcılı testler.
- AI: yalnızca backend, yalnızca izin listesindeki toplu veri, anahtar yalnızca ortam değişkeninde.
- Hesap silme: tüm kullanıcı verisi CASCADE ile silinir.

## 8. Mimari karar kayıtları (ADR) — özet

| ADR | Karar |
|---|---|
| ADR-1 | Monorepo (backend + mobile + docs) |
| ADR-2 | REST + OpenAPI, `/api/v1` |
| ADR-3 | Rapor hesaplaması backend'de SQL ile; `report` modülü tabloları salt okur; AI yalnızca metin üretir |
| ADR-4 | Para `NUMERIC(14,2)` / `BigDecimal` / JSON string |
| ADR-5 | AI sağlayıcısı arayüz arkasında; testlerde sahte sağlayıcı, CI'da gerçek anahtar yok |
| ADR-6 | Kategoriler silinmez, arşivlenir |
| ADR-7 | Modüler monolit; sınırlar ArchUnit ile denetlenir |
| ADR-8 | Testler Docker'sız çalışır; veritabanı bağlantısı ortam değişkeninden (CI: servis konteyneri, Cloud: yerel PostgreSQL) |
| ADR-9 | PostgreSQL 16 tüm ortamlarda aynı ana sürüm |
