# AI Expense Tracker — Geliştirme Planı

> Durum: **Revizyon 3 — ürün sahibi onayı bekleniyor (PR #1)**

## 1. Çalışma şekli

- Geliştirme ortamı **Claude Code Cloud**, doğrulama ortamı (doğruluk kaynağı) **GitHub Actions**'tır (D-14). Yerel VS Code veya Docker kurulumu gerekmez.
- Claude Code Cloud ortamının PostgreSQL veya servis başlatma yetkisi sunduğu **varsayılmaz**; `scripts/check-env.sh` ile kontrol edilir. Ortam kısıtı nedeniyle yerelde çalıştırılamayan testler, GitHub Actions doğrulamasını engellemez.
- Her görev (T-x.y) ayrı branch (`feat/T0.2-backend-skeleton` gibi) ve `main`'e ayrı PR'dır. PR'lar **küçük, tek amaçlı ve incelenebilir** tutulur; satır sayısı katı bir sınır değildir (üretilmiş dosyalar, kilit dosyaları ve wrapper bu değerlendirmeye girmez).
- Görevler bağımlılık sırasıyla yapılır. Her kilometre taşı sonunda ürün sahibine özet sunulur; onay alınmadan sonraki kilometre taşına geçilmez.
- Belgelerde yanıtı olmayan bir ürün/mimari kararı çıkarsa iş durdurulur ve sorulur.

### Tamamlanma Tanımı (tüm görevler için)
1. Kabul kriterleri karşılandı ve her biri bir test, bir CI çalıştırması veya PR'da belirtilen elle doğrulama adımıyla kanıtlandı.
2. Gerekli testler yazıldı: API değiştiyse API testleri, şema değiştiyse veritabanı testleri, veri erişimi varsa iki kullanıcılı izolasyon testi.
3. GitHub Actions'ta `ci-gate` ve `security-gate` yeşil (M0'da bu iş akışları oluşana kadar: o göreve ait komutların Claude Code Cloud'daki çıktısı).
4. API değiştiyse `docs/api-contract.md`, şema değiştiyse `docs/database-design.md` aynı PR'da güncel; belgeler tutarlı.
5. PR açıklamasında ayrı bölümler: **Çalıştırılan testler** (komut, ortam, sonuç) ve **Çalıştırılmayan testler** (neden, nerede doğrulanacak). Çalıştırılmamış test başarılı olarak raporlanmaz (NFR-11).

## 2. Kilometre taşları

| KT | Ad | Çıktı |
|---|---|---|
| M0 | Proje iskeleti ve CI | Boş backend + boş mobil uygulama + GitHub Actions'ta build/test/güvenlik hatları |
| M1 | Kimlik ve kategoriler | Kayıt/giriş/yenileme/çıkış, oturum yönetimi, hazır kategoriler, kategori yönetimi |
| M2 | Harcamalar | Harcama CRUD uçtan uca |
| M3 | Rapor ve grafikler | Aylık rapor, trend, 3 grafik |
| M4 | AI yorumları | İzin listeli yük, kota ve üretim istekleri, Anthropic adaptörü, mobil bölüm |
| M5 | Sağlamlaştırma | Hesap silme, hata ve ağ durumları, erişilebilirlik, MVP kapanışı |

## 3. Görevler

### M0 — Proje iskeleti ve CI

Bağımlılık sırası: **T0.1 → T0.2 → T0.3 → T0.4 → T0.5 → T0.6.** CI iş akışları (T0.4–T0.6), çalıştıracakları backend ve mobil iskeletler (T0.2, T0.3) hazır olduktan sonra eklenir; böylece her iş akışı ilk PR'ında gerçek bir derlemeyi doğrular.

**T0.1 Depo yapısı ve ortam kontrolü**
- Klasör iskeleti, `.gitignore`, `.editorconfig`, `.nvmrc`, `.env.example` (tüm değişkenler açıklamalı, gerçek değer yok), README'ye kurulum ve ortam notları.
- `scripts/check-env.sh`: Java 21, Node (`.nvmrc` sürümü), `git`, PostgreSQL istemcisi (`psql`), sunucu araçları (`initdb`, `pg_ctl`) ve bunların ana sürümü, yazılabilir dizin ve boş port kontrolü. Her öğe için `OK / EKSİK / UYARI` raporlar; hiçbir şey kurmaz.
- `scripts/dev-db.sh`: Sunucu araçları varsa **root veya sistem servisi gerektirmeden** kullanıcı dizininde (`.local/pgdata`, git dışında) PostgreSQL kümesi oluşturur, ayrı bir portta başlatır, `aiexpense` ve `aiexpense_test` veritabanlarını oluşturur ve `TEST_DB_*` değişkenlerini yazdırır. Tekrar çalıştırılabilir. Araçlar yoksa veya sürüm 16 değilse açık bir mesajla sıfırdan farklı kodla çıkar.
- AC:
  - `check-env.sh` bu Claude Code Cloud oturumunda çalıştırılır ve çıktısı PR açıklamasına eklenir.
  - Ortam destekliyorsa `dev-db.sh` çalıştırılıp `psql` ile test veritabanına bağlanıldığı gösterilir; desteklemiyorsa bu açıkça raporlanır ve DB testleri GitHub Actions'a bırakılır (görev yine tamamlanabilir).
  - `dev-db.sh` iki kez üst üste çalıştırıldığında hata vermez.
  - Depoda gizli değer yoktur (T0.5 sonrası Gitleaks ile de doğrulanır).

**T0.2 Backend iskeleti (modüler monolit)**
- Gradle Kotlin DSL + wrapper + version catalog + dependency locking (`gradle.lockfile` commit edilir); Java 21 toolchain; Spring Boot güncel kararlı sürüm.
- Modül paketleri; `common`: problem+json hata işleyici, `requestId` filtresi, JSON loglama ve log maskeleme, `Clock` bean'i; actuator; springdoc; yapılandırma doğrulaması (architecture.md §3.4).
- Test altyapısı: `TEST_DB_*` ile gerçek PostgreSQL'e bağlanan entegrasyon test tabanı (değişken yoksa başarısız); ArchUnit kuralları (modül sınırları, para için float/double yasağı, `LocalDate.now()` yasağı); JaCoCo; OpenAPI şemasını `build/openapi.json`'a yazan test/Gradle görevi.
- AC:
  - DB'siz testler (birim + ArchUnit + yapılandırma doğrulaması) Claude Code Cloud'da çalıştırılır ve sonuçları raporlanır.
  - DB'li testler (health `UP`, bilinmeyen yol → problem+json 404, `X-Request-Id` başlığı) ortamda PostgreSQL varsa çalıştırılır; yoksa "çalıştırılmadı" olarak raporlanır ve T0.4'te GitHub Actions'ta doğrulanır.
  - `TEST_DB_URL` olmadan DB testinin **başarısız** olduğu gösterilir (atlanmadığı).
  - `AI_DAILY_LIMIT=0` ile uygulama bağlamının açılmadığını doğrulayan test.
  - Yasak bir modül bağımlılığı geçici olarak eklendiğinde ArchUnit testinin kırıldığı gösterilir (değişiklik commit edilmez; çıktı PR'da).

**T0.3 Mobil iskelet**
- Expo güncel kararlı SDK + TypeScript strict + Expo Router; 4 sekme (Ana sayfa, Harcamalar, Rapor, Ayarlar) boş ekranlarla; açık/koyu tema; i18n (`tr`); ESLint + Prettier + Jest (`jest-expo`) + React Native Testing Library; `lint`, `typecheck`, `test` betikleri; `package-lock.json` commit edilir.
- AC:
  - `npm ci`, `npm run lint`, `npm run typecheck`, `npm test`, `npx expo export` Claude Code Cloud'da çalıştırılır ve sonuçları raporlanır.
  - Sekme geçişini ve ekran başlıklarının çeviri dosyasından geldiğini doğrulayan bileşen testi.
  - Ekran bileşenlerinde sabit kullanıcı metni olmadığını denetleyen lint kuralı.
  - iOS simülatörü/Android emülatörü bu ortamda yoktur; cihazda deneme M0 sonunda ürün sahibinin isteğe bağlı kontrolüdür.

**T0.4 CI hattı (`ci.yml`)**
- `changes`, `workflow-lint`, `backend` (PostgreSQL 16 servis konteyneri, test edilmiş JAR'dan Docker imajı ve aynı işte Trivy imaj taraması), `mobile`, `ci-gate` işleri (architecture.md §6.2); `backend/Dockerfile`; isteğe bağlı `docker-compose.yml`.
- AC:
  - İş akışı bu PR'da GitHub Actions'ta çalışır ve yeşildir (çalıştırma bağlantısı PR'da).
  - T0.2'de yerelde çalıştırılamayan DB testleri burada çalışır ve sonuçları (test sayısı) PR'da raporlanır.
  - Yalnızca `mobile/` değiştiren bir doğrulama commit'inde `backend` işi `skipped`, `ci-gate` `success` olur; `backend/gradle/libs.versions.toml` değiştiren commit'te `backend` işi çalışır (her iki çalıştırmanın bağlantısı PR'da; doğrulama commit'leri PR birleşmeden önce geri alınır).
  - Bilerek kırılan bir testle `ci-gate`'in `failure` olduğu gösterilir (aynı şekilde geri alınır).

**T0.5 Güvenlik hattı (`security.yml`, `dependabot.yml`, `codeql.yml`, `.gitleaks.toml`, `.trivyignore`)**
- architecture.md §6.3–6.4'teki işler; `scanner-self-test` dahil.
- AC:
  - `security-gate` bu PR'da yeşil.
  - `scanner-self-test`, Gitleaks'in çalışma anında üretilen gizli anahtar biçimini ve Trivy'nin çalışma anında üretilen açıklı bağımlılığı **bulduğunu** kanıtlar; depoya hiçbir gizli anahtar veya açıklı bağımlılık commit edilmez.
  - `codeql.yml` özel depoda `skipped` olur, başarısız olmaz.
  - Dependabot yapılandırması GitHub tarafından hatasız okunur (Insights → Dependency graph → Dependabot ekranında hata yok).

**T0.6 Sözleşme kontrolü (`contract` işi)**
- `backend` işinin ürettiği `openapi.json` artifact'ından `openapi-typescript` ile `mobile/src/api/schema.ts` üretimi; fark varsa başarısız. Geliştirici için aynı işi yapan `npm run api:generate` betiği (yerelde backend şeması `backend/build/openapi.json`'dan okunur).
- AC: Üretilmiş tipler güncel olduğunda iş yeşil; bir doğrulama commit'inde `schema.ts` elle değiştirildiğinde kırmızı (bağlantılar PR'da, commit geri alınır).

**M0 kapanışı**
- `README.md` ve `CLAUDE.md` "Komutlar" bölümü gerçekten çalıştırılmış komutlarla güncellenir.
- Ürün sahibine dal koruma önerisi (S-4'e göre).
- **Expo Go ile gerçek cihaz doğrulaması (ürün sahibi, isteğe bağlı):**
  1. Telefona App Store / Google Play'den **Expo Go** kurulur.
  2. Claude, uygulamayı telefona ulaştırmak için iki yoldan birini dener ve hangisinin çalıştığını raporlar:
     a. Claude Code Cloud oturumundan `npx expo start --tunnel` ile QR kodu/bağlantı üretmek (oturumun ağ politikası tünele izin vermeyebilir);
     b. Ürün sahibinin Expo hesabıyla `eas update` yayımlayıp Expo Go'da açılabilir bağlantı üretmek (Expo hesabı ve `EXPO_TOKEN` gerekir — S-5).
  3. Ürün sahibi QR kodu Expo Go ile okutur; uygulama açılır.
  4. Kontrol listesi: 4 sekme görünüyor ve geçiş çalışıyor; tüm metinler Türkçe; telefon koyu moda alınınca tema değişiyor; uygulama kapatıp açınca çökmeden açılıyor.
  5. Sonuç (geçti / kaldı / yapılmadı) M0 özetine yazılır. Bu adım yapılmasa da M0 otomatik testlere göre tamamlanabilir; ancak "cihazda doğrulanmadı" olarak belirtilir.

### M1 — Kimlik ve kategoriler

**T1.1 Migration'lar V1–V3** (`users`, `auth_sessions`, `refresh_tokens`, `categories`) — AC: database-design.md §10'daki ilgili testler; e-posta benzersizliği.
**T1.2 Backend: kayıt / giriş / yenileme / çıkış** — AC:
- FR-1 AC'leri API testleriyle.
- architecture.md §3.6 tablosundaki **her satır** için bir test: normal rotasyon; süresi dolmuş; bilinmeyen; iptal edilmiş oturum; 30 sn içinde yeniden kullanım (oturum açık kalır); 30 sn sonra yeniden kullanım (oturum iptal); aynı token ile eşzamanlı iki istek (biri başarılı, diğeri `TOKEN_ALREADY_ROTATED`, oturum açık); çıkış sonrası access token'ın hemen `401` alması. Zaman, sabit/ilerletilebilir `Clock` ile kontrol edilir.
- DB'de yalnızca BCrypt ve SHA-256 özetleri bulunduğu; yakalanan log çıktısında şifre ve token değerlerinin bulunmadığı testle doğrulanır.
- E-posta normalizasyonu: `Ali@Example.com ` ile kayıt, `ali@example.com` ile giriş; Türkçe `I` testi.
- Giriş hız sınırı testi.
**T1.3 Backend: hazır kategoriler + kategori uç noktaları** — AC: 11 hazır kategori; FR-3 AC'leri; iki kullanıcılı izolasyon testi.
**T1.4 Mobil: API istemcisi ve oturum** — AC: 401'de tek yenileme ve isteğin tekrarı; eşzamanlı 401'lerde tek yenileme isteği; `TOKEN_ALREADY_ROTATED` alındığında elde daha yeni token varsa onunla devam; `TOKEN_REUSED` / `SESSION_REVOKED` → giriş ekranı (birim testleri).
**T1.5 Mobil: Karşılama, Giriş, Kayıt, Çıkış** — AC: FR-1 form kuralları; sunucu hataları Türkçe; kayıtta cihaz saat dilimi gönderilir.
**T1.6 Mobil: Kategori yönetimi** — AC: Ekle/düzenle/arşivle/arşivden çıkar; "Diğer" için arşivle pasif.

### M2 — Harcamalar

**T2.1 Migration V4 + harcama uç noktaları** — AC:
- FR-2 AC'leri API testleriyle; bileşik FK ve iki kullanıcılı izolasyon testleri.
- İleri tarih kuralı **servis katmanında**, sabit `Clock` ile: `Europe/Istanbul` kullanıcısı için UTC 20:59 ve 21:00 anlarında "bugün"ün değiştiği; `Pacific/Kiritimati` (UTC+14) ve `Pacific/Pago_Pago` (UTC−11) kullanıcıları için aynı UTC anında farklı "bugün" kabul edildiği; ay sonu ve yıl sonu gece yarısı geçişleri.
- Para: `"0"`, `"-1"`, `"1.234"`, `"1e3"`, `"1,5"`, `"1000000000000.00"` reddedilir; `"5"` → `"5.00"`, `"999999999999.99"` kabul edilir.
**T2.2 Mobil: Harcama ekle/düzenle** — AC: En fazla 3 dokunuş; tarih seçici bugünden sonrasını göstermez; `1.250,50` girişi API'ye `"1250.50"` olarak gider.
**T2.3 Mobil: Harcama listesi** — AC: Ay seçici, gruplu liste, sonsuz kaydırma, filtre, arama, onaylı silme; boş/yükleniyor/hata durumları.
**T2.4 Mobil: Ana sayfa** — AC: Toplam ve değişim rapor API'siyle tutarlı; aşağı çekerek yenileme.

### M3 — Rapor ve grafikler

**T3.1 Backend: aylık rapor ve trend** — AC: FR-4 AC'leri; toplamlar tek tek toplamla birebir; önceki ay boşken `null`; ay sınırı/saat dilimi uç durumları; izolasyon testi.
**T3.2 Mobil: Rapor ekranı — özet kartları ve kategori tablosu** — AC: Ay değiştirme; boş ay durumu.
**T3.3 Mobil: Grafikler** — AC: FR-5 AC'leri; açık/koyu tema.

### M4 — AI yorumları

**T4.1 Migration V5 + `InsightPayload` + parmak izi + sahte sağlayıcı** — AC: İzin listesi testi (açıklama/not/ad/e-posta/kullanıcı kimliği içeren test verisiyle yükte bunların bulunmadığı); kanonik JSON ile aynı veri → aynı parmak izi, farklı veri → farklı parmak izi; S-1 ve S-2 kararları uygulanmış.
**T4.2 Anthropic adaptörü + istem şablonu v1** — AC (WireMock ile; gerçek API çağrılmaz): başarı; zaman aşımı → 1 yeniden deneme → `FAILED/PROVIDER_TIMEOUT`; HTTP 429/5xx → 1 yeniden deneme; 4xx (429 hariç) → yeniden denenmez; şemaya uymayan yanıt → `FAILED/INVALID_RESPONSE`; `ANTHROPIC_API_KEY` boş → `FAILED/NOT_CONFIGURED`; istek gövdesinde yalnızca izin verilen alanlar; anahtar loglarda yok; model adı `AI_MODEL`'den.
**T4.3 Insight uç noktaları, rezervasyon ve kota** — AC (gerçek PostgreSQL, sabit `Clock`):
- Aynı veri ikinci kez → 200, AI çağrılmaz, kota değişmez.
- 5 başarılı üretimden sonra 6.'sı → 429 + kullanıcının yerel gece yarısına göre `Retry-After`.
- Başarısız çağrı (`FAILED`) kotayı düşürmez: 5 başarısız + 5 başarılı mümkün.
- **Eşzamanlılık:** Aynı kullanıcı, farklı verilerle 10 paralel istek, kalan hak 2 → en fazla 2 rezervasyon, diğerleri 429.
- **Tekilleştirme:** Aynı kullanıcı + ay + veriyle 5 paralel istek → sağlayıcı **tam 1 kez** çağrılır; diğerleri 409 (veya rezervasyon bittiyse 200).
- **Harici çağrı sırasında kilit yok:** Sahte sağlayıcı bekletilirken aynı kullanıcı için başka bir uç noktanın (ör. `GET /me`) gecikmeden yanıt verdiği ve `pg_locks`'ta bu kullanıcıya ait kilit kalmadığı doğrulanır.
- **Yarım kalan işlem:** `RESERVED` satırı TTL'i geçtikten sonra kotaya sayılmaz ve aynı veri için yeni üretim yapılabilir (uygulama yeniden başlaması simülasyonu); zamanlanmış görev durumu `EXPIRED` yapar.
- **Geç sonuç:** Sağlayıcı TTL'den sonra dönerse sonuç kaydedilmez, `RESULT_AFTER_EXPIRY`.
- **Gün sınırı:** `Europe/Istanbul` kullanıcısının kotası yerel gece yarısında sıfırlanır (UTC 21:00), UTC gece yarısında sıfırlanmaz.
- `generationId` yanıt gövdesinde, `X-AI-Generation-Id` başlığında ve loglarda bulunur.
**T4.4 Mobil: AI yorumu bölümü** — AC: Yükleniyor/hata/sınır/yetersiz veri durumları; 409 ve ağ hatasında 3 sn aralıkla en fazla 60 sn yoklama; `stale` ise "Güncelle"; kalan hak; feragat notu.

İsteğe bağlı manuel kontrol: Ürün sahibi kendi API anahtarıyla gerçek bir yorum üretip kaliteyi değerlendirir; otomatik testlerin yerine geçmez, yapılmadıysa "yapılmadı" olarak raporlanır.

### M5 — Sağlamlaştırma

**T5.1 Hesap silme (backend + mobil)** — AC: FR-7 AC'leri; silme sonrası tüm tablolarda kullanıcıya ait satır yok; silinmiş kullanıcının access token'ı hemen `401`.
**T5.2 Hata ve ağ durumları** — AC: Ağ yokken anlaşılır uyarı; hata sınırı bileşeni testi.
**T5.3 Erişilebilirlik** — AC: NFR-8 kontrol listesi; etkileşimli öğelerde erişilebilirlik etiketi (testle).
**T5.4 MVP kapanışı** — Belgelerin son hâli, bilinen sorunlar, sonraki aşama hazırlık notları.

## 4. Sonraki aşama (MVP sonrası, D-11)

Bulut dağıtımı, EAS Build, App Store / Google Play yayını, bütçeler (S-1'e göre), ileri tarihli ödemeler, şifre değiştirme/sıfırlama.

**Üretime çıkmadan önce zorunlu görevler:**
- **P-1** Üretim gizli değer yönetimi (`JWT_SECRET`, `ANTHROPIC_API_KEY`, DB) ve anahtar rotasyon prosedürü.
- **P-2** Veri saklama politikası: yedek saklama süresi, silinen hesap verisinin yedeklerden kalkma süresi, log saklama süresi, KVKK aydınlatma metni ve gizlilik politikası. Yazılı ve ürün sahibince onaylanmış olmadan üretime çıkılmaz.
- **P-3** Yedekten geri dönüş tatbikatı.
- **P-4** Çok örnekli çalışmada hız sınırının paylaşılan bir depoya taşınması (gerekirse).

## 5. Riskler

| Risk | Etki | Önlem |
|---|---|---|
| AI'a kişisel veri sızması | Gizlilik | İzin listeli yük + testler (T4.1, T4.2); S-2 |
| AI kotasının eşzamanlı isteklerle aşılması | Maliyet | Kullanıcı satırı kilidiyle kısa rezervasyon + kısmi benzersiz indeks + paralel testler (T4.3) |
| Yarım kalan rezervasyonların kotayı kilitlemesi | Kullanıcı deneyimi | TTL'li sayım (temizlik görevine bağımlı değil) + zamanlanmış temizlik |
| Refresh token çalınması | Hesap güvenliği | Rotasyon + yeniden kullanım tespiti + her istekte oturum kontrolü |
| Saat dilimi kaynaklı yanlış gün/ay | Doğruluk | Servis katmanında `Clock` + uç durum testleri; DB'de zamana bağlı kısıt yok |
| Claude Code Cloud'da PostgreSQL olmaması | Yerel geri bildirim kaybı | `check-env.sh`; DB testleri GitHub Actions'ta; açık raporlama |
| Path filtresi yüzünden zorunlu kontrolün "pending" kalması | Birleştirme engeli | İş düzeyinde filtre + `ci-gate`/`security-gate` |
| Özel depoda GHAS özelliklerinin olmaması | Analiz eksikliği | Trivy + Gitleaks + npm audit + Dependabot; CodeQL koşullu |
| Özel depoda dal korumanın ücretsiz planda olmaması | Kırmızı PR'ın birleştirilebilmesi | S-4; kontroller yine görünür |
| Mobilin gerçek cihazda test edilememesi | Cihaz hataları | Bileşen testleri + `expo export`; M0 sonu Expo Go kontrol listesi |

## 6. Onay sonrası ilk adım

Bu belgeler onaylanınca **T0.1** başlar.
