# AI Expense Tracker — Geliştirme Planı

> Durum: **TASLAK — ürün sahibi onayı bekleniyor**

## 1. Çalışma şekli

- Her görev (T-x.y) **ayrı bir branch ve ayrı bir PR**'dır; küçük ve tek başına incelenebilir tutulur (hedef: < 400 satır değişiklik).
- Bir görev ancak **Tamamlanma Tanımı (DoD)** karşılandığında biter.
- Görevler sırayla yapılır; bağımlılığı olmayanlar paralel ilerleyebilir.
- Her kilometre taşı sonunda ürün sahibine kısa demo/özet sunulur ve onay alınır.

### Tamamlanma Tanımı (tüm görevler için)
1. Kabul kriterleri karşılandı.
2. Testler yazıldı ve CI yeşil.
3. Lint / tip kontrolü hatasız.
4. API değiştiyse `docs/api-contract.md` ve OpenAPI şeması güncel; şema değiştiyse `docs/database-design.md` güncel.
5. PR açıklamasında ne yapıldığı ve nasıl test edileceği yazılı.

## 2. Kilometre taşları

| KT | Ad | Çıktı |
|---|---|---|
| M0 | Proje iskeleti | Çalışan boş backend + boş mobil uygulama + Docker Compose + CI |
| M1 | Kimlik ve kategoriler | Kayıt/giriş, hazır kategoriler, kategori yönetimi (backend + mobil) |
| M2 | Harcamalar | Harcama CRUD uçtan uca |
| M3 | Rapor ve grafikler | Aylık rapor, trend, 3 grafik |
| M4 | AI yorumları | Aylık AI yorumu, kota, önbellek |
| M5 | Sağlamlaştırma | Hesap silme, hata durumları, erişilebilirlik, mağaza öncesi kontroller |

## 3. Görevler

### M0 — Proje iskeleti

**T0.1 Depo yapısı ve temel dosyalar**
- `backend/`, `mobile/`, `docs/`, `.github/` klasörleri, `.gitignore`, `.editorconfig`, `.env.example`, README'de kurulum adımları.
- AC: README'deki adımlarla yeni bir geliştirici projeyi klonlayıp ne yapacağını anlayabiliyor; depoda hiçbir gizli değer yok.

**T0.2 Backend iskeleti**
- Spring Boot projesi (Java 21, KARAR-12'deki araç), paket yapısı, `common/` altında global hata işleyici (problem+json), JSON loglama, `requestId` filtresi, actuator, springdoc.
- AC: `./gradlew build` geçer; uygulama açılınca `/actuator/health` `UP` döner; var olmayan bir yol problem+json formatında 404 döner; örnek bir entegrasyon testi Testcontainers ile çalışır.

**T0.3 Docker Compose**
- `postgres` + `backend` servisleri, sağlık kontrolleri, kalıcı volume; backend için çok aşamalı Dockerfile.
- AC: `docker compose up` tek komutla her şeyi ayağa kaldırır; backend DB hazır olmadan başlamaz; Flyway boş V0 migration'ı uygular.

**T0.4 Mobil iskelet**
- Expo + TypeScript (strict) + Expo Router, alt sekme navigasyonu (Ana sayfa, Harcamalar, Rapor, Ayarlar) boş ekranlarla, tema (açık/koyu), i18n kurulumu, ESLint + Prettier + Jest.
- AC: Uygulama iOS simülatöründe, Android emülatöründe ve Expo Go'da açılır; sekmeler arasında geçiş yapılır; `npm run lint`, `npm run typecheck`, `npm test` geçer; ekranlarda sabit Türkçe metin yok, hepsi çeviri dosyasından gelir.

**T0.5 CI**
- `backend.yml` ve `mobile.yml` iş akışları (bölüm: architecture.md §6).
- AC: Bilerek bozulmuş bir test PR'ı kırmızı olur; düzeltince yeşil olur; yalnızca ilgili klasör değişince ilgili iş akışı çalışır.

### M1 — Kimlik ve kategoriler

**T1.1 DB: users, refresh_tokens, categories migration'ları (V1, V2)**
- AC: Migration'lar boş DB'ye sorunsuz uygulanır; benzersizlik ve CHECK kısıtları testle doğrulanır.

**T1.2 Backend: kayıt / giriş / refresh / çıkış**
- AC: requirements.md FR-1 AC'lerinin tamamı entegrasyon testiyle doğrulanır; refresh rotasyonu (eski belirteç ikinci kez kullanılamaz) test edilir; şifre ve belirteç loglarda görünmez.

**T1.3 Backend: hazır kategorilerin kayıtta oluşturulması + kategori uç noktaları**
- AC: Yeni kullanıcı 11 hazır kategoriyle başlar; FR-3 AC'leri testle doğrulanır; A kullanıcısı B'nin kategorisine erişince 404 alır.

**T1.4 Mobil: API istemcisi ve oturum yönetimi**
- OpenAPI'den tip üretimi, fetch sarmalayıcısı, belirteç yenileme, SecureStore, TanStack Query kurulumu.
- AC: 401 alınınca tek seferlik refresh yapılır ve istek tekrarlanır; eşzamanlı birden fazla 401'de tek refresh isteği gider; refresh başarısızsa giriş ekranına yönlendirilir.

**T1.5 Mobil: Giriş, Kayıt, Çıkış ekranları**
- AC: Form doğrulamaları FR-1'e uygun; sunucu hataları anlaşılır Türkçe mesajla gösterilir; uygulama kapanıp açılınca oturum devam eder.

**T1.6 Mobil: Kategori yönetimi ekranı**
- AC: Ekle / düzenle / arşivle / arşivden çıkar çalışır; ikon ve renk seçici var; "Diğer" için arşivle butonu pasif.

### M2 — Harcamalar

**T2.1 DB + Backend: expenses tablosu (V3) ve CRUD uç noktaları**
- AC: FR-2 AC'leri testle doğrulanır; liste filtreleri (ay, kategori, arama) ve sayfalama doğru çalışır; tutar string↔BigDecimal dönüşümü yuvarlama hatasız; başka kullanıcının harcaması/kategorisi kullanılamaz.

**T2.2 Mobil: Harcama ekle/düzenle formu**
- Tutar girişi (yerel biçim, ör. `1.250,50`), kategori ızgarası, tarih seçici, isteğe bağlı alanlar.
- AC: En fazla 3 dokunuşla kaydetme; geçersiz tutar engellenir; kayıt sonrası liste ve ana sayfa otomatik güncellenir.

**T2.3 Mobil: Harcama listesi**
- Ay seçici, gün başlıklarıyla gruplu liste, sonsuz kaydırma, kategori filtresi, arama, kaydırarak silme + onay.
- AC: Boş, yükleniyor ve hata durumları tasarlanmış; silme sonrası liste ve özetler güncellenir.

**T2.4 Mobil: Ana sayfa**
- Bu ayın toplamı, önceki aya göre değişim, son 5 harcama, "+" butonu.
- AC: Veriler rapor uç noktasıyla tutarlı; aşağı çekerek yenileme çalışır.

### M3 — Rapor ve grafikler

**T3.1 Backend: aylık rapor ve trend uç noktaları**
- AC: FR-4 AC'leri testle doğrulanır; test verisinde toplamlar elle hesaplananla birebir aynı; önceki ay boşken `changePercent: null`; ay sınırları kullanıcının saat dilimine göre doğru (ay sonu/başı uç durum testleri).

**T3.2 Mobil: Rapor ekranı — özet kartları ve kategori tablosu**
- AC: Ay değiştirme çalışır; boş ay için boş durum gösterilir.

**T3.3 Mobil: Grafikler (donut, günlük çubuk, 6 aylık trend)**
- AC: FR-5 AC'leri; açık/koyu temada okunur; dilime dokununca detay gösterilir.

### M4 — AI yorumları

**T4.1 DB + Backend: ai_insights, ai_usage (V4), InsightProvider arayüzü, sahte sağlayıcı**
- AC: Testler gerçek AI çağırmadan sahte sağlayıcıyla çalışır; veri parmak izi aynı özet için aynı, farklı özet için farklı.

**T4.2 Backend: gerçek AI sağlayıcısı adaptörü ve istem (prompt) şablonu v1**
- Yapılandırılmış JSON çıktısı, 20 sn zaman aşımı, tek yeniden deneme, token kullanım kaydı.
- AC: AI'a giden yükte ad/e-posta/not olmadığı testle doğrulanır; geçersiz JSON yanıtı 503'e dönüşür ve kota düşülmez; API anahtarı yoksa uygulama yine açılır, AI uç noktası 503 döner.

**T4.3 Backend: insights uç noktaları, önbellek ve günlük kota**
- AC: FR-6 AC'leri; aynı veri için ikinci istek AI çağırmaz; 6. istek 429 döner; eşzamanlı isteklerde kota aşılmaz.

**T4.4 Mobil: Rapor ekranında AI yorumu bölümü**
- AC: Yükleniyor iskeleti, hata/sınır/yetersiz veri durumları; `stale` ise "Güncelle" önerisi; feragat notu görünür.

### M5 — Sağlamlaştırma

**T5.1 Hesap silme (backend + mobil)** — AC: Silme sonrası kullanıcıya ait hiçbir satır kalmaz (testle); çift onay istenir.
**T5.2 Genel hata ve çevrimdışı durumu** — AC: Ağ yokken anlaşılır uyarı; hiçbir ekran çökmez (KARAR-5).
**T5.3 Erişilebilirlik ve görsel gözden geçirme** — AC: NFR-7 kontrol listesi tamam.
**T5.4 Mağaza hazırlığı** — Uygulama ikonu, açılış ekranı, gizlilik politikası taslağı, EAS Build yapılandırması. AC: iOS ve Android test derlemesi alınır.

## 4. Riskler

| Risk | Etki | Önlem |
|---|---|---|
| AI yanıtının yanlış sayı üretmesi | Kullanıcı güveni | AI'a hazır rakamlar verilir; çıktıda sayılar özetle çapraz kontrol edilir (T4.2) |
| AI maliyetinin artması | Bütçe | Önbellek + günlük kota + token kaydı |
| Saat dilimi kaynaklı yanlış ay | Rapor doğruluğu | Harcamada yerel tarih (DATE) + uç durum testleri |
| Mobil–backend sözleşme kayması | Hatalar | OpenAPI'den tip üretimi + CI kontrolü |
| Expo kütüphane uyumsuzluğu | Gecikme | Yalnızca Expo SDK uyumlu sürümler (`npx expo install`) |

## 5. Onay sonrası ilk adım

Onay verilirse sıradaki iş **T0.1 → T0.5** (M0). M0 bittiğinde kısa bir özetle tekrar onayına sunulur.
