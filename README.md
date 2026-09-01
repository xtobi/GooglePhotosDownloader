# PachaFoto

Application Android moderne pour transférer et restaurer facilement vos photos et vidéos issues d'archives **Google Takeout** directement sur votre smartphone.

---

## ✨ Fonctionnalités

- 📦 **Support multi-archives** : Sélectionnez une ou plusieurs archives ZIP Google Takeout simultanément.
- 🖼️ **Large compatibilité média** :
  - **Photos** : JPEG, PNG, WEBP, HEIC, HEIF, GIF, BMP, TIFF, DNG, RAW, CR2, NEF, ARW.
  - **Vidéos** : MP4, M4V, MOV, AVI, MKV, WEBM, 3GP, TS.
- ⚡ **Lecture fluide en streaming** : Traitement direct depuis le flux URI sans dupliquer l'archive ZIP en mémoire.
- 📱 **Intégration MediaStore native** : Les fichiers apparaissent instantanément dans votre application Galerie et Google Photos dans l'album `Pictures/PachaFoto`.
- 🛡️ **Protection contre les doublons** : Détection des fichiers déjà présents pour éviter les doublons inutiles.
- 📊 **Tableau de bord de transfert en direct** : Suivi du pourcentage, nombre de photos, vidéos, fichiers transférés, fichiers ignorés et erreurs.
- 🔒 **100% Hors-ligne et Privé** : Aucun mot de passe ni connexion Google requis. Le traitement s'effectue intégralement sur votre appareil.

---

## 🚀 Utilisation

1. Rendez-vous sur [takeout.google.com](https://takeout.google.com) et demandez l'exportation de votre bibliothèque **Google Photos**.
2. Téléchargez les fichiers ZIP sur votre téléphone.
3. Lancez **PachaFoto** et appuyez sur **Choisir les fichiers Takeout**.
4. Sélectionnez vos archives ZIP.
5. Appuyez sur **Commencer le transfert**.
6. Retrouvez vos souvenirs directement dans votre Galerie photo sous l'album `Pictures/PachaFoto`.

---

## 🛠️ Compilation

Le projet utilise Gradle (Kotlin DSL) et Jetpack Compose avec Material Design 3.

```bash
# Génération de l'APK de débogage / release
gradle assembleDebug
```

L'APK généré sera nommé : `pachafoto.apk`.
