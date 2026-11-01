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
| T0.2 | Backend iskeleti (`backend/`) |
| T0.3 | Mobil iskelet (`mobile/`) |
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
| Gerçek telefon, backend Claude Code Cloud'da | Bulut oturumu telefondan doğrudan erişilebilir değildir. Herkese açık bir tünel adresi veya dağıtılmış bir backend gerekir. Hangisinin kullanılacağı, cihazda backend ile deneme yapılacak ilk görevde (M1) kararlaştırılacak |

`EXPO_PUBLIC_` önekli değerler uygulama paketine gömülür ve **gizli değildir**; bu değişkene asla anahtar veya şifre yazılmaz. M0 sonundaki Expo Go denemesi backend'e bağlanmayı gerektirmez (yalnızca boş ekranlar).

### Backend ve mobil komutları

Backend (T0.2) ve mobil (T0.3) iskeletleriyle eklenecek; M0 kapanışında doğrulanmış komutlarla güncellenecek.

## Katkı kuralları

- Her görev `docs/development-plan.md`'deki bir T-x.y görevidir; ayrı branch ve `main`'e ayrı PR.
- `main`'e doğrudan commit yapılmaz.
- PR açıklamalarında "Çalıştırılan testler" ve "Çalıştırılmayan testler" ayrı bölümlerdir; çalıştırılmamış test başarılı olarak raporlanmaz.
