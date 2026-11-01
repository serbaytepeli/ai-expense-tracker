# AI Expense Tracker — Mimari

> Durum: **Revizyon 3 — ürün sahibi onayı bekleniyor (PR #1)**
> Karar kodları (D-x) ve açık sorular (S-x) `requirements.md` §7–8'dedir. Tablo ayrıntıları `database-design.md`'dedir.

## 1. Genel bakış

```
┌──────────────────────┐   HTTPS / JSON (REST, /api/v1)   ┌──────────────────────────────────────┐
│  Mobil uygulama       │ ───────────────────────────────▶ │  Backend — modüler monolit (D-12)     │
│  React Native + Expo  │ ◀─────────────────────────────── │  Java 21 + Spring Boot                │
│  TypeScript           │        JWT Bearer token           │                                      │
└──────────────────────┘                                    │  auth · user · category · expense    │
                                                            │  report · insight · common           │
                                                            │                    │ insight          │
                                                            │                    └──────────────────┼──▶ Anthropic API
                                                            └───────────┬──────────────────────────┘    (yalnızca toplu veri, D-8)
                                                                        │ JDBC + Flyway
                                                                ┌───────▼───────┐
                                                                │ PostgreSQL 16  │
                                                                └───────────────┘
```

Temel ilkeler:
- **Tek dağıtılabilir birim:** Backend tek bir Spring Boot uygulamasıdır; içeride sınırları belirli modüllere ayrılır. Kuyruk, mesaj aracısı veya dağıtık bileşen yoktur.
- **İş kuralları backend'de:** Rapor hesaplama, yetki, zamana bağlı doğrulamalar (ör. ileri tarih) sunucudadır; mobil yalnızca kullanıcı deneyimi için ön doğrulama yapar.
- **AI çağrısı yalnızca backend'den;** API anahtarı yalnızca backend ortam değişkenindedir.
- **AI hesaplama yapmaz:** Rakamları backend hesaplar; AI yalnızca yorum metni üretir.

## 2. Depo yapısı (monorepo)

```
ai-expense-tracker/
├── CLAUDE.md
├── README.md
├── .env.example
├── .nvmrc                      # Node sürümü (CI ve geliştirme aynı)
├── .gitleaks.toml              # Gitleaks yapılandırması
├── .trivyignore                # Kabul edilen bulgular (gerekçe + bitiş tarihiyle)
├── docker-compose.yml          # İsteğe bağlı; Docker olan makineler için
├── docs/
├── scripts/
│   ├── check-env.sh            # Ortamda gereken araçları ve izinleri raporlar
│   ├── dev-db.sh               # Destekleniyorsa depo dışındaki ayrı bir dizinde PostgreSQL kümesi başlatır
│   └── ci/                     # CI'da kullanılan küçük betikler (ör. tarayıcı öz-testleri)
├── backend/
│   ├── build.gradle.kts  settings.gradle.kts  gradlew  gradle/wrapper/
│   ├── gradle/libs.versions.toml
│   ├── gradle.lockfile
│   ├── Dockerfile              # Test edilmiş JAR'dan çalışma imajı
│   └── src/main/java/com/aiexpense/{auth,user,category,expense,report,insight,common}/
├── mobile/
│   ├── app/                    # Expo Router ekranları
│   ├── src/{api,features,components,i18n,theme}/
│   ├── package.json  package-lock.json
│   └── app.config.ts
└── .github/
    ├── workflows/{ci.yml,security.yml,codeql.yml}
    └── dependabot.yml
```

## 3. Backend — modüler monolit

### 3.1 Modüller

| Modül | Sorumluluk | Bağımlı olabileceği modüller |
|---|---|---|
| `common` | Hata işleme (problem+json), güvenlik altyapısı, para yardımcıları, `Clock`, `requestId`, log maskeleme | — |
| `auth` | Kayıt, giriş, JWT, oturum ve refresh token | `user`, `category`, `common` |
| `user` | Profil, saat dilimi, hesap silme | `common` |
| `category` | Kategori CRUD, arşivleme, hazır kategori listesi | `common` |
| `expense` | Harcama CRUD, listeleme, filtreleme | `category`, `user`, `common` |
| `report` | Aylık rapor ve trend (salt okuma) | `category`, `user`, `common` |
| `insight` | AI yorumu, üretim istekleri ve kota, AI sağlayıcı adaptörü | `report`, `user`, `common` |

### 3.2 Modül kuralları
- Her modülün dışa açık yüzü yalnızca kök paketindeki public servis arayüzleri ve DTO'lardır. Alt paketler (`internal`, `web`, `persistence`) başka modüllerden kullanılamaz.
- Modüller birbirinin tablosuna erişmez. İstisna: `report` raporlama için `expenses` ve `categories` tablolarını salt okur (ADR-3).
- Döngüsel bağımlılık yasaktır. Kurallar **ArchUnit** testleriyle denetlenir.
- Hesap silme: `user` modülü kullanıcıyı siler; diğer tablolar `ON DELETE CASCADE` ile temizlenir.

### 3.3 Teknik seçimler

| Konu | Seçim |
|---|---|
| Dil / çatı | Java 21, Spring Boot 4.1 (T0.2'de 4.1.2 seçildi; sürümler `gradle/libs.versions.toml`'da, kesin sürümler `gradle.lockfile`'da) |
| Build | Gradle Kotlin DSL + wrapper + version catalog + dependency locking (D-13) |
| Veri erişimi | Spring Data JPA (CRUD) + rapor ve kota için `JdbcClient` ile SQL |
| Migration | Flyway |
| Doğrulama | Jakarta Bean Validation (biçim) + servis katmanı (zamana ve kullanıcıya bağlı kurallar) |
| Güvenlik | Spring Security, stateless JWT (HS256), BCrypt cost 12 |
| Hata formatı | RFC 9457 `application/problem+json` |
| API dokümantasyonu | springdoc-openapi; `/v3/api-docs` ve Swagger UI yalnızca `dev` profilinde |
| Profiller | Varsayılan ayarlar üretim kadar güvenli (Swagger kapalı, health ayrıntısız, stack trace yok, yalnızca `health` açık). `dev` yalnızca geliştirme kolaylıklarını açar; `prod` güvenli ayarları açıkça tekrar eder |
| AI | `InsightProvider` arayüzü; `AnthropicInsightProvider` (gerçek), `FakeInsightProvider` (test) |
| Zamanlanmış işler | Spring `@Scheduled` (database-design.md §7) |
| Test | JUnit 5, Mockito, Spring Boot Test + MockMvc, ArchUnit, JaCoCo, WireMock (sahte Anthropic HTTP sunucusu) |
| Loglama | Spring Boot yapılandırılmış loglama (Logback, `logstash` JSON biçimi); MDC'de `requestId` ve varsa `generationId`; tüm log değerlerinde şifre/token/API anahtarı maskeleme (güvenlik ağı) |

### 3.4 Yapılandırma doğrulaması
Yapılandırma `@ConfigurationProperties` + `@Validated` sınıflarıyla okunur; geçersiz değer **uygulamanın açılmasını engeller** (hızlı hata).

| Değişken | Kural |
|---|---|
| `AI_DAILY_LIMIT` | Tam sayı, `>= 1` (`@Positive`). Varsayılan `5`. `0`, negatif veya sayı olmayan değer → açılış hatası |
| `AI_RESERVATION_TTL` | Süre, `>= 30s`. Varsayılan `2m`. Sağlayıcı zaman aşımı × 2 + 5 sn'den küçükse açılış hatası |
| `AI_PROVIDER_TIMEOUT` | Süre, varsayılan `20s` |
| `AI_MODEL` | `ANTHROPIC_API_KEY` doluysa boş olamaz |
| `ANTHROPIC_API_KEY` | Boş olabilir; boşsa AI uç noktası `503 AI_UNAVAILABLE` döner (`NOT_CONFIGURED`) |
| `JWT_SECRET` | En az 32 bayt (256 bit); aksi açılış hatası |

### 3.5 AI üretimi, kota ve eşzamanlılık

**Hedefler:** Kullanıcı başına günde en fazla `AI_DAILY_LIMIT` (5) **başarılı** üretim; eşzamanlı istekler sınırı aşamaz; başarısız çağrı kota tüketmez; harici çağrı sırasında veritabanı transaction'ı veya kilidi tutulmaz; aynı veri için eşzamanlı istekler tek üretime iner.

**Kavramlar**
- *Anahtar*: (`user_id`, `period_month`, `data_fingerprint`). `data_fingerprint` = `InsightPayload`'ın kanonik JSON'unun SHA-256'sı.
- *Üretim kimliği (generationId)*: `ai_generation_requests.id`. Her üretim isteğini baştan sona izler; yanıt başlığında, gövdede ve loglarda bulunur.
- *Kota günü*: kullanıcının profilindeki saat dilimine göre bugünün tarihi (`LocalDate.now(clock.withZone(userZone))`).

**Akış — `POST /insights/monthly?month=YYYY-MM`**

| Adım | İşlem | Transaction |
|---|---|---|
| 1 | `report` modülünden toplu özet alınır, `InsightPayload` ve parmak izi hesaplanır | Salt okuma |
| 2 | Harcama sayısı < 3 → `422 INSUFFICIENT_DATA` | — |
| 3 | Aynı anahtarla bir `ai_insights` kaydı varsa → **200**, mevcut yorum (AI çağrılmaz, kota değişmez) | Salt okuma |
| 4 | **Rezervasyon transaction'ı** (database-design.md §6): kullanıcı satırı kilidi → kullanıcının süresi dolmuş rezervasyonları `EXPIRED` → aynı anahtarla aktif `RESERVED` varsa **409 `AI_GENERATION_IN_PROGRESS`** → kota dolu ise **429 `AI_DAILY_LIMIT`** → `RESERVED` satırı eklenir → commit | Kısa (ms) |
| 5 | Sağlayıcı çağrısı: zaman aşımı `AI_PROVIDER_TIMEOUT` (20 sn). Yalnızca bağlantı hatası, zaman aşımı, HTTP 429/5xx'te **1 kez** yeniden denenir (1 sn bekleme). En kötü süre ≈ 41 sn < TTL (2 dk) | **Yok** |
| 6a | Başarı: yanıt şeması doğrulanır → tek transaction'da `ai_insights` eklenir ve istek `WHERE status='RESERVED'` koşuluyla `SUCCEEDED` yapılır → **201** | Kısa |
| 6b | Başarısızlık: istek `FAILED` (+`error_code`) yapılır → **503 `AI_UNAVAILABLE`** | Kısa |
| 6c | 6a'daki koşullu güncelleme 0 satır etkilerse (rezervasyon bu arada `EXPIRED` olmuş): sonuç atılır, `error_code = RESULT_AFTER_EXPIRY` loglanır → 503 | Kısa |

**Durumlar ve yarım kalan işlemler**
- `RESERVED`: devam ediyor; kota sayımına **yalnızca `expires_at` geçmemişse** dahil edilir. Bu sayede uygulama 5. adımda çökerse veya yeniden başlarsa, rezervasyon en geç TTL sonunda kendiliğinden kotadan düşer — temizlik görevi çalışmasa bile.
- `EXPIRED`: rezervasyon sonuçlanmadan süresi doldu. Rezervasyon transaction'ı (kullanıcı bazında) ve 1 dakikalık zamanlanmış görev (genel) bu durumu yazar.
- `SUCCEEDED` kalıcı olarak kotadan düşer; `FAILED` ve `EXPIRED` düşmez.

**Retry ve idempotency**
- Sunucu tarafı: sağlayıcıya en fazla 2 HTTP denemesi (yukarıda). Uygulama düzeyinde başka otomatik yeniden deneme yoktur.
- İstemci tarafı: `POST` doğal olarak idempotenttir — aynı veri için tekrar gönderilen istek ya mevcut yorumu (200), ya devam eden üretimi (409) ya da yeni üretimi döner; aynı veri için iki kez kota tüketilmez. Ayrı bir `Idempotency-Key` başlığı gerekmez.
- Mobil davranış: `POST` ağ hatası veya zaman aşımıyla biterse ya da 409 alırsa, `GET /insights/monthly`'yi 3 sn aralıkla en fazla 60 sn yoklar; 503'te kullanıcıya "Tekrar dene" gösterir.

**Kabul edilen sınırlamalar**
- Kullanıcı gün içinde saat dilimini değiştirirse, kota günü yeni saat dilimine göre hesaplanır (o gün en fazla bir kez fazladan veya eksik hak doğabilir).
- Başarısız denemeler kotadan düşmez; kötüye kullanıma karşı ayrı bir günlük deneme sınırı konup konmayacağı açık soru S-3'tür.

### 3.6 Kimlik doğrulama, oturum ve refresh token

**Belirteçler**
- Access token: JWT (HS256), 15 dk; claim'ler `sub` (userId), `sid` (oturum kimliği), `iat`, `exp`.
- Refresh token: 256 bit kriptografik rastgele değer (base64url), 30 gün; DB'de yalnızca SHA-256 özeti (`refresh_tokens.token_hash`). Yüksek entropi nedeniyle tuz gerekmez; arama özet üzerinden yapılır.
- Oturum (`auth_sessions`): bir girişte oluşur; rotasyonla üretilen tüm refresh token'lar bu oturuma bağlıdır (token ailesi).

**Her istekte oturum kontrolü:** JWT imzası ve süresi doğrulandıktan sonra `sid` ile oturumun iptal edilmemiş olduğu kontrol edilir (PK sorgusu). Böylece çıkış, token yeniden kullanımı ve hesap silme **access token'ı da hemen** geçersiz kılar; 15 dakika beklenmez.

**Senaryolar**

| Senaryo | Davranış |
|---|---|
| Giriş / kayıt | Yeni oturum + ilk refresh token |
| Normal yenileme | Tek transaction: token satırı `SELECT … FOR UPDATE` ile kilitlenir; geçerliyse `used_at` doldurulur, aynı oturumda yeni token üretilir, `last_refreshed_at` güncellenir → yeni çift döner |
| Süresi dolmuş token | `401 TOKEN_EXPIRED` (oturum iptal edilmez) |
| Bilinmeyen token | `401 UNAUTHORIZED` |
| İptal edilmiş oturumun token'ı | `401 SESSION_REVOKED` |
| Kullanılmış token, rotasyondan **≤ 30 sn** sonra (eşzamanlı istek veya yanıtı kaybolmuş yeniden deneme) | `401 TOKEN_ALREADY_ROTATED`; **oturum iptal edilmez**. İstemci elindeki daha yeni token'ı kullanır, yoksa giriş ekranına döner |
| Kullanılmış token, rotasyondan **> 30 sn** sonra | Çalınma şüphesi: **oturumun tamamı iptal edilir** (`TOKEN_REUSE`), `401 TOKEN_REUSED`, güvenlik logu (kullanıcı ve oturum kimliğiyle; token'sız) |
| Aynı token ile eşzamanlı iki yenileme | Satır kilidi istekleri sıraya koyar: ilki başarılı olur, ikincisi `TOKEN_ALREADY_ROTATED` alır; oturum açık kalır. Mobil istemci zaten tek seferde tek yenileme yapar (T1.4) |
| Çıkış | Oturum iptal edilir (`LOGOUT`); oturumun access ve refresh token'ları hemen geçersiz |
| Hesap silme | Kullanıcı ve oturumları silinir; tüm token'lar hemen geçersiz |
| Şifre değiştirme (MVP sonrası) | Kullanıcının mevcut oturum hariç tüm oturumları iptal edilir (`PASSWORD_CHANGE`). Şema bunu şimdiden destekler; ek değişiklik gerekmez |

**Log ve gizlilik:** İstek/yanıt gövdeleri loglanmaz. `Authorization` başlığı, `password`, `refreshToken`, `accessToken` alanları log maskeleme kuralıyla gizlenir. Testler, kayıt/giriş/yenileme sırasında yakalanan log çıktısında şifre ve token değerlerinin bulunmadığını doğrular.

**Hız sınırı:** Giriş ve kayıtta IP başına (bellek içi, uygulama örneği başına) sınır; MVP için yeterli, üretimde yeniden değerlendirilir.

### 3.7 Para ve zaman
- Para kuralları: database-design.md §2 (tek kaynak).
- Harcama tarihi `LocalDate`; zaman damgaları `Instant` (UTC). "Bugün", ay sınırları ve kota günü kullanıcının saat dilimi ve enjekte edilen `Clock` ile hesaplanır; testlerde sabit saat kullanılır. Doğrudan `LocalDate.now()` çağrısı ArchUnit ile yasaklanır.

## 4. Mobil uygulama

| Konu | Seçim |
|---|---|
| Çatı | Expo (managed workflow; T0.3'te güncel kararlı SDK seçilir), TypeScript `strict` |
| Navigasyon | Expo Router |
| Sunucu durumu | TanStack Query |
| İstemci durumu | Zustand |
| Formlar | React Hook Form + Zod |
| API tipleri | Backend OpenAPI şemasından `openapi-typescript` |
| Grafikler | `react-native-gifted-charts` + `react-native-svg` |
| Güvenli depo | `expo-secure-store` |
| i18n | `i18next` + `expo-localization` (yalnızca `tr`) |
| Test | Jest (`jest-expo`) + React Native Testing Library; ESLint + Prettier |

CI'da mobil doğrulama: lint, tip kontrolü, Jest ve `npx expo export` (JavaScript paketinin üretilebildiğini gösterir; yerel iOS/Android derlemesi değildir). Gerçek cihaz denemesi Expo Go ile ürün sahibinin isteğe bağlı kontrolüdür (development-plan.md M0 sonu). Mağaza derlemesi (EAS Build) MVP sonrasıdır.

## 5. Ortamlar

| Ortam | Nerede | Veritabanı | Rolü |
|---|---|---|---|
| `ci` | GitHub Actions (ubuntu runner) | `services: postgres:16` | **Doğruluk kaynağı.** Bir değişiklik ancak burada yeşilse doğrulanmış sayılır |
| `cloud-dev` | Claude Code Cloud oturumu | Ortam destekliyorsa `scripts/dev-db.sh` ile depo dışındaki ayrı bir dizinde PostgreSQL kümesi; desteklemiyorsa yok | Hızlı geri bildirim. Ortamın PostgreSQL sunduğu **varsayılmaz**: `scripts/check-env.sh` her oturumda kontrol eder |
| `local` (isteğe bağlı) | Docker olan bir makine | `docker compose` | Zorunlu değil |
| `prod` | MVP sonrası (D-11) | — | — |

`cloud-dev` ortamında veritabanı yoksa: DB gerektirmeyen testler (birim, mimari, mobil) yine çalıştırılır; DB testleri "çalıştırılamadı" olarak raporlanır ve doğrulama GitHub Actions'a bırakılır. Bu durum işi engellemez.

Ortam değişkenleri `.env.example`'da açıklamalıdır; gerçek değerler commit edilmez. CI'da `ANTHROPIC_API_KEY` **tanımlanmaz**; testler sahte sağlayıcı/WireMock ile çalışır.

## 6. Test stratejisi ve CI/CD

### 6.1 Test katmanları

| Katman | Kapsam | Veritabanı |
|---|---|---|
| Birim | Servisler, para/tarih/kota hesapları, `InsightPayload` izin listesi, e-posta normalizasyonu, yapılandırma doğrulaması | Yok |
| Mimari | ArchUnit: modül sınırları, para için float/double yasağı, `LocalDate.now()` yasağı | Yok |
| Veritabanı | Migration'lar, kısıtlar, cascade, kota sorguları, rapor SQL'leri | PostgreSQL 16 |
| API | MockMvc: doğrulama, hata formatı, yetki, iki kullanıcılı izolasyon, token senaryoları | PostgreSQL 16 |
| Eşzamanlılık | Kota ve token rotasyonu için paralel istek testleri (`ExecutorService` + `CountDownLatch`) | PostgreSQL 16 |
| AI adaptörü | WireMock ile sahte Anthropic: başarı, zaman aşımı, 429/5xx, geçersiz JSON | Yok |
| Sözleşme | OpenAPI şeması ↔ mobil üretilmiş tipler | — |
| Mobil | Bileşen, hook ve API istemcisi testleri (ağ sahte) | — |

DB testleri bağlantıyı `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`'den alır. Değişken yoksa test **başarısız olur** (sessizce atlanmaz). Testcontainers kullanılmaz (Docker gerektirir).

**Raporlama kuralı (NFR-11):** Her PR açıklaması "Çalıştırılan testler" ve "Çalıştırılmayan testler" bölümlerini ayrı ayrı içerir; her satırda komut, ortam (Claude Code Cloud / GitHub Actions) ve sonuç bulunur. CI sonucu görülmeden "CI yeşil" denmez.

### 6.2 İş akışları

| Dosya | Tetikleyici | İçerik |
|---|---|---|
| `ci.yml` | **Her** `pull_request` (`main`'e), `main`'e push, `workflow_dispatch` | Değişiklik tespiti + backend, mobil, sözleşme, workflow lint işleri + **`ci-gate`** |
| `security.yml` | **Her** `pull_request`, `main`'e push, haftalık zamanlama, `workflow_dispatch` | Gitleaks, Trivy (bağımlılık), npm audit, tarayıcı öz-testleri + **`security-gate`** |
| `codeql.yml` | `main`'e push, haftalık | Yalnızca kullanılabilir olduğunda çalışır (§6.4) |

**`ci.yml` işleri**

| İş | Çalışma koşulu | Adımlar |
|---|---|---|
| `changes` | Her zaman | `dorny/paths-filter` ile hangi alanların değiştiğini belirler. `push`/`workflow_dispatch`'te tüm çıktılar `true` kabul edilir |
| `workflow-lint` | `.github/**` değiştiyse | `actionlint` |
| `backend` | `backend` **veya** `contract` filtresi (sözleşme kontrolü `openapi.json`'a ihtiyaç duyar) | JDK 21 + Gradle önbelleği → PostgreSQL 16 servis konteyneri → `./gradlew build` (tüm testler + JaCoCo) → test raporları ve `openapi.json` artifact olarak yüklenir → **test edilmiş JAR'dan Docker imajı derlenir (`load: true`, push yok) → aynı işte bu yerel imaj Trivy ile taranır** |
| `mobile` | `mobile` filtresi | `.nvmrc`'deki Node + npm önbelleği → `npm ci` → lint → typecheck → Jest → `npx expo export` |
| `contract` | `contract` filtresi; `needs: [backend]` | `backend` işinin ürettiği `openapi.json` indirilir → mobilde tipler yeniden üretilir → `git diff --exit-code` |
| `ci-gate` | **Her zaman** (`if: always()`), `needs:` yukarıdakilerin hepsi | Herhangi bir iş `failure` veya `cancelled` ise başarısız; `success` veya `skipped` ise başarılı |

**Yol filtreleri** (yalnızca `backend/**` ve `mobile/**` değil):

| Filtre | Yollar |
|---|---|
| `backend` | `backend/**` (wrapper, `libs.versions.toml`, `gradle.lockfile`, Dockerfile dahil), `.github/workflows/ci.yml`, `scripts/ci/**`, `.trivyignore` |
| `mobile` | `mobile/**` (`package.json`, `package-lock.json` dahil), `.nvmrc`, `.github/workflows/ci.yml`, `scripts/ci/**` |
| `contract` | `backend` filtresinin tamamı + `mobile/src/api/**`, `mobile/package.json`, `mobile/package-lock.json`, `.nvmrc` (tip üretim aracı sürümü) |
| `workflows` | `.github/**` |

**Zorunlu kontroller ve path-filter sorunu:** Path filtresi workflow düzeyinde (`on.pull_request.paths`) **kullanılmaz**; çünkü tetiklenmeyen bir workflow'un kontrolü dal korumasında sonsuza dek "pending" kalır. Bunun yerine workflow her PR'da başlar, filtre iş düzeyinde uygulanır ve dal korumasında yalnızca her zaman sonuç üreten **`ci-gate`** ve **`security-gate`** zorunlu tutulur.

### 6.3 Güvenlik ve bağımlılık kontrolleri (`security.yml`)

| İş | Araç ve davranış | Başarısızlık eşiği |
|---|---|---|
| `gitleaks` | Gitleaks **CLI ikilisi** (sabit sürüm + SHA-256 doğrulaması). PR'da yalnızca PR commit aralığı, zamanlamada tüm geçmiş. `gitleaks-action` kullanılmaz (organizasyon hesaplarında lisans ister) | Herhangi bir bulgu |
| `trivy-deps` | `trivy fs` → `backend/gradle.lockfile`, `mobile/package-lock.json`; `--ignore-unfixed` | HIGH / CRITICAL |
| `npm-audit` | `npm audit --omit=dev --audit-level=high` (üretim bağımlılıkları); geliştirme bağımlılıkları yalnızca raporlanır | high ve üstü (üretim) |
| `scanner-self-test` | Tarayıcıların gerçekten çalıştığını kanıtlar (aşağıda) | Tarayıcı beklenen bulguyu bulamazsa |
| `security-gate` | `if: always()` | Yukarıdakilerden biri başarısızsa |

Docker imajı taraması imajın üretildiği `ci.yml` → `backend` işindedir (imaj başka işe taşınmaz, kayıt defterine gönderilmez).

**Tarayıcıların gerçekçi ve güvenli testi (`scanner-self-test`):** Depoya hiçbir gerçek veya sahte gizli anahtar ya da açıklı bağımlılık commit edilmez.
- Gitleaks: İş sırasında depo **dışındaki** geçici bir dizine, gizli anahtar biçimine uyan ama hiçbir hesaba ait olmayan bir değer **çalışma anında üretilip** yazılır (değer parçalardan birleştirilir, böylece betik dosyasının kendisi bir eşleşme içermez). `gitleaks dir` bu dizinde çalıştırılır; çıkış kodu sıfırdan farklı değilse iş başarısız olur.
- Trivy: Geçici dizinde, bilinen bir CVE'ye sahip eski bir paket sürümüne işaret eden minimal bir `package-lock.json` çalışma anında oluşturulur; `trivy fs` bu açığı bulmazsa iş başarısız olur.
- Kabul edilen bulgular `.trivyignore`'da CVE kimliği, gerekçe ve bitiş tarihiyle tutulur; Gitleaks istisnaları `.gitleaks.toml` allowlist'inde gerekçesiyle tutulur.

**Dependabot** (`dependabot.yml`): Gradle, npm, GitHub Actions ve Docker için haftalık güncelleme PR'ları. Dependabot PR'larında gizli değer gerekmez (CI hiç gizli değer kullanmaz).

**Sabit sürüm:** Tüm üçüncü taraf Actions tam commit SHA'sıyla referanslanır (yorumda sürüm etiketiyle); Dependabot bunları günceller.

### 6.4 Özel (private) depo kısıtları

| Özellik | Özel depoda durum | Bu projedeki yaklaşım |
|---|---|---|
| CodeQL / code scanning, SARIF yükleme | GitHub Advanced Security (Code Security) lisansı gerekir | `codeql.yml` yalnızca depo açıksa veya `vars.CODEQL_ENABLED == 'true'` ise çalışır; aksi halde iş **atlanır** (başarısız olmaz) ve zorunlu kontrol değildir. Trivy/Gitleaks sonuçları SARIF yerine iş çıktısı ve artifact olarak raporlanır |
| Dependency review action | Özel depoda GHAS gerekir | Kullanılmaz; Trivy + npm audit karşılar |
| Secret scanning / push protection | Özel depoda ücretli (Secret Protection) | Gitleaks karşılar |
| Dependabot alerts ve version updates | Ücretsiz | Kullanılır |
| Dal koruma kuralları / rulesets | Kişisel hesapta **özel** depo için GitHub Pro (veya Team) gerekir | Ücretsiz planda zorunlu kontrol uygulanamaz; kontroller yine çalışır ve PR'da görünür. Karar: açık soru S-4 |
| GitHub Actions dakikaları | Özel depolarda aylık ücretsiz kota sınırlı | İş düzeyinde filtre, önbellek, haftalık (günlük değil) zamanlama |

### 6.5 Dal koruma (plan uygunsa)
`main`: PR zorunlu, zorunlu kontroller **yalnızca** `ci-gate` ve `security-gate`, dal güncel olmalı. Ürün sahibi tarafından açılır (S-4).

## 7. Güvenlik özeti

- Şifreler BCrypt (cost 12); düz metin saklanmaz, loglanmaz.
- Oturum ailesi + rotasyonlu refresh token + yeniden kullanım tespiti; token'lar DB'de SHA-256 özeti (§3.6).
- Her istekte oturum geçerliliği kontrolü (çıkış/silme anında etkili).
- Veri izolasyonu: `user_id` yalnızca JWT'den; her sorguda filtre; bileşik FK; iki kullanıcılı testler.
- AI: yalnızca backend, yalnızca izin listesindeki toplu veri, anahtar yalnızca ortam değişkeninde; CI'da anahtar yok.
- CI: bağımlılık, imaj ve gizli anahtar taraması her PR'da.

## 8. Mimari karar kayıtları (ADR) — özet

| ADR | Karar |
|---|---|
| ADR-1 | Monorepo |
| ADR-2 | REST + OpenAPI, `/api/v1` |
| ADR-3 | Rapor hesaplaması backend'de SQL ile; `report` tabloları salt okur |
| ADR-4 | Para `NUMERIC(14,2)` / `BigDecimal` / JSON string |
| ADR-5 | AI sağlayıcısı arayüz arkasında; testlerde sahte sağlayıcı ve WireMock |
| ADR-6 | Kategoriler arşivlenir |
| ADR-7 | Modüler monolit; sınırlar ArchUnit ile |
| ADR-8 | Testler Docker'sız; doğruluk kaynağı GitHub Actions |
| ADR-9 | PostgreSQL 16 tüm ortamlarda |
| ADR-10 | AI kotası `ai_generation_requests` ile: kısa rezervasyon transaction'ı, TTL'li rezervasyon, harici çağrı transaction dışında |
| ADR-11 | Oturum ailesi (`auth_sessions`) + rotasyonlu refresh token + 30 sn yeniden kullanım toleransı |
| ADR-12 | Workflow düzeyinde path filtresi yok; iş düzeyinde filtre + `ci-gate`/`security-gate` |
| ADR-13 | Zamana bağlı kurallar veritabanı kısıtı değil, servis katmanı kuralıdır |
