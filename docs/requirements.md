# AI Expense Tracker — Gereksinimler (MVP)

> Durum: **TASLAK — ürün sahibi onayı bekleniyor**
> `[KARAR-x]` ile işaretli maddeler açık kararlardır; yanlarında önerilen varsayılan yazılıdır. Tam liste: bölüm 7.

## 1. Ürün özeti

AI Expense Tracker, iOS ve Android için kişisel harcama takip uygulamasıdır. Kullanıcı harcamalarını hızlıca kaydeder, kategorilere ayırır, aylık rapor ve grafiklerle harcamalarını görür ve yapay zekâdan harcama alışkanlıkları hakkında kısa, anlaşılır yorumlar alır.

**Hedef kullanıcı:** Kendi bütçesini takip etmek isteyen bireysel kullanıcı (aile/ortak bütçe MVP dışında).

## 2. Kapsam

### MVP kapsamında
1. Hesap oluşturma ve giriş (e-posta + şifre) `[KARAR-1]`
2. Harcama ekleme, listeleme, düzenleme, silme
3. Kategoriler (hazır sistem kategorileri + kullanıcının kendi kategorileri)
4. Aylık rapor (toplam, kategori dağılımı, önceki aya göre değişim)
5. Grafikler (kategori pasta/halka grafiği, günlük harcama çubuk grafiği, son 6 ay trendi)
6. AI destekli aylık harcama yorumu

### MVP dışında (sonraki sürümler)
- Gelir takibi, bütçe limitleri ve bildirimler
- Çoklu para birimi ve kur dönüşümü `[KARAR-3]`
- Fiş fotoğrafından otomatik harcama okuma (OCR)
- Banka entegrasyonu, CSV/Excel içe/dışa aktarma
- Ortak/aile hesapları
- Çevrimdışı kullanım ve senkronizasyon `[KARAR-5]`
- Web arayüzü, sosyal giriş (Google/Apple) `[KARAR-1]`

## 3. Fonksiyonel gereksinimler

Her gereksinimin kabul kriterleri (AC) test edilebilir şekilde yazılmıştır.

### FR-1 Kimlik doğrulama
- FR-1.1 Kullanıcı e-posta, şifre ve görünen adla kayıt olabilir.
- FR-1.2 Kullanıcı e-posta ve şifreyle giriş yapabilir; oturum uygulama kapatılıp açılınca korunur.
- FR-1.3 Kullanıcı çıkış yapabilir.
- FR-1.4 Kullanıcı yalnızca kendi verisini görebilir ve değiştirebilir.

**AC**
- Aynı e-postayla ikinci kayıt `409` döner ve uygulamada anlaşılır hata gösterilir.
- Şifre en az 8 karakterdir; kurala uymayan şifre sunucuya gönderilmeden formda reddedilir.
- Yanlış şifre `401` döner; hangi alanın yanlış olduğu söylenmez.
- Erişim belirtecinin süresi dolunca uygulama yenileme belirteciyle sessizce yeni belirteç alır; kullanıcı tekrar giriş ekranına düşmez.
- Başka kullanıcıya ait bir harcamaya erişim denemesi `404` döner (varlığı sızdırılmaz).

### FR-2 Harcama yönetimi
- FR-2.1 Harcama alanları: tutar (zorunlu, > 0), tarih (zorunlu, varsayılan bugün), kategori (zorunlu), açıklama (isteğe bağlı, en fazla 140 karakter), not (isteğe bağlı, en fazla 500 karakter), ödeme yöntemi (isteğe bağlı: nakit / kart / diğer).
- FR-2.2 Harcamalar tarihe göre azalan sırada, ay bazında listelenir; sayfalı yüklenir.
- FR-2.3 Liste kategoriye ve metin aramasına (açıklama) göre filtrelenebilir.
- FR-2.4 Harcama düzenlenebilir.
- FR-2.5 Harcama silinebilir; silmeden önce onay istenir.

**AC**
- Harcama ekleme ekranı en fazla 3 dokunuşla kaydedilebilir (tutar → kategori → kaydet).
- Tutar 0, negatif veya 2 ondalık basamaktan fazla ise kaydedilmez, alan altında hata görünür.
- Gelecek tarihli harcama kabul edilir ama en fazla 1 yıl ileri. `[KARAR-7]`
- Kaydedilen/düzenlenen/silinen harcama, liste ve rapor ekranlarına ekran yenilenmeden yansır.
- Silinen harcama raporlardan ve AI yorumlarına giden verilerden çıkar.
- 1.000 harcamalık bir hesapta liste ilk sayfası 1 saniyenin altında açılır (yerel ağ, orta seviye cihaz).

### FR-3 Kategoriler
- FR-3.1 Her kullanıcı için hazır kategoriler gelir: Market, Yeme-İçme, Ulaşım, Faturalar, Kira/Konut, Sağlık, Eğlence, Giyim, Eğitim, Abonelikler, Diğer.
- FR-3.2 Kullanıcı ad, ikon ve renk seçerek kendi kategorisini ekleyebilir, düzenleyebilir.
- FR-3.3 Kullanıcı kategoriyi arşivleyebilir (silme yerine). Arşivli kategori yeni harcamada seçilemez, eski harcamalarda ve raporlarda görünmeye devam eder.
- FR-3.4 Hazır kategoriler yeniden adlandırılabilir, arşivlenebilir ama kalıcı silinemez.

**AC**
- Aynı kullanıcıda aynı adla iki aktif kategori olamaz (büyük/küçük harf duyarsız) → `409`.
- "Diğer" kategorisi arşivlenemez (her zaman bir yedek kategori bulunur).
- Kategori adı 1–30 karakterdir.

### FR-4 Aylık rapor
- FR-4.1 Kullanıcı ay seçer (varsayılan: içinde bulunulan ay).
- FR-4.2 Rapor şunları gösterir: toplam harcama, harcama adedi, günlük ortalama, kategori bazında toplam ve yüzde, önceki aya göre toplam ve kategori bazında değişim (tutar ve %), en büyük 5 harcama.

**AC**
- Rapor toplamları, aynı aydaki harcamaların elle toplamıyla kuruşu kuruşuna eşittir (otomatik testle doğrulanır).
- Ay sınırları kullanıcının saat dilimine göre hesaplanır. `[KARAR-4]`
- Harcaması olmayan ay için boş durum ekranı gösterilir (hata değil).
- Önceki ayda harcama yoksa değişim yüzdesi "—" olarak gösterilir (sonsuz/NaN gösterilmez).

### FR-5 Grafikler
- FR-5.1 Kategori dağılımı: halka (donut) grafik + açıklama listesi.
- FR-5.2 Seçili ayın günlük harcamaları: çubuk grafik.
- FR-5.3 Son 6 ayın toplamları: çubuk/çizgi grafik.

**AC**
- Grafik değerleri FR-4 rapor verisiyle birebir aynıdır (tek kaynak: rapor API'si).
- Bir kategori dilimine dokununca o kategorinin tutarı ve yüzdesi görünür.
- Grafikler açık ve koyu temada okunabilir; renkler yalnızca renk farkına dayanmaz (etiket/açıklama listesi bulunur).

### FR-6 AI harcama yorumu
- FR-6.1 Kullanıcı aylık rapor ekranında "AI yorumu" bölümünü görür ve yorum oluşturabilir.
- FR-6.2 Yorum içeriği: ayın kısa özeti, dikkat çeken 2–4 gözlem (ör. "Yeme-İçme geçen aya göre %35 arttı"), 1–3 uygulanabilir öneri.
- FR-6.3 Yorum, o ayın verisi için saklanır; veri değişmediyse tekrar açılınca yeniden üretilmez.
- FR-6.4 Kullanıcı yorumu yeniden oluşturabilir (sınırlı sayıda). `[KARAR-9]`
- FR-6.5 AI'a gönderilen veri en aza indirilir. `[KARAR-8]`

**AC**
- Yorum Türkçe'dir ve en fazla ~150 kelimedir. `[KARAR-6]`
- Yorumdaki her sayısal ifade, rapor verisinden doğrulanabilir (sunucu, AI'a yalnızca hesaplanmış özet rakamları verir; AI hesaplama yapmaz).
- AI servisi hata verirse veya 20 saniyede yanıt vermezse kullanıcı anlaşılır bir mesaj görür; rapor ekranının geri kalanı çalışmaya devam eder.
- Harcaması 3'ten az olan ay için AI yorumu yerine "yeterli veri yok" mesajı gösterilir.
- Yorumun altında "Bu yorum yapay zekâ tarafından üretilmiştir, finansal tavsiye değildir" notu bulunur.
- Kullanıcı başına günlük yeniden oluşturma sınırı aşılırsa `429` döner ve uygulamada bilgilendirme gösterilir.

## 4. Fonksiyonel olmayan gereksinimler

| Kod | Konu | Gereksinim |
|---|---|---|
| NFR-1 | Platform | iOS 15+ ve Android 8+ (Expo'nun desteklediği alt sınırlar esas alınır) |
| NFR-2 | Performans | API uç noktalarının p95 yanıt süresi < 300 ms (AI hariç) |
| NFR-3 | Güvenlik | Şifreler BCrypt ile saklanır; tüm trafik HTTPS; belirteçler cihazda güvenli depoda (SecureStore) tutulur |
| NFR-4 | Gizlilik | AI sağlayıcısına kimlik bilgisi (ad, e-posta) gönderilmez; kullanıcı hesabını ve tüm verisini silebilir `[KARAR-10]` |
| NFR-5 | Doğruluk | Para tutarları kayan nokta (float/double) ile tutulmaz; `BigDecimal` / `NUMERIC` kullanılır |
| NFR-6 | Dil | Arayüz Türkçe; metinler çeviri dosyalarında tutulur (ileride İngilizce eklemeye hazır) `[KARAR-6]` |
| NFR-7 | Erişilebilirlik | Dokunma hedefleri ≥ 44pt, ekran okuyucu etiketleri, dinamik yazı boyutu desteği |
| NFR-8 | Test | Backend servis katmanında ≥ %80 satır kapsamı; kritik akışlar için entegrasyon testi (Testcontainers) |
| NFR-9 | CI | Her PR'da lint, tip kontrolü, testler ve Docker imaj derlemesi otomatik çalışır |
| NFR-10 | Gözlemlenebilirlik | Yapılandırılmış (JSON) loglar, her istekte `requestId`; sağlık uç noktası `/actuator/health` |

## 5. Kullanıcı akışları (özet)

1. **İlk açılış:** Karşılama → Kayıt → Ana ekran (bu ayın özeti + "Harcama ekle" butonu)
2. **Harcama ekleme:** Ana ekran → "+" → tutar → kategori → (isteğe bağlı alanlar) → Kaydet → liste güncellenir
3. **Rapor:** Alt menü "Rapor" → ay seçimi → özet kartları → grafikler → AI yorumu
4. **Kategori yönetimi:** Ayarlar → Kategoriler → ekle / düzenle / arşivle

## 6. Ekranlar (MVP)

Giriş, Kayıt, Ana sayfa (ay özeti + son harcamalar), Harcama listesi, Harcama ekle/düzenle, Rapor (grafikler + AI yorumu), Kategoriler, Ayarlar (profil, çıkış, hesap silme).

## 7. Açık kararlar

Ürün sahibinin onayı gereken kararlar. Önerilen seçenek ilk sırada.

| No | Karar | Önerilen | Alternatifler |
|---|---|---|---|
| KARAR-1 | Kimlik doğrulama | E-posta + şifre, kendi JWT'miz (access + refresh) | Sosyal giriş (Apple/Google) MVP'de; hesapsız yalnız-cihaz kullanımı |
| KARAR-2 | AI sağlayıcısı | Anthropic Claude API (model yapılandırmadan seçilir) | OpenAI; yerel model |
| KARAR-3 | Para birimi | Kullanıcı başına tek para birimi (varsayılan TRY) | Harcama başına para birimi + kur dönüşümü |
| KARAR-4 | Saat dilimi | Kullanıcı profilinde saat dilimi (varsayılan `Europe/Istanbul`), harcamada sadece tarih | Harcamada tam zaman damgası |
| KARAR-5 | Çevrimdışı | MVP yalnızca çevrimiçi; çevrimdışıyken anlaşılır uyarı | Çevrimdışı kayıt + senkronizasyon |
| KARAR-6 | Dil | Yalnızca Türkçe arayüz, i18n altyapısı hazır | Türkçe + İngilizce |
| KARAR-7 | Gelecek tarih | En fazla 1 yıl ileri tarihe izin ver | Gelecek tarihe hiç izin verme |
| KARAR-8 | AI'a giden veri | Yalnızca hesaplanmış özet (kategori toplamları, değişimler, en büyük 5 harcamanın tutar + kategori + açıklaması); not alanı gönderilmez | Açıklamalar da hiç gönderilmez; tüm ham veri gönderilir |
| KARAR-9 | AI kullanım sınırı | Kullanıcı başına günde 5 yorum üretimi | Sınırsız; aylık kota |
| KARAR-10 | Hesap silme | MVP'de var (App Store kuralı gereği gerekli) | Sonraki sürüm |
| KARAR-11 | Dağıtım hedefi | MVP'de sadece yerel/Docker Compose; bulut ortamı sonra seçilir | Şimdiden bir bulut (Fly.io, Render, AWS vb.) |
| KARAR-12 | Backend derleme aracı | Gradle (Kotlin DSL) | Maven |
