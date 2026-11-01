# AI Expense Tracker — Geliştirme Planı

> Durum: **Onaylı kararlar işlendi — belge onayı bekleniyor (PR)**

## 1. Çalışma şekli

- Geliştirme ortamı **Claude Code Cloud**, doğrulama ortamı **GitHub Actions**'tır (D-14). Hiçbir adım yerel Docker'a bağımlı değildir.
- Her görev (T-x.y) ayrı branch (`feat/T0.2-backend-skeleton` gibi) ve `main`'e yönelik ayrı PR'dır; hedef < 400 satır değişiklik.
- Görevler sırayla yapılır. Her kilometre taşı sonunda ürün sahibine özet sunulur ve onay alınmadan sonraki kilometre taşına geçilmez.
- Belgelerde yanıtı olmayan bir ürün/mimari kararı çıkarsa iş durdurulur ve ürün sahibine sorulur.

### Tamamlanma Tanımı (tüm görevler için)
1. Kabul kriterleri karşılandı.
2. Gerekli testler yazıldı: API değiştiyse API testleri, şema değiştiyse veritabanı testleri, veri erişimi varsa iki kullanıcılı izolasyon testi.
3. GitHub Actions'taki tüm zorunlu kontroller (build, test, lint, güvenlik) yeşil.
4. API değiştiyse `docs/api-contract.md`, şema değiştiyse `docs/database-design.md` aynı PR'da güncel; belgeler birbiriyle tutarlı.
5. PR açıklamasında: ne yapıldı, **hangi testler nerede çalıştırıldı (Claude Code Cloud / GitHub Actions) ve sonuçları**, çalıştırılamayan testler ve nedeni. Çalıştırılmamış test başarılı olarak raporlanmaz (NFR-11).

## 2. Kilometre taşları

| KT | Ad | Çıktı |
|---|---|---|
| M0 | Proje iskeleti ve CI | Çalışan boş backend + boş mobil uygulama + GitHub Actions build/test/güvenlik hatları |
| M1 | Kimlik ve kategoriler | Kayıt/giriş/çıkış, hazır kategoriler, kategori yönetimi |
| M2 | Harcamalar | Harcama CRUD uçtan uca |
| M3 | Rapor ve grafikler | Aylık rapor, trend, 3 grafik |
| M4 | AI yorumları | Aylık AI yorumu, izin listeli yük, kota, önbellek |
| M5 | Sağlamlaştırma | Hesap silme, hata ve ağ durumları, erişilebilirlik |

**Sonraki aşama (MVP sonrası, D-11):** bulut dağıtımı, EAS Build, App Store / Google Play yayını, bütçeler (S-1'e göre), ileri tarihli ödemeler.

## 3. Görevler

### M0 — Proje iskeleti ve CI

**T0.1 Depo yapısı ve geliştirici ortamı**
- Klasörler (`backend/`, `mobile/`, `scripts/`, `.github/`), `.gitignore`, `.editorconfig`, `.env.example` (tüm değişkenler açıklamalı, gerçek değer yok), README'de Claude Code Cloud ve Docker'lı makine için kurulum adımları.
- `scripts/dev-db.sh`: Docker olmadan yerel PostgreSQL 16'yı başlatır, `aiexpense` ve `aiexpense_test` veritabanlarını ve kullanıcıyı oluşturur, gereken ortam değişkenlerini yazdırır. Tekrar çalıştırılabilir (idempotent).
- AC: Temiz bir Claude Code Cloud oturumunda `scripts/dev-db.sh` çalışır ve `psql` ile test veritabanına bağlanılabilir; depoda gizli değer yok.

**T0.2 Backend iskeleti (modüler monolit)**
- Gradle Kotlin DSL + wrapper + version catalog + dependency locking; Spring Boot (güncel kararlı sürüm), Java 21 toolchain.
- Boş modül paketleri: `auth`, `user`, `category`, `expense`, `report`, `insight`, `common`.
- `common`: global hata işleyici (problem+json), `requestId` filtresi, JSON loglama, `Clock` bean'i; actuator; springdoc.
- Flyway etkin (henüz migration yok); veritabanı bağlantısı ortam değişkenlerinden.
- Test altyapısı: `TEST_DB_URL` ile gerçek PostgreSQL'e bağlanan ortak entegrasyon test tabanı (yoksa test **başarısız** olur, sessizce atlanmaz); ArchUnit modül sınırı testleri ve "para alanlarında float/double yasak" kuralı; JaCoCo.
- AC:
  - `./gradlew build` Claude Code Cloud'da (yerel PostgreSQL ile) geçer.
  - Uygulama açılınca `/actuator/health` `UP` döner (entegrasyon testi).
  - Var olmayan yol problem+json biçiminde 404 döner (API testi).
  - Yanıtlarda `X-Request-Id` bulunur (API testi).
  - Modüller arası yasak bir bağımlılık eklendiğinde ArchUnit testi kırılır (bilerek ihlal edilerek doğrulanır, sonra geri alınır).

**T0.3 Backend CI hattı (`backend-ci.yml`)**
- `backend/**` değişen PR'larda ve `main` push'unda çalışır. PostgreSQL 16 servis konteyneri, JDK 21, Gradle önbelleği, `./gradlew build`, JaCoCo raporu, test raporlarını artifact olarak yükleme, Dockerfile ile imaj derleme (push yok).
- `backend/Dockerfile` (çok aşamalı, root olmayan kullanıcı) ve isteğe bağlı `docker-compose.yml` (Docker olan makineler için).
- AC: İş akışı GitHub Actions'ta yeşil; bilerek bozulan bir testle kırmızıya döndüğü gösterilir (PR'da bağlantıyla); yalnızca `mobile/` değişen PR'da çalışmaz.

**T0.4 Mobil iskelet**
- Expo (güncel kararlı SDK) + TypeScript strict + Expo Router; 4 sekme (Ana sayfa, Harcamalar, Rapor, Ayarlar) boş ekranlarla; açık/koyu tema; i18n (`tr`); ESLint + Prettier + Jest (`jest-expo`) + React Native Testing Library; `npm run lint | typecheck | test` betikleri.
- AC: `npm run lint`, `npm run typecheck`, `npm test` ve `npx expo export` Claude Code Cloud'da geçer; ekran bileşenlerinde sabit Türkçe metin yok (lint kuralı veya testle denetlenir); sekme geçişi bir bileşen testiyle doğrulanır.
- Not: iOS simülatörü/Android emülatörü bu ortamda yoktur; cihazda deneme ürün sahibinin Expo Go ile yapacağı isteğe bağlı bir kontroldür ve PR'da "çalıştırılmadı" olarak belirtilir.

**T0.5 Mobil CI hattı (`mobile-ci.yml`)**
- `mobile/**` değişen PR'larda ve `main` push'unda: Node LTS, npm önbelleği, `npm ci`, lint, typecheck, Jest, `npx expo export`.
- AC: İş akışı yeşil; bilerek bozulan bir tip hatasıyla kırmızıya döndüğü gösterilir.

**T0.6 Güvenlik ve bağımlılık kontrolleri (`security.yml`, `dependabot.yml`)**
- Trivy (Gradle + npm kilit dosyaları, HIGH/CRITICAL'da başarısız), `npm audit --audit-level=high`, Trivy imaj taraması, Gitleaks (tüm geçmiş), Dependabot (Gradle, npm, GitHub Actions, Docker; haftalık). Her PR'da, `main` push'unda ve haftada bir zamanlanmış çalışır.
- CodeQL iş akışı dosyası eklenir ancak depo özel ve GitHub Advanced Security yoksa çalışmayacağı README'de belirtilir (architecture.md §6.3).
- AC: İş akışı yeşil; test amaçlı sahte bir gizli anahtar içeren geçici bir commit'te Gitleaks'in PR'ı kırdığı gösterilir (sonra commit geri alınır); Dependabot yapılandırması GitHub tarafından geçerli kabul edilir.

**T0.7 Sözleşme kontrolü iskeleti (`contract-ci.yml`)**
- Backend'den OpenAPI şeması üreten Gradle görevi; mobilde `openapi-typescript` ile `src/api/schema.ts` üretimi; CI'da şema farkı varsa başarısız.
- AC: Backend'e yeni bir uç nokta eklenip mobil tipler güncellenmezse iş akışı kırmızı olur.

**M0 sonu:** README'deki "Komutlar" bölümü ve `CLAUDE.md` gerçek komutlarla güncellenir; ürün sahibine `main` dal koruma ayarları önerisi sunulur (architecture.md §6.4).

### M1 — Kimlik ve kategoriler

**T1.1 Migration'lar V1, V2** — AC: Migration'lar boş veritabanına uygulanır; her kısıt için ihlal testi (database-design.md §7).
**T1.2 Backend: kayıt / giriş / refresh / çıkış** — AC: FR-1 AC'leri API testleriyle; refresh rotasyonu ve tekrar kullanım koruması; şifre ve belirteçlerin DB'de özet olarak saklandığı ve loglarda geçmediği testle doğrulanır; giriş hız sınırı.
**T1.3 Backend: hazır kategoriler + kategori uç noktaları** — AC: Yeni kullanıcı 11 kategoriyle başlar; FR-3 AC'leri; iki kullanıcılı izolasyon testi (B, A'nın kategorisine erişince 404).
**T1.4 Mobil: API istemcisi ve oturum** — AC: 401'de tek refresh ve isteğin tekrarı; eşzamanlı 401'lerde tek refresh isteği; refresh başarısızsa giriş ekranı (birim testleri).
**T1.5 Mobil: Karşılama, Giriş, Kayıt, Çıkış** — AC: FR-1 form kuralları; sunucu hataları Türkçe mesajla; kayıtta cihaz saat dilimi gönderilir.
**T1.6 Mobil: Kategori yönetimi** — AC: Ekle/düzenle/arşivle/arşivden çıkar; "Diğer" için arşivle pasif.

### M2 — Harcamalar

**T2.1 Migration V3 + harcama uç noktaları** — AC: FR-2 AC'leri API testleriyle; ileri tarih reddi (kullanıcı saat dilimine göre, gece yarısı uç durumları dahil, sabit `Clock` ile); bileşik FK testi; iki kullanıcılı izolasyon testi; para dönüşümünde hassasiyet kaybı yok.
**T2.2 Mobil: Harcama ekle/düzenle** — AC: En fazla 3 dokunuş; tarih seçici bugünden sonrasını göstermez; tutar yerel biçimde girilir (`1.250,50`) ve API'ye `"1250.50"` gider.
**T2.3 Mobil: Harcama listesi** — AC: Ay seçici, gruplu liste, sonsuz kaydırma, filtre, arama, onaylı silme; boş/yükleniyor/hata durumları.
**T2.4 Mobil: Ana sayfa** — AC: Bu ayın toplamı ve değişim rapor API'siyle tutarlı; aşağı çekerek yenileme.

### M3 — Rapor ve grafikler

**T3.1 Backend: aylık rapor ve trend** — AC: FR-4 AC'leri; toplamlar tek tek toplamla birebir; önceki ay boşken `null`; ay sınırı/saat dilimi uç durum testleri; izolasyon testi.
**T3.2 Mobil: Rapor ekranı — özet kartları ve kategori tablosu** — AC: Ay değiştirme; boş ay durumu.
**T3.3 Mobil: Grafikler** — AC: FR-5 AC'leri; açık/koyu tema.

### M4 — AI yorumları

**T4.1 Migration V4 + `InsightPayload` oluşturucu + sahte sağlayıcı** — AC: İzin listesi testi (yükte yalnızca api-contract.md §7'deki alanlar; açıklama/not/ad/e-posta/kullanıcı kimliği yok — bunları içeren test verisiyle doğrulanır); aynı veri → aynı parmak izi, farklı veri → farklı parmak izi.
**T4.2 Anthropic adaptörü + istem şablonu v1** — Model adı `AI_MODEL`'den. AC: Testler gerçek API çağırmaz (HTTP katmanı sahte sunucuyla test edilir); şemaya uymayan yanıt → 503; zaman aşımı → 503; `ANTHROPIC_API_KEY` yokken uygulama açılır, uç nokta 503 döner; anahtar loglarda geçmez.
**T4.3 Insight uç noktaları, önbellek, günlük kota** — AC: FR-6 AC'leri; aynı veriyle ikinci istek AI çağırmaz ve kota düşmez; 6. üretim 429; başarısız çağrı kota düşürmez; eşzamanlı isteklerde kota aşılmaz; gün sınırı kullanıcı saat dilimine göre.
**T4.4 Mobil: AI yorumu bölümü** — AC: Yükleniyor/hata/sınır/yetersiz veri durumları; `stale` ise "Güncelle"; kalan hak gösterilir; feragat notu görünür.

İsteğe bağlı manuel kontrol: Ürün sahibi kendi API anahtarıyla Claude Code Cloud ortamında gerçek bir yorum üretip kaliteyi değerlendirir. Bu kontrol otomatik testlerin yerine geçmez ve yapılmadıysa "yapılmadı" olarak raporlanır.

### M5 — Sağlamlaştırma

**T5.1 Hesap silme (backend + mobil)** — AC: FR-7 AC'leri; silme sonrası tüm tablolarda kullanıcıya ait satır yok (veritabanı testi).
**T5.2 Hata ve ağ durumları** — AC: Ağ yokken anlaşılır uyarı; beklenmeyen hatada ekran çökmez (hata sınırı bileşeni testi).
**T5.3 Erişilebilirlik ve görsel gözden geçirme** — AC: NFR-8 kontrol listesi; tüm etkileşimli öğelerde erişilebilirlik etiketi (testle).
**T5.4 MVP kapanış** — Belgelerin son hâli, bilinen sorunlar listesi, sonraki aşama (dağıtım ve mağaza) için hazırlık notları.

## 4. Riskler

| Risk | Etki | Önlem |
|---|---|---|
| AI yanıtının yanlış sayı üretmesi | Kullanıcı güveni | AI'a hazır rakamlar verilir; yanıt şeması doğrulanır |
| AI'a kişisel veri sızması | Gizlilik | İzin listeli yük + testle doğrulama (T4.1); S-2 kararı |
| AI maliyeti | Bütçe | Önbellek + günde 5 sınır + token kaydı |
| Saat dilimi kaynaklı yanlış ay / yanlış "bugün" | Doğruluk | DATE + kullanıcı saat dilimi + sabit `Clock` ile uç durum testleri |
| Claude Code Cloud ile CI ortam farkı | "Bende çalışıyor" sorunu | Aynı PostgreSQL ana sürümü (16), aynı ortam değişkenleri, CI tek doğruluk kaynağı |
| Mobilin gerçek cihazda test edilememesi | Görsel/cihaz hataları | Bileşen testleri + `expo export`; cihaz denemesi ürün sahibinin isteğe bağlı kontrolü, raporlarda açıkça belirtilir |
| Özel depoda CodeQL'in çalışmaması | Statik analiz eksikliği | Trivy + Gitleaks + npm audit + Dependabot; GHAS/açık depo durumunda CodeQL etkinleştirilir |
| Mobil–backend sözleşme kayması | Hatalar | OpenAPI'den tip üretimi + `contract-ci` |

## 5. Onay sonrası ilk adım

Bu belgeler onaylanınca sıradaki iş **T0.1**'dir; ardından T0.2 → T0.7 sırayla, her biri ayrı PR olarak ilerler.
