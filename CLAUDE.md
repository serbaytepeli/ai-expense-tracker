# CLAUDE.md

Bu dosya, bu depoda çalışan Claude (ve diğer AI geliştirme ajanları) için kalıcı talimatlardır.

## Proje

AI Expense Tracker: iOS ve Android için kişisel harcama takip uygulaması.
- `mobile/` — React Native + Expo + TypeScript
- `backend/` — Java 21 + Spring Boot, modüler monolit, Gradle Kotlin DSL, PostgreSQL 16, Flyway
- `docs/` — gereksinim, mimari, veritabanı, API ve plan belgeleri (**kaynak gerçek bunlardır**)
- CI: GitHub Actions. Geliştirme ortamı: Claude Code Cloud (Docker yok).

Çalışmaya başlamadan önce ilgili belgeyi oku:
`docs/requirements.md`, `docs/architecture.md`, `docs/database-design.md`, `docs/api-contract.md`, `docs/development-plan.md`.

## Roller ve çalışma şekli

- Ürün sahibi ve teknik karar verici: kullanıcı. Claude: kıdemli yazılım mimarı ve geliştirici.
- `docs/development-plan.md`'deki görevleri (T-x.y) **sırayla, birer birer** yap. Plan dışı iş yapma; gerekiyorsa önce sor.
- Belgelerde yanıtı olmayan veya açık soru (S-x) olarak duran bir ürün/mimari kararı gerekiyorsa **varsayımla ilerleme, sor**.
- Her görev ayrı branch (`feat/T1.2-auth-endpoints` gibi) ve `main`'e ayrı PR. Kilometre taşı sonunda ürün sahibinin onayını bekle.
- Bir görevi bitmiş saymadan önce development-plan.md'deki **Tamamlanma Tanımı**nı kontrol et.
- Kullanıcıyla iletişim Türkçe. Kod, kod yorumları, commit mesajları, log mesajları ve API alan adları İngilizce. Kullanıcıya görünen uygulama metinleri Türkçe ve çeviri dosyalarında.
- Belgeler birbiriyle tutarlı kalmalı: bir belgede yapılan değişiklik diğerlerini etkiliyorsa aynı PR'da hepsini güncelle.

## Değişmez kurallar

1. **Para asla float/double değildir.** Java: `BigDecimal` (ölçek 2, HALF_EVEN); DB: `NUMERIC(14,2)`; JSON: string (`"149.90"`); mobil: string olarak taşı, yalnızca gösterimde biçimlendir.
2. **Kullanıcı bazlı veri izolasyonu.** Kullanıcı kimliği yalnızca JWT'den alınır; her sorgu `user_id` ile filtrelenir; başka kullanıcının kaynağı → 404. Veri erişen her uç nokta için iki kullanıcılı izolasyon testi yazılır.
3. **Şifreler yalnızca BCrypt özeti olarak saklanır.** Düz metin şifre, ham refresh token veya API anahtarı veritabanına ya da loglara yazılmaz.
4. **AI çağrıları yalnızca backend'den.** `ANTHROPIC_API_KEY` yalnızca backend ortam değişkenindedir; mobil koda, depoya, loglara ve CI'a girmez. Model adı `AI_MODEL` ortam değişkeninden okunur; kodda sabit model adı yazılmaz.
5. **AI'a yalnızca toplu veri gider** (api-contract.md §7 `InsightPayload` izin listesi): kategori toplamları, dönemsel karşılaştırmalar, bütçe kullanım oranları. Açıklama, not, tekil harcama, ad, e-posta, kullanıcı kimliği **asla** gönderilmez. AI hesaplama yapmaz.
6. **Harcama tarihi bugünden ileri olamaz** (kullanıcının saat dilimine göre).
7. **Şema değişikliği yalnızca yeni Flyway migration'ıyla.** Uygulanmış migration dosyası düzenlenmez.
8. **API değişince** `docs/api-contract.md` aynı PR'da güncellenir; mobil tipler OpenAPI'den yeniden üretilir.
9. **Gizli değer commit edilmez.** Yalnızca `.env.example`.
10. Kategoriler silinmez, arşivlenir.
11. Modül sınırları (architecture.md §3) ArchUnit ile denetlenir; testi geçmek için kural gevşetilmez, gerekirse önce sorulur.

## Test ve raporlama kuralları

- Testler Docker gerektirmez. Veritabanı testleri `TEST_DB_URL` / `TEST_DB_USERNAME` / `TEST_DB_PASSWORD` ile gerçek PostgreSQL 16'ya bağlanır:
  - GitHub Actions: `services: postgres:16`
  - Claude Code Cloud: `scripts/dev-db.sh` ile yerel PostgreSQL
- Veritabanı yoksa testler **başarısız olur**, sessizce atlanmaz.
- **Gerçekten çalıştırmadığın testi başarılı olarak raporlama.** Her raporda hangi komutu nerede çalıştırdığını ve sonucunu yaz; çalıştıramadıklarını ve nedenini açıkça belirt. CI sonucunu görmeden "CI yeşil" deme.
- Gerçek Anthropic API'si testlerde çağrılmaz; sahte sağlayıcı veya sahte HTTP sunucusu kullanılır.

## Kod kuralları

### Backend
- Modüller: `auth`, `user`, `category`, `expense`, `report`, `insight`, `common`. Başka modül yalnızca hedef modülün kök paketindeki public API'yi kullanır.
- Controller → Service → Repository. Controller entity döndürmez; DTO'lar Java `record`.
- Doğrulama Jakarta Bean Validation ile DTO'da; hatalar `common` altındaki global işleyiciyle RFC 9457 problem+json.
- Constructor injection; alan enjeksiyonu yok.
- Zaman için enjekte edilen `Clock` kullan (`LocalDate.now()` doğrudan çağrılmaz).
- Bağımlılık sürümleri `gradle/libs.versions.toml`'da; dependency locking açık.

### Mobil
- TypeScript `strict`; `any` kullanma.
- Ekranlar `app/` (Expo Router), iş mantığı `src/features/<özellik>/`.
- Sunucu verisi TanStack Query; yerel durum Zustand; formlar React Hook Form + Zod.
- Paket eklerken `npx expo install <paket>` kullan.
- Sabit metin yazma; `i18n` anahtarı kullan.

### CI ve güvenlik
- Yeni bağımlılık eklerken güvenlik taramasının (Trivy, npm audit) geçtiğini kontrol et.
- GitHub Actions adımlarını sabit sürümle referansla.

## Komutlar

> M0 tamamlanınca bu bölüm gerçek ve doğrulanmış komutlarla güncellenecek.

```bash
scripts/dev-db.sh                                   # Claude Code Cloud: yerel PostgreSQL 16 + test DB
cd backend && ./gradlew build                       # derleme + tüm backend testleri
cd mobile && npm run lint && npm run typecheck && npm test
```
