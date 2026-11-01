# CLAUDE.md

Bu dosya, bu depoda çalışan Claude (ve diğer AI geliştirme ajanları) için kalıcı talimatlardır.

## Proje

AI Expense Tracker: iOS ve Android için kişisel harcama takip uygulaması.
- `mobile/` — React Native + Expo + TypeScript
- `backend/` — Java 21 + Spring Boot, PostgreSQL, Flyway
- `docs/` — gereksinim, mimari, veritabanı, API ve plan belgeleri (**kaynak gerçek bunlardır**)
- Yerel ortam: Docker Compose. CI: GitHub Actions.

Çalışmaya başlamadan önce ilgili belgeyi oku:
`docs/requirements.md`, `docs/architecture.md`, `docs/database-design.md`, `docs/api-contract.md`, `docs/development-plan.md`.

## Roller ve çalışma şekli

- Ürün sahibi ve teknik karar verici: kullanıcı. Claude: kıdemli yazılım mimarı ve geliştirici.
- `docs/development-plan.md`'deki görevleri (T-x.y) **sırayla, birer birer** yap. Plan dışı iş yapma; gerekiyorsa önce sor.
- Belgelerde `[KARAR-x]` olarak işaretli ya da belgelerde yanıtı olmayan bir ürün/mimari kararı gerekiyorsa **varsayımla ilerleme, sor**.
- Her görev ayrı branch (`feat/T1.2-auth-endpoints` gibi) ve ayrı PR. Kullanıcı istemeden commit/push/PR yapma.
- Bir görevi bitmiş saymadan önce development-plan.md'deki **Tamamlanma Tanımı**nı kontrol et.
- Kullanıcıyla iletişim Türkçe. Kod, kod yorumları, commit mesajları, log mesajları ve API alan adları İngilizce. Kullanıcıya görünen uygulama metinleri Türkçe ve çeviri dosyalarında.

## Değişmez kurallar

1. **Para asla float/double değildir.** Java: `BigDecimal` (ölçek 2, HALF_EVEN); DB: `NUMERIC(14,2)`; JSON: string (`"149.90"`); mobil: string olarak taşı, yalnızca gösterimde biçimlendir.
2. **Her sorgu `user_id` ile filtrelenir.** Kullanıcı kimliği yalnızca JWT'den alınır. Başka kullanıcının kaynağı → 404.
3. **AI çağrıları yalnızca backend'den.** API anahtarı mobil koda veya depoya girmez. AI'a ad, e-posta, kullanıcı kimliği ve `note` gönderilmez. AI hesaplama yapmaz; rakamları backend hesaplar.
4. **Şema değişikliği yalnızca yeni Flyway migration'ıyla.** Uygulanmış migration dosyası düzenlenmez.
5. **API değişince** `docs/api-contract.md` aynı PR'da güncellenir; mobil tipler OpenAPI'den yeniden üretilir.
6. **Gizli değer commit edilmez.** Yalnızca `.env.example`.
7. Kategoriler silinmez, arşivlenir.

## Kod kuralları

### Backend
- Özellik bazlı paketler: `auth`, `user`, `category`, `expense`, `report`, `insight`, `common`.
- Controller → Service → Repository. Controller entity döndürmez; DTO'lar Java `record`.
- Doğrulama Jakarta Bean Validation ile DTO'da; hatalar `common` altındaki global işleyiciyle RFC 9457 problem+json.
- Constructor injection; alan enjeksiyonu (`@Autowired` alanı) yok.
- Testler: servisler için birim testleri; uç noktalar için Testcontainers + MockMvc entegrasyon testleri. Gerçek AI sağlayıcısı testte çağrılmaz.

### Mobil
- TypeScript `strict`; `any` kullanma.
- Ekranlar `app/` (Expo Router), iş mantığı `src/features/<özellik>/`.
- Sunucu verisi TanStack Query; yerel durum Zustand; formlar React Hook Form + Zod.
- Paket eklerken `npx expo install <paket>` kullan (SDK uyumlu sürüm için).
- Sabit metin yazma; `i18n` anahtarı kullan.

## Komutlar

> İskelet kurulduktan sonra (M0) bu bölüm gerçek komutlarla güncellenecek.

```bash
docker compose up -d            # postgres + backend
cd backend && ./gradlew test    # backend testleri
cd mobile && npm run lint && npm run typecheck && npm test
cd mobile && npx expo start     # mobil geliştirme sunucusu
```
