# ticket-service

Udsteder billetter (én pr. booket sæde) når en booking bekræftes, og annullerer dem når bookingen annulleres.
Port **8085**, MongoDB `tickets_db`, RabbitMQ (`cinema.events`). Følger datamodellen *Cinema Platform Data Model*.

## Endpoints
Headerne `X-User-Id` (brugerens UUID fra User-servicen, påkrævet) og `X-User-Role` (`CUSTOMER` eller `ADMIN`) sættes af gateway'en.
Brugere ser kun deres egne billetter – andres giver 404. `ADMIN` ser alle.

| Metode | Sti | Beskrivelse |
|---|---|---|
| GET | `/api/tickets/me` | Mine billetter (nyeste forestilling først) |
| GET | `/api/tickets/booking/{bookingId}` | Billetter for en booking |
| GET | `/api/tickets/{id}` | Én billet |
| GET | `/api/tickets/{id}/qr` | QR-kode som PNG (tegnes ud fra `qrToken`) |
| POST | `/api/tickets/scan` | Scanning ved indgangen. Body `{"qrToken":"…","showtimeId":"…"}` (`showtimeId` valgfri). Kun `ADMIN` |

Scanning: ugyldig/forfalsket QR → 400, ikke admin → 403, ukendt billet → 404, billet der er `SCANNED`/`VOID` eller til en anden forestilling → 409.
Billetter oprettes og annulleres **kun** via events – der er ingen REST-endpoint til det.

## Billetten
```json
{
  "ticketNumber": "TK-7F3K-92QD",
  "bookingId": "d3c0b5a2-…", "userId": "a91e44f0-…", "showtimeId": "66f…c7",
  "seat": { "row": "F", "number": 7, "type": "STANDARD" },
  "movieTitle": "Dune: Part Two", "hallName": "Sal 3", "startsAt": "2026-10-09T17:30:00Z",
  "qrToken": "eyJhbGciOiJIUzI1NiJ9…",
  "status": "VALID", "issuedAt": "…", "scannedAt": null, "voidedAt": null
}
```
- **Status:** `VALID` → `SCANNED` (ved døren) eller `VALID` → `VOID` (booking annulleret). Scannede billetter røres ikke ved annullering.
- **QR-koden** indeholder `qrToken`: en HS256-signeret token over `{ticketNumber, showtimeId}`. Den kan ikke forfalskes uden hemmeligheden (`TICKET_QR_SECRET`). `qrToken` vises ikke i REST-svarene – kun som billede via `qrUrl` og i `ticket.issued`.
- **Indexes:** `ticketNumber` unik, `{bookingId, seat.row, seat.number}` unik, `{userId, startsAt}`, `{showtimeId, status}`.
- **Genbooking:** en annulleret billet blokerer ikke sædet – en ny booking kan få billet til samme sæde. Det er Booking-servicen der forhindrer dobbeltbooking.

## Events
Alle beskeder på topic exchange `cinema.events` bruger samme envelope; routing key = `type`:
```json
{ "eventId": "6b1f0c1e-…", "type": "booking.confirmed", "version": 1, "occurredAt": "2026-10-07T18:42:10Z",
  "producer": "booking-service", "correlationId": "<bookingId>", "data": { … } }
```

| Event | Retning | data |
|---|---|---|
| `booking.confirmed` | lytter (kø `ticket-service.booking.confirmed`) | `bookingId, userId, showtimeId, movieTitle, hallName, startsAt, seats[{row, number, type, price}]` |
| `booking.cancelled` | lytter (kø `ticket-service.booking.cancelled`) | `bookingId, userId, reason, wasPaid` |
| `ticket.issued` | publicerer (én pr. booking) | `bookingId, userId, tickets[{ticketNumber, row, number, qrToken}]` |

Ukendte felter ignoreres. En besked uden gyldig envelope sendes direkte til `ticket-service.dead-letter`; andre fejl prøves 5 gange først.

### Inbox og outbox (MongoDB)
- **`processed_events`** – hvert håndteret `eventId`. Samme event to gange håndteres kun én gang.
- **`outbox_events`** – `ticket.issued` gemmes her først; `OutboxRelay` sender ventende events hvert sekund og sætter `publishedAt` når RabbitMQ har bekræftet dem.

### Afvigelser fra datamodellen (bevidste)
- **Rækkefølgen i inbox.** Modellen siger "indsæt eventId først i samme transaktion". MongoDB har kun transaktioner på et replica set, så `eventId` gemmes *efter* handleren er lykkedes. Går servicen ned midt i, bliver eventet leveret igen og håndteret igen – det er sikkert, fordi indexet pr. booket sæde og "én `ticket.issued` pr. booking" gør handleren idempotent.
- **`cancelled_bookings`** (ekstra collection). `booking.confirmed` og `booking.cancelled` ligger i hver sin kø og kan komme i forkert rækkefølge. Kommer annulleringen først, huskes den her, så en sen bekræftelse ikke udsteder billetter.

## Kør lokalt
Servicen skal bruge MongoDB og RabbitMQ:
```bash
docker run -d --name bioflix-rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
mvn spring-boot:run
```
Åbn **http://localhost:8085/swagger-ui.html** – her kan du udfylde `X-User-Id`/`X-User-Role` og prøve alle endpoints.
RabbitMQ's admin-side: http://localhost:15672 (guest/guest).

| Env | Default |
|---|---|
| `MONGO_HOST` | `localhost` |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `guest` / `guest` |
| `TICKET_QR_SECRET` | en lokal udviklingsnøgle – **skal sættes** (mindst 32 tegn) uden for udvikling |

## Testdata
Start med profilen `seed` for at lægge demo-billetter i MongoDB (springes over hvis de findes i forvejen; sender ingen events):
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=seed
```
| Booking | Bruger (`X-User-Id`) | Film | Sæder | Status |
|---|---|---|---|---|
| `b0000000-…-000000000001` | `11111111-1111-4111-8111-111111111111` | Dune: Part Two (i morgen) | C7, C8 | `VALID` |
| `b0000000-…-000000000002` | `22222222-2222-4222-8222-222222222222` | Oppenheimer (i går) | A1 | `SCANNED` |
| `b0000000-…-000000000003` | `11111111-1111-4111-8111-111111111111` | Interstellar (om en uge) | F5 | `VOID` |
| `b0000000-…-000000000004` | `33333333-3333-4333-8333-333333333333` | Interstellar (om en uge) | F5, F6 | `VALID` – samme sæde F5 genbooket |

Til scanning: brug fx `X-User-Id: 99999999-9999-4999-8999-999999999999` og `X-User-Role: ADMIN`.

## Tests
`mvn test` – integrationstesten starter MongoDB og RabbitMQ med Testcontainers, så Docker skal køre.
Den sender events som rå JSON (præcis som de andre services vil gøre), så den tjekker også event-kontrakten.
