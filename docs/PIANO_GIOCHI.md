# Piano: giochini senza ambiguità e senza "vicoli ciechi"

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
