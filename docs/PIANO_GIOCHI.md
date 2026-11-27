# Piano: giochini senza ambiguità e senza "vicoli ciechi"

> **Stato (13/11/2026):** ✅ giochini fatti nella **v1.4** (Sudoku, KenKen, Kakuro, Sumplete
> 5/6/7 più difficile, Sequenze non ambigue; verificati sulla JVM, zero schemi non validi).
> ✅ Paracadute: versioni numerate (v1.3, v1.4…).
> ✅ **v1.5** (24/11/2026): giochi nuovi Futoshiki, Hitori, Binario; la partita non si azzera
> più al cambio colore; colore bloccato (chiaro/scuro) + "Ricalcola colore";
> interruttore **Anti-lampo** (acceso di serie): copertura all'apertura e al tocco della casetta,
> controllo rapido con ricerche mirate (scopre subito in chat/profilo), lettura completa su
> binario separato, maschera sulle app recenti (best effort, da verificare con diagnosi).
> ⏳ Da fare: fumetto notifiche, bollicina messaggi, audio, propria storia, notifiche di sistema.

*Scritto il 12/11/2026 dopo la segnalazione di Sara: alcuni schemi hanno più soluzioni,
e a volte non c'è nessun numero certo da inserire (bisogna tirare a indovinare).*

## Il problema

Nella v1.3:

| Gioco | Soluzione unica? | Risolvibile solo con la logica? |
|---|---|---|
| Sudoku | ✅ controllata | ❌ non controllata (a volte serve indovinare) |
| KenKen | ❌ non controllata | ❌ |
| Kakuro | ❌ non controllata | ❌ |
| Sumplete | ❌ non controllata | ❌ |
| Equazione nascosta | ✅ (c'è una sola equazione nascosta) | — |
| Sequenze | ⚠️ a volte la serie è ambigua (più regole possibili) | — |
| 24 / Bersaglio / Calcolo | più soluzioni sono normali (va bene qualsiasi) | — |

## La soluzione (metodo standard dei generatori di puzzle)

Il metodo più usato è quello della raccolta di Simon Tatham (Keen = KenKen, Solo = Sudoku).
**Prima** si scrive un risolutore che ragiona come una persona, con tecniche ordinate
per difficoltà; **poi** il generatore tiene solo gli schemi che quel risolutore chiude
**senza mai tirare a indovinare**. Ne seguono due garanzie:
1. **soluzione unica** (se la logica porta a una sola soluzione, è l'unica);
2. **c'è sempre una mossa certa**: è proprio la definizione del controllo.

Il livello (Facile / Medio / Difficile) = **quali tecniche servono**, non quanti numeri
mancano. Il risolutore annota la tecnica più difficile che ha dovuto usare.

Struttura comune da aggiungere in `Puzzles.kt`:

```
LogicSolver<P>:
  candidati per ogni casella
  tecniche[livello] = lista di regole di deduzione
  solve(puzzle, maxLivello) -> RISOLTO(livelloUsato) | BLOCCATO
generate(livello):
  ripeti (max ~1,5 s): crea candidato → solve → tieni se RISOLTO e livelloUsato == livello
  se il tempo scade: accetta livello più basso (mai uno schema bloccato)
```

## Gioco per gioco

### Sudoku
- Oggi: unicità sì, logica no.
- Tecniche umane:
  - **Facile**: single nascosti e single "nudi" (un solo numero possibile).
  - **Medio**: + candidati bloccati (pointing/claiming) e coppie nude.
  - **Difficile**: + coppie/triple nascoste, X-wing.
- Generazione: si toglie un numero alla volta. Il numero si rimette se l'unicità salta
  **o** se il risolutore umano del livello non riesce più a chiudere.

### KenKen
- Oggi: gabbie a caso, nessun controllo → più soluzioni possibili.
- Risolutore:
  - per ogni gabbia si elencano le combinazioni di cifre che danno il risultato
    (es. "6×" in 2 caselle = {1,6},{2,3}) e si tengono come candidati solo le cifre presenti;
  - poi le regole del quadrato latino: single per riga/colonna, single nascosti;
  - nel **Difficile** anche le coppie nude.
- Generazione:
  - quadrato latino → gabbie → operazioni → risolutore; se resta bloccato, nuove gabbie;
  - nel **Facile** più gabbie da 1 casella (numeri già dati) e più "+";
  - evitare troppe gabbie "−" e "÷" da 2, che sono le più ambigue.
- È così che lavora Keen di Tatham: le difficoltà richiedono ragionamenti via via più
  complessi, ma la soluzione è sempre unica.

### Kakuro
- Oggi: riempimento casuale + somme, nessun controllo → spesso più soluzioni.
- Risolutore:
  - tabella precalcolata delle combinazioni (somma, lunghezza) → insiemi di cifre;
  - esempi di combinazioni uniche: 3 in 2 = 1+2; 4 in 2 = 1+3; 16 in 2 = 7+9; 17 in 2 = 8+9;
  - incroci: la cifra di una casella deve stare in entrambe le combinazioni (riga e colonna);
  - min/max della casella che incrocia due somme;
  - "casella solitaria": l'ultima casella vuota di un gruppo = somma − le altre.
- Generazione:
  - griglia → riempimento → somme → risolutore; se bloccato, si cambia il riempimento
    (o si aggiunge una casella nera) e si riprova;
  - nel **Facile** si preferiscono somme con combinazione unica.

### Sumplete
- Oggi: numeri e "tenuti" a caso → spesso più modi di arrivare alle somme.
- Risolutore (righe/colonne piccole: max 2⁶ = 64 sottoinsiemi per riga):
  - per ogni riga e colonna si elencano i sottoinsiemi che danno la somma,
    compatibili con quanto già deciso;
  - una casella presente in **tutti** i sottoinsiemi → certamente tenuta;
    in **nessuno** → certamente tolta;
  - si ripete tra righe e colonne finché tutto è deciso.
- Generazione: si tiene lo schema solo se questo procedimento lo chiude tutto.
  Livello = numero di giri riga↔colonna necessari (e dimensione 4/5/6).

### Sequenze
- Oggi: alcune serie ammettono più regole (es. 2, 4, 8 → raddoppio oppure +2, +4…).
- Correzione:
  - dopo aver generato la serie, la si prova con **tutte** le altre regole del gioco;
  - se un'altra regola spiega gli stessi numeri ma dà un seguito diverso → si scarta;
  - per le regole "a salti" (alternate) si mostrano 6 numeri invece di 5.

### Equazione nascosta
- Già a soluzione unica. Miglioria facoltativa: evitare equazioni con troppi
  simboli ripetuti, che rendono gli indizi poco informativi.

### 24 e Numero bersaglio
- Più soluzioni sono previste dal gioco; vale qualsiasi soluzione corretta.
- Miglioria facoltativa: usare il **numero di soluzioni** come livello
  (Facile = tante strade, Difficile = una o due, magari con frazioni nel 24).

### Calcolo a mente
- Nessun problema.

## Controlli automatici da aggiungere (test sulla JVM, prima di pubblicare)

Per ogni gioco a griglia e per ogni livello, 200 partite generate:
- [ ] soluzione unica (contatore di soluzioni con limite 2);
- [ ] il risolutore logico le chiude tutte senza indovinare;
- [ ] livello misurato == livello richiesto, almeno nell'80% dei casi;
- [ ] tempo medio di generazione < 300 ms, massimo < 1,5 s.

## Fonti
- Simon Tatham, *Keen* (KenKen): https://www.chiark.greenend.org.uk/~sgtatham/puzzles/doc/keen.html
- Simon Tatham, guida allo sviluppo (unicità tramite risolutore): https://www.chiark.greenend.org.uk/~sgtatham/puzzles/devel/writing.html
- Generatori di Sudoku con valutazione "umana": https://codemia.io/knowledge-hub/path/sudoku_generator_algorithm_closed
- Kakuro, strategie umane usate per generare: https://www.peterfrank.be/puzzles/frmKakuroStrategy.aspx
- Kakuro, combinazioni uniche: https://www.markhuckvale.com/games/kakuro/
- Kakuro, risolutore per propagazione + prova di unicità: https://dev.to/sendotltd/solving-kakuro-cross-sums-with-constraint-propagation-sum-combination-domains-fewest-candidate-4mje

---

# Altre richieste (13/11/2026)

## Sumplete più difficile
- Dimensioni: Facile 5×5, Medio 6×6, **Difficile 7×7** (risolutore: 2⁷ = 128 sottoinsiemi per riga, ok).
- Nel Difficile tenere solo schemi che richiedono **molti giri** di deduzione riga↔colonna
  (misurati dal risolutore logico), mai risolvibili guardando una riga sola.
- Più numeri ripetuti nella stessa riga/colonna → più sottoinsiemi con la stessa somma.

## Maschere della home
- **Fumetto notifiche** (id `activity_feed_tooltip`, già visto in diagnosi): quando è visibile,
  la maschera del feed viene divisa in rettangoli che lasciano scoperto solo il fumetto.
- **Bollicina dei messaggi in arrivo** dentro Instagram (senza diagnosi): lasciare scoperto
  qualsiasi elemento piccolo (< 30% dello schermo) sopra la maschera con id che contiene
  `notification` (escluso il badge fisso), `banner`, `toast`, `tooltip`, `in_app`, `snackbar`.
  Se non basta, si affina con una diagnosi.
- **Audio dal feed coperto**: NIENTE volume di sistema (richiesta di Sara).
  Usare il pulsante audio di Instagram sul video visibile: se la home è coperta e
  `AudioManager.isMusicActive()` è vero, cliccare il pulsante audio solo se la sua
  descrizione dice "disattiva/mute" (mai alla cieca, per non riattivarlo).
  Da verificare: id e descrizione del pulsante (registrare in diagnosi la descrizione
  dei nodi audio, che non è un dato personale).
  Trucco manuale nel frattempo: un tocco sull'altoparlante → Instagram lo ricorda.
- **La propria storia nella barra in alto**: lasciare scoperto solo il cerchietto con
  `reel_empty_badge` (il "+" della propria storia). Se il tocco (TYPE_VIEW_CLICKED) cade lì,
  il visualizzatore storie aperto dalla home è permesso finché è visibile
  `self_reel_item_toolbar_container` (pulsanti presenti solo sulle proprie storie);
  quando sparisce per > 400 ms → indietro.

## Lampi dei primi post sulla home (13/11/2026)
Limite onesto: gli eventi di accessibilità arrivano DOPO che Instagram ha disegnato,
quindi lo "zero assoluto" non è garantibile; l'obiettivo è che nel margine si veda lo sfondo.
1. **Copertura prudente**: nelle situazioni incerte (UNKNOWN, transizioni, apertura, indietro)
   la maschera della home resta/va accesa; si toglie solo quando si è certi che NON è il feed.
2. **Controllo lampo**: a ogni evento di Instagram (anche cambio contenuto), prima della lettura
   completa, `findAccessibilityNodeInfosByViewId(".../main_feed_action_bar")` (ricerca mirata,
   velocissima). Se visibile → maschera del feed dalla memoria, subito.
3. **Tocco sulla casetta** (TYPE_VIEW_CLICKED su `feed_tab`) → maschera accesa già al tocco.
4. **App recenti** (scelta di Sara: opzione A, niente sfocatura, niente "casetta → chat"):
   quando si aprono le recenti (launcher Xiaomi), trovare la scheda di Instagram tramite
   accessibilità (descrizione "Instagram") e, se l'ultima schermata lasciata era il FEED,
   coprire la parte della scheda corrispondente al feed (rettangolo del feed in scala
   scheda/schermo). Aggiornare a ogni evento di scorrimento; limite noto: piccolo ritardo
   mentre le schede scorrono.

## Notifiche di sistema a comparsa coperte dalle maschere (13/11/2026)
Decisione (proposta di Sara): spostare TUTTE le maschere da TYPE_ACCESSIBILITY_OVERLAY a
**TYPE_APPLICATION_OVERLAY**, che sta sopra Instagram ma SOTTO notifiche a comparsa,
barra di stato e tendina → le notifiche di sistema restano visibili senza "buchi".
- Serve il permesso SYSTEM_ALERT_WINDOW ("Mostra sopra altre app"): pulsante nella
  schermata principale + stato; su Xiaomi anche "Mostra finestre pop-up in background".
- Android 12+ blocca i tocchi che attraversano finestre "non attendibili" di altre app:
  quando una maschera è spenta impostare `alpha = 0` (oltre a FLAG_NOT_TOUCHABLE),
  così Instagram riceve i tocchi normalmente.
- Se il permesso manca: ripiego automatico sullo strato di accessibilità attuale.
- Fumetto notifiche e bollicina messaggi sono DENTRO Instagram → servono comunque i
  "buchi" (zone escluse) nella maschera del feed.

## Colore delle maschere sbagliato (24/11/2026) — per la prossima versione
Misurato su uno screenshot di Sara (tema scuro): sfondo Instagram **#0C0F14**, maschera **#13161B**
(leggermente più chiara) → il colore bloccato nella 1.5 è sbagliato.
Causa probabile: i punti di campionamento sul bordo della `main_feed_action_bar` cadono su una
sfumatura diversa dallo sfondo vero. "Ricalcola colore" rifarebbe lo stesso errore.
Correzione:
- campionare dove Instagram disegna sicuramente lo sfondo puro: striscia della **barra di stato**
  (x ≈ 3–8 dp a sinistra, lontano da icone e fotocamera, 2–3 altezze) e striscia **in fondo**
  sotto la barra dei pulsanti (x ≈ 3 dp); nello screenshot entrambe = #0C0F14;
- accordo di almeno 4 punti su 5;
- cambiare le chiavi del colore bloccato (es. suffisso `_v2`) e ignorare `bg_color` salvato,
  così il colore sbagliato già bloccato viene scartato all'aggiornamento.
**Decisione di Sara (24/11):** niente campionamento automatico. Usare direttamente i colori misurati
dagli screenshot: tema scuro = **#0C0F14**; tema chiaro = #FFFFFF finché non arriva uno screenshot
in tema chiaro da misurare. Il campionamento resta solo dietro al pulsante "Ricalcola colore"
(con i punti corretti qui sopra), per il caso in cui Instagram cambi colore.

## Segnalazioni sulla 1.5 (25/11/2026)
1. **Lampi da Esplora**: l'anti-lampo copre solo la home. Estendere a Esplora:
   controllo rapido su `explore_action_bar` (+ niente `row_search_user*`/campo di ricerca attivo),
   copertura al tocco di `search_tab`, all'apertura se l'ultima schermata era Esplora;
   salvare in memoria anche la maschera "esplora" (come "feed" in `mem_home`).
2. **App recenti da Esplora**: coprire anche se la memoria "resume" contiene "esplora".
3. **La maschera delle recenti blocca il tocco sulla scheda**: renderla trasparente ai tocchi
   (FLAG_NOT_TOUCHABLE; lo strato di accessibilità è attendibile, i tocchi passano).
   Serve in MaskManager un tipo di maschera "solo visiva".
Nota positiva: il riconoscimento delle app recenti su Xiaomi funziona (maschera vista sulla home).

## Richieste giochi (26/11/2026)
- **Annulla** (indietro di una mossa) e **Ricomincia** in tutti i giochi a griglia
  (Sudoku, KenKen, Kakuro, Futoshiki, Sumplete, Hitori, Binario). 24/Bersaglio li hanno già.
  Implementazione: pila delle mosse (casella, valore precedente) nel `numberGrid` e nei giochi a tocco.
- **Errori nascosti di serie**: niente sottolineatura automatica delle caselle sbagliate.
- Pulsante **"vedi errori"** = tastino con l'icona di un **occhio** 👁 (niente scritta), accanto ad
  Annulla/Ricomincia: mostra la sottolineatura degli errori solo finché è tenuto premuto.
- **Sumplete** (decisione di Sara): niente cerchietto automatico sulle somme giuste; è lei a
  cerchiarle toccando i numeri fuori dalla griglia (somme di riga a destra, di colonna sotto),
  come promemoria. Tocco di nuovo = toglie il cerchio. Anche queste azioni vanno nella pila di Annulla.
  Il cerchio automatico (somma davvero giusta) compare solo tenendo premuto "vedi errori".

## Versione 1.6 (26/11/2026) — fatto
- **Colore**: niente più campionamento automatico. In Altro → "🎯 Campiona colore": si apre Instagram (messaggi) con un mirino trascinabile; ✓ legge quel pixel esatto, anteprima, Salva (uno per tema scuro e uno per chiaro). "Torna al colore di base" = nero/bianco.
- **Esplora anti-lampo**: copertura al tocco della lente, appena compare `explore_action_bar` (non se stai scrivendo o ci sono risultati), memoria "explore" salvata.
- **App recenti**: maschera anche se avevi lasciato Instagram su Esplora; ora lascia passare i tocchi (resta sul livello accessibilità, l'unico che lo permette).
- **Notifiche sopra le maschere**: con il permesso "Mostra sopra le altre app" le maschere usano TYPE_APPLICATION_OVERLAY (sotto le notifiche). Da nascoste: alpha 0 + non toccabili. Se il livello app non va (permesso tolto, finestra nascosta) si torna da sole al livello accessibilità. Scheda nella schermata principale con i link alle impostazioni (anche quelle Xiaomi: "Mostra finestre pop-up in background").
- **🔇 sulla home**: preme il tasto audio del video di Instagram sotto la maschera (mai il volume di sistema). Nel registro diagnosi finiscono le descrizioni trovate, utili se non funziona.
- **Giochini**: ↶ Annulla, ⟲ Ricomincia, 👁 tieni premuto per vedere gli errori (nascosti di base) in Sumplete, KenKen, Sudoku, Kakuro, Futoshiki, Hitori, Binario. Sumplete: tocchi le somme fuori dalla griglia per cerchiarle tu; il cerchio "vero" (somma giusta) si vede solo con 👁.

Rimandati: buco per la bollina notifiche e per la bollina dei messaggi, tua storia nella barra in alto, difficoltà per 24/Bersaglio, notifica fissa "Solo DM attiva".

## 1.6.1 (26/11/2026)
- Flash: dalla diagnosi, durante il cambio pagina la casetta risultava già selezionata ma la pagina era "sconosciuta" → la maschera veniva tolta. Ora casetta selezionata (senza freccia indietro) = home, coperta con il rettangolo in memoria. La memoria della home si salva solo quando c'è `main_feed_action_bar`.
- Le coperture rapide di home ed Esplora non si spengono più a vicenda.
- App recenti: coperta tutta la scheda di Instagram (scelta B di Sara, da valutare: "non so se mi soddisfa").

## 1.7 (26/11/2026) — R · allenamento
- Nuovo gioco "R · allenamento" portato dal prototipo (artifact): Mix, Cosa stampa?, Completa, Componi la riga, Riordina, Trova il bug; livelli Facile/Medio/Difficile = 1/2/3 del prototipo.
- Offline: esercizi in `assets/r_esercizi.json` (estratti dal prototipo con node; "Cosa stampa?" = 1058 partite pre-generate dagli stessi schemi, ~30 per schema).
- Errore → "✗ No, riprova." + tasto "Mostra soluzione"; punteggio ✓/totale · serie salvato.
- Tendina dei giochi: ora si apre sopra la schermata (non sposta più giù tutto); tocco fuori = chiude.

## 1.8 (26/11/2026) — flash e leggerezza
- Diagnosi: il tocco su `feed_tab`/`search_tab` non arriva come evento → la maschera partiva solo a pagina disegnata.
- Finestre trasparenti (Kind.GUARD, livello accessibilità) sopra casetta e lente: al tocco accendono la maschera, poi premono l'icona vera (ACTION_CLICK). Se l'icona non c'è più, le finestre si tolgono.
- Le maschere nuove si accendono prima di spegnere le vecchie (mai un istante scoperto). Restano due giochi separati per home ed Esplora.
- "Indietro" verso home/Esplora: freccia indietro → copertura immediata; gesto di sistema → controllo rapido sempre attivo finché home/Esplora possono ricomparire (`returnBase`).
- Tolta la maschera delle app recenti: si usa la funzione Xiaomi "Sfoca anteprime app".
- Lettura completa meno frequente a pagina ferma (700 ms invece di 450).
- R · allenamento: "Trova il bug" sostituito da "Teoria" (97 domande a 3 risposte, dall'artifact aggiornato).

## 1.8.1 (26/11/2026)
- R · allenamento non partiva: la zona dei messaggi veniva creata dopo l'avvio del primo esercizio (ordine di inizializzazione Kotlin). Corretto.
- Uscita verso home del telefono / recenti con home o Esplora coperte: copertura di tutto lo schermo per 0,5 s (animazione della scheda).
- Rientro verso home/Esplora: tutto coperto finché la pagina non è riconosciuta (max 1,5 s).
- Scheda nelle recenti: Sara usa lo sfocamento Xiaomi, funziona.

## 1.9 (27/11/2026) — una maschera sola, copertura di base, leggerezza
- Home ed Esplora = una sola maschera (chiave `contenuto`) e un solo gioco: cambia solo forma (memorie vecchie feed/esplora convertite).
- Copertura di base: su pagina sconosciuta la maschera resta (max 1,5 s, poi si libera: es. fotocamera); si toglie su chat, profilo, ricerca mentre scrivi, viewer permessi.
- Pulsanti trasparenti su casetta, lente (accendono, tengono la maschera fino a 1 s finché la pagina non compare), messaggi e profilo (tolgono subito). Rettangoli tagliati sull'icona vicina (prima la lente copriva anche il profilo).
- Apertura da notifica: niente maschera sul contenuto né pulsanti trasparenti (si va dritti in chat).
- Leggerezza: ogni domanda di accessibilità la esegue Instagram stesso → controllo rapido ogni 120 ms (era 30), solo per 0,8 s dopo un cambio, meno ID cercati; lettura completa a pagina ferma ogni 1,2 s.
- 🔇: Solo DM prende l'audio focus (AUDIOFOCUS_GAIN, uso media) → Instagram smette di suonare; rilasciato all'uscita. Nessun cambio del volume di sistema.
- Giochi: partite salvate per ogni gioco e livello (memoria + file in `partite/`, salvataggio 0,6 s dopo ogni tocco); si riparte da zero solo con ↻ Nuova o a partita finita.
