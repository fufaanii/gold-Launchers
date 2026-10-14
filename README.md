# Zlatý Launcher

Lehký černo‑zlatý launcher + zlatá klávesnice pro Android (Xiaomi Redmi 12 a další, Android 8+).
Bez jediné knihovny, bez XML layoutů – jen dvě třídy a pár zdrojů.

## Co umí
- Dock s 5 aplikacemi (dlouhé podržení ikony = výměna aplikace).
- Tah prstem nahoru = menu aplikací s hledáním (bez diakritiky), dlouhé podržení aplikace = info / odinstalovat / do doku.
- Tlačítko **⚡ Vyčistit paměť** – jedním klikem ukončí aplikace na pozadí a ukáže, kolik RAM se uvolnilo.
- Dlouhé podržení na ploše: změna tapety, hotová zlato‑černá tapeta, přidání/odebrání widgetů, zapnutí a přepnutí klávesnice.
- Zlato‑černá česká klávesnice (QWERTZ) s bílým textem; háčky a čárky přes dlouhé podržení písmene.

## Jak získat APK

### A) GitHub (bez Android Studia)
1. Vytvoř nový repozitář na github.com a nahraj do něj **celý obsah** této složky (včetně skryté složky `.github`).
2. Otevři záložku **Actions** → workflow **Build APK** (spustí se sám po nahrání, nebo ho spusť ručně přes *Run workflow*).
3. Po dokončení (cca 3–5 min) stáhni artefakt **ZlatyLauncher-apk** → uvnitř je `app-release.apk`.

### B) Android Studio
Otevři složku jako projekt a zvol *Build → Build APK(s)*, nebo v terminálu: `./gradlew assembleRelease`.
APK najdeš v `app/build/outputs/apk/release/`.

## Instalace a nastavení
1. Nainstaluj APK (povol instalaci z neznámých zdrojů).
2. Stiskni tlačítko Domů → vyber **Zlatý Launcher** → *Vždy*. (Na Xiaomi případně Nastavení → Aplikace → Výchozí aplikace → Domovská aplikace.)
3. Klávesnice: dlouhé podržení na ploše → **Zapnout zlatou klávesnici** → zapni přepínač → pak **Přepnout klávesnici**.
4. Pro spolehlivé čištění paměti na MIUI povol v Nastavení → Aplikace → Zlatý Launcher → *Automatické spuštění* a v Úspoře baterie zvol *Bez omezení*.
