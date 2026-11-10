# Solo DM

App Android che usa l'**accessibilità** per tenere Instagram solo per messaggi e pubblicazione.
Niente internet, niente dati inviati.

## Cosa fa
| | |
|---|---|
| 🚫 Reels (scheda) | pulsante coperto; se ci arrivi → torni ai Messaggi |
| 🚫 Esplora | coperto sotto la barra di ricerca |
| 🔍 Ricerca | libera, ma solo per trovare profili |
| 🚫 Feed | barra delle storie coperta; appena scorri si copre tutto |
| 🚫 Reel/post/storie aperti da Feed, Esplora o Reels | chiusi subito |
| 👀 Reel ricevuti in chat | apribili, ma lo swipe al successivo li chiude |
| 👀 Storie dal profilo di una persona | solo le sue; se passa a un'altra persona si chiude |
| 👀 Post di un profilo | uno alla volta (scorrere oltre lo chiude) |
| ✅ Messaggi, tue storie, pubblicare | liberi |
| ⏸ Pausa | in *Altro*, tenendo premuto 10 s → 10 minuti liberi |

## Installazione
1. Scarica `SoloDM.apk` dalla pagina **Releases** (si aggiorna da sola a ogni modifica).
2. Installalo (consenti l'installazione da fonti sconosciute).
3. Apri Solo DM → **Attiva nelle impostazioni** → accendi *Solo DM – blocco Instagram*.
   - Android 13+: se dice "Impostazione con limitazioni" → Impostazioni → App → Solo DM → ⋮ → *Consenti impostazioni con limitazioni*.
4. Tocca **Escludi dal risparmio batteria**.

## Se Instagram si aggiorna e qualcosa smette di funzionare
In *Altro* attiva **Registra diagnosi**, ripeti la cosa su Instagram, poi **Condividi diagnosi**.
Gli identificativi da aggiornare stanno tutti in `app/src/main/java/com/saranave/solodm/IgUi.kt`.
