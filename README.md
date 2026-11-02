# AI Expense Tracker

iOS ve Android için kişisel harcama takip uygulaması. Kullanıcı gerçekleşmiş harcamalarını kaydeder, kategorilere ayırır, aylık rapor ve grafiklerle inceler ve yapay zekâdan harcama alışkanlıkları hakkında kısa yorumlar alır.

> **Durum:** M0 — proje iskeleti. Uygulama kodu henüz yok. Yol haritası: [`docs/development-plan.md`](docs/development-plan.md).

## Mimari özeti

| Katman | Teknoloji |
|---|---|
| Mobil | React Native + Expo + TypeScript (`mobile/`) |
| Backend | Java 21 + Spring Boot, modüler monolit, Gradle Kotlin DSL (`backend/`) |
| Veritabanı | PostgreSQL 16, Flyway migration'ları |
| AI | Anthropic Claude API — yalnızca backend'den, yalnızca toplu harcama verisiyle |
| CI | GitHub Actions (build, test, güvenlik ve bağımlılık taraması) |

```
Mobil uygulama ──HTTPS/JSON (/api/v1, JWT)──▶ Backend (auth · user · category · expense · report · insight)
                                                 │                                   └──▶ Anthropic API
                                                 ▼
                                           PostgreSQL 16
```

Ayrıntılar:
- [Gereksinimler](docs/requirements.md)
- [Mimari](docs/architecture.md)
- [Veritabanı tasarımı](docs/database-design.md)
- [API sözleşmesi](docs/api-contract.md)
- [Geliştirme planı](docs/development-plan.md)
- [AI geliştirme ajanı kuralları](CLAUDE.md)

## Depo yapısı

```
backend/      Spring Boot uygulaması
mobile/       Expo uygulaması
scripts/      Geliştirme ortamı betikleri
docs/         Tasarım belgeleri
.github/      GitHub Actions iş akışları ve Dependabot
```

M0 görevleri ([`docs/development-plan.md`](docs/development-plan.md) §3, bu sırayla):

| Görev | Kapsam |
|---|---|
| T0.1 | Depo yapısı, `scripts/check-env.sh`, `scripts/dev-db.sh` (bu README) |
| T0.2 | Backend iskeleti (`backend/`) — aşağıdaki "Backend" bölümü |
| T0.3 | Mobil iskelet (`mobile/`) — aşağıdaki "Mobil" bölümü |
| T0.4 | CI hattı: `.github/workflows/ci.yml`, `backend/Dockerfile`, isteğe bağlı `docker-compose.yml` |
| T0.5 | Güvenlik hattı: `security.yml`, `codeql.yml`, `dependabot.yml`, `.gitleaks.toml`, `.trivyignore` |
| T0.6 | Sözleşme kontrolü: `ci.yml` içindeki `contract` işi, `npm run api:generate` |

## Geliştirme ortamı

Birincil geliştirme ortamı **Claude Code Cloud**'dur; doğrulama ortamı (doğruluk kaynağı) **GitHub Actions**'tır. Yerel VS Code veya Docker kurulumu gerekmez.

### 1. Ortamı kontrol et

```bash
scripts/check-env.sh
```

Java 21, Node (`.nvmrc`), npm, git ve isteğe bağlı yerel PostgreSQL 16 için `OK / MISSING / WARN / INFO` raporu verir. Hiçbir şey kurmaz veya değiştirmez. Zorunlu araçlardan biri eksikse `1` ile çıkar.

### 2. Yerel veritabanını başlat (ortam destekliyorsa)

```bash
scripts/dev-db.sh
```

```bash
eval "$(scripts/dev-db.sh env)"
```

- PostgreSQL 16 sunucu araçlarını bulur (`PATH`, `PG_BIN` veya bilinen kurulum dizinleri).
- Docker, sistem servisi veya sistem yapılandırması kullanmadan, projeye ayrılmış bir kümeyi `127.0.0.1:5433` üzerinde başlatır.
- Root olarak çalışılıyorsa sunucuyu `postgres` kullanıcısıyla çalıştırır (PostgreSQL root ile çalışmaz).
- `aiexpense` rolünü ve `aiexpense` (geliştirme) ile `aiexpense_test` (test) veritabanlarını oluşturur.
- Şifre ilk çalıştırmada rastgele üretilir ve depo dışında, `credentials.env` dosyasında (izin `600`) saklanır.
- Tekrar çalıştırılabilir: var olan küme, rol ve veritabanlarına dokunmaz.

Diğer komutlar:

| Komut | İşlem |
|---|---|
| `scripts/dev-db.sh status` | Sunucu çalışıyor mu |
| `scripts/dev-db.sh env` | `DB_*` ve `TEST_DB_*` değişkenlerini yazdırır |
| `scripts/dev-db.sh stop` | Sunucuyu durdurur |
| `scripts/dev-db.sh destroy --yes` | Sunucuyu durdurur ve tüm verisini siler (aşağıdaki güvenlik kurallarıyla) |

Durum dizini güvenliği: Betik her komutta durum dizinini doğrular. `/`, sistem dizinleri (`/usr`, `/var`, `/tmp` …), ev dizini, depo kökü, depo içindeki veya depoyu içeren dizinler ve göreli yollar reddedilir. `destroy` yalnızca betiğin oluşturduğu `.aiexpense-dev-db` işaret dosyasını taşıyan ve içinde yalnızca betiğin oluşturduğu girdiler (`data`, `socket`, `postgres.log`, `credentials.env`) bulunan dizini siler.

Ayarlar: `DEV_DB_PORT` (varsayılan `5433`), `DEV_DB_STATE_DIR` (root için `/var/tmp/aiexpense-pg`, diğer kullanıcılar için `~/.local/share/aiexpense-pg`), `PG_BIN`.

**Ortam desteklemiyorsa** (PostgreSQL 16 araçları yoksa): `dev-db.sh` bunu açıkça söyleyip `1` ile çıkar. Veritabanı gerektirmeyen testler yine yerelde çalışır; veritabanı testleri GitHub Actions'taki PostgreSQL 16 servis konteynerinde çalışır. Bu durum geliştirmeyi engellemez.

> Claude Code Cloud oturumları geçicidir. Oturum yeniden başlarsa yerel veritabanı silinmiş olabilir; `scripts/dev-db.sh` yeniden çalıştırılır.

### 3. Ortam değişkenleri

Tüm değişkenler [`.env.example`](.env.example) dosyasında açıklanmıştır. Gerçek değerler (şifre, `JWT_SECRET`, `ANTHROPIC_API_KEY`) **asla commit edilmez**. `.env` dosyası git tarafından yok sayılır.

#### Mobil uygulamanın backend adresi (`EXPO_PUBLIC_API_BASE_URL`)

`.env.example`'daki `http://127.0.0.1:8080/api/v1` yalnızca uygulamanın **backend ile aynı makinede** çalıştığı durumlarda işe yarar. `127.0.0.1` her cihazda "cihazın kendisi" demektir.

| Uygulamanın çalıştığı yer | Kullanılacak adres |
|---|---|
| Backend ile aynı makinedeki iOS simülatörü veya web | `http://127.0.0.1:8080/api/v1` |
| Android emülatörü | `http://10.0.2.2:8080/api/v1` (emülatörden ana makineye özel adres) |
| Gerçek telefon (Expo Go), aynı yerel ağ | Backend'i çalıştıran bilgisayarın yerel ağ IP'si, ör. `http://192.168.1.20:8080/api/v1`. Backend yalnızca `127.0.0.1`'i dinliyorsa telefon erişemez; backend'in ağ arayüzünü dinlemesi ve güvenlik duvarının izin vermesi gerekir |
| Gerçek telefon, backend Claude Code Cloud'da | Bulut oturumu telefondan doğrudan erişilebilir değildir. Cihaz denemesi backend'in Mac'te çalıştırılmasıyla yapılır (aşağıda "Mobil", 3. adım); dağıtılmış backend MVP sonrasıdır |

`EXPO_PUBLIC_` önekli değerler uygulama paketine gömülür ve **gizli değildir**; bu değişkene asla anahtar veya şifre yazılmaz. Değişken verilmezse geliştirme sürümü paketi sunan bilgisayarın adresini kullanır (`src/config/apiBaseUrl.ts`).

### Backend (Java 21 + Spring Boot 4.1)

Gereksinimler: Java 21 ve bir PostgreSQL 16 veritabanı. Gradle kurmak gerekmez; depodaki Gradle Wrapper (`backend/gradlew`, Gradle 9.8.2) kullanılır.

**1. Ortam değişkenlerini ver.** Uygulama veritabanı ve JWT ayarları olmadan **açılmaz**; eksik değişkenleri adıyla söyleyerek durur. Kodda veya depoda varsayılan şifre yoktur.

```bash
eval "$(scripts/dev-db.sh env)"
```

```bash
export JWT_SECRET="$(openssl rand -base64 48)"
```

```bash
export SPRING_PROFILES_ACTIVE=dev
```

`dev-db.sh env` komutu `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` ve `TEST_DB_*` değişkenlerini verir. Diğer değişkenler (`AI_*`, `ANTHROPIC_API_KEY`) isteğe bağlıdır; açıklamaları [`.env.example`](.env.example) dosyasındadır. Geçersiz değerler (ör. `AI_DAILY_LIMIT=0`, 32 bayttan kısa `JWT_SECRET`) uygulamanın açılmasını engeller.

**2. Başlat.**

```bash
cd backend && ./gradlew bootRun
```

Uygulama `http://127.0.0.1:8080` üzerinde açılır. Loglar JSON biçimindedir.

**3. Health endpoint'ini kontrol et.**

```bash
curl -i http://127.0.0.1:8080/actuator/health
```

Beklenen: `HTTP 200` ve `prod`/varsayılan profilde tam olarak `{"status":"UP"}`. Veritabanına ulaşılamazsa `HTTP 503` ve `{"status":"DOWN"}`. Her yanıtta `X-Request-Id` başlığı bulunur.

**Profiller**

| Profil | Ne zaman | Swagger (`/v3/api-docs`, `/swagger-ui/index.html`) | Health ayrıntıları |
|---|---|---|---|
| (yok) | Varsayılan; güvenli ayarlar | Kapalı | Yok — yalnızca `{"status":"UP"}` |
| `dev` | Yerel geliştirme | Açık | Açık (veritabanı, disk …) |
| `prod` | Üretim | Kapalı | Yok |

Tüm profillerde Actuator'da yalnızca `health` açıktır; hata yanıtlarında stack trace veya iç hata mesajı bulunmaz.

**Testler**

```bash
cd backend && ./gradlew test
```

Veritabanı testleri `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD` değişkenlerini kullanır (`eval "$(scripts/dev-db.sh env)"`). Bu değişkenler yoksa testler **başarısız olur**, atlanmaz. Veritabanı olmayan bir ortamda yalnızca veritabanı gerektirmeyen testleri çalıştırmak için `-PnoDb` verilir; bu durumda veritabanı testleri "çalıştırılmadı" olarak raporlanmalıdır:

```bash
cd backend && ./gradlew test -PnoDb
```

Diğer Gradle görevleri:

| Komut | İşlem |
|---|---|
| `./gradlew build` | Derleme + tüm testler + JaCoCo raporu (`build/reports/jacoco/`) |
| `./gradlew bootJar` | Çalıştırılabilir JAR (`build/libs/`) |
| `./gradlew exportOpenApi` | OpenAPI belgesini `build/openapi.json`'a yazar (veritabanı gerekir) |
| `./gradlew resolveAndLockAll --write-locks` | Bağımlılık değiştikten sonra `gradle.lockfile`'ı yeniler |

**Docker imajı** (Docker olan bir makinede; CI bunu her backend değişikliğinde yapar):

```bash
cd backend && ./gradlew build
```

```bash
cd backend && docker build --build-arg JAR_FILE=build/libs/ai-expense-tracker-backend-0.0.1-SNAPSHOT.jar -t aiexpense-backend:local .
```

İmaj yalnızca test edilmiş JAR'ı içerir (Eclipse Temurin 21 JRE, Ubuntu 24.04), root olmayan kullanıcıyla (`10001`) çalışır ve `8080` portunu açar. Çalıştırırken `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` ve `JWT_SECRET` verilmelidir.

Veritabanı şeması yalnızca Flyway migration'larıyla (`backend/src/main/resources/db/migration/`) değişir. İlk migration'lar T1.1'de eklenecek.

**Sorun giderme (Claude Code Cloud):** Ağ politikası bazı oturumlarda Maven Central'a hız sınırı (`429`) uygulayabilir veya `downloads.gradle.org`'u engelleyebilir. Bu ortama özel çözümler (ör. `~/.gradle/init.d/` altındaki bir ayna betiği) depoya eklenmez; GitHub Actions standart depoları kullanır.

### CI (GitHub Actions)

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) her pull request'te ve `main`'e her push'ta çalışır. İşler yalnızca ilgili alan değiştiğinde çalışır; sonuç tek bir **`ci-gate`** kontrolünde toplanır.

| İş | Ne zaman | Ne yapar |
|---|---|---|
| `changes` | Her zaman | Hangi alanların değiştiğini belirler (push ve elle çalıştırmada her şey) |
| `workflow-lint` | `.github/**` değiştiyse | `actionlint` (+ shellcheck) |
| `backend` | `backend/**`, `ci.yml`, `scripts/ci/**`, `.trivyignore` (ve `contract` değişikliklerinde) | JDK 21 + PostgreSQL 16 servis konteyneri → `./gradlew build` (veritabanı ve Flyway testleri dahil) → test özeti ve raporlar → test edilmiş JAR'dan Docker imajı → imaj duman testi → Trivy HIGH/CRITICAL taraması |
| `mobile` | `mobile/**`, `.nvmrc`, `ci.yml`, `scripts/ci/**` | Node 22 → `npm ci` → lint → format → typecheck → test → `expo export` |
| `contract` | `backend` filtresi + `mobile/src/api/**`, `mobile/package.json`, `mobile/package-lock.json`, `.nvmrc` | `backend` işinin ürettiği `openapi.json` ile `npm run api:generate` → `mobile/src/api` Git'tekiyle aynı değilse başarısız |
| `ci-gate` | Her zaman | Yukarıdaki işlerden biri başarısız veya iptal olduysa başarısız; çalışan işler başarılıysa ve diğerleri atlandıysa başarılı |

Dal korumasında zorunlu tutulacak kontroller `ci-gate` ve `security-gate`'tir. Test sayıları ve sonuçlar her çalıştırmanın **Summary** sayfasında, ayrıntılı raporlar artifact olarak (`backend-test-reports`, `openapi`) bulunur.

### Güvenlik (GitHub Actions)

[`.github/workflows/security.yml`](.github/workflows/security.yml) her pull request'te, `main`'e her push'ta, haftalık (pazartesi) ve elle çalışır; yol filtresi yoktur. Sonuç **`security-gate`** kontrolünde toplanır.

| İş | Ne yapar | Başarısız olur |
|---|---|---|
| `gitleaks` | Gizli anahtar taraması (PR'da PR'ın commit'leri, diğerlerinde tüm geçmiş), [`.gitleaks.toml`](.gitleaks.toml) | Herhangi bir bulguda |
| `trivy-deps` | `backend/gradle.lockfile` ve `mobile/package-lock.json` açık taraması | Düzeltmesi olan HIGH/CRITICAL'da |
| `npm-audit` | `npm audit --omit=dev --audit-level=high`; geliştirme bağımlılıkları yalnızca raporlanır | Üretim bağımlılığında high ve üstünde |
| `scanner-self-test` | Çalışma anında üretilen sahte anahtarı ve açıklı kilit dosyasını tarayıcıların bulduğunu kanıtlar | Tarayıcı bulamazsa |
| `security-gate` | Yukarıdakilerin sonucu | Biri başarılı değilse |

Docker imajı taraması `ci.yml` içindedir. [`codeql.yml`](.github/workflows/codeql.yml) özel depoda GitHub Advanced Security olmadan **atlanır**; depo açık olursa veya `CODEQL_ENABLED=true` depo değişkeni verilirse çalışır. [`.github/dependabot.yml`](.github/dependabot.yml) Gradle, npm, GitHub Actions ve Docker için haftalık güncelleme PR'ları açar.

**İstisna kuralı:** Bir güvenlik bulgusu yalnızca gerekçesi, onaylayanı ve (Trivy için) `exp:` bitiş tarihiyle [`.trivyignore`](.trivyignore) veya [`.gitleaks.toml`](.gitleaks.toml) içine yazılabilir. Gerçek bir gizli anahtar istisnaya alınmaz; geçmişten temizlenir ve değiştirilir.

### Mobil (React Native + Expo SDK 58)

Gereksinimler: Node 22 (`.nvmrc`). Uygulama iOS ve Android içindir; web hedefi yoktur.

**1. Bağımlılıkları kur** (kilit dosyasındaki kesin sürümlerle):

```bash
cd mobile && npm ci
```

**2. Kontroller** (CI'da da bunlar çalışacak):

| Komut | İşlem |
|---|---|
| `npm run lint` | ESLint (`eslint-config-expo`) + ekranda sabit metin yasağı + `any` yasağı; uyarı da hata sayılır |
| `npm run format:check` | Prettier biçim kontrolü (`npm run format` düzeltir) |
| `npm run typecheck` | TypeScript `strict` (`tsc --noEmit`); çeviri anahtarları da tip denetimlidir |
| `npm test` | Jest (`jest-expo`) + React Native Testing Library |
| `npm run export:check` | `expo export` ile iOS ve Android JavaScript paketlerini `dist/`'e üretir (yerel derleme değildir) |
| `npm run api:generate` | `src/api/schema.ts` API tiplerini backend'in OpenAPI belgesinden (`../backend/build/openapi.json`) yeniden üretir. Önce `cd backend && ./gradlew exportOpenApi` (veritabanı gerekir) |

**3. Uygulamayı backend ile çalıştır (MacBook + iPhone, Expo Go).** Telefon ve Mac **aynı Wi-Fi ağında** olmalıdır.

1. Mac'te PostgreSQL 16, Java 21 ve Node 22 kurulu olsun (ör. `brew install postgresql@16 openjdk@21 node@22`).
2. Backend'i yukarıdaki "Backend" adımlarıyla başlat (`scripts/dev-db.sh`, ortam değişkenleri, `./gradlew bootRun`). Backend tüm ağ arayüzlerinde `8080` portunu dinler; macOS güvenlik duvarı Java için gelen bağlantı sorarsa izin ver.
3. Yeni bir terminalde:

   ```bash
   cd mobile && npm ci && npx expo start
   ```

4. iPhone'a App Store'dan **Expo Go** kur, iPhone Kamera uygulamasıyla terminaldeki QR kodu okut ve "Expo Go'da aç"a dokun.

`EXPO_PUBLIC_API_BASE_URL` verilmezse uygulama, paketi sunan bilgisayarın adresini kullanır (Metro `192.168.1.20:8081` ise backend `http://192.168.1.20:8080/api/v1`). Bu yüzden aynı Wi-Fi'de ek ayar gerekmez. Farklı bir backend için başlatmadan önce `export EXPO_PUBLIC_API_BASE_URL=https://…/api/v1` verilir. Simülatörde de aynı komut çalışır (`i` iOS, `a` Android).

Sorun giderme: "Sunucuya ulaşılamadı" mesajında Mac'in IP'sini (`ipconfig getifaddr en0`) ve `curl http://<IP>:8080/actuator/health` yanıtını kontrol et. Misafir veya kurumsal ağlar cihazlar arası trafiği engelleyebilir; o durumda `npx expo start --tunnel` yalnızca paketi taşır, backend için yine erişilebilir bir adres gerekir.

**Yapı**

```
mobile/
  app/                 Expo Router ekranları (yalnızca route ve layout dosyaları)
    _layout.tsx        kök layout: i18n, tema, TanStack Query, oturuma göre korunan rotalar
    login.tsx, register.tsx   giriş ve kayıt (oturum yokken)
    (tabs)/            4 sekme: index (Ana sayfa), expenses, report, settings
    expense/           new.tsx (ekle) ve [id].tsx (düzenle/sil), modal
  src/
    components/        ortak bileşenler
    features/          auth (oturum, token yenileme), expenses (form, para, liste), categories
    config/            backend adresi (EXPO_PUBLIC_API_BASE_URL)
    i18n/              i18next kurulumu ve locales/tr.json
    theme/             renk paletleri ve useAppTheme
    api/               client.ts (fetch + 401'de tek yenileme), schema.ts: OpenAPI'den üretilen tipler (elle düzenlenmez)
  __tests__/           testler (app/ içine test konmaz); test-support/ sahte backend yardımcıları
```

**Kurallar:** Ekranlarda ve bileşenlerde kullanıcıya görünen her metin `src/i18n/locales/tr.json`'dan `t('…')` ile gelir; sabit metin lint hatasıdır. Paket eklerken SDK uyumlu sürüm için `npx expo install <paket>` kullanılır.

**Sorun giderme (Claude Code Cloud):** Bu ortamda `api.expo.dev` engelli olabilir; `npx expo install` "Forbidden" verirse SDK'nın kendi uyumluluk tablosunu kullanan çevrimdışı mod çalışır: `EXPO_OFFLINE=1 npx expo install <paket>`.

## Katkı kuralları

- Her görev `docs/development-plan.md`'deki bir T-x.y görevidir; ayrı branch ve `main`'e ayrı PR.
- `main`'e doğrudan commit yapılmaz.
- PR açıklamalarında "Çalıştırılan testler" ve "Çalıştırılmayan testler" ayrı bölümlerdir; çalıştırılmamış test başarılı olarak raporlanmaz.
