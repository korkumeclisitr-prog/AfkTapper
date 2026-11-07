# AFK Tapper

Seçtiğin ekran noktalarına senin yerine otomatik dokunan Android uygulaması.
Başlatırken "ban / yasaklanma durumunda sorumluluk kabul edilmez" uyarısı gösterir.

## APK'yı derleme (Android Studio gerekmez)

1. Bu klasörü bir GitHub deposuna yükle (`main` dalı).
2. Depoda **Actions → Build APK → Run workflow**'a bas (push'ta da kendiliğinden çalışır).
3. İş bitince sayfanın altındaki **Artifacts → AfkTapper-apk**'tan APK'yı indir.

Android Studio ile derlemek istersen klasörü açıp **Build → Build APK(s)** yeterli.

## itch.io'ya yükleme

- Yeni proje → *Kind of project*: **Downloadable**
- APK'yı yükle, dosyada **Android** platformunu işaretle.
- Açıklamaya şunu ekle: Android 13+ cihazlarda APK ile yüklenen uygulamada erişilebilirlik
  anahtarı gri olabilir. Çözüm: Ayarlar → Uygulamalar → AFK Tapper → ⋮ →
  **Kısıtlanmış ayarlara izin ver**.
- Açıklamada uygulamanın ne yaptığını ve erişilebilirlik izni istediğini açıkça yaz.

## Notlar

- Tıklama için Android Erişilebilirlik Servisi (`dispatchGesture`) kullanılır; root gerekmez.
- **Sabit nokta modu:** ekran okunmaz, internete bağlanılmaz.
- **AI modu (Gemini):** her adımda ekran görüntüsü alınıp Google Gemini API'ye gönderilir,
  dönen hamle (dokun / uzun bas / kaydır / bekle) uygulanır. Android 11+ gerekir.
  API anahtarını her kullanıcı kendi girer, cihazda saklanır. Anahtarı APK'ya gömme.
  Her adım yaklaşık 2-5 sn sürer: sıra tabanlı, idle ve yavaş oyunlara uygundur, hızlı
  aksiyon oyunlarına değil. Güvenlik bayraklı (FLAG_SECURE) oyunlarda ekran görüntüsü siyah gelir.
- AI modu kullanan uygulamanın itch.io açıklamasında, ekran görüntülerinin Google'a
  gönderildiğini açıkça yaz.
- Release APK, yan yükleme için debug anahtarıyla imzalanır. Kendi anahtarınla imzalamak
  istersen `app/build.gradle.kts` içindeki `signingConfig` satırını değiştir.
- Oyun içinde otomasyon çoğu oyunun şartlarında yasaktır; online oyunlarda ban riski vardır.
