# AI Expense Tracker — Gereksinimler (MVP)

> Durum: **Revizyon 3 — ürün sahibi onayı bekleniyor (PR #1)**
> Kararlar bölüm 7'de, açık sorular bölüm 8'dedir. Belgeler arası tutarlılık için kaynak sırası: requirements → architecture → database-design → api-contract → development-plan.

## 1. Ürün özeti

AI Expense Tracker, iOS ve Android için kişisel harcama takip uygulamasıdır. Kullanıcı gerçekleşmiş harcamalarını hızlıca kaydeder, kategorilere ayırır, aylık rapor ve grafiklerle harcamalarını görür ve yapay zekâdan harcama alışkanlıkları hakkında kısa, anlaşılır yorumlar alır.

**Hedef kullanıcı:** Kendi bütçesini takip etmek isteyen bireysel kullanıcı.

## 2. Kapsam

### MVP kapsamında
1. Hesap oluşturma, giriş, çıkış (e-posta + şifre) — D-1
2. Gerçekleşmiş (bugün veya geçmiş tarihli) harcama ekleme, listeleme, düzenleme, silme — D-7
3. Kategoriler (hazır sistem kategorileri + kullanıcının kendi kategorileri)
4. Aylık rapor (toplam, kategori dağılımı, önceki aya göre değişim)
5. Grafikler (kategori halka grafiği, günlük harcama çubuk grafiği, son 6 ay trendi)
6. AI destekli aylık harcama yorumu (günde en fazla 5 başarılı üretim) — D-2, D-8, D-9, D-16
7. Hesap ve tüm verinin silinmesi — D-10

### MVP dışında (sonraki sürümler)
- İleri tarihli / planlanmış ödemeler (ayrı özellik olarak) — D-7
- Bütçe limitleri ve bütçe bildirimleri (bkz. açık soru S-1)
- Gelir takibi
- Çoklu para birimi ve kur dönüşümü — D-3
- Çevrimdışı kullanım ve senkronizasyon — D-5
- İngilizce ve diğer diller — D-6
- Sosyal giriş (Apple / Google)
- Fiş fotoğrafından harcama okuma (OCR), banka entegrasyonu, CSV içe/dışa aktarma
- Ortak/aile hesapları, web arayüzü
- Bulut ortamına dağıtım (deployment) ve App Store / Google Play yayını — D-11

## 3. Fonksiyonel gereksinimler

Her gereksinimin kabul kriterleri (AC) otomatik testle doğrulanabilecek şekilde yazılmıştır.

### FR-1 Kimlik doğrulama
- FR-1.1 Kullanıcı e-posta, şifre ve görünen adla kayıt olabilir.
- FR-1.2 Kullanıcı e-posta ve şifreyle giriş yapabilir; oturum uygulama kapatılıp açılınca korunur.
- FR-1.3 Kullanıcı çıkış yapabilir.
- FR-1.4 Kullanıcı yalnızca kendi verisini görebilir ve değiştirebilir.

**AC**
- Aynı e-postayla ikinci kayıt `409 EMAIL_TAKEN` döner (e-posta büyük/küçük harf duyarsız karşılaştırılır).
- Şifre 8–72 karakterdir ve UTF-8'de en fazla 72 bayttır (BCrypt sınırı; Türkçe karakterler 2 bayt sayılır); kurala uymayan şifre formda ve sunucuda reddedilir.
- Şifre veritabanında yalnızca BCrypt özeti olarak bulunur; düz metin şifre hiçbir tabloda ve logda yer almaz (testle doğrulanır).
- Yanlış e-posta veya şifre aynı `401 INVALID_CREDENTIALS` yanıtını döner; hangi alanın yanlış olduğu söylenmez.
- E-posta kayıt ve girişte aynı şekilde normalize edilir (boşluk kırpma + `Locale.ROOT` ile küçük harf; database-design.md §3).
- Erişim belirtecinin (15 dk) süresi dolunca uygulama yenileme belirteciyle (30 gün, her kullanımda yenilenir) sessizce yeni belirteç alır.
- Kullanılmış bir yenileme belirteci 30 saniyeden sonra tekrar gönderilirse o oturum tamamen iptal edilir (çalınma koruması); architecture.md §3.6'daki tüm senaryolar testle doğrulanır.
- Çıkış yapıldığında o oturumun erişim ve yenileme belirteçleri **hemen** geçersiz olur.
- Başka kullanıcıya ait bir kaynağa erişim denemesi `404` döner (varlığı sızdırılmaz).

### FR-2 Harcama yönetimi
- FR-2.1 Harcama alanları: tutar (zorunlu, > 0), tarih (zorunlu, varsayılan bugün), kategori (zorunlu), açıklama (isteğe bağlı, en fazla 140 karakter), not (isteğe bağlı, en fazla 500 karakter), ödeme yöntemi (isteğe bağlı: nakit / kart / diğer).
- FR-2.2 Harcamalar tarihe göre azalan sırada, ay bazında listelenir; sayfalı yüklenir.
- FR-2.3 Liste kategoriye ve açıklamada metin aramasına göre filtrelenebilir.
- FR-2.4 Harcama düzenlenebilir.
- FR-2.5 Harcama silinebilir; silmeden önce onay istenir.

**AC**
- Harcama ekleme ekranı en fazla 3 dokunuşla kaydedilebilir (tutar → kategori → kaydet).
- Tutar 0, negatif, 999.999.999.999,99'dan büyük veya 2'den fazla ondalık basamaklı ise kaydedilmez; girdi yuvarlanmaz (para kuralları: database-design.md §2).
- **Tarih, kullanıcının saat dilimine göre bugünden ileri olamaz.** Mobil tarih seçici bugünden sonrasını göstermez; sunucu ileri tarihi `400 VALIDATION_ERROR` ile reddeder (D-7). Kontrol Java servis katmanında kullanıcının IANA saat dilimi ve enjekte edilen `Clock` ile yapılır; veritabanında tarih kısıtı yoktur. Gece yarısı ve farklı saat dilimleri sabit saatle test edilir.
- Kaydedilen/düzenlenen/silinen harcama, liste ve rapor ekranlarına elle yenileme gerekmeden yansır.
- Silinen harcama raporlardan ve AI'a giden özetten çıkar.
- 1.000 harcamalık bir hesapta liste ilk sayfasının API yanıtı 300 ms altındadır (NFR-2).

### FR-3 Kategoriler
- FR-3.1 Her yeni kullanıcı için hazır kategoriler oluşturulur: Market, Yeme-İçme, Ulaşım, Faturalar, Kira/Konut, Sağlık, Eğlence, Giyim, Eğitim, Abonelikler, Diğer.
- FR-3.2 Kullanıcı ad, ikon ve renk seçerek kendi kategorisini ekleyebilir ve düzenleyebilir.
- FR-3.3 Kullanıcı kategoriyi arşivleyebilir ve arşivden çıkarabilir. Kategoriler silinmez. Arşivli kategori yeni harcamada seçilemez; eski harcamalarda ve raporlarda görünmeye devam eder.
- FR-3.4 Hazır kategoriler yeniden adlandırılabilir ve arşivlenebilir.

**AC**
- Aynı kullanıcıda aynı adla iki aktif kategori olamaz (büyük/küçük harf duyarsız) → `409 CATEGORY_NAME_TAKEN`.
- "Diğer" kategorisi arşivlenemez → `409 CATEGORY_PROTECTED`.
- Kategori adı 1–30 karakterdir; renk `#RRGGBB` biçimindedir.

### FR-4 Aylık rapor
- FR-4.1 Kullanıcı ay seçer (varsayılan: kullanıcının saat dilimine göre içinde bulunulan ay).
- FR-4.2 Rapor şunları gösterir: toplam harcama, harcama adedi, günlük ortalama, kategori bazında toplam ve yüzde, önceki aya göre toplam ve kategori bazında değişim (tutar ve %), en büyük 5 harcama.

**AC**
- Rapor toplamları, aynı aydaki harcamaların tek tek toplamıyla kuruşu kuruşuna eşittir (otomatik testle doğrulanır).
- Ay sınırları ve "bugün" kullanıcının saat dilimine göre hesaplanır (D-4).
- Harcaması olmayan ay için boş durum ekranı gösterilir (hata değil).
- Önceki ayda harcama yoksa değişim yüzdesi `null` döner ve "—" olarak gösterilir.

### FR-5 Grafikler
- FR-5.1 Kategori dağılımı: halka (donut) grafik + açıklama listesi.
- FR-5.2 Seçili ayın günlük harcamaları: çubuk grafik.
- FR-5.3 Son 6 ayın toplamları: çubuk grafik.

**AC**
- Grafik değerleri FR-4 rapor verisiyle birebir aynıdır (tek kaynak: rapor API'si).
- Bir kategori dilimine dokununca o kategorinin tutarı ve yüzdesi görünür.
- Grafikler açık ve koyu temada okunabilir; bilgi yalnızca renkle verilmez (etiketli açıklama listesi bulunur).

### FR-6 AI harcama yorumu
- FR-6.1 Kullanıcı aylık rapor ekranında "AI yorumu" bölümünü görür ve yorum oluşturabilir.
- FR-6.2 Yorum içeriği: ayın kısa özeti, dikkat çeken 2–4 gözlem, 1–3 uygulanabilir öneri.
- FR-6.3 Yorum o ayın verisi için saklanır; veri değişmediyse yeniden üretilmez ve kotadan düşmez. Aynı veri için eşzamanlı istekler tek üretime indirgenir.
- FR-6.4 Kullanıcı başına günde en fazla **5 başarılı** yorum üretilir (D-9, `AI_DAILY_LIMIT`). Başarısız üretimler kotadan düşmez. Gün sınırı kullanıcının saat dilimine göre hesaplanır. Kota mekanizması: architecture.md §3.5.
- FR-6.5 AI'a **yalnızca** şu veriler gönderilir (D-8): ay, para birimi, ay toplamı, harcama adedi, kategori adları ve toplamları, kategori yüzdeleri, önceki ay ve son 6 ay toplamlarıyla karşılaştırmalar, (bütçe özelliği geldiğinde) bütçe kullanım oranları. **Gönderilmez:** harcama açıklamaları, notlar, tekil harcama kayıtları, ad, e-posta, kullanıcı kimliği, saat dilimi dışındaki profil bilgileri.

**AC**
- Yorum Türkçe'dir ve en fazla ~150 kelimedir.
- AI'a giden istek gövdesi testte yakalanır ve yalnızca izin verilen alanları içerdiği doğrulanır (izin listesi testi).
- Kullanıcının oluşturduğu kategori adlarının AI'a hangi biçimde gönderileceği açık soru S-2'nin yanıtına göre kesinleşir.
- Yorumdaki sayısal ifadeler sunucunun gönderdiği özetten gelir; AI hesaplama yapmaz.
- AI servisi hata verirse veya zaman aşımına uğrarsa (deneme başına 20 sn, en fazla 1 yeniden deneme) `503 AI_UNAVAILABLE` döner, kota düşülmez; rapor ekranının geri kalanı çalışmaya devam eder.
- Aynı ay ve veri için üretim sürerken gelen ikinci istek `409 AI_GENERATION_IN_PROGRESS` alır; mobil bu durumda sonucu bekler (yoklama).
- Eşzamanlı istekler günlük sınırı aşamaz (paralel testle doğrulanır).
- Her üretim isteği benzersiz bir `generationId` ile izlenir.
- Harcaması 3'ten az olan ay için `422 INSUFFICIENT_DATA` döner ve "yeterli veri yok" mesajı gösterilir.
- Yorumun altında "Bu yorum yapay zekâ tarafından üretilmiştir, finansal tavsiye değildir." notu bulunur.
- Günlük sınır aşılırsa `429 AI_DAILY_LIMIT` döner ve uygulamada kalan süre bilgisi gösterilir.

### FR-7 Hesap silme
- FR-7.1 Kullanıcı Ayarlar ekranından hesabını silebilir; işlem çift onay ister ve şifre doğrulaması gerektirir.

**AC**
- Silme sonrası kullanıcıya ait hiçbir satır veritabanında kalmaz (testle doğrulanır).
- Silinen hesabın belirteçleri artık çalışmaz (`401`).

## 4. Fonksiyonel olmayan gereksinimler

| Kod | Konu | Gereksinim |
|---|---|---|
| NFR-1 | Platform | iOS ve Android; minimum sürümler seçilen Expo SDK'nın desteklediği alt sınırlardır |
| NFR-2 | Performans | AI hariç API uç noktalarının yanıt süresi tipik yükte < 300 ms |
| NFR-3 | Kimlik güvenliği | Şifreler BCrypt (cost 12) ile özetlenir; düz metin saklanmaz. Oturum ailesi + rotasyonlu refresh token; token'lar DB'de SHA-256 özeti olarak saklanır; şifre ve token'lar loglanmaz. Mobilde belirteçler `expo-secure-store` içinde tutulur |
| NFR-4 | Veri izolasyonu | Her okuma/yazma kimliği doğrulanmış kullanıcının `user_id`'siyle sınırlıdır; izolasyon her modül için iki kullanıcılı entegrasyon testleriyle doğrulanır |
| NFR-5 | Gizlilik | AI'a yalnızca FR-6.5'teki toplu veriler gider. AI API anahtarı yalnızca backend ortam değişkenindedir; mobil uygulamaya, depoya veya loglara girmez |
| NFR-6 | Para doğruluğu | Para hesapları Java'da `BigDecimal`, PostgreSQL'de `NUMERIC(14,2)`; JSON'da string. Float/double yasak (statik kontrolle denetlenir) |
| NFR-7 | Dil | Arayüz Türkçe; tüm metinler çeviri dosyasında |
| NFR-8 | Erişilebilirlik | Dokunma hedefleri ≥ 44pt, ekran okuyucu etiketleri, dinamik yazı boyutu desteği |
| NFR-9 | Test | API ve veritabanı testleri zorunludur. Backend servis katmanında ≥ %80 satır kapsamı. Testler Docker gerektirmez; doğruluk kaynağı GitHub Actions'tır (PostgreSQL 16 servis konteyneri). Claude Code Cloud'da PostgreSQL varlığı varsayılmaz (architecture.md §5–6) |
| NFR-10 | CI ve güvenlik | Her PR'da derleme, lint, tip kontrolü, testler, bağımlılık ve Docker imajı açık taraması, gizli anahtar taraması otomatik çalışır; yüksek/kritik bulgu PR'ı kırmızıya düşürür. Zorunlu kontroller: `ci-gate`, `security-gate` |
| NFR-11 | Test raporlama dürüstlüğü | Çalıştırılmamış veya atlanmış testler "başarılı" olarak raporlanmaz; PR açıklamalarında "Çalıştırılan testler" ve "Çalıştırılmayan testler" ayrı bölümlerdir |
| NFR-12 | Gözlemlenebilirlik | Yapılandırılmış (JSON) loglar, her istekte `requestId`; `/actuator/health` |
| NFR-13 | Mimari | Modüler monolit; modül sınırları otomatik testle denetlenir (D-12) |

## 5. Kullanıcı akışları (özet)

1. **İlk açılış:** Karşılama → Kayıt → Ana sayfa (bu ayın özeti + "Harcama ekle")
2. **Harcama ekleme:** Ana sayfa → "+" → tutar → kategori → (isteğe bağlı alanlar) → Kaydet → liste güncellenir
3. **Rapor:** "Rapor" sekmesi → ay seçimi → özet kartları → grafikler → AI yorumu
4. **Kategori yönetimi:** Ayarlar → Kategoriler → ekle / düzenle / arşivle
5. **Hesap silme:** Ayarlar → Hesabı sil → şifre → son onay → Karşılama ekranı

## 6. Ekranlar (MVP)

Karşılama, Giriş, Kayıt, Ana sayfa, Harcama listesi, Harcama ekle/düzenle, Rapor (grafikler + AI yorumu), Kategoriler, Ayarlar (profil, saat dilimi, çıkış, hesap silme).

## 7. Kararlar

| No | Konu | Karar |
|---|---|---|
| D-1 | Kimlik doğrulama | E-posta + şifre; backend'in ürettiği JWT (access + refresh) |
| D-2 | AI sağlayıcısı | Anthropic Claude API. Model adı ortam değişkeni (`AI_MODEL`) ile yönetilir; kodda sabit model adı yok |
| D-3 | Para birimi | Kullanıcı başına tek para birimi, varsayılan `TRY`. MVP'de kayıttan sonra değiştirilemez |
| D-4 | Saat dilimi | Kullanıcı profilinde IANA saat dilimi, varsayılan `Europe/Istanbul`; harcamada yalnızca tarih (DATE) |
| D-5 | Çevrimdışı | MVP'de yok; ağ yokken anlaşılır uyarı |
| D-6 | Dil | İlk sürüm yalnızca Türkçe; i18n altyapısı hazır |
| D-7 | Harcama tarihi | Yalnızca bugün veya geçmiş tarih. İleri tarihli ödemeler sonraki sürümde ayrı özellik |
| D-8 | AI'a giden veri | Yalnızca toplu veriler (FR-6.5). Açıklama, not, tekil kayıt ve kişisel bilgi yok |
| D-9 | AI kullanım sınırı | Kullanıcı başına günde en fazla 5 **başarılı** yorum üretimi (`AI_DAILY_LIMIT`, pozitif tam sayı); başarısız üretim kota tüketmez |
| D-10 | Hesap silme | MVP kapsamında |
| D-11 | Dağıtım | MVP'de bulut dağıtımı ve mağaza yayını yok; sonraki aşamada |
| D-12 | Mimari | Modüler monolit (tek Spring Boot uygulaması, sınırları denetlenen modüller) |
| D-13 | Build aracı | Gradle Kotlin DSL |
| D-14 | Geliştirme/test ortamı | Claude Code Cloud + GitHub Actions; yerel VS Code/Docker kurulumu yok. Claude Code Cloud'da PostgreSQL varlığı varsayılmaz; doğruluk kaynağı GitHub Actions |
| D-15 | Oturum yönetimi | Access 15 dk, refresh 30 gün; oturum ailesi, rotasyon, 30 sn yeniden kullanım toleransı, her istekte oturum kontrolü (architecture.md §3.6) |
| D-16 | AI kota mekanizması | `ai_generation_requests` tablosu, kısa rezervasyon transaction'ı, TTL'li rezervasyon, harici çağrı transaction dışında (architecture.md §3.5) |

## 8. Açık sorular (ürün sahibinin yanıtı bekleniyor)

| No | Soru | Önerim |
|---|---|---|
| S-1 | AI'a "bütçe kullanım oranları" gönderilmesi istendi, ancak bütçe özelliği MVP kapsamında değil. Bütçeyi MVP'ye alalım mı? | MVP dışında kalsın. AI sözleşmesinde `budgetUsage` alanı şimdiden tanımlı ama MVP'de boş gönderilir; bütçe özelliği gelince doldurulur. Alternatif: kategori bazlı basit aylık bütçe limitini MVP'ye ekle (+1 kilometre taşı kadar iş). |
| S-2 | Kullanıcının kendi oluşturduğu kategori adları kişisel bilgi içerebilir (ör. "Ayşe'nin okulu"). Bunlar AI'a gönderilsin mi? | Hazır kategoriler adıyla, kullanıcı kategorileri "Özel kategori 1, 2…" gibi genel etiketle gönderilsin; AI yanıtındaki etiketler backend'de gerçek adlara geri çevrilsin. |
| S-3 | Başarısız AI denemeleri kotadan düşmüyor. Sağlayıcı sürekli hata verirken kullanıcı sınırsız deneme yapabilir. Ayrı bir günlük **deneme** sınırı olsun mu? | Evet: günlük toplam deneme (başarılı + başarısız) en fazla `3 × AI_DAILY_LIMIT` (15); aşılırsa `429 AI_DAILY_LIMIT`. |
| S-4 | Depo özel ve kişisel hesapta. Özel depoda dal koruma / zorunlu kontrol GitHub Pro (veya Team) gerektirir. | Pro'ya geçilirse `ci-gate` + `security-gate` zorunlu yapılsın; geçilmezse kontroller yine çalışır, birleştirme öncesi elle kontrol edilir. |
| S-5 | M0 sonunda Expo Go ile cihaz denemesi için Claude Code Cloud'dan tünel çalışmazsa Expo hesabı ve `EXPO_TOKEN` gerekir. | Önce tünel denensin; çalışmazsa ürün sahibi ücretsiz Expo hesabı açıp token'ı oturum ortam değişkeni olarak versin (depoya girmez). |

**M0'a engel olan açık soru yoktur.** S-1, S-2, S-3 M4'ten önce; S-4 ve S-5 M0 kapanışında yanıtlanmalıdır.
