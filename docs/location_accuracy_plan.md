# Rencana Implementasi: Deteksi Provider, Satelit, dan Age Lokasi

## Tujuan
Menambahkan metadata lokasi yang berguna (provider/source, jumlah satelit, satelit yang dipakai, umur data) dan mengadaptasi logika pengambilan lokasi sehingga aplikasi:
- Dapat mengidentifikasi apakah fix berasal dari GPS, Wi‑Fi, atau Cell (heuristik).
- Menyertakan `ageMs` yang akurat saat mengirim ke Firestore / menyimpan lokal.
- Merekam `satellitesTotal` dan `satellitesUsed` dari GNSS bila tersedia.

## Ringkasan Solusi
1. Daftarkan `GnssStatus.Callback` (API >= 24) di service untuk mengumpulkan status satelit secara berkala.
2. Saat mendapatkan `Location` dari fused provider, hitung `ageMs` dengan `SystemClock.elapsedRealtimeNanos()` vs `location.elapsedRealtimeNanos`.
3. Tentukan `provider` heuristik:
   - Jika `location.provider == "gps"` atau terakhir GNSS `usedInFix > 0` → `source = "GPS"`.
   - Else jika ada Wi‑Fi terkoneksi pada saat yang sama → `source = "WIFI"`.
   - Else jika cell info tersedia → `source = "CELL"`.
   - Else `source = "UNKNOWN"`.
4. Sertakan fields baru pada payload: `source`, `ageMs`, `satellitesTotal`, `satellitesUsed`.
5. Update entity Room / `LocationDataModel` dan `toFirestoreMap()`.
6. Tambahkan permission runtime check: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, dan (opsional) `ACCESS_WIFI_STATE`, `READ_PHONE_STATE`/`ACCESS_COARSE_LOCATION` untuk telco info.

## Tugas Teknis (step-by-step)
- [ ] Tambah helper `GnssStatusManager` (register/unregister) di `TrackingForegroundService`.
- [ ] Simpan ringkasan GNSS (total & used) di memori service atau `PrefsManager` sementara.
- [ ] Hitung `ageMs` ketika membentuk payload:
  ```kotlin
  val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
  ```
- [ ] Implement heuristik `source` (GPS/WIFI/CELL) di service sebelum kirim.
- [ ] Perbarui `LocationDataModel` dan `toFirestoreMap()` untuk menyertakan metadata baru.
- [ ] Perbarui penyimpanan Room entity (`PendingLocationEntity`) jika perlu menambah kolom.
- [ ] Update `FirestoreRepository.sendDirect()` dan `saveToRoom()` agar menyertakan field baru.
- [ ] Tambahkan unit/integration test sederhana untuk fungsi `ageMs` dan heuristik (mocks).
- [ ] Uji di perangkat nyata (scenarios: outdoor GPS, indoor Wi‑Fi, only-cell).

## Permission & Compatibility
- `GnssStatus.Callback` memerlukan `ACCESS_FINE_LOCATION`.
- Beberapa info (Wi‑Fi/Cell) memerlukan tambahan permission (`ACCESS_WIFI_STATE`, telephony access) dan mungkin tidak selalu akurat.
- Pastikan handling fallback saat GNSS tidak tersedia.

## Data Model (fields yang ditambahkan)
- `source: String` ("GPS"|"WIFI"|"CELL"|"UNKNOWN")
- `ageMs: Long`
- `satellitesTotal: Int?`
- `satellitesUsed: Int?`

## Catatan Implementasi
- Jangan mengirim data yang hanya berdasar `provider=="fused"` tanpa memeriksa `satellitesUsed` jika tujuan adalah memastikan GPS fix.
- Simpan ringkasan GNSS di service (volatile) — tidak perlu persist kecuali untuk debugging/history.
- Untuk membedakan Wi‑Fi vs Cell gunakan korelasi timestamps antara scan/connection dan `location.elapsedRealtimeNanos`.

## Estimasi Waktu
- Implementasi GNSS callback + field plumbing: 2–4 jam
- Update Room + Firestore model: 1–2 jam
- Pengujian dan tweak heuristik: 2–4 jam (tergantung akses perangkat nyata)

---
Dokumen dibuat otomatis pada: 2026-06-01
