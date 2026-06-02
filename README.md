## Alur proses pengambilan lokasi hingga penyimpanan

### 1. Pengambilan lokasi
File utama: TrackingForegroundService.kt

- Service `TrackingForegroundService` berjalan sebagai foreground service.
- Setiap `CHECK_INTERVAL_MS` = 5 menit, service memanggil `checkAndSend()`.

### 2. Dipilih lokasi terbaik
File: LocationRepository.kt

- `LocationRepository.getCurrentLocation()` memanggil `fusedClient.getCurrentLocation(...)`.
- Di service, ada dua fase:
  1. 10 percobaan mencari akurasi `<= 20m`.
  2. Jika gagal, 10 percobaan mencari akurasi `<= 100m`.
- Jika masih gagal, fallback pakai lokasi dengan `accuracy` terbaik dari semua percobaan.

### 3. Validasi sebelum kirim
Di `TrackingForegroundService.checkAndSend()`:

- Ambil data terakhir dari `PrefsManager.getLastSentLocation()`:
  - `latitude`
  - `longitude`
  - `timestamp`
- Hitung jarak dari lokasi terakhir ke lokasi baru.
- Kirim hanya jika:
  - bergeser > 200 meter, atau
  - sudah lewat interval wajib `MANDATORY_INTERVAL_MS` = 60 menit.
- Ada juga deduplikasi waktu:
  - jika data terakhir dikirim < 5 menit lalu (`MIN_TIME_DELTA_MS`), maka skip kecuali pengiriman wajib.

### 4. Pembuatan payload lokasi
File: LocationDataModel.kt

Payload dibuat seperti ini:

- `deviceId`, `userName`, `deviceModel`
- `latitude`, `longitude`, `accuracy`
- `battery`, `isCharging`
- `localTimestamp = System.currentTimeMillis()`
- `source = "online"` atau `"offline_sync"`

Catatan penting:
- `localTimestamp` diisi saat payload dibuat.
- `timestamp` Firestore tidak dikirim langsung, melainkan menggunakan `FieldValue.serverTimestamp()`.

### 5. Penyimpanan langsung ke Firestore
File: FirestoreRepository.kt

- Jika online dan Firebase tersedia:
  - Autentikasi anonim dengan Firebase Auth.
  - Jika `overwriteLastDoc = true`, lakukan `document(lastDocId).set(...)`.
  - Jika tidak, lakukan `.add(payload.toFirestoreMap())`.
  - Simpan `lastDocId` ke `PrefsManager` untuk referensi berikutnya.

### 6. Penyimpanan ke lokal / cache offline
Masih di FirestoreRepository.kt:

- Jika offline atau `sendDirect()` gagal:
  - `saveToRoom(payload)` menyimpan ke Room DB sebagai `PendingLocationEntity`.
  - `localTimestamp` juga disimpan di Room.
  - Data pending akan disinkronkan saat online lagi melalui `syncPendingFromRoom()`.

### 7. Sinkronisasi pending ke Firestore
- `syncPendingFromRoom()` mengambil semua entry status `PENDING`.
- Untuk setiap item:
  - buat `LocationDataModel` dengan `source = "offline_sync"`.
  - kirim ke Firestore melalui `.add(...)`.
  - jika sukses, tandai `status = SENT` dan hapus later.

---

## Mengapa bisa muncul `timestamp` / `localTimestamp` sama persis?

### Kemungkinan penyebab
1. `localTimestamp` hanya `System.currentTimeMillis()`
   - Jika dua payload dibuat sangat berdekatan, nilai bisa sama persis pada milidetik yang sama.
   - Ini mudah terjadi ketika service restart, kirim pending, atau data dibuat kembali dalam loop.

2. Data offline yang disimpan lalu disinkronkan
   - Payload gagal dikirim, disimpan ke Room dengan `localTimestamp` yang sama.
   - Saat sinkronisasi, Firestore menerima beberapa dokumen berbeda tetapi tetap punya `localTimestamp` sama karena itu berasal dari payload lama.

3. `timestamp` Firestore bukan nilai unik
   - `FieldValue.serverTimestamp()` di Firestore memberikan berupa server-side timestamp.
   - Dua dokumen yang ditulis sangat berdekatan dapat memiliki nilai timestamp yang sama juga, karena timestamp server bisa sama dalam unit waktu yang sangat kecil.

### Inti masalah
- Tidak ada mekanisme unik untuk membedakan event selain `localTimestamp` dan lokasi.
- Timestamp sama bisa muncul karena:
  - event dibuat pada milidetik identik, atau
  - ada retry / re-send dengan payload yang sama.

---

## Rekomendasi perbaikan
Jika kamu ingin menghindari duplikat waktu, opsi yang bisa dipertimbangkan:

- Tambahkan field unik di payload, misalnya `eventId = UUID.randomUUID().toString()`
- Simpan `createdAt = System.currentTimeMillis()` saat pertama capture, bukan hanya saat kirim
- Untuk Room pending, jangan buat ulang `localTimestamp` bila payload sudah ada
- Jika ingin deduplikasi lebih kuat, bandingkan bukan hanya timestamp tetapi juga `latitude+longitude+accuracy`

> Kesimpulan: logika saat ini sudah pilih lokasi dan kirim/penyimpanan dengan baik, tetapi `localTimestamp` tidak guaranteed unik. Sama persisnya bisa terjadi karena dua data dibuat dalam milidetik yang sama atau karena sinkronisasi ulang.
